import Foundation
import MercuryCore
import Security

/// Bounds for locally paired Mercury Relay targets. Relay records live in
/// their own Keychain service, fully separate from direct OAuth/basic
/// credentials (`com.unsupportedpastels.mercury.tokens`) and from the direct
/// server catalog, so removing or renaming either side can never touch the
/// other's material.
enum RelayTargetPolicy {
    static let maxTargets = Int(MercuryCore.RelayTargetCodec.shared.maxTargets)
    static let maxLabelCharacters = Int(MercuryCore.RelayTargetCodec.shared.maxLabelCharacters)
    static let maxPersistedBytes = Int(MercuryCore.RelayTargetCodec.shared.maxPersistedBytes)
    static let persistedVersion = Int(MercuryCore.RelayTargetCodec.shared.persistedVersion)
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
    /// Router admission token; renewed by the host on every lease attach.
    var relayRoutingToken: String?

    init(
        id: UUID,
        label: String,
        relayOrigin: String,
        installationID: Data,
        hostPublicKey: Data,
        deviceID: String,
        deviceStaticPrivateKey: Data,
        fingerprint: String,
        status: RelayTargetStatus,
        createdAtEpochSeconds: Int64,
        lastUsedEpochSeconds: Int64?,
        relayRoutingToken: String? = nil
    ) {
        self.id = id
        self.label = label
        self.relayOrigin = relayOrigin
        self.installationID = installationID
        self.hostPublicKey = hostPublicKey
        self.deviceID = deviceID
        self.deviceStaticPrivateKey = deviceStaticPrivateKey
        self.fingerprint = fingerprint
        self.status = status
        self.createdAtEpochSeconds = createdAtEpochSeconds
        self.lastUsedEpochSeconds = lastUsedEpochSeconds
        self.relayRoutingToken = relayRoutingToken
    }

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
        guard data.count <= RelayTargetPolicy.maxPersistedBytes else {
            throw RelayTargetStoreError.corruptState
        }
        let targets: [RelayPairedTarget]
        do {
            targets = try MercuryCore.RelayTargetCodec.shared
                .decode(data: data.relayKotlinBytes)
                .map(RelayPairedTarget.init(core:))
        } catch {
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

    /// Stores a router token the host renewed over the authenticated channel.
    func updateRoutingToken(id: UUID, token: String) throws {
        var targets = try load()
        guard let index = targets.firstIndex(where: { $0.id == id }) else {
            throw RelayTargetStoreError.unknownTarget
        }
        guard !token.isEmpty,
              token.count <= Int(MercuryCore.RelayProtocolPolicy.shared.maxRoutingTokenCharacters),
              token.utf8.allSatisfy({ $0 >= 0x21 && $0 <= 0x7e })
        else { throw RelayTargetStoreError.corruptState }
        targets[index].relayRoutingToken = token
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

    /// Deletes every target and its key material. Test-support surface: the
    /// UI-test reset hook is the only production caller.
    func removeAll() throws {
        // Bypass load() so a corrupt persisted envelope can still be cleared.
        try persist([])
    }

    private func epochSeconds() -> Int64 {
        max(0, Int64(now().timeIntervalSince1970))
    }

    private func persist(_ targets: [RelayPairedTarget]) throws {
        let data: Data
        do {
            data = try MercuryCore.RelayTargetCodec.shared
                .encode(targets: targets.map(\.coreTarget))
                .relayData
        } catch {
            throw RelayTargetStoreError.persistenceFailed
        }
        guard data.count <= RelayTargetPolicy.maxPersistedBytes else {
            throw RelayTargetStoreError.persistenceFailed
        }
        try persistence.writeRelayTargetData(data)
        loaded = targets
    }
}

private extension RelayPairedTarget {
    var coreTarget: MercuryCore.RelayPairedTarget {
        MercuryCore.RelayPairedTarget(
            id: id.uuidString,
            label: label,
            relayOrigin: relayOrigin,
            installationId: installationID.relayKotlinBytes,
            hostPublicKey: hostPublicKey.relayKotlinBytes,
            deviceId: deviceID,
            deviceStaticPrivateKey: deviceStaticPrivateKey.relayKotlinBytes,
            fingerprint: fingerprint,
            status: status == .approved ? .approved : .pending,
            createdAtEpochSeconds: createdAtEpochSeconds,
            lastUsedEpochSeconds: lastUsedEpochSeconds.map(KotlinLong.init(value:)),
            relayRoutingToken: relayRoutingToken
        )
    }

    init(core: MercuryCore.RelayPairedTarget) throws {
        guard let id = UUID(uuidString: core.id) else {
            throw RelayTargetStoreError.corruptState
        }
        self.init(
            id: id,
            label: core.label,
            relayOrigin: core.relayOrigin,
            installationID: core.installationId.relayData,
            hostPublicKey: core.hostPublicKey.relayData,
            deviceID: core.deviceId,
            deviceStaticPrivateKey: core.deviceStaticPrivateKey.relayData,
            fingerprint: core.fingerprint,
            status: core.status == .approved ? .approved : .pending,
            createdAtEpochSeconds: core.createdAtEpochSeconds,
            lastUsedEpochSeconds: core.lastUsedEpochSeconds?.int64Value,
            relayRoutingToken: core.relayRoutingToken
        )
    }
}
