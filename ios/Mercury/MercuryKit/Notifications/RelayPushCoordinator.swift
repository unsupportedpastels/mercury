import Foundation
import Observation
import CoreFoundation
import Security
import MercuryNotificationPreviewKit
import MercuryCore

/// Native APNs lifecycle only. Tokens travel exclusively in authenticated Relay RPCs.
@MainActor @Observable
final class RelayPushCoordinator {
    typealias Request = @MainActor (String, [String: Any]) async throws -> [String: Any]
    struct ResolvedSessionRoute: Equatable, Sendable {
        let durableSessionID: String
        let profile: String
    }
    struct Scope: Codable, Equatable {
        let id: UUID
        let origin: String
        let installation: Data
        let device: String
        let hostKey: Data
        init(_ target: RelayPairedTarget) {
            id = target.id; origin = target.relayOrigin; installation = target.installationID
            device = target.deviceID; hostKey = target.hostPublicKey
        }
    }
    struct Binding: Codable {
        let scope: Scope
        let wake: String
        var previewKeyID: String? = nil
        var completion: Bool? = nil
        var attention: Bool? = nil
        var retiredKeyID: String? = nil
        var retiredWake: String? = nil
    }
    /// Routing metadata only: never a delivery owner or decryption key.
    struct RetiredPreviewScope: Codable {
        let scope: Scope
        let wake: String
        let expiresAt: Int64
    }
    private(set) var status = "Relay push is off. Direct notifications remain best effort."
    private(set) var enabled = false
    private(set) var previewEnabled: Bool
    private(set) var includeTitle: Bool
    private(set) var includeResponseExcerpt: Bool
    private var token: String?
    private var appleRegistrationUnavailable = false
    private var scope: Scope?
    private var request: Request?
    private var connectionID: ObjectIdentifier?
    private var requests: [UUID: Request] = [:]
    private var generation = UUID()
    private var forcePreviewRotation = false
    private var work: Task<Void, Never>?
    private var bindings: [Binding]
    private var retiredPreviewScopes: [RetiredPreviewScope]
    private let now: () -> Int64
    private let retiredScopesKey = "mercury.apns.retired-preview-scopes.v1"
    // A retired key may authenticate one final arrival during its existing
    // 15-minute grace. That arrival's receipt has its own seven-day lifetime.
    static let retiredRouteRetention = PreviewRouteStore.maxRetentionSeconds + 900
    static let maxRetiredScopes = 64
    static let maxRetiredScopeBytes = 32 * 1024
    private let defaults: UserDefaults
    private let sandbox: Bool
    private let storageKey = "mercury.apns.wake-bindings.v1"
    private let previewKeys = PreviewKeychainRepository()
    private let previewEnabledKey = "mercury.apns.preview.enabled.v1"
    private let previewTitleKey = "mercury.apns.preview.title.v1"
    private let previewExcerptKey = "mercury.apns.preview.excerpt.v1"

    init(defaults: UserDefaults = .standard, sandbox: Bool = RelayPushCoordinator.isSandboxBuild,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970) }) {
        self.defaults = defaults; self.sandbox = sandbox; self.now = now
        previewEnabled = defaults.bool(forKey: previewEnabledKey)
        includeTitle = defaults.bool(forKey: previewTitleKey)
        includeResponseExcerpt = defaults.bool(forKey: previewExcerptKey)
        bindings = (defaults.data(forKey: storageKey).flatMap { try? JSONDecoder().decode([Binding].self, from: $0) }) ?? []
        retiredPreviewScopes = (defaults.data(forKey: retiredScopesKey).flatMap { data -> [RetiredPreviewScope]? in
            // Bound decoding as well as the resulting collection. An oversized
            // legacy value is discarded, never reimported or granted a new TTL.
            guard data.count <= Self.maxRetiredScopeBytes else { return nil }
            return try? JSONDecoder().decode([RetiredPreviewScope].self, from: data)
        }) ?? []
        if defaults.object(forKey: retiredScopesKey) == nil {
            // One-time upgrade of the formerly key-cleanup-only predecessor.
            // Persist immediately so cold launches cannot renew its lifetime.
            retiredPreviewScopes = bindings.suffix(Self.maxRetiredScopes).compactMap { binding in
                guard let wake = binding.retiredWake, Self.validWake(wake) else { return nil }
                return RetiredPreviewScope(scope: binding.scope, wake: wake, expiresAt: now() + Self.retiredRouteRetention)
            }
        }
        persist()
    }
    nonisolated static var isSandboxBuild: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
    nonisolated static func tokenHex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }
    nonisolated static func validWake(_ value: String) -> Bool {
        value.utf8.count == 43 && value.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        }
    }
    nonisolated static func wake(from info: [AnyHashable: Any]) -> String? {
        guard let wake = info["mercury_wake"] as? String, validWake(wake) else { return nil }
        return wake
    }
    nonisolated static func supports(_ status: [String: Any]) -> Bool {
        MercuryCore.RelayPushRoutePolicy.shared.supports(status: sharedPushStatus(status))
    }
    nonisolated static func supportsSessionResolution(_ status: [String: Any]) -> Bool {
        MercuryCore.RelayPushRoutePolicy.shared.supportsSessionResolution(status: sharedPushStatus(status))
    }
    struct PreviewCapability: Equatable { let maxPlaintextBytes, maxTitleBytes, maxBodyBytes: Int }
    nonisolated static func supportsArrivalInspection(_ status: [String: Any]) -> Bool {
        guard let caps = status["capabilities"] as? [String: Any],
              let route = caps["push_notification_routes"] as? [String: Any],
              exactInteger(route["version"], equals: 1) != nil,
              route["inspect_method"] as? String == "relay.push.inspect" else { return false }
        return true
    }
    nonisolated static func previewCapability(_ status: [String: Any]) -> PreviewCapability? {
        guard let caps = status["capabilities"] as? [String: Any], let p = caps["push_previews"] as? [String: Any],
              exactInteger(p["version"], equals: 1) != nil,
              p["register_method"] as? String == "relay.push.preview.register",
              p["unregister_method"] as? String == "relay.push.unregister",
              p["aead"] as? String == "CHACHA20-POLY1305",
              let plaintext = exactInteger(p["max_plaintext_bytes"]), (1...1280).contains(plaintext),
              let title = exactInteger(p["max_title_utf8_bytes"]), (1...160).contains(title),
              let body = exactInteger(p["max_body_utf8_bytes"]), (1...640).contains(body) else { return nil }
        return PreviewCapability(maxPlaintextBytes: plaintext, maxTitleBytes: title, maxBodyBytes: body)
    }
    private nonisolated static func exactInteger(_ value: Any?, equals: Int? = nil) -> Int? {
        guard let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.rounded() == n.doubleValue else { return nil }
        let result = n.intValue
        return equals.map { $0 == result ? result : nil } ?? result
    }
    private nonisolated static func random(_ count: Int) -> Data? {
        var data = Data(count: count)
        let result = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return result == errSecSuccess ? data : nil
    }
    nonisolated static func resolvedSessionRoute(_ result: [String: Any]) -> ResolvedSessionRoute? {
        var wire = result
        wire["resolved"] = sharedPushValue(result["resolved"])
        guard let route = MercuryCore.RelayPushRoutePolicy.shared.resolvedSessionRoute(result: wire) else { return nil }
        return ResolvedSessionRoute(durableSessionID: route.durableSessionId, profile: route.profile)
    }
    // Foundation's NSNumber can bridge numeric 1 as Bool. Preserve its JSON type
    // explicitly for Kotlin's plain-map API; all acceptance policy stays shared.
    nonisolated private static func sharedPushValue(_ value: Any?) -> Any? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return value }
        return KotlinBoolean(bool: number.boolValue)
    }
    nonisolated private static func sharedPushStatus(_ status: [String: Any]) -> [String: Any] {
        var wire = status
        if var caps = status["capabilities"] as? [String: Any] {
            for key in ["push_notifications_v1", "push_notifications_v2"] { caps[key] = sharedPushValue(caps[key]) }
            wire["capabilities"] = caps
        }
        return wire
    }
    static func resolveSessionRoute(wake: String, request: Request) async -> ResolvedSessionRoute? {
        guard validWake(wake) else { return nil }
        do {
            let status = try await request("relay.status", [:])
            guard supportsSessionResolution(status) else { return nil }
            let result = try await request("relay.push.resolve", ["wake_handle": wake])
            return resolvedSessionRoute(result)
        } catch {
            // V1/older or temporarily unavailable hosts retain the existing
            // safe fallback: select the mapped Relay host's Home screen.
            return nil
        }
    }
    nonisolated static func genericPushEnabled(_ preferences: MercuryNotificationPreferences, authorized: Bool) -> Bool {
        // This first delivery contract is intentionally content-free and cannot
        // remotely distinguish per-category choices. Keep selective choices on
        // the existing local path rather than overriding a disabled category.
        authorized && preferences.notificationsEnabled && preferences.completionEnabled && preferences.attentionEnabled
    }
    nonisolated static func replacesLocalDelivery(for event: ChatEvent) -> Bool {
        switch event {
        case .messageComplete(_, let text, let wireStatus, _, _, _, _, _, _):
            return NotificationTextPolicy.completionStatus(fromWire: wireStatus) == .finished
                && !(text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && !(text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("Operation interrupted:")
        case .approvalRequest, .clarifyRequest: return true
        case .unsupportedBlockingRequest(_, let kind, _, _): return kind == .secret || kind == .sudo
        default: return false
        }
    }
    /// `enabled` is desired registration, while bindings are acknowledged host
    /// ownership. Selective fallback retains the latter until removal succeeds.
    var needsConnection: Bool { enabled || !bindings.isEmpty }
    func setEnabled(_ value: Bool, retainOwnershipUntilUnregister: Bool = false) {
        guard enabled != value || (!value && !retainOwnershipUntilUnregister && (!bindings.isEmpty || !retiredPreviewScopes.isEmpty)) else { return }
        enabled = value; generation = UUID()
        if !value {
            deletePreviewKeys()
            if !retainOwnershipUntilUnregister { bindings.removeAll(); retiredPreviewScopes.removeAll(); persist() }
            status = "Relay push is off. Direct notifications remain best effort."
            for (id, request) in requests { enqueueUnregister(request, targetID: id) }
        } else { reconcile() }
        writeDiagnostic()
    }
    func setPreview(enabled value: Bool, includeTitle title: Bool, includeResponseExcerpt excerpt: Bool) {
        guard previewEnabled != value || includeTitle != title || includeResponseExcerpt != excerpt else { return }
        if !value { deletePreviewKeys() }
        previewEnabled = value; includeTitle = title; includeResponseExcerpt = excerpt
        defaults.set(value, forKey: previewEnabledKey); defaults.set(title, forKey: previewTitleKey); defaults.set(excerpt, forKey: previewExcerptKey)
        generation = UUID(); reconcile()
    }
    func rotatePreviewKey() { guard previewEnabled else { return }; forcePreviewRotation = true; generation = UUID(); reconcile() }
    func setPreviewCategories(completion: Bool, attention: Bool) {
        guard currentCompletion != completion || currentAttention != attention else { return }
        defaults.set(completion, forKey: "mercury.notif.preview.completion")
        defaults.set(attention, forKey: "mercury.notif.preview.attention")
        generation = UUID(); reconcile()
    }
    private var currentCompletion: Bool { defaults.object(forKey: "mercury.notif.preview.completion") as? Bool ?? true }
    private var currentAttention: Bool { defaults.object(forKey: "mercury.notif.preview.attention") as? Bool ?? true }
    func receivedToken(_ data: Data) {
        guard !data.isEmpty else { registrationFailed(); return }
        let next = Self.tokenHex(data)
        guard token != next else { return }
        // Preserve the active binding across cold launch and token rotation until
        // the generation-fenced replacement registration succeeds. This also
        // lets preview mode re-provision the same independent key.
        token = next; appleRegistrationUnavailable = false
        generation = UUID(); reconcile(); writeDiagnostic()
    }
    func registrationFailed() {
        // Apple invalidated this registration. Restore local delivery immediately,
        // including persisted cold-launch routes, and reject any pending RPC result.
        token = nil; generation = UUID()
        appleRegistrationUnavailable = true
        deletePreviewKeys()
        bindings.removeAll(); retiredPreviewScopes.removeAll(); persist()
        status = "Apple push is unavailable. Notifications remain best effort."
        // Do not cancel/replace the pending-task chain: cleanup must follow an
        // in-flight register, and a fresh callback must register after cleanup.
        for request in requests.values { enqueueUnregister(request) }
        writeDiagnostic()
    }
    func select(_ target: RelayPairedTarget?) {
        let next = target.map(Scope.init)
        guard next != scope else { return }
        scope = next; request = nil; connectionID = nil; generation = UUID()
        if enabled { status = "Waiting for a supported paired Relay host." }
    }
    func connected(target: RelayPairedTarget, identity: ObjectIdentifier, request: @escaping Request) {
        guard scope == Scope(target), target.status == .approved else { return }
        guard connectionID != identity else { return }
        connectionID = identity; self.request = request; requests[target.id] = request; generation = UUID()
        if !enabled || appleRegistrationUnavailable { enqueueUnregister(request, targetID: target.id) }
        if enabled { reconcile() }
    }
    func target(for wake: String, targets: [RelayPairedTarget]) -> RelayPairedTarget? {
        guard Self.validWake(wake), let binding = bindings.first(where: { $0.wake == wake }) else { return nil }
        return targets.first { $0.status == .approved && Scope($0) == binding.scope }
    }
    /// Only a protected authenticated receipt may use historical routing scope.
    /// Generic wake resolution, inspection RPCs, and delivery ownership continue
    /// using current bindings exclusively.
    func previewTarget(for wake: String, targets: [RelayPairedTarget]) -> RelayPairedTarget? {
        guard Self.validWake(wake) else { return nil }
        if pruneRetiredScopes() { persistRetiredScopes() }
        let scopes = bindings.filter { $0.wake == wake }.map(\.scope)
            + retiredPreviewScopes.filter { $0.wake == wake }.map(\.scope)
        guard let scope = scopes.first, scopes.allSatisfy({ $0 == scope }) else { return nil }
        return targets.first { $0.status == .approved && Scope($0) == scope }
    }
    func ownsDelivery(for target: RelayPairedTarget?) -> Bool {
        guard let target else { return false }
        return bindings.contains { $0.scope == Scope(target) }
    }
    func ownsDelivery(for target: RelayPairedTarget?, event: ChatEvent) -> Bool {
        guard let target, let binding = bindings.first(where: { $0.scope == Scope(target) }) else { return false }
        guard binding.previewKeyID != nil else { return true }
        switch event {
        case .messageComplete: return binding.completion == true
        case .approvalRequest, .clarifyRequest: return binding.attention == true
        case .unsupportedBlockingRequest(_, let kind, _, _): return binding.attention == true && (kind == .secret || kind == .sudo)
        default: return false
        }
    }
    func remove(_ target: RelayPairedTarget) {
        let removed = Scope(target)
        deletePreviewKeys(for: removed)
        bindings.removeAll { $0.scope == removed }
        retiredPreviewScopes.removeAll { $0.scope == removed }; persist()
        if let request = requests.removeValue(forKey: target.id) { enqueueUnregister(request) }
        if scope == removed {
            generation = UUID()
            scope = nil; request = nil; connectionID = nil
        }
    }
    private func reconcile() {
        guard enabled else { return }
        guard sandbox else {
            bindings.removeAll(); persist()
            status = "Relay push currently supports development builds only. Production tokens are not sent."
            return
        }
        guard let token else {
            status = appleRegistrationUnavailable
                ? "Apple push is unavailable. Notifications remain best effort."
                : "Waiting for Apple push registration."
            return
        }
        guard let scope, let request else { status = "Waiting for a supported paired Relay host."; return }
        let revision = generation
        let previous = work
        work = Task { [weak self] in
            await previous?.value
            guard let self, self.generation == revision, self.enabled else { return }
            var pendingPreviewKeyID: String?
            defer {
                if let pendingPreviewKeyID {
                    self.previewKeys.delete(environment: "sandbox", wake: "pending", keyID: pendingPreviewKeyID)
                }
            }
            do {
                let status = try await request("relay.status", [:])
                guard self.generation == revision, self.enabled else { return }
                let capability = Self.previewCapability(status)
                guard Self.supports(status) || (self.previewEnabled && capability != nil) else {
                    self.status = "This host does not support Relay push. Notifications remain best effort."
                    self.bindings.removeAll { $0.scope == scope }; self.persist(); return
                }
                var previewKeyID: String?
                var previewKey: Data?
                let result: [String: Any]
                if self.previewEnabled, capability != nil {
                    let now = Int64(Date().timeIntervalSince1970)
                    let existing = self.forcePreviewRotation ? nil : self.bindings.first { $0.scope == scope && $0.previewKeyID != nil }
                    let existingRecord = try existing.flatMap { binding in
                        try self.previewKeys.load(environment: "sandbox", wake: binding.wake, keyID: binding.previewKeyID!, now: now)
                    }
                    let key: Data
                    let keyID: String
                    if let existingRecord, let existingKey = existingRecord.keyData {
                        key = existingKey; keyID = existingRecord.keyID
                    } else {
                        guard let generatedKey = Self.random(32), let keyIDBytes = Self.random(16) else { throw URLError(.cannotCreateFile) }
                        key = generatedKey; keyID = PushPreviewEnvelope.encode(keyIDBytes); pendingPreviewKeyID = keyID
                        // A pending key is durable before provisioning; it is promoted to the authenticated wake scope only after exact response validation.
                        try self.previewKeys.save(PreviewKeyRecord(key: key, keyID: keyID, wake: "pending", environment: "sandbox", createdAt: now))
                    }
                    result = try await request("relay.push.preview.register", [
                        "device_token": token, "environment": "sandbox",
                        "preview": ["version": 1, "key_id": keyID, "key": PushPreviewEnvelope.encode(key),
                                    "completion": self.currentCompletion, "attention": self.currentAttention,
                                    "include_title": self.includeTitle, "include_response_excerpt": self.includeResponseExcerpt]
                    ])
                    guard let response = result["preview"] as? [String: Any], Self.exactInteger(response["version"], equals: 1) != nil,
                          response["key_id"] as? String == keyID else { throw URLError(.badServerResponse) }
                    previewKeyID = keyID; previewKey = key
                } else {
                    guard !self.previewEnabled || (self.currentCompletion && self.currentAttention) else {
                        self.status = "This host does not support selective encrypted previews. Local delivery remains active."
                        self.bindings.removeAll { $0.scope == scope }; self.persist(); return
                    }
                    result = try await request("relay.push.register", ["device_token": token, "environment": "sandbox"])
                }
                guard self.generation == revision, self.enabled else { return }
                guard let registered = result["registered"] as? NSNumber,
                      CFGetTypeID(registered) == CFBooleanGetTypeID(), registered.boolValue,
                      let wake = result["wake_handle"] as? String, Self.validWake(wake) else { throw URLError(.badServerResponse) }
                let now = Int64(Date().timeIntervalSince1970)
                if let keyID = previewKeyID, let key = previewKey {
                    try self.previewKeys.save(PreviewKeyRecord(key: key, keyID: keyID, wake: wake, environment: "sandbox", createdAt: now))
                    self.previewKeys.delete(environment: "sandbox", wake: "pending", keyID: keyID)
                    for old in self.bindings where old.scope == scope && (old.previewKeyID != keyID || old.wake != wake) {
                        if let retiredID = old.retiredKeyID, let retiredWake = old.retiredWake,
                           retiredID != keyID || retiredWake != wake {
                            self.previewKeys.delete(environment: "sandbox", wake: retiredWake, keyID: retiredID)
                        }
                        if let oldID = old.previewKeyID, let oldRecord = try? self.previewKeys.load(environment: "sandbox", wake: old.wake, keyID: oldID, now: now), let oldKey = oldRecord.keyData {
                            try? self.previewKeys.save(PreviewKeyRecord(key: oldKey, keyID: oldID, wake: old.wake, environment: "sandbox", createdAt: oldRecord.createdAt ?? now, retireAt: now + 900))
                        }
                    }
                }
                let previousPreview = self.bindings.first { $0.scope == scope && $0.previewKeyID != nil }
                let sameKeyScope = previousPreview?.previewKeyID == previewKeyID && previousPreview?.wake == wake
                let retainedID = sameKeyScope ? previousPreview?.retiredKeyID : previousPreview?.previewKeyID
                let retainedWake = sameKeyScope ? previousPreview?.retiredWake : previousPreview?.wake
                if previewKeyID == nil { self.deletePreviewKeys(for: scope) }
                for old in self.bindings where old.scope == scope && old.wake != wake && old.previewKeyID != nil {
                    self.retainPreviewRouteScope(old)
                }
                self.bindings.removeAll { $0.scope == scope || $0.wake == wake }
                self.bindings.append(Binding(scope: scope, wake: wake, previewKeyID: previewKeyID, completion: previewKeyID == nil ? nil : self.currentCompletion, attention: previewKeyID == nil ? nil : self.currentAttention, retiredKeyID: previewKeyID == nil ? nil : retainedID, retiredWake: previewKeyID == nil ? nil : retainedWake)); self.persist()
                if previewKeyID != nil { self.forcePreviewRotation = false }
                self.status = previewKeyID == nil ? "Relay push active (development). Alerts contain no session content." : "Encrypted previews active (development). Locked devices receive the generic alert."
            } catch {
                guard self.generation == revision else { return }
                if self.bindings.contains(where: { $0.scope == scope }) {
                    self.status = "Relay push update failed; previous registration remains active."
                } else {
                    self.status = "Relay push unavailable. Notifications remain best effort; reconnect to retry."
                }
            }
            self.writeDiagnostic()
        }
    }
    private func enqueueUnregister(_ request: @escaping Request, targetID: UUID? = nil) {
        // Snapshot only this target's acknowledged binding. The serialized chain
        // removes it after host ACK, before any newer registration can commit.
        let retiring = bindings.filter { $0.scope.id == targetID }
        let previous = work
        work = Task { [weak self] in
            await previous?.value
            do {
                let capabilities = try await request("relay.status", [:])
                guard Self.supports(capabilities) || Self.previewCapability(capabilities) != nil else { return }
                let result = try await request("relay.push.unregister", [:])
                guard let registered = result["registered"] as? NSNumber,
                      CFGetTypeID(registered) == CFBooleanGetTypeID(), !registered.boolValue else { throw URLError(.badServerResponse) }
                self?.bindings.removeAll { binding in
                    retiring.contains { $0.scope == binding.scope && $0.wake == binding.wake }
                }
                self?.persist()
            } catch {
                if self?.enabled == false { self?.status = "Push off on this device. Host removal pending; reconnect to retry." }
            }
            self?.writeDiagnostic()
        }
    }
    /// Best-effort host revocation before deleting local pairing keys. A fresh,
    /// dedicated channel does not displace the selected metadata/chat lease.
    static func unregisterPairedTarget(_ target: RelayPairedTarget) async {
        guard target.status == .approved else { return }
        let pool = RelayConnectionPool()
        guard let connection = try? await pool.acquire(target: target, profile: "default", channel: "push-cleanup-" + UUID().uuidString) else { return }
        do {
            let capabilities = try await connection.relayRequest("relay.status")
            if supports(capabilities) || previewCapability(capabilities) != nil {
                let result = try await connection.relayRequest("relay.push.unregister", params: [:])
                guard let registered = result["registered"] as? NSNumber,
                      CFGetTypeID(registered) == CFBooleanGetTypeID(), !registered.boolValue else {
                    throw URLError(.badServerResponse)
                }
            }
        } catch { /* Offline revocation remains best effort; local routing is already removed. */ }
        await connection.close()
    }

    func waitForWork() async { await work?.value }
    private func deletePreviewKeys(for selected: Scope? = nil) {
        for binding in bindings where selected == nil || binding.scope == selected {
            if let keyID = binding.previewKeyID { previewKeys.delete(environment: "sandbox", wake: binding.wake, keyID: keyID) }
            if let keyID = binding.retiredKeyID, let wake = binding.retiredWake { previewKeys.delete(environment: "sandbox", wake: wake, keyID: keyID) }
        }
        // Key deletion is a privacy boundary, not proof of host unregister.
        // Keep delivery/category ownership and wake routing until replacement
        // registration or explicit revocation updates the binding.
    }
    private func retainPreviewRouteScope(_ binding: Binding) {
        retiredPreviewScopes.removeAll { $0.scope == binding.scope && $0.wake == binding.wake }
        retiredPreviewScopes.append(RetiredPreviewScope(scope: binding.scope, wake: binding.wake,
            expiresAt: now() + Self.retiredRouteRetention))
    }
    @discardableResult
    private func pruneRetiredScopes() -> Bool {
        let count = retiredPreviewScopes.count
        let timestamp = now()
        retiredPreviewScopes.removeAll {
            let remaining = $0.expiresAt.subtractingReportingOverflow(timestamp)
            return !Self.validWake($0.wake) || remaining.overflow
                || !(1...Self.retiredRouteRetention).contains(remaining.partialValue)
        }
        return retiredPreviewScopes.count != count
    }

    private func persistRetiredScopes() {
        pruneRetiredScopes()
        // Persisted order breaks equal-expiry ties, keeping newer rotations.
        retiredPreviewScopes = retiredPreviewScopes.enumerated().sorted {
            if $0.element.expiresAt != $1.element.expiresAt {
                return $0.element.expiresAt < $1.element.expiresAt
            }
            return $0.offset < $1.offset
        }.suffix(Self.maxRetiredScopes).map(\.element)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        // At most 64 records enter this loop. Evict oldest first until the
        // entire encoded array fits; active delivery bindings are untouched.
        while let data = try? encoder.encode(retiredPreviewScopes) {
            if data.count <= Self.maxRetiredScopeBytes {
                if defaults.data(forKey: retiredScopesKey) != data {
                    defaults.set(data, forKey: retiredScopesKey)
                }
                return
            }
            retiredPreviewScopes.removeFirst()
        }
    }

    private func persist() {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        if let data = try? encoder.encode(bindings), defaults.data(forKey: storageKey) != data {
            defaults.set(data, forKey: storageKey)
        }
        persistRetiredScopes()
    }
    private func writeDiagnostic() {
        #if DEBUG && targetEnvironment(simulator)
        guard ProcessInfo.processInfo.arguments.contains("-debug-apns-diagnostics") else { return }
        writeDiagnostic(to: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0])
        #endif
    }
    #if DEBUG && targetEnvironment(simulator)
    // Internal destination seam lets hermetic tests exercise the real file writer.
    func writeDiagnostic(to directory: URL) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Explicit allowlist: never export operational token/handle bindings.
        let state: [String: Any] = ["status": status, "environment": sandbox ? "sandbox" : "production",
                                    "registered": !bindings.isEmpty, "enabled": enabled, "has_token": token != nil]
        let url = directory.appendingPathComponent("apns-diagnostics.json")
        // An older opt-in build may have left secrets here. Remove that artifact
        // before writing, so a failed replacement cannot leave legacy contents.
        try? FileManager.default.removeItem(at: url)
        if let data = try? JSONSerialization.data(withJSONObject: state) {
            try? data.write(to: url, options: [.atomic, .completeFileProtection])
            try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        }
    }
    #endif
}
