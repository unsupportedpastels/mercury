import Foundation
import Security

/// Hard limits for the local server picker. Catalog rows contain UI metadata
/// only; credentials remain in `KeychainCredentialStore` under their normalized
/// origin and are never read or copied by this module.
enum ServerCatalogPolicy {
    static let maxEntries = 8
    static let maxLabelCharacters = 80
    static let maxPersistedBytes = 64 * 1024
    static let maxDecodedEntries = maxEntries * 2
}

enum ServerCatalogError: Error, Equatable, LocalizedError {
    case invalidOrigin
    case invalidLabel
    case duplicateOrigin
    case unknownServer
    case activeServerCannotBeRemoved
    case persistenceFailed

    var errorDescription: String? {
        switch self {
        case .invalidOrigin: "Enter a valid server address."
        case .invalidLabel: "Server labels must be at most 80 characters and contain no control characters."
        case .duplicateOrigin: "That server is already in the list."
        case .unknownServer: "That server is no longer in the list."
        case .activeServerCannotBeRemoved: "Switch servers before removing the active server."
        case .persistenceFailed: "The server list could not be saved securely."
        }
    }
}

struct ServerCatalogEntry: Identifiable, Equatable, Sendable {
    let id: UUID
    let origin: String
    var label: String
    var lastUsedEpochSeconds: Int64?

    var displayLabel: String { label.isEmpty ? origin : label }

    init(
        id: UUID,
        origin: String,
        label: String = "",
        lastUsedEpochSeconds: Int64? = nil
    ) throws {
        guard let normalizedOrigin = ServerOrigin.normalize(origin) else {
            throw ServerCatalogError.invalidOrigin
        }
        let normalizedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard normalizedLabel.count <= ServerCatalogPolicy.maxLabelCharacters,
              !normalizedLabel.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              lastUsedEpochSeconds == nil || lastUsedEpochSeconds! >= 0
        else { throw ServerCatalogError.invalidLabel }
        self.id = id
        self.origin = normalizedOrigin
        self.label = normalizedLabel
        self.lastUsedEpochSeconds = lastUsedEpochSeconds
    }
}

struct ServerCatalog: Equatable, Sendable {
    fileprivate(set) var entries: [ServerCatalogEntry]
    fileprivate(set) var activeID: UUID?

    var activeEntry: ServerCatalogEntry? {
        entries.first { $0.id == activeID }
    }

    static let empty = ServerCatalog(entries: [], activeID: nil)
}

protocol ServerCatalogPersisting: Sendable {
    func readCatalogData() throws -> Data?
    func writeCatalogData(_ data: Data) throws
}

protocol LegacyServerOriginPersisting: Sendable {
    func readLegacyOrigin() -> String?
    func clearLegacyOrigin()
}

/// Migration seam for the former single-origin preference. Integration may
/// choose another suite/key without changing catalog decisions.
final class UserDefaultsLegacyServerOrigin: LegacyServerOriginPersisting, @unchecked Sendable {
    private let defaults: UserDefaults
    private let key: String

    init(defaults: UserDefaults = .standard, key: String = "server_origin") {
        self.defaults = defaults
        self.key = key
    }

    func readLegacyOrigin() -> String? { defaults.string(forKey: key) }
    func clearLegacyOrigin() { defaults.removeObject(forKey: key) }
}

/// Catalog payload in a dedicated Keychain service. This is deliberately
/// separate from the token service, preserving origin-scoped credential
/// isolation even while server rows are renamed, reordered, or removed.
struct KeychainServerCatalogPersistence: ServerCatalogPersisting {
    private static let service = "com.unsupportedpastels.mercury.server-catalog"
    private static let account = "catalog-v1"

    func readCatalogData() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else {
            throw ServerCatalogError.persistenceFailed
        }
        return data
    }

    func writeCatalogData(_ data: Data) throws {
        guard data.count <= ServerCatalogPolicy.maxPersistedBytes else {
            throw ServerCatalogError.persistenceFailed
        }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        var status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var add = baseQuery
            attributes.forEach { add[$0.key] = $0.value }
            status = SecItemAdd(add as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw ServerCatalogError.persistenceFailed }
    }

    func clearCatalogData() {
        SecItemDelete(baseQuery as CFDictionary)
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account
        ]
    }
}

actor ServerCatalogStore {
    private let persistence: ServerCatalogPersisting
    private let legacyOrigin: LegacyServerOriginPersisting?
    private let idGenerator: @Sendable () -> UUID
    private let now: @Sendable () -> Date
    private var loadedCatalog: ServerCatalog?

    init(
        persistence: ServerCatalogPersisting = KeychainServerCatalogPersistence(),
        legacyOrigin: LegacyServerOriginPersisting? = UserDefaultsLegacyServerOrigin(),
        idGenerator: @escaping @Sendable () -> UUID = { UUID() },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.persistence = persistence
        self.legacyOrigin = legacyOrigin
        self.idGenerator = idGenerator
        self.now = now
    }

    func load() throws -> ServerCatalog {
        if let loadedCatalog { return loadedCatalog }
        if let data = try persistence.readCatalogData() {
            let decoded = decodeTolerantly(data)
            loadedCatalog = decoded
            return decoded
        }

        guard let legacy = legacyOrigin?.readLegacyOrigin(),
              let normalized = ServerOrigin.normalize(legacy),
              let entry = try? ServerCatalogEntry(id: idGenerator(), origin: normalized)
        else {
            loadedCatalog = .empty
            return .empty
        }
        let migrated = ServerCatalog(entries: [entry], activeID: entry.id)
        try persist(migrated)
        legacyOrigin?.clearLegacyOrigin() // clear only after secure persistence succeeds
        loadedCatalog = migrated
        return migrated
    }

    @discardableResult
    func add(origin: String, label: String = "") throws -> ServerCatalogEntry {
        var catalog = try load()
        guard let normalized = ServerOrigin.normalize(origin) else { throw ServerCatalogError.invalidOrigin }
        guard !catalog.entries.contains(where: { $0.origin == normalized }) else {
            throw ServerCatalogError.duplicateOrigin
        }
        let entry = try ServerCatalogEntry(
            id: idGenerator(),
            origin: normalized,
            label: label,
            lastUsedEpochSeconds: epochSeconds()
        )
        catalog.entries.append(entry)
        catalog.activeID = entry.id
        catalog = Self.bounded(catalog)
        try persist(catalog)
        loadedCatalog = catalog
        return entry
    }

    func select(id: UUID) throws {
        var catalog = try load()
        guard let index = catalog.entries.firstIndex(where: { $0.id == id }) else {
            throw ServerCatalogError.unknownServer
        }
        catalog.entries[index].lastUsedEpochSeconds = epochSeconds()
        catalog.activeID = id
        try persist(catalog)
        loadedCatalog = catalog
    }

    func updateLabel(id: UUID, label: String) throws {
        var catalog = try load()
        guard let index = catalog.entries.firstIndex(where: { $0.id == id }) else {
            throw ServerCatalogError.unknownServer
        }
        let old = catalog.entries[index]
        catalog.entries[index] = try ServerCatalogEntry(
            id: old.id,
            origin: old.origin,
            label: label,
            lastUsedEpochSeconds: old.lastUsedEpochSeconds
        )
        try persist(catalog)
        loadedCatalog = catalog
    }

    @discardableResult
    func remove(id: UUID) throws -> Bool {
        var catalog = try load()
        guard catalog.activeID != id else { return false }
        guard catalog.entries.contains(where: { $0.id == id }) else { return false }
        catalog.entries.removeAll { $0.id == id }
        try persist(catalog)
        loadedCatalog = catalog
        return true
    }

    private func epochSeconds() -> Int64 {
        max(0, Int64(now().timeIntervalSince1970))
    }

    private func persist(_ catalog: ServerCatalog) throws {
        let payload = PersistedServerCatalog(
            entries: catalog.entries.map(PersistedServerCatalog.Entry.init),
            activeID: catalog.activeID?.uuidString
        )
        let data = try JSONEncoder().encode(payload)
        guard data.count <= ServerCatalogPolicy.maxPersistedBytes else {
            throw ServerCatalogError.persistenceFailed
        }
        try persistence.writeCatalogData(data)
    }

    /// Local state is attacker/corruption tolerant: an invalid envelope yields
    /// an empty catalog; malformed rows are skipped; labels are safely clipped;
    /// duplicate origins retain their position but adopt the newest metadata.
    private func decodeTolerantly(_ data: Data) -> ServerCatalog {
        guard data.count <= ServerCatalogPolicy.maxPersistedBytes,
              let persisted = try? JSONDecoder().decode(PersistedServerCatalog.self, from: data)
        else { return .empty }

        let raw = Array(persisted.entries.prefix(ServerCatalogPolicy.maxDecodedEntries))
        let requestedActiveOrigin = raw.first(where: { $0.id == persisted.activeID })
            .flatMap { ServerOrigin.normalize($0.origin) }
        var order: [String] = []
        var byOrigin: [String: ServerCatalogEntry] = [:]
        var seenIDs = Set<UUID>()
        for row in raw {
            guard let id = UUID(uuidString: row.id),
                  seenIDs.insert(id).inserted,
                  let normalized = ServerOrigin.normalize(row.origin),
                  row.lastUsedEpochSeconds == nil || row.lastUsedEpochSeconds! >= 0
            else { continue }
            let clipped = String(row.label.prefix(ServerCatalogPolicy.maxLabelCharacters))
            guard let entry = try? ServerCatalogEntry(
                id: id,
                origin: normalized,
                label: clipped,
                lastUsedEpochSeconds: row.lastUsedEpochSeconds
            ) else { continue }
            if byOrigin[normalized] == nil { order.append(normalized) }
            byOrigin[normalized] = entry
        }
        var entries = order.compactMap { byOrigin[$0] }
        let activeID = requestedActiveOrigin.flatMap { byOrigin[$0]?.id } ?? entries.first?.id
        var catalog = ServerCatalog(entries: entries, activeID: activeID)
        catalog = Self.bounded(catalog)
        entries = catalog.entries
        return ServerCatalog(entries: entries, activeID: catalog.activeID)
    }

    private static func bounded(_ input: ServerCatalog) -> ServerCatalog {
        var entries = input.entries
        while entries.count > ServerCatalogPolicy.maxEntries {
            let candidates = entries.indices.filter { entries[$0].id != input.activeID }
            let removal = candidates.min { lhs, rhs in
                let left = entries[lhs].lastUsedEpochSeconds ?? Int64.min
                let right = entries[rhs].lastUsedEpochSeconds ?? Int64.min
                return left == right ? lhs < rhs : left < right
            } ?? entries.indices.last!
            entries.remove(at: removal)
        }
        let active = input.activeID.flatMap { id in entries.contains(where: { $0.id == id }) ? id : nil }
            ?? entries.first?.id
        return ServerCatalog(entries: entries, activeID: active)
    }
}

private struct PersistedServerCatalog: Codable {
    struct Entry: Codable {
        let id: String
        let origin: String
        let label: String
        let lastUsedEpochSeconds: Int64?

        init(_ entry: ServerCatalogEntry) {
            id = entry.id.uuidString
            origin = entry.origin
            label = entry.label
            lastUsedEpochSeconds = entry.lastUsedEpochSeconds
        }
    }

    let entries: [Entry]
    let activeID: String?
}
