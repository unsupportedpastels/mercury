import Foundation

/// Tokens returned by a successful native refresh, mirroring Android's
/// `NativeTokenSet` (`connection/NativeRefreshClient.kt`).
struct NativeTokenSet: Equatable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Int64
    var provider: String
    var userID: String
}

/// Failure modes of the native token refresh contract, in parity with the
/// Android `NativeRefreshException` hierarchy:
/// - `expired`   ↔ `NativeRefreshExpiredException` (HTTP 401)
/// - `transient` ↔ `NativeRefreshTransientException` (HTTP 503)
/// - `failed`    ↔ `NativeRefreshException` (everything else)
enum RefreshError: Error, Equatable {
    case expired
    case transient
    case failed(String)
}

/// Pure port of `refreshIfNeeded` from `HermesConnectionViewModel.kt`
/// (TOKEN_REFRESH_SKEW_SECONDS = 30): a refresh is needed only when the token
/// carries a usable expiry that falls within the skew window AND both the
/// refresh token and provider are non-blank.
enum TokenRefreshPolicy {

    /// Matches Android's TOKEN_REFRESH_SKEW_SECONDS.
    static let skewSeconds: Int64 = 30

    static func needsRefresh(
        expiresAt: Int64,
        now: Int64,
        refreshToken: String?,
        provider: String?
    ) -> Bool {
        guard expiresAt > 0 else { return false }
        guard expiresAt <= now + skewSeconds else { return false }
        return isUsable(refreshToken) && isUsable(provider)
    }

    private static func isUsable(_ value: String?) -> Bool {
        guard let value = value else { return false }
        return !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// Swift port of Android's `HttpHermesNativeRefreshClient`.
///
/// POSTs `{origin}/auth/native/refresh` with body
/// `{"refresh_token": ..., "provider": ...}` and validates the response per
/// Android's `validate()`: all four string fields non-blank, `expires_at > 0`,
/// every field bounded to 16 KiB UTF-8. Status mapping:
/// 401 → `.expired`, 503 → `.transient`, other non-2xx → `.failed("HTTP <n>")`.
///
/// Logging policy: never logs token material or full request/response bodies.
final class NativeRefreshClient: @unchecked Sendable {

    /// Hard cap on any single response/request field (16 KiB UTF-8 bytes),
    /// matching MAX_REFRESH_FIELD_BYTES on Android.
    static let maxFieldBytes = 16 * 1024

    /// Hard cap on the total response body read (16 KiB).
    static let maxBodyBytes = 16 * 1024

    private let session: URLSession
    private let ownsSession: Bool

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
            self.ownsSession = false
        } else {
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 20
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: config)
            self.ownsSession = true
        }
    }

    deinit {
        if ownsSession {
            session.finishTasksAndInvalidate()
        }
    }

    // MARK: - Wire types

    private struct RefreshRequest: Encodable {
        var refreshToken: String
        var provider: String

        enum CodingKeys: String, CodingKey {
            case refreshToken = "refresh_token"
            case provider
        }
    }

    private struct RefreshResponse: Decodable {
        var accessToken: String
        var refreshToken: String
        var expiresAt: Int64
        var provider: String
        var userID: String

        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token"
            case refreshToken = "refresh_token"
            case expiresAt = "expires_at"
            case provider
            case userID = "user_id"
        }
    }

    // MARK: - Refresh

    func refresh(
        origin: String,
        refreshToken: String,
        provider: String
    ) async throws -> NativeTokenSet {
        do {
            try requireBoundedField(refreshToken, name: "refresh_token")
            try requireBoundedField(provider, name: "provider")
            guard !isBlank(refreshToken), !isBlank(provider) else {
                throw RefreshError.failed("Hermes native refresh request was incomplete")
            }

            guard let url = URL(string: "\(origin)/auth/native/refresh") else {
                throw RefreshError.failed("Hermes native refresh origin was invalid")
            }
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.cachePolicy = .reloadIgnoringLocalCacheData
            let encoder = JSONEncoder()
            request.httpBody = try encoder.encode(RefreshRequest(refreshToken: refreshToken, provider: provider))

            let data: Data
            let httpResponse: HTTPURLResponse
            do {
                let (body, response) = try await perform(request)
                data = body
                httpResponse = response
            } catch let error as RefreshError {
                throw error
            } catch {
                throw RefreshError.failed("Hermes native token refresh failed")
            }

            switch httpResponse.statusCode {
            case 401:
                throw RefreshError.expired
            case 503:
                throw RefreshError.transient
            case 200...299:
                break
            default:
                throw RefreshError.failed("Hermes native refresh returned HTTP \(httpResponse.statusCode)")
            }

            let decoded: RefreshResponse
            do {
                decoded = try JSONDecoder().decode(RefreshResponse.self, from: data)
            } catch {
                throw RefreshError.failed("Hermes native token refresh failed")
            }

            let tokens = NativeTokenSet(
                accessToken: decoded.accessToken,
                refreshToken: decoded.refreshToken,
                expiresAt: decoded.expiresAt,
                provider: decoded.provider,
                userID: decoded.userID
            )
            try validate(tokens)
            return tokens
        } catch let error as RefreshError {
            throw error
        } catch {
            // Encoding or other local failures collapse into a generic failure,
            // mirroring the catch-all on Android.
            throw RefreshError.failed("Hermes native token refresh failed")
        }
    }

    // MARK: - Internals

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        // `data(for:)` (not `.bytes`) so URLProtocol-based test sessions and
        // some proxies observe the request correctly; the bounded read below
        // still enforces the response cap after the fetch.
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw RefreshError.failed("Hermes native token refresh failed")
        }
        if data.count > Self.maxBodyBytes {
            throw RefreshError.failed("Hermes native refresh response exceeded \(Self.maxBodyBytes) bytes")
        }
        return (data, http)
    }

    private func validate(_ tokens: NativeTokenSet) throws {
        for field in [
            ("access_token", tokens.accessToken),
            ("refresh_token", tokens.refreshToken),
            ("provider", tokens.provider),
            ("user_id", tokens.userID),
        ] {
            if isBlank(field.1) {
                throw RefreshError.failed("Hermes native refresh returned blank \(field.0)")
            }
            try requireBoundedField(field.1, name: field.0)
        }
        if tokens.expiresAt <= 0 {
            throw RefreshError.failed("Hermes native refresh returned invalid expires_at")
        }
    }

    private func requireBoundedField(_ value: String, name: String) throws {
        let byteCount = value.utf8.count
        guard byteCount <= Self.maxFieldBytes else {
            throw RefreshError.failed("Hermes native refresh field \(name) exceeded \(Self.maxFieldBytes) bytes")
        }
    }

    private func isBlank(_ value: String) -> Bool {
        value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
