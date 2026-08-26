import Foundation

/// Thrown when a response body exceeds the 64 KiB safety cap.
struct ResponseTooLargeError: Error {}

/// Minimal URLSession-based HTTP client for the Hermes `serve` API.
///
/// - Builds requests from a normalized origin plus path/query items.
/// - Shares the process-wide `HTTPCookieStorage` so `Set-Cookie` values
///   (e.g. the session cookie) are stored and replayed automatically.
/// - Enforces a 64 KiB response cap to bound memory on untrusted payloads.
///
/// Logging policy: this type never logs request headers, cookies, or URLs
/// that may carry query strings.
final class HermesHTTPClient {

    /// Hard cap on response body size (64 KiB).
    static let maxResponseBytes = 65_536

    /// Normalized origin, e.g. "https://hermes.example.com" (no trailing slash).
    let origin: String

    /// Optional bearer token sent as `Authorization: Bearer *** on every
    /// request. Set after native sign-in so authenticated endpoints
    /// (`/api/profiles/sessions`, etc.) accept the call. Never logged.
    var bearerToken: String?

    /// Produces a fresh token set when a request draws HTTP 401, or nil when
    /// no refresh is possible (expired refresh token, blank provider…). Nil
    /// provider = legacy behavior: 401 surfaces unchanged. The returned
    /// access token replaces `bearerToken` for the retried attempt AND all
    /// subsequent requests.
    var refreshTokenProvider: (() async -> NativeTokenSet?)?

    private let session: URLSession

    /// Single-flight guard: concurrent 401s await one shared refresh task so
    /// N simultaneous rejections trigger exactly one token refresh.
    private let retryLock = NSLock()
    private var inFlightRefresh: Task<NativeTokenSet?, Never>?
    private var inFlightRefreshID: UUID?

    /// Whether this instance created (and therefore owns) `session`. Only an
    /// owned session is invalidated on `deinit`; an injected session may be
    /// shared across clients and must outlive any single one.
    private let ownsSession: Bool

    /// Creates a client for a normalized origin. The origin is re-normalized
    /// defensively via `ServerOrigin.normalize` when possible; otherwise it is
    /// used as given (tests inject synthetic origins).
    init(origin: String) {
        self.origin = ServerOrigin.normalize(origin) ?? origin
        let config = URLSessionConfiguration.ephemeral
        // Route cookie handling through the shared store even though the rest
        // of the session is ephemeral: Set-Cookie responses are persisted here
        // and resent on subsequent requests to matching hosts.
        config.httpCookieStorage = HTTPCookieStorage.shared
        config.httpShouldSetCookies = true
        config.timeoutIntervalForRequest = 20
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        self.session = URLSession(configuration: config)
        self.ownsSession = true
    }

    /// Injects a preconfigured session (e.g. a shared session, or one whose
    /// `protocolClasses` include a mock `URLProtocol`). The caller retains
    /// ownership; this client will not invalidate it.
    init(origin: String, session: URLSession) {
        self.origin = ServerOrigin.normalize(origin) ?? origin
        self.session = session
        self.ownsSession = false
    }

    deinit {
        if ownsSession {
            session.finishTasksAndInvalidate()
        }
    }

    // MARK: - Requests

    /// Performs a GET against `origin + path + queryItems`.
    func get(
        path: String,
        queryItems: [URLQueryItem] = [],
        maximumResponseBytes: Int = HermesHTTPClient.maxResponseBytes
    ) async throws -> (Data, HTTPURLResponse) {
        var components = try urlComponents(path: path)
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        guard let url = components.url else {
            throw TransportError(underlying: URLError(.badURL))
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        // Replay stored cookies explicitly: URLProtocol-based test sessions and
        // some configurations don't auto-attach them from the shared store.
        if let storage = session.configuration.httpCookieStorage {
            let cookies = storage.cookies(for: url) ?? []
            if !cookies.isEmpty {
                let header = HTTPCookie.requestHeaderFields(with: cookies)["Cookie"] ?? ""
                request.setValue(header, forHTTPHeaderField: "Cookie")
            }
        }
        return try await run(request, maximumResponseBytes: maximumResponseBytes)
    }

    /// Performs a POST with a JSON-encoded `jsonBody`.
    func post<T: Encodable>(path: String, jsonBody: T) async throws -> (Data, HTTPURLResponse) {
        try await sending(path: path, method: "POST", jsonBody: jsonBody)
    }

    /// Performs a PATCH with a JSON-encoded `jsonBody`.
    ///
    /// Mirrors the Android client's `client.patch(...)` usage for session
    /// updates (`PATCH /api/sessions/{id}`).
    func patch<T: Encodable>(path: String, jsonBody: T) async throws -> (Data, HTTPURLResponse) {
        try await sending(path: path, method: "PATCH", jsonBody: jsonBody)
    }

    /// Performs a DELETE against `origin + path + queryItems` with no body.
    ///
    /// Mirrors the Android client's `client.delete(...)` usage for session
    /// deletion (`DELETE /api/sessions/{id}?profile=…`).
    func delete(path: String, queryItems: [URLQueryItem] = []) async throws -> (Data, HTTPURLResponse) {
        var components = try urlComponents(path: path)
        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }
        guard let url = components.url else {
            throw TransportError(underlying: URLError(.badURL))
        }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return try await run(request)
    }

    // MARK: - Internals

    /// Shared request builder for body-carrying verbs (POST/PATCH).
    private func sending<T: Encodable>(path: String, method: String, jsonBody: T) async throws -> (Data, HTTPURLResponse) {
        let components = try urlComponents(path: path)
        guard let url = components.url else {
            throw TransportError(underlying: URLError(.badURL))
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(jsonBody)
        return try await run(request)
    }

    private func urlComponents(path: String) throws -> URLComponents {
        guard var components = URLComponents(string: origin + path) else {
            throw TransportError(underlying: URLError(.badURL))
        }
        // Never allow user-supplied query strings inside `path` to smuggle in
        // parameters; queries are only set explicitly via `queryItems`.
        components.query = nil
        return components
    }

    /// Builds a client pre-wired for authenticated self-hosted use: bearer
    /// from the origin-scoped keychain store, and a single-flight refresh
    /// provider that consults TokenRefreshPolicy, calls the native refresh
    /// endpoint, and persists the rotated pair back under the same origin.
    static func makeAuthenticated(
        origin rawOrigin: String,
        urlSession: URLSession = .shared,
        credentialStore: CredentialStoring = KeychainCredentialStore()
    ) -> HermesHTTPClient {
        let client = HermesHTTPClient(origin: rawOrigin, session: urlSession)
        guard let origin = ServerOrigin.normalize(rawOrigin) else { return client }

        if let pair = credentialStore.tokens(for: origin),
           let token = String(data: pair.accessToken, encoding: .utf8),
           !token.isEmpty {
            client.bearerToken = token
        }

        client.refreshTokenProvider = { [weak client] in
            guard let client else { return nil }
            guard let pair = credentialStore.tokens(for: origin) else { return nil }
            let now = Int64(Date().timeIntervalSince1970)
            let refreshToken = pair.refreshToken.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            guard TokenRefreshPolicy.needsRefresh(
                expiresAt: pair.expiresAt,
                now: now,
                refreshToken: refreshToken,
                provider: pair.provider
            ) else {
                // Policy says the stored access token should still work; a 401
                // then means it was revoked — nothing to refresh.
                return nil
            }
            do {
                let refreshed = try await NativeRefreshClient(session: urlSession)
                    .refresh(origin: origin, refreshToken: refreshToken, provider: pair.provider)
                let newPair = TokenPair(
                    accessToken: Data(refreshed.accessToken.utf8),
                    refreshToken: Data(refreshed.refreshToken.utf8),
                    expiresAt: refreshed.expiresAt,
                    provider: refreshed.provider
                )
                credentialStore.setTokens(newPair, for: origin)
                return refreshed
            } catch {
                // Expired/transient/failed all end the same way: no retry, the
                // caller surfaces sign-in-required through the 401 path.
                return nil
            }
        }
        return client
    }

    private func run(
        _ request: URLRequest,
        maximumResponseBytes: Int = HermesHTTPClient.maxResponseBytes
    ) async throws -> (Data, HTTPURLResponse) {
        var request = request
        // Attach the bearer token to every request when present. Hermes
        // authenticates API calls via `Authorization: Bearer ***`
        // (see the Android client's bearerAuth on every authenticated call).
        if let bearerToken, !bearerToken.isEmpty {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw TransportError(underlying: error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw TransportError(underlying: URLError(.badServerResponse))
        }
        guard data.count <= maximumResponseBytes else {
            throw ResponseTooLargeError()
        }

        // 401 → refresh once → retry once (Android parity: a single
        // refresh-and-retry, then the caller sees the auth rejection). No
        // provider configured = unchanged legacy behavior.
        if http.statusCode == 401, refreshTokenProvider != nil,
           await refreshAndApplyToken() {
            return try await runOnce(request, maximumResponseBytes: maximumResponseBytes)
        }
        return (data, http)
    }

    /// One request attempt with the CURRENT bearer state (used for the
    /// post-refresh retry; the original attempt is `run`'s first pass).
    private func runOnce(
        _ request: URLRequest,
        maximumResponseBytes: Int = HermesHTTPClient.maxResponseBytes
    ) async throws -> (Data, HTTPURLResponse) {
        var request = request
        if let bearerToken, !bearerToken.isEmpty {
            request.setValue("Bearer \(bearerToken)", forHTTPHeaderField: "Authorization")
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw TransportError(underlying: error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw TransportError(underlying: URLError(.badServerResponse))
        }
        guard data.count <= maximumResponseBytes else {
            throw ResponseTooLargeError()
        }
        return (data, http)
    }

    /// Runs the refresh provider at most once across concurrent callers;
    /// everyone awaits the same in-flight task. Returns true when a fresh
    /// access token is now installed.
    private func refreshAndApplyToken() async -> Bool {
        let (taskID, task): (UUID, Task<NativeTokenSet?, Never>) = {
            retryLock.lock()
            if let inFlight = inFlightRefresh,
               let existingID = inFlightRefreshID {
                retryLock.unlock()
                return (existingID, inFlight)
            }
            let createdID = UUID()
            let created = Task<NativeTokenSet?, Never> { [weak self] in
                guard let self, let provider = self.refreshTokenProvider else { return nil }
                return await provider()
            }
            inFlightRefresh = created
            inFlightRefreshID = createdID
            retryLock.unlock()
            return (createdID, created)
        }()

        let tokens = await task.value

        // Clear the single-flight slot once the refresh settles.
        retryLock.lock()
        if inFlightRefreshID == taskID {
            inFlightRefresh = nil
            inFlightRefreshID = nil
        }
        retryLock.unlock()

        guard let tokens, !tokens.accessToken.isEmpty else { return false }
        bearerToken = tokens.accessToken
        return true
    }
}
