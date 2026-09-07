import Foundation

/// Pure state for the managed host-files browser. Every asynchronous load is
/// represented by a generation token, so a response from an older path or an
/// older origin/profile scope can never replace the current listing.
struct HostFilesBrowserState: Equatable {
    struct LoadRequest: Equatable, Sendable {
        let generation: UInt64
        let scope: String
        let path: String?
    }

    struct OperationRequest: Equatable, Sendable {
        let generation: UInt64
        let scope: String
        let path: String?
    }

    private(set) var scope = ""
    private(set) var generation: UInt64 = 0
    private(set) var operationGeneration: UInt64 = 0
    private(set) var listing: HostFileListing?
    private(set) var isLoading = false
    private(set) var errorMessage: String?
    var filter = ""

    var visibleEntries: [HostFileEntry] {
        let rows = listing?.entries ?? []
        let query = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return rows }
        return rows.filter { $0.name.localizedCaseInsensitiveContains(query) }
    }

    var isEmpty: Bool {
        !isLoading && errorMessage == nil && visibleEntries.isEmpty
    }

    /// Starts a load. A scope change deliberately clears all canonical path
    /// state because paths are identities only within the authenticated host.
    mutating func beginLoad(scope newScope: String, path: String?) -> LoadRequest {
        generation &+= 1
        operationGeneration &+= 1
        if scope != newScope {
            scope = newScope
            listing = nil
            filter = ""
        }
        isLoading = true
        errorMessage = nil
        return LoadRequest(generation: generation, scope: newScope, path: path)
    }

    /// Starts a preview operation without changing the visible listing. The
    /// token is invalidated by any later list, preview, or create request.
    mutating func beginPreview(path: String) -> OperationRequest {
        beginOperation(path: path)
    }

    /// Starts a directory-creation operation rooted at the server-returned
    /// parent path. The path is carried for identity only; callers must still
    /// submit the server's canonical value unchanged.
    mutating func beginCreate(parentPath: String) -> OperationRequest {
        beginOperation(path: parentPath)
    }

    func isCurrent(_ request: OperationRequest) -> Bool {
        request.generation == operationGeneration && request.scope == scope
    }

    private mutating func beginOperation(path: String) -> OperationRequest {
        operationGeneration &+= 1
        return OperationRequest(
            generation: operationGeneration,
            scope: scope,
            path: path
        )
    }

    @discardableResult
    mutating func apply(_ loaded: HostFileListing, for request: LoadRequest) -> Bool {
        guard request.generation == generation, request.scope == scope else { return false }
        listing = loaded
        isLoading = false
        errorMessage = nil
        return true
    }

    @discardableResult
    mutating func fail(_ message: String, for request: LoadRequest) -> Bool {
        guard request.generation == generation, request.scope == scope else { return false }
        isLoading = false
        errorMessage = message
        return true
    }
}
