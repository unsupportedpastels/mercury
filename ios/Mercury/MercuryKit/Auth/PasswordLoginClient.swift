import Foundation

enum PasswordLoginError: Error, Equatable {
    case invalidCredentials
    case invalidOrigin
    case rejected(Int)
    case transient(Int)
    case responseTooLarge
    case transport(Int)
}

/// Cookie-backed username/password authentication for Hermes dashboard servers.
///
/// Mirrors Android's `HttpHermesPasswordAuthClient` exactly: POST the selected
/// password-capable provider, a bounded trimmed username, the unmodified
/// password, and `next: "/"` to `/auth/password-login`. Passwords are never
/// persisted or included in errors; the resulting HttpOnly session cookie is
/// replayed by the shared cookie store.
struct PasswordLoginClient: @unchecked Sendable {
    private struct Payload: Encodable {
        let provider: String
        let username: String
        let password: String
        let next: String
    }

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func signIn(
        origin rawOrigin: String,
        provider rawProvider: String,
        username rawUsername: String,
        password: String
    ) async throws {
        guard let origin = ServerOrigin.normalize(rawOrigin),
              let url = URL(string: origin + "/auth/password-login") else {
            throw PasswordLoginError.invalidOrigin
        }
        let provider = rawProvider.trimmingCharacters(in: .whitespacesAndNewlines)
        let username = rawUsername.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !provider.isEmpty, provider.count <= 256,
              !username.isEmpty, username.count <= 256,
              !password.isEmpty, password.count <= 4_096 else {
            throw PasswordLoginError.invalidCredentials
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(Payload(
            provider: provider,
            username: username,
            password: password,
            next: "/"
        ))

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            throw PasswordLoginError.transport(error.code.rawValue)
        } catch {
            throw PasswordLoginError.transport((error as NSError).code)
        }
        guard let http = response as? HTTPURLResponse else {
            throw PasswordLoginError.transport(URLError.badServerResponse.rawValue)
        }
        guard data.count <= HermesHTTPClient.maxResponseBytes else {
            throw PasswordLoginError.responseTooLarge
        }

        switch http.statusCode {
        case 200..<300:
            persistResponseCookies(http, for: url)
        case 401, 403:
            throw PasswordLoginError.invalidCredentials
        case 408, 425, 429, 500...599:
            throw PasswordLoginError.transient(http.statusCode)
        default:
            throw PasswordLoginError.rejected(http.statusCode)
        }
    }

    private func persistResponseCookies(_ response: HTTPURLResponse, for url: URL) {
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, field in
            guard let name = field.key as? String, let value = field.value as? String else { return }
            result[name] = value
        }
        let cookies = HTTPCookie.cookies(withResponseHeaderFields: headers, for: url)
        guard !cookies.isEmpty else { return }
        let configuredStore = session.configuration.httpCookieStorage
        for cookie in cookies {
            configuredStore?.setCookie(cookie)
            if configuredStore !== HTTPCookieStorage.shared {
                HTTPCookieStorage.shared.setCookie(cookie)
            }
        }
    }
}
