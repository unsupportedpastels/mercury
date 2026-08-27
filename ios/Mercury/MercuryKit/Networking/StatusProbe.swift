import Foundation

/// Probes the official Hermes HTTP API for server status and authentication
/// capabilities. Thin wrapper over `HermesHTTPClient` that adds status-code
/// classification via `HermesAuthError.classify`.
struct StatusProbe {

    private let client: HermesHTTPClient

    init(client: HermesHTTPClient) {
        self.client = client
    }

    /// Fetches `GET /api/status` and decodes the server status payload.
    ///
    /// - Returns: The decoded `HermesStatus`.
    /// - Throws: `HermesAuthError` for 401/403 (`.authRejected`) or 5xx
    ///   (`.transient`); `TransportError` for URLSession failures;
    ///   `ResponseTooLargeError` if the body exceeds the cap; decoding errors
    ///   from `JSONDecoder`.
    func probe() async throws -> HermesStatus {
        let (data, http) = try await client.get(path: "/api/status")
        if let error = HermesAuthError.classify(http.statusCode) {
            throw error
        }
        return try JSONDecoder().decode(HermesStatus.self, from: data)
    }

    /// Fetches `GET /api/auth/providers` and decodes the advertised auth
    /// providers. Error mapping matches `probe()`.
    func authProviders() async throws -> AuthProvidersResponse {
        let (data, http) = try await client.get(path: "/api/auth/providers")
        if let error = HermesAuthError.classify(http.statusCode) {
            throw error
        }
        return try JSONDecoder().decode(AuthProvidersResponse.self, from: data)
    }
}
