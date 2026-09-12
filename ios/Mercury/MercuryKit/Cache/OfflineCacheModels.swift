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
    let displayKind: String?

    init(role: OfflineCachedMessageRole, text: String, reasoningText: String = "", displayKind: String? = nil) {
        self.role = role
        self.text = text
        self.reasoningText = reasoningText
        self.displayKind = displayKind
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
