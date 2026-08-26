import Foundation

enum OfflineCachePolicy {
    static let maxSessionCount = 100
    static let maxMessagesPerSession = 200
    static let maxBodyBytes = 128 * 1024
    static let maxTotalBytes = 4 * 1024 * 1024
    static let retentionSeconds: Int64 = 30 * 24 * 60 * 60
    static let maxTextBytes = 4 * 1024
    static let maxIDBytes = 256
    static let maxProfileBytes = 64
    static let maxCandidateRows = maxSessionCount * 3
    static let maxEncryptedRowBytes = maxTotalBytes + (64 * 1024)
}

enum OfflineCacheError: Error, Equatable {
    case invalidScope
    case invalidSessionID
    case corruptRow
    case persistenceFailed
    case keyUnavailable
}

struct OfflineCacheScope: Hashable, Sendable {
    let origin: String
    let profile: String

    init(origin: String, profile: String) throws {
        let normalizedProfile = profile.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let normalizedOrigin = ServerOrigin.normalize(origin),
              !normalizedProfile.isEmpty,
              normalizedProfile.utf8.count <= OfflineCachePolicy.maxProfileBytes
        else { throw OfflineCacheError.invalidScope }
        self.origin = normalizedOrigin
        self.profile = normalizedProfile
    }
}

enum OfflineCachedMessageRole: String, Codable, Sendable {
    case user
    case assistant
    case system
    case tool
}

struct OfflineCachedMessage: Codable, Equatable, Sendable {
    let role: OfflineCachedMessageRole
    let text: String
    let reasoningText: String

    init(role: OfflineCachedMessageRole, text: String, reasoningText: String = "") {
        self.role = role
        self.text = text
        self.reasoningText = reasoningText
    }
}

struct OfflineCachedSession: Equatable {
    let summary: SessionRow
    let messages: [OfflineCachedMessage]
    let updatedAtEpochSeconds: Int64
}

struct OfflineCacheSnapshot: Equatable {
    var sessions: [OfflineCachedSession] = []
}

protocol OfflineCacheBacking: Sendable {
    func listRowKeys(limit: Int) throws -> [String]
    func readRow(key: String) throws -> Data?
    func writeRow(_ data: Data, key: String) throws
    func deleteRow(key: String) throws
    func readTranscriptCachingEnabled() -> Bool
    func writeTranscriptCachingEnabled(_ enabled: Bool) throws
}

protocol OfflineCacheCrypting: Sendable {
    func seal(_ plaintext: Data, authenticating associatedData: Data) throws -> Data
    func open(_ ciphertext: Data, authenticating associatedData: Data) throws -> Data
}

/// Cached-first UI state with an explicit request token. A cache read can paint
/// quickly, but never replace a newer scope or a live response from the same
/// generation. Parent integration owns when requests begin and when teardown
/// advances the generation.
struct CachedFirstSessionState: Equatable {
    struct Request: Equatable, Sendable {
        fileprivate let generation: UInt64
        fileprivate let scope: OfflineCacheScope
    }

    enum Source: Equatable, Sendable { case cached, live }

    private(set) var generation: UInt64 = 0
    private(set) var scope: OfflineCacheScope?
    private(set) var sessions: [SessionRow] = []
    private(set) var source: Source?

    mutating func begin(scope: OfflineCacheScope) -> Request {
        generation &+= 1
        self.scope = scope
        sessions = []
        source = nil
        return Request(generation: generation, scope: scope)
    }

    @discardableResult
    mutating func applyCached(_ rows: [SessionRow], for request: Request) -> Bool {
        guard accepts(request), source != .live else { return false }
        sessions = rows
        source = .cached
        return true
    }

    @discardableResult
    mutating func applyLive(_ rows: [SessionRow], for request: Request) -> Bool {
        guard accepts(request) else { return false }
        sessions = rows
        source = .live
        return true
    }

    mutating func invalidate() {
        generation &+= 1
        scope = nil
        sessions = []
        source = nil
    }

    private func accepts(_ request: Request) -> Bool {
        request.generation == generation && request.scope == scope
    }
}
