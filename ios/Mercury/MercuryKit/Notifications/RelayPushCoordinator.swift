import Foundation
import Observation
import CoreFoundation

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
    struct Binding: Codable { let scope: Scope; let wake: String }
    private(set) var status = "Relay push is off. Direct notifications remain best effort."
    private(set) var enabled = false
    private var token: String?
    private var appleRegistrationUnavailable = false
    private var scope: Scope?
    private var request: Request?
    private var connectionID: ObjectIdentifier?
    private var requests: [UUID: Request] = [:]
    private var generation = UUID()
    private var work: Task<Void, Never>?
    private var bindings: [Binding]
    private let defaults: UserDefaults
    private let sandbox: Bool
    private let storageKey = "mercury.apns.wake-bindings.v1"

    init(defaults: UserDefaults = .standard, sandbox: Bool = RelayPushCoordinator.isSandboxBuild) {
        self.defaults = defaults; self.sandbox = sandbox
        bindings = (defaults.data(forKey: storageKey).flatMap { try? JSONDecoder().decode([Binding].self, from: $0) }) ?? []
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
        guard let caps = status["capabilities"] as? [String: Any],
              let value = caps["push_notifications_v1"] as? NSNumber,
              CFGetTypeID(value) == CFBooleanGetTypeID() else { return false }
        return value.boolValue
    }
    nonisolated static func supportsSessionResolution(_ status: [String: Any]) -> Bool {
        guard let caps = status["capabilities"] as? [String: Any],
              let value = caps["push_notifications_v2"] as? NSNumber,
              CFGetTypeID(value) == CFBooleanGetTypeID() else { return false }
        return value.boolValue
    }
    nonisolated static func resolvedSessionRoute(_ result: [String: Any]) -> ResolvedSessionRoute? {
        guard let resolved = result["resolved"] as? NSNumber,
              CFGetTypeID(resolved) == CFBooleanGetTypeID(), resolved.boolValue,
              let durable = result["durable_session_id"] as? String,
              (1...256).contains(durable.utf8.count),
              let profile = result["profile"] as? String,
              (1...64).contains(profile.utf8.count),
              !durable.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              !profile.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
        else { return nil }
        return ResolvedSessionRoute(durableSessionID: durable, profile: profile)
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
    func setEnabled(_ value: Bool) {
        guard enabled != value else { return }
        enabled = value; generation = UUID()
        if !value {
            bindings.removeAll(); persist()
            status = "Relay push is off. Direct notifications remain best effort."
            for request in requests.values { enqueueUnregister(request) }
        } else { reconcile() }
        writeDiagnostic()
    }
    func receivedToken(_ data: Data) {
        guard !data.isEmpty else { registrationFailed(); return }
        let next = Self.tokenHex(data)
        guard token != next else { return }
        // The first callback after a cold launch is not evidence of rotation.
        // Preserve the persisted handle so an arriving notification can still route.
        if token != nil { bindings.removeAll(); persist() }
        token = next; appleRegistrationUnavailable = false
        generation = UUID(); reconcile(); writeDiagnostic()
    }
    func registrationFailed() {
        // Apple invalidated this registration. Restore local delivery immediately,
        // including persisted cold-launch routes, and reject any pending RPC result.
        token = nil; generation = UUID()
        appleRegistrationUnavailable = true
        bindings.removeAll(); persist()
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
        if !enabled || appleRegistrationUnavailable { enqueueUnregister(request) }
        if enabled { reconcile() }
    }
    func target(for wake: String, targets: [RelayPairedTarget]) -> RelayPairedTarget? {
        guard enabled, Self.validWake(wake), let binding = bindings.first(where: { $0.wake == wake }) else { return nil }
        return targets.first { $0.status == .approved && Scope($0) == binding.scope }
    }
    func ownsDelivery(for target: RelayPairedTarget?) -> Bool {
        guard enabled, let target else { return false }
        return bindings.contains { $0.scope == Scope(target) }
    }
    func remove(_ target: RelayPairedTarget) {
        let removed = Scope(target)
        bindings.removeAll { $0.scope == removed }; persist()
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
            do {
                let status = try await request("relay.status", [:])
                guard self.generation == revision, self.enabled else { return }
                guard Self.supports(status) else {
                    self.status = "This host does not support Relay push. Notifications remain best effort."
                    self.bindings.removeAll { $0.scope == scope }; self.persist(); return
                }
                let result = try await request("relay.push.register", ["device_token": token, "environment": "sandbox"])
                guard self.generation == revision, self.enabled else { return }
                guard let registered = result["registered"] as? NSNumber,
                      CFGetTypeID(registered) == CFBooleanGetTypeID(), registered.boolValue,
                      let wake = result["wake_handle"] as? String, Self.validWake(wake) else { throw URLError(.badServerResponse) }
                self.bindings.removeAll { $0.scope == scope || $0.wake == wake }
                self.bindings.append(Binding(scope: scope, wake: wake)); self.persist()
                self.status = "Relay push active (development). Alerts contain no session content."
            } catch {
                guard self.generation == revision else { return }
                self.bindings.removeAll { $0.scope == scope }; self.persist()
                self.status = "Relay push unavailable. Notifications remain best effort; reconnect to retry."
            }
            self.writeDiagnostic()
        }
    }
    private func enqueueUnregister(_ request: @escaping Request) {
        let previous = work
        work = Task { [weak self] in
            await previous?.value
            do {
                guard Self.supports(try await request("relay.status", [:])) else { return }
                let result = try await request("relay.push.unregister", [:])
                guard let registered = result["registered"] as? NSNumber,
                      CFGetTypeID(registered) == CFBooleanGetTypeID(), !registered.boolValue else { throw URLError(.badServerResponse) }
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
            if supports(try await connection.relayRequest("relay.status")) {
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
    private func persist() { defaults.set(try? JSONEncoder().encode(bindings), forKey: storageKey) }
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
