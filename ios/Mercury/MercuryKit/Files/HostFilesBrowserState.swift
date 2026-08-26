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

    private(set) var scope = ""
    private(set) var generation: UInt64 = 0
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
        if scope != newScope {
            scope = newScope
            listing = nil
            filter = ""
        }
        isLoading = true
        errorMessage = nil
        return LoadRequest(generation: generation, scope: newScope, path: path)
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
