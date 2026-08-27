import Foundation

public struct ShareInboxEntry: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let createdAt: Date
    public let payload: SharePayload

    public init(id: String = UUID().uuidString, createdAt: Date = Date(), payload: SharePayload) {
        self.id = id
        self.createdAt = createdAt
        self.payload = payload
    }
}

public enum ShareInboxError: Error, Equatable {
    case appGroupUnavailable
    case invalidRelativePath
    case invalidEntry
}

/// Cross-process App Group inbox. Each payload is a separate atomic JSON file,
/// avoiding a shared mutable queue between the extension and host process.
/// Reading is non-destructive; only `consumeAll` removes entries. Consuming
/// returns composer material and performs no network or send operation.
public final class ShareInboxStore: @unchecked Sendable {
    public static let inboxDirectoryName = "MercuryShareInbox"
    public static let stagedDirectoryName = "MercuryShareStaged"
    public static let maxEntryBytes = 128 * 1024

    public let containerURL: URL
    public let inboxURL: URL
    public let stagedURL: URL

    private let fileManager: FileManager
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    public convenience init(appGroupIdentifier: String) throws {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroupIdentifier
        ) else {
            throw ShareInboxError.appGroupUnavailable
        }
        try self.init(containerURL: container)
    }

    public init(containerURL: URL, fileManager: FileManager = .default) throws {
        self.containerURL = containerURL.standardizedFileURL
        self.inboxURL = self.containerURL.appendingPathComponent(Self.inboxDirectoryName, isDirectory: true)
        self.stagedURL = self.containerURL.appendingPathComponent(Self.stagedDirectoryName, isDirectory: true)
        self.fileManager = fileManager
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
        try fileManager.createDirectory(at: inboxURL, withIntermediateDirectories: true)
        try fileManager.createDirectory(at: stagedURL, withIntermediateDirectories: true)
    }

    public func enqueue(_ payload: SharePayload, createdAt: Date = Date()) throws {
        guard !payload.isEmpty else { throw ShareInboxError.invalidEntry }
        guard payload.attachments.allSatisfy({ SharePayloadPolicy.isSafeRelativePath($0.stagedRelativePath) }) else {
            throw ShareInboxError.invalidRelativePath
        }
        let entry = ShareInboxEntry(createdAt: createdAt, payload: payload)
        let data = try encoder.encode(entry)
        guard data.count <= Self.maxEntryBytes else { throw ShareInboxError.invalidEntry }
        let destination = inboxURL.appendingPathComponent("\(entry.id).json", isDirectory: false)
        try data.write(to: destination, options: [.atomic, .completeFileProtectionUnlessOpen])
    }

    public func peek() throws -> [ShareInboxEntry] {
        try entryFiles().compactMap { url in
            guard let values = try? url.resourceValues(forKeys: [.fileSizeKey]),
                  let size = values.fileSize,
                  size <= Self.maxEntryBytes,
                  let data = try? Data(contentsOf: url, options: [.mappedIfSafe]),
                  let entry = try? decoder.decode(ShareInboxEntry.self, from: data),
                  entry.id == url.deletingPathExtension().lastPathComponent,
                  entry.payload.attachments.allSatisfy({ SharePayloadPolicy.isSafeRelativePath($0.stagedRelativePath) })
            else { return nil }
            return entry
        }.sorted {
            if $0.createdAt == $1.createdAt { return $0.id < $1.id }
            return $0.createdAt < $1.createdAt
        }
    }

    /// Returns every valid entry and removes exactly those files. Invalid files
    /// stay in place for diagnosis rather than being silently interpreted.
    public func consumeAll() throws -> [ShareInboxEntry] {
        let filesByID = Dictionary(uniqueKeysWithValues: try entryFiles().map {
            ($0.deletingPathExtension().lastPathComponent, $0)
        })
        let entries = try peek()
        for entry in entries {
            if let url = filesByID[entry.id] {
                try? fileManager.removeItem(at: url)
            }
        }
        return entries
    }

    public func consume(id: String) throws -> ShareInboxEntry? {
        guard let entry = try peek().first(where: { $0.id == id }) else { return nil }
        guard let file = try entryFiles().first(where: {
            $0.deletingPathExtension().lastPathComponent == entry.id
        }) else { return nil }
        try fileManager.removeItem(at: file)
        return entry
    }

    public func stagedFileURL(for attachment: SharedAttachment) throws -> URL {
        guard SharePayloadPolicy.isSafeRelativePath(attachment.stagedRelativePath) else {
            throw ShareInboxError.invalidRelativePath
        }
        let resolved = containerURL.appendingPathComponent(attachment.stagedRelativePath).standardizedFileURL
        let root = containerURL.path.hasSuffix("/") ? containerURL.path : containerURL.path + "/"
        guard resolved.path.hasPrefix(root) else { throw ShareInboxError.invalidRelativePath }
        return resolved
    }

    /// Removes staged bytes only after the host has copied/admitted them into
    /// its own transient composer storage (or after the user discards them).
    public func removeStagedFiles(for entry: ShareInboxEntry) {
        for attachment in entry.payload.attachments {
            guard let url = try? stagedFileURL(for: attachment) else { continue }
            try? fileManager.removeItem(at: url)
        }
    }

    private func entryFiles() throws -> [URL] {
        try fileManager.contentsOfDirectory(
            at: inboxURL,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ).filter { $0.pathExtension.lowercased() == "json" }
    }
}
