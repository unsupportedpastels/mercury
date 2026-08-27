import Foundation

/// Errors arising from Hermes authentication/transport classification.
enum HermesAuthError: Error, Equatable {
    /// The server explicitly refused the credentials (401/403).
    case authRejected
    /// A retryable server-side condition (5xx), with a coarse reason code.
    case transient(String)
}

extension HermesAuthError {

    /// Maps an HTTP status to an auth error, or `nil` when the status needs
    /// no special handling (success, 4xx other than 401/403, etc.).
    static func classify(_ status: Int) -> HermesAuthError? {
        switch status {
        case 401, 403:
            return .authRejected
        case 500...599:
            return .transient("http_\(status)")
        default:
            return nil
        }
    }
}

/// Wraps a URLSession-level failure (DNS, TLS, timeout, connection reset…).
struct TransportError: Error {
    let underlying: any Error
}
