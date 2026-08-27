import Foundation

/// Thrown when the portal reports a non-retryable OAuth error
/// (e.g. `invalid_grant`, `invalid_token`, `refresh_token_reused`).
public struct PortalTerminalError: Error {
    /// Raw `error` code returned by the portal.
    public let reason: String
}

/// Client for the Nous Portal OAuth device flow and agent discovery.
///
/// Stateless by design: tokens are supplied by and persisted via the caller,
/// so no cookie jar or shared credential state is needed.
public struct PortalClient {
    /// Production portal origin.
    public static let defaultOrigin = "https://portal.nousresearch.com"

    /// Base origin for all requests.
    public let origin: String
    /// Session used for every request.
    private let session: URLSession

    /// - Parameters:
    ///   - origin: Override for tests or alternate deployments.
    ///   - session: Injectable for testing; defaults to `.shared`.
    public init(origin: String = PortalClient.defaultOrigin, session: URLSession = .shared) {
        self.origin = origin
        self.session = session
    }

    // MARK: - Endpoints

    /// Starts the OAuth device authorization grant.
    ///
    /// POSTs `client_id=hermes-cli&scope=inference:invoke` as a form body.
    ///
    /// - Returns: The decoded device code, user code, verification URI(s),
    ///   expiry, and polling interval.
    public func startDeviceCode() async throws -> DeviceCode {
        var request = URLRequest(url: Self.url(origin, path: "/api/oauth/device/code"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(Self.formEncode([
            "client_id": "hermes-cli",
            "scope": "inference:invoke",
        ]).utf8)

        let (data, statusCode) = try await perform(request, session: session)
        guard (200 ..< 300).contains(statusCode) else {
            throw PortalHTTPError(statusCode: statusCode)
        }
        return try JSONDecoder().decode(DeviceCode.self, from: data)
    }

    /// Outcome of a single token poll against the OAuth token endpoint.
    public enum DevicePollOutcome {
        /// The user has not authorized yet; keep waiting at the same interval.
        case pending
        /// Polling too fast; retry using the returned interval (previous + 5s).
        case slowDown(interval: Int)
        /// Authorization complete; persist the rotated refresh token.
        case success(TokenSet)
        /// Non-retryable failure; abandon the device code.
        case terminal(String)
    }

    /// Polls the token endpoint once with the given device code.
    ///
    /// The JSON `error` field takes precedence over any success-shaped
    /// payload, so a malformed success body cannot mask an error condition.
    ///
    /// - Parameters:
    ///   - deviceCode: Device code from `startDeviceCode()`.
    ///   - interval: Interval used for this poll; only raised on `slow_down`.
    public func pollDeviceCode(deviceCode: String, interval: Int = 5) async throws -> DevicePollOutcome {
        var request = URLRequest(url: Self.url(origin, path: "/api/oauth/token"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(Self.formEncode([
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
            "client_id": "hermes-cli",
            "device_code": deviceCode,
        ]).utf8)

        let (data, statusCode) = try await perform(request, session: session)

        // Error field wins over any coincidental success decode.
        if let errorBody = try? JSONDecoder().decode(OAuthErrorBody.self, from: data),
           let code = errorBody.error {
            switch code {
            case "authorization_pending":
                return .pending
            case "slow_down":
                return .slowDown(interval: interval + 5)
            default:
                return .terminal(code)
            }
        }

        guard (200 ..< 300).contains(statusCode) else {
            return .terminal("http_\(statusCode)")
        }

        do {
            return .success(try JSONDecoder().decode(TokenSet.self, from: data))
        } catch {
            // Neither a recognized error nor a decodable token set.
            return .terminal("malformed_response")
        }
    }

    /// Lists agents visible to the bearer token.
    ///
    /// When the portal responds 409 (`org_selection_required`), the offered
    /// organizations are decoded into `OrgSelectionRequiredError`; re-call
    /// with `org:` set to the chosen slug.
    ///
    /// - Parameters:
    ///   - bearer: Portal access token.
    ///   - org: Optional organization slug to scope discovery to
    ///     (appended as `?org=`).
    public func agents(bearer: String, org: String? = nil) async throws -> AgentDiscovery {
        var components = URLComponents(string: origin)!
        components.path = "/api/agents"
        if let org { components.queryItems = [URLQueryItem(name: "org", value: org)] }

        var request = URLRequest(url: components.url!)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")

        let (data, statusCode) = try await perform(request, session: session)
        if statusCode == 409 {
            if let selection = try? JSONDecoder().decode(OrgSelectionBody.self, from: data),
               selection.error == "org_selection_required" {
                throw OrgSelectionRequiredError(choices: selection.orgs)
            }
        }
        guard (200 ..< 300).contains(statusCode) else {
            throw PortalHTTPError(statusCode: statusCode)
        }
        return try JSONDecoder().decode(AgentDiscovery.self, from: data)
    }

    /// Exchanges a refresh token for a rotated token set.
    ///
    /// The current refresh token travels in the custom
    /// `x-nous-refresh-token` header rather than the form body. On success
    /// the caller must persist the new refresh token; terminal errors
    /// (`invalid_token`, `refresh_token_reused`, …) throw
    /// `PortalTerminalError` and stored credentials should be discarded.
    public func refresh(_ current: TokenSet) async throws -> TokenSet {
        guard let refreshToken = current.refreshToken else {
            throw PortalTerminalError(reason: "missing_refresh_token")
        }

        var request = URLRequest(url: Self.url(origin, path: "/api/oauth/token"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        // Never logged; header-only by contract.
        request.setValue(refreshToken, forHTTPHeaderField: "x-nous-refresh-token")
        request.httpBody = Data(Self.formEncode([
            "grant_type": "refresh_token",
            "client_id": "hermes-cli",
        ]).utf8)

        let (data, statusCode) = try await perform(request, session: session)

        // Terminal error field wins over any success decode.
        if let errorBody = try? JSONDecoder().decode(OAuthErrorBody.self, from: data),
           let code = errorBody.error {
            if Self.terminalRefreshErrors.contains(code) {
                throw PortalTerminalError(reason: code)
            }
            throw PortalHTTPError(statusCode: statusCode)
        }
        guard (200 ..< 300).contains(statusCode) else {
            throw PortalHTTPError(statusCode: statusCode)
        }
        let refreshed = try JSONDecoder().decode(TokenSet.self, from: data)
        // The Portal rotates refresh tokens, but may omit a replacement when it
        // reuses the current one. Carry the previous refresh token forward in
        // that case so the caller never persists a nil and locks itself out.
        // (Matches the Android client's refresh semantics.)
        if refreshed.refreshToken?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty != false {
            return TokenSet(accessToken: refreshed.accessToken, refreshToken: refreshToken)
        }
        return refreshed
    }

    // MARK: - Wire types (internal)

    /// Error envelope: `{"error": "<code>"}`.
    struct OAuthErrorBody: Decodable {
        let error: String?
    }

    /// 409 envelope: `{"error":"org_selection_required","orgs":[...]}`.
    struct OrgSelectionBody: Decodable {
        let error: String
        let orgs: [OrgChoice]
    }

    /// Non-2xx status without a recognized error payload.
    struct PortalHTTPError: Error {
        let statusCode: Int
    }

    // MARK: - Helpers

    private static func url(_ origin: String, path: String) -> URL {
        URL(string: origin + path)!
    }

    /// Performs the request and returns the raw body plus the HTTP status
    /// code (status is kept so callers can decode error envelopes from
    /// non-2xx responses).
    private func perform(_ request: URLRequest, session: URLSession) async throws -> (Data, Int) {
        let (data, response) = try await session.data(for: request)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? -1
        return (data, statusCode)
    }

    /// Percent-encodes form fields per RFC 3986 (unreserved set kept literal;
    /// space becomes `%20`, not `+`). Keys are sorted for stable output.
    static func formEncode(_ fields: [String: String]) -> String {
        let unreserved = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        )
        func encode(_ value: String) -> String {
            value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? value
        }
        return fields
            .sorted { $0.key < $1.key }
            .map { key, value in "\(encode(key))=\(encode(value))" }
            .joined(separator: "&")
    }

    private static let terminalRefreshErrors: Set<String> = [
        "invalid_grant",
        "invalid_token",
        "refresh_token_reused",
    ]
}

// MARK: - Poll loop driver

/// Drives `PortalClient.pollDeviceCode` until the user authorizes, the
/// portal reports a terminal error, or the surrounding task is cancelled.
///
/// Hardening (Android parity: `HermesCloudClient.awaitDeviceToken`):
/// - the interval is clamped to the 1...30 second window no matter what the
///   server sent;
/// - `slow_down` raises the interval by exactly 5 seconds (re-clamped);
/// - a transient network failure *during* a poll is treated exactly like
///   `authorization_pending` and polling continues. When the user opens the
///   browser to approve, the OS may background this app and tear the network
///   down — killing the poll there would abort sign-in the instant the
///   browser steals focus. That was a solved Android bug; it stays solved
///   here.
public enum PortalPoller {

    /// Clamps a poll interval into the 1...30 second window.
    public static func clamp(_ seconds: Int) -> Int {
        max(1, min(30, seconds))
    }

    /// Pure next-interval decision, extracted so tests need no async loop.
    ///
    /// - `.pending` keeps the current interval (clamped).
    /// - `.slowDown` adopts the raised interval the client computed
    ///   (previous + 5), clamped again.
    /// - Success/terminal never advance the loop; the clamped current value
    ///   is returned for completeness.
    public static func nextInterval(current: Int, outcome: PortalClient.DevicePollOutcome) -> Int {
        switch outcome {
        case .pending:
            return clamp(current)
        case .slowDown(let raised):
            return clamp(raised)
        case .success, .terminal:
            return clamp(current)
        }
    }

    /// Runs the poll loop until success, terminal error, or cancellation.
    ///
    /// Waits one interval *before* each attempt (matching the Android
    /// delay-then-poll shape, so a fresh code is never polled immediately).
    ///
    /// - Parameters:
    ///   - deviceCode: Device code from `startDeviceCode()`.
    ///   - initialInterval: Server-suggested interval; clamped before use.
    ///   - poll: One poll attempt; receives the device code and the current
    ///     interval. Injectable so tests never touch the network.
    ///   - sleep: Waits the given number of seconds. Injectable so tests run
    ///     instantly.
    /// - Returns: The token set once authorization completes.
    /// - Throws: `PortalTerminalError` on a terminal outcome;
    ///   `CancellationError` when the surrounding task is cancelled.
    public static func run(
        deviceCode: String,
        initialInterval: Int,
        expiresIn: Int = 600,
        nowSeconds: () -> Int64 = { Int64(Date().timeIntervalSince1970) },
        poll: (String, Int) async throws -> PortalClient.DevicePollOutcome,
        sleep: (Int) async throws -> Void = defaultSleep
    ) async throws -> TokenSet {
        var interval = clamp(initialInterval)
        let deadline = nowSeconds() + Int64(max(1, expiresIn))
        while !Task.isCancelled, nowSeconds() < deadline {
            try await wait(interval, sleep: sleep)
            do {
                let outcome = try await poll(deviceCode, interval)
                switch outcome {
                case .pending:
                    continue
                case .slowDown(let raised):
                    interval = nextInterval(current: interval, outcome: .slowDown(interval: raised))
                case .success(let tokens):
                    return tokens
                case .terminal(let reason):
                    throw PortalTerminalError(reason: reason)
                }
            } catch let error as PortalTerminalError {
                throw error
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError where error.code == .cancelled {
                // URLSession surfaces task cancellation as URLError.cancelled.
                throw CancellationError()
            } catch let error as URLError where isTransientPollFailure(error) {
                // Transient network failure during a poll: keep waiting.
                continue
            }
        }
        if !Task.isCancelled {
            throw PortalTerminalError(reason: "expired_token")
        }
        throw CancellationError()
    }

    /// Convenience: drives the real `PortalClient` token endpoint.
    public static func run(client: PortalClient, deviceCode: DeviceCode) async throws -> TokenSet {
        try await run(
            deviceCode: deviceCode.deviceCode,
            initialInterval: deviceCode.interval,
            expiresIn: deviceCode.expiresIn
        ) { code, interval in
            try await client.pollDeviceCode(deviceCode: code, interval: interval)
        }
    }

    /// Waits one interval, propagating only cancellation. Any other sleep
    /// failure falls through to the next poll rather than killing sign-in.
    private static func wait(
        _ seconds: Int,
        sleep: (Int) async throws -> Void
    ) async throws {
        do {
            try await sleep(seconds)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        }
    }

    public static func defaultSleep(_ seconds: Int) async throws {
        try Task.checkCancellation()
        try await Task.sleep(nanoseconds: UInt64(clamp(seconds)) * 1_000_000_000)
    }

    private static func isTransientPollFailure(_ error: URLError) -> Bool {
        switch error.code {
        case .notConnectedToInternet, .dnsLookupFailed, .cannotFindHost,
             .cannotConnectToHost, .networkConnectionLost, .timedOut:
            return true
        default:
            return false
        }
    }
}
