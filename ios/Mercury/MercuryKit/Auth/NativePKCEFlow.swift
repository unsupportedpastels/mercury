import CryptoKit
import Foundation

/// Errors surfaced by the native PKCE sign-in flow.
enum FlowError: Error, Equatable {
    case stateMismatch
    case rejected(Int)
    case transient(String)
    case badResponse
}

/// Result of a successful authorization callback.
struct CallbackResult: Equatable {
    let code: String
    let state: String
}

/// Outcome of the token exchange: either JSON tokens or auth cookies set by
/// the server.
enum TokenOutcome: Equatable {
    case json(accessToken: String, refreshToken: String?)
    case cookies([String: String])
}

/// Native-browser PKCE sign-in against the Hermes dashboard.
///
/// Flow: `begin()` → open returned authorize URL in browser → Portal 302s to
/// the loopback listener → `awaitCallback()` → `exchange(code:)`.
final class NativePKCEFlow {
    let origin: String
    let provider: String

    private var pkce: PKCE?
    private var state: String?
    private var listener: LoopbackListener?

    init(origin: String, provider: String = "nous") {
        self.origin = origin
        self.provider = provider
    }

    /// Starts the loopback listener and builds the authorize URL to open in
    /// the user's browser.
    func begin() async throws -> URL {
        let listener = LoopbackListener()
        let redirectURI = try await listener.start()

        let pkce = PKCE.generate()
        let state = PKCE.randomState()

        self.listener = listener
        self.pkce = pkce
        self.state = state

        return Self.makeAuthorizeURL(
            origin: origin,
            provider: provider,
            state: state,
            challenge: pkce.challenge,
            redirectURI: redirectURI.absoluteString
        )
    }

    /// Waits for the browser redirect and validates the returned state.
    func awaitCallback() async throws -> CallbackResult {
        guard let listener, let expectedState = state else {
            throw FlowError.badResponse
        }
        let callbackURL = try await listener.waitForCallback()
        return try Self.validate(callbackURL: callbackURL, expectedState: expectedState)
    }

    /// Runs the authorization in an app-owned browser while receiving the
    /// Hermes loopback callback from the listener directly. ASWebAuthenticationSession
    /// renders the Portal pages, but does not reliably invoke its completion
    /// handler for an http://127.0.0.1 callback on iOS.
    func authorizeInSystemBrowser(url: URL) async throws -> CallbackResult {
        guard let listener, let expectedState = state else {
            throw FlowError.badResponse
        }

        let browserTask = Task { @MainActor in
            try? await BrowserAuthenticationSession.shared.authenticate(
                url: url,
                callbackURLScheme: "http"
            )
        }
        defer {
            browserTask.cancel()
            Task { @MainActor in
                BrowserAuthenticationSession.shared.cancel()
            }
        }

        let callbackURL = try await listener.waitForCallback()
        listener.stop()
        self.listener = nil
        debug("callback-listener-returned")
        return try Self.validate(callbackURL: callbackURL, expectedState: expectedState)
    }

    /// Exchanges the authorization code for tokens or cookies.
    func exchange(code: String) async throws -> TokenOutcome {
        guard let pkce else {
            throw FlowError.badResponse
        }

        var request = URLRequest(url: URL(string: "\(origin)/auth/native/token")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Matches the Hermes dashboard native_pkce contract (see Android
        // NativeOAuth.NativeTokenRequest): only code + code_verifier. The
        // server binds state via the authorize request, not the token body.
        let body: [String: String] = [
            "code": code,
            "code_verifier": pkce.verifier,
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        let httpResponse = try Self.requireHTTP(response)
        debug("token-http-\(httpResponse.statusCode)")

        switch httpResponse.statusCode {
        case 401, 403:
            throw FlowError.rejected(httpResponse.statusCode)
        case 500...599:
            throw FlowError.transient("token endpoint returned \(httpResponse.statusCode)")
        case 200..<300:
            break
        default:
            throw FlowError.transient("unexpected token status \(httpResponse.statusCode)")
        }

        let contentType = (httpResponse.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased()
        if contentType.contains("json") {
            struct TokenJSON: Decodable {
                let access_token: String
                let refresh_token: String?
            }
            let decoded = try JSONDecoder().decode(TokenJSON.self, from: data)
            if decoded.access_token.isEmpty {
                throw FlowError.badResponse
            }
            return .json(accessToken: decoded.access_token, refreshToken: decoded.refresh_token)
        }

        let cookies = Self.setCookies(from: httpResponse)
        if cookies.isEmpty {
            throw FlowError.badResponse
        }
        return .cookies(cookies)
    }

    /// Tears down the listener if still running.
    func cancel() {
        listener?.stop()
        listener = nil
        Task { @MainActor in
            BrowserAuthenticationSession.shared.cancel()
        }
    }

    deinit {
        listener?.stop()
    }

    private func debug(_ message: String) {
        guard ProcessInfo.processInfo.arguments.contains("-uitest-auth-debug") else { return }
        FileHandle.standardError.write(Data("AUTHDEBUG \(message)\n".utf8))
    }

    // MARK: - Testable pure functions

    /// Builds `{origin}/auth/native/authorize?...` with the params the Hermes
    /// dashboard expects (see Android NativeOAuth.authorizationUrl):
    /// `provider`, `code_challenge`, `code_challenge_method=S256`,
    /// `redirect_uri`, `state`.
    static func makeAuthorizeURL(origin: String, provider: String, state: String, challenge: String, redirectURI: String) -> URL {
        var components = URLComponents(string: "\(origin)/auth/native/authorize")!
        // URLComponents' default query encoding leaves ':' and '/' unescaped in
        // values; OAuth servers expect a fully percent-encoded redirect_uri.
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let encodedRedirect = redirectURI.addingPercentEncoding(withAllowedCharacters: unreserved) ?? redirectURI
        components.percentEncodedQuery = [
            "provider=\(provider)",
            "code_challenge=\(challenge)",
            "code_challenge_method=S256",
            "redirect_uri=\(encodedRedirect)",
            "state=\(state)",
        ].joined(separator: "&")
        return components.url!
    }

    /// Validates a loopback callback URL against the expected state and
    /// extracts the authorization code.
    static func validate(callbackURL: URL, expectedState: String) throws -> CallbackResult {
        guard let components = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false) else {
            throw FlowError.badResponse
        }
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        guard query["state"] == expectedState else {
            throw FlowError.stateMismatch
        }
        guard let code = query["code"], !code.isEmpty else {
            throw FlowError.badResponse
        }
        return CallbackResult(code: code, state: query["state"]!)
    }

    /// Collects Set-Cookie name→value pairs from an HTTP response.
    ///
    /// Note: HTTPURLResponse merges multiple Set-Cookie headers into one
    /// comma-separated value; split conservatively and take only the
    /// first attribute (`name=value`) of each pair.
    static func setCookies(from response: HTTPURLResponse) -> [String: String] {
        var result: [String: String] = [:]
        for (name, value) in response.allHeaderFields {
            guard let name = name as? String,
                  let value = value as? String,
                  name.caseInsensitiveCompare("Set-Cookie") == .orderedSame else { continue }
            for pair in value.components(separatedBy: ",") {
                let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
                guard parts.count == 2 else { continue }
                let cookieName = parts[0].trimmingCharacters(in: .whitespaces)
                guard isValidCookieName(cookieName) else { continue }
                let rawValue = parts[1]
                let token = rawValue.split(separator: ";").first.map(String.init) ?? rawValue
                result[cookieName] = token.trimmingCharacters(in: .whitespaces)
            }
        }
        return result
    }

    private static func isValidCookieName(_ name: String) -> Bool {
        !name.isEmpty && !name.contains(" ") && !name.contains(";") && !name.contains(",")
    }

    private static func requireHTTP(_ response: URLResponse) throws -> HTTPURLResponse {
        guard let http = response as? HTTPURLResponse else { throw FlowError.badResponse }
        return http
    }
}
