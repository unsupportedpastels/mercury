import Foundation
import Security

/// Bounds for locally paired Mercury Relay targets. Relay records live in
/// their own Keychain service, fully separate from direct OAuth/basic
/// credentials (`com.unsupportedpastels.mercury.tokens`) and from the direct
/// server catalog, so removing or renaming either side can never touch the
/// other's material.
enum RelayTargetPolicy {
    static let maxTargets = 8
    static let maxLabelCharacters = 80
    static let maxPersistedBytes = 64 * 1024
    static let persistedVersion = 1
}

enum RelayTargetStoreError: Error, Equatable {
    case persistenceFailed
    case corruptState
    case targetLimitReached
    case unknownTarget
    case invalidLabel
}

enum RelayTargetStatus: String, Codable, Sendable {
    /// Paired; awaiting the host operator's explicit fingerprint approval.
    case pending
    /// The host admitted this device at least once after approval.
    case approved
}

/// One paired relay installation. Contains the device's long-lived static
/// private key, so instances must never be logged or serialized outside the
/// store; UI reads only the display accessors.
struct RelayPairedTarget: Identifiable, Equatable, Sendable {
    let id: UUID
    var label: String
    let relayOrigin: String
    let installationID: Data
    let hostPublicKey: Data
    let deviceID: String
    let deviceStaticPrivateKey: Data
    /// SAS the operator compared (or must compare) during approval.
    let fingerprint: String
    var status: RelayTargetStatus
    let createdAtEpochSeconds: Int64
    var lastUsedEpochSeconds: Int64?

    var displayLabel: String {
        if !label.isEmpty { return label }
        // Never show the raw installation route; the fingerprint prefix is
        // the human handle both endpoints already display.
        return "Relay \(fingerprint.prefix(6))"
    }
}

protocol RelayTargetPersisting: Sendable {
    func readRelayTargetData() throws -> Data?
    func writeRelayTargetData(_ data: Data) throws
}

/// Device-only Keychain persistence in a dedicated relay service.
struct KeychainRelayTargetPersistence: RelayTargetPersisting {
    private static let service = "com.unsupportedpastels.mercury.relay-targets"
    private static let account = "targets-v1"

    func readRelayTargetData() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else {
            throw RelayTargetStoreError.persistenceFailed
        }
        return data
    }

    func writeRelayTargetData(_ data: Data) throws {
        guard data.count <= RelayTargetPolicy.maxPersistedBytes else {
            throw RelayTargetStoreError.persistenceFailed
        }
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        var status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var add = baseQuery
            attributes.forEach { add[$0.key] = $0.value }
            status = SecItemAdd(add as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw RelayTargetStoreError.persistenceFailed }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: Self.account
        ]
    }
}

/// Owns every paired relay target and its key material. Unlike the direct
/// server catalog's tolerant decoding, relay state fails closed: a corrupt
/// envelope surfaces an error instead of silently discarding device keys.
actor RelayTargetStore {
    private let persistence: RelayTargetPersisting
    private let now: @Sendable () -> Date
    private var loaded: [RelayPairedTarget]?

    init(
        persistence: RelayTargetPersisting = KeychainRelayTargetPersistence(),
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.persistence = persistence
        self.now = now
    }

    func load() throws -> [RelayPairedTarget] {
        if let loaded { return loaded }
        guard let data = try persistence.readRelayTargetData() else {
            loaded = []
            return []
        }
        guard data.count <= RelayTargetPolicy.maxPersistedBytes,
              let persisted = try? JSONDecoder().decode(PersistedRelayTargets.self, from: data),
              persisted.version == RelayTargetPolicy.persistedVersion
        else { throw RelayTargetStoreError.corruptState }
        var targets: [RelayPairedTarget] = []
        var seenIDs = Set<UUID>()
        for row in persisted.targets {
            guard let target = row.validated(), seenIDs.insert(target.id).inserted else {
                throw RelayTargetStoreError.corruptState
            }
            targets.append(target)
        }
        guard targets.count <= RelayTargetPolicy.maxTargets else {
            throw RelayTargetStoreError.corruptState
        }
        loaded = targets
        return targets
    }

    @discardableResult
    func add(_ target: RelayPairedTarget) throws -> RelayPairedTarget {
        var targets = try load()
        guard targets.count < RelayTargetPolicy.maxTargets else {
            throw RelayTargetStoreError.targetLimitReached
        }
        guard !targets.contains(where: { $0.id == target.id }) else {
            throw RelayTargetStoreError.persistenceFailed
        }
        targets.append(target)
        try persist(targets)
        return target
    }

    func markApproved(id: UUID) throws {
        var targets = try load()
        guard let index = targets.firstIndex(where: { $0.id == id }) else {
            throw RelayTargetStoreError.unknownTarget
        }
        targets[index].status = .approved
        targets[index].lastUsedEpochSeconds = epochSeconds()
        try persist(targets)
    }

    func touch(id: UUID) throws {
        var targets = try load()
        guard let index = targets.firstIndex(where: { $0.id == id }) else {
            throw RelayTargetStoreError.unknownTarget
        }
        targets[index].lastUsedEpochSeconds = epochSeconds()
        try persist(targets)
    }

    func updateLabel(id: UUID, label: String) throws {
        var targets = try load()
        guard let index = targets.firstIndex(where: { $0.id == id }) else {
            throw RelayTargetStoreError.unknownTarget
        }
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count <= RelayTargetPolicy.maxLabelCharacters,
              !trimmed.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
        else { throw RelayTargetStoreError.invalidLabel }
        targets[index].label = trimmed
        try persist(targets)
    }

    /// Deletes the target and all of its key material; siblings are untouched.
    /// Host-side revocation is a separate, explicitly reported step owned by
    /// the caller (removal while offline must not pretend the host knows).
    func remove(id: UUID) throws {
        var targets = try load()
        guard targets.contains(where: { $0.id == id }) else {
            throw RelayTargetStoreError.unknownTarget
        }
        targets.removeAll { $0.id == id }
        try persist(targets)
    }

    private func epochSeconds() -> Int64 {
        max(0, Int64(now().timeIntervalSince1970))
    }

    private func persist(_ targets: [RelayPairedTarget]) throws {
        let payload = PersistedRelayTargets(
            version: RelayTargetPolicy.persistedVersion,
            targets: targets.map(PersistedRelayTargets.Row.init)
        )
        let data = try JSONEncoder().encode(payload)
        guard data.count <= RelayTargetPolicy.maxPersistedBytes else {
            throw RelayTargetStoreError.persistenceFailed
        }
        try persistence.writeRelayTargetData(data)
        loaded = targets
    }
}

private struct PersistedRelayTargets: Codable {
    struct Row: Codable {
        let id: String
        let label: String
        let relayOrigin: String
        let installationID: Data
        let hostPublicKey: Data
        let deviceID: String
        let deviceStaticPrivateKey: Data
        let fingerprint: String
        let status: String
        let createdAtEpochSeconds: Int64
        let lastUsedEpochSeconds: Int64?

        init(_ target: RelayPairedTarget) {
            id = target.id.uuidString
            label = target.label
            relayOrigin = target.relayOrigin
            installationID = target.installationID
            hostPublicKey = target.hostPublicKey
            deviceID = target.deviceID
            deviceStaticPrivateKey = target.deviceStaticPrivateKey
            fingerprint = target.fingerprint
            status = target.status.rawValue
            createdAtEpochSeconds = target.createdAtEpochSeconds
            lastUsedEpochSeconds = target.lastUsedEpochSeconds
        }

        func validated() -> RelayPairedTarget? {
            guard let uuid = UUID(uuidString: id),
                  let parsedStatus = RelayTargetStatus(rawValue: status),
                  label.count <= RelayTargetPolicy.maxLabelCharacters,
                  RelayPairingPayload.normalizeOrigin(relayOrigin) != nil,
                  installationID.count == RelayProtocolPolicy.installationIDBytes,
                  hostPublicKey.count == RelayProtocolPolicy.hostPublicKeyBytes,
                  deviceStaticPrivateKey.count == RelaySecureChannelPolicy.keyBytes,
                  RelayBase64.urlSafeDecodeExact(
                      deviceID, count: RelayProtocolPolicy.deviceIDBytes
                  ) != nil,
                  fingerprint.count == RelayProtocolPolicy.fingerprintHexCharacters,
                  createdAtEpochSeconds >= 0,
                  lastUsedEpochSeconds == nil || lastUsedEpochSeconds! >= 0
            else { return nil }
            return RelayPairedTarget(
                id: uuid,
                label: label,
                relayOrigin: relayOrigin,
                installationID: installationID,
                hostPublicKey: hostPublicKey,
                deviceID: deviceID,
                deviceStaticPrivateKey: deviceStaticPrivateKey,
                fingerprint: fingerprint,
                status: parsedStatus,
                createdAtEpochSeconds: createdAtEpochSeconds,
                lastUsedEpochSeconds: lastUsedEpochSeconds
            )
        }
    }

    let version: Int
    let targets: [Row]
}
