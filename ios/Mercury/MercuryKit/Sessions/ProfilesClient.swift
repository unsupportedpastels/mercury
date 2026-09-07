import Foundation
import MercuryCore

/// Profile discovery failures are deliberately non-fatal to an authenticated
/// connection. Unsupported is distinct so callers can avoid surfacing a
/// misleading connection error for older Hermes servers.
enum ProfilesClientError: Error, Equatable {
    case unsupported
    case invalidResponse
    case requestFailed
}

/// Narrow seam for controller tests and for the two official transports.
protocol ProfilesListing {
    func list() async throws -> [String]
}

/// Reads the official profile catalog without owning a connection.
///
/// Direct mode uses the released `GET /api/profiles` endpoint. Relay mode
/// carries the released `profiles.list` JSON-RPC request through the already
/// admitted `ChatConnection`; no `relay.profiles.list` method is invented.
struct ProfilesClient: ProfilesListing {
    typealias RPCRequest = (String, [String: Any]) async throws -> [String: Any]

    private let load: () async throws -> [String]

    init(httpClient: HermesHTTPClient) {
        self.load = {
            let (data, response) = try await httpClient.get(path: "/api/profiles")
            if [404, 405, 501].contains(response.statusCode) {
                throw ProfilesClientError.unsupported
            }
            guard (200..<300).contains(response.statusCode) else {
                throw ProfilesClientError.requestFailed
            }
            guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let rawRows = root["profiles"] as? [Any] else {
                throw ProfilesClientError.invalidResponse
            }
            let names = rawRows.compactMap { row -> String? in
                guard let object = row as? [String: Any] else { return nil }
                return object["name"] as? String
            }
            return Self.restNames(names)
        }
    }

    init(rpcRequest: @escaping RPCRequest) {
        self.load = {
            let result: [String: Any]
            do {
                result = try await rpcRequest(
                    "profiles.list",
                    ["include_sessions": false]
                )
            } catch let error as ChatMethodNotFoundError where error.method == "profiles.list" {
                throw ProfilesClientError.unsupported
            }
            guard let rawRows = result["profiles"] as? [Any] else {
                throw ProfilesClientError.invalidResponse
            }
            let names = rawRows.compactMap { row -> String? in
                guard let object = row as? [String: Any] else { return nil }
                return object["name"] as? String
            }
            return Self.rpcNames(names)
        }
    }

    func list() async throws -> [String] { try await load() }

    /// Shared REST adapter, exposed for characterization tests and to keep the
    /// transport distinction visible at the native boundary.
    static func restNames(_ names: [String]) -> [String] {
        Array(MercuryCore.ProfileCatalogPolicy.shared.sanitizeRestNames(names: names))
    }

    /// Shared JSON-RPC adapter, preserving the gateway's exact admission
    /// grammar and cap.
    static func rpcNames(_ names: [String]) -> [String] {
        Array(MercuryCore.ProfileCatalogPolicy.shared.sanitizeRpcNames(names: names))
    }
}
