import Foundation
import MercuryCore

/// Folder operations are Mercury-owned, capability-gated RPCs, never private
/// Hermes routes or a fallback to a saved direct-mode origin.
enum RelayFoldersError: Error, Equatable, LocalizedError {
    case unsupported
    case invalidInput
    case invalidResponse
    case hostRejected(String)

    var errorDescription: String? {
        switch self {
        case .unsupported:
            return RelayFoldersContract.shared.unsupportedMessage
        case .invalidInput:
            return "Choose an absolute server folder and a valid single folder name."
        case .invalidResponse:
            return "The Relay host returned an invalid folder listing."
        case let .hostRejected(message):
            return message
        }
    }
}

@MainActor
final class RelayFoldersClient {
    typealias Request = @MainActor (String, [String: Any]) async throws -> [String: Any]
    private let profile: String
    private let request: Request

    init(profile: String, request: @escaping Request) {
        self.profile = profile
        self.request = request
    }

    func list(path: String? = nil) async throws -> HostFileListing {
        guard let json = RelayFoldersContract.shared.listParams(profile: profile, path: path) else {
            throw RelayFoldersError.invalidInput
        }
        return try await perform("relay.folders.list", paramsJSON: json)
    }

    func createDirectory(parentPath: String, name: String) async throws -> HostFileListing {
        guard let json = RelayFoldersContract.shared.createParams(
            profile: profile, parentPath: parentPath, name: name
        ) else { throw RelayFoldersError.invalidInput }
        // Do not retry mutations automatically after an ambiguous transport error.
        return try await perform("relay.folders.create", paramsJSON: json)
    }

    private func perform(_ method: String, paramsJSON: String) async throws -> HostFileListing {
        try Task.checkCancellation()
        let status: [String: Any]
        do { status = try await request("relay.status", [:]) }
        catch is ChatMethodNotFoundError { throw RelayFoldersError.unsupported }
        guard RelayFoldersContract.shared.supports(statusJson: try json(status)) else {
            throw RelayFoldersError.unsupported
        }
        try Task.checkCancellation()
        guard let data = paramsJSON.data(using: .utf8),
              let params = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RelayFoldersError.invalidInput
        }
        let result: [String: Any]
        do { result = try await request(method, params) }
        catch is ChatMethodNotFoundError { throw RelayFoldersError.unsupported }
        try Task.checkCancellation()
        guard let listing = RelayFoldersContract.shared.decodeListing(json: try json(result)) else {
            throw RelayFoldersError.invalidResponse
        }
        return HostFileListing(
            path: listing.path,
            entries: listing.entries.map { HostFileEntry(name: $0.name, path: $0.path, isDirectory: true) },
            parentPath: listing.parentPath,
            root: listing.root,
            lockedRoot: listing.lockedRoot,
            canChangePath: listing.canChangePath
        )
    }

    private func json(_ value: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value)
        guard let value = String(data: data, encoding: .utf8) else {
            throw RelayFoldersError.invalidResponse
        }
        return value
    }
}
