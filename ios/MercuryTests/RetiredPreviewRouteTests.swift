import XCTest
import UserNotifications
import UIKit
import MercuryNotificationPreviewKit
@testable import Mercury

@MainActor
final class RetiredPreviewRouteTests: XCTestCase {
    func testMultipleRotationsColdReloadAndAuthenticatedDelegateTapKeepOriginalScope() async throws {
        let suite = "retired-preview-\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let target = makeTarget()
        let owner = NSObject()
        var clock = Int64(Date().timeIntervalSince1970)
        let push = RelayPushCoordinator(defaults: defaults, now: { clock })
        push.setPreview(enabled: true, includeTitle: false, includeResponseExcerpt: false)
        push.select(target); push.setEnabled(true); push.receivedToken(Data([1]))
        var registeredWakes: [String] = []
        var keyIDs: [String] = []
        push.connected(target: target, identity: ObjectIdentifier(owner)) { method, params in
            if method == "relay.status" { return Self.capabilities }
            if method == "relay.push.unregister" { return ["registered": false] }
            XCTAssertEqual(method, "relay.push.preview.register")
            let wake = RelayBase64.urlSafeEncode(Data(repeating: UInt8(registeredWakes.count + 10), count: 32))
            registeredWakes.append(wake)
            let preview = try XCTUnwrap(params["preview"] as? [String: Any])
            keyIDs.append(try XCTUnwrap(preview["key_id"] as? String))
            return ["registered": true, "wake_handle": wake, "preview": ["version": 1, "key_id": preview["key_id"]!]]
        }
        await push.waitForWork()
        for _ in 0..<3 { push.rotatePreviewKey(); await push.waitForWork() }
        XCTAssertEqual(registeredWakes.count, 4, "Exercise real registration/rotation, not seeded bindings")
        let cold = RelayPushCoordinator(defaults: defaults, now: { clock })
        defer { cold.remove(target); push.remove(target) }
        XCTAssertTrue(cold.ownsDelivery(for: target))
        XCTAssertNil(try PreviewKeychainRepository().load(environment: "sandbox", wake: registeredWakes[0], keyID: keyIDs[0], now: clock), "Earlier key was deleted by the existing privacy policy, not retained for route TTL")
        for wake in registeredWakes.dropLast() {
            XCTAssertNil(cold.target(for: wake, targets: [target]), "Retired handles never become current generic push ownership")
        }
        let persistence = InMemoryRelayTargetPersistence()
        try await RelayTargetStore(persistence: persistence).add(target)
        let preferences = NotificationPreferencesStore(userDefaults: defaults)
        preferences.save(.permissiveAll)
        let model = AppModel(relayTargetStore: RelayTargetStore(persistence: persistence),
            startupChoiceStore: StartupConnectionChoiceStore(persistence: MemoryStartupChoicePersistence()),
            relayPush: cold, notificationPreferencesStore: preferences)
        model.injectNotificationCoordinator(NotificationCoordinator(client: RetiredRouteNotificationClient(),
            store: UserDefaultsWatermarkStore(userDefaults: defaults)))
        model.injectController(ConnectionController(appModel: model,
            relayProfilesClientFactory: { _, _ in ProfilesClient(rpcRequest: { _, _ in ["profiles": [["name": "default"]]] }) },
            relaySessionsPageLoader: { _, _, _, _ in SessionPage(rows: [], total: 0, hasMore: false) }))
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory); model.disconnect() }
        let routes = PreviewRouteStore(url: directory.appendingPathComponent("routes.json"))
        let delegate = NotificationDelegate(previewRoutes: routes)
        var assertions = 0
        delegate.beginTask = { _ in assertions += 1; return UIBackgroundTaskIdentifier(rawValue: 54) }
        delegate.endTask = { _ in assertions -= 1 }
        let now = Int64(Date().timeIntervalSince1970)
        for (index, wake) in registeredWakes.dropLast().enumerated() {
            let event = RelayBase64.urlSafeEncode(Data(repeating: UInt8(index + 20), count: 32))
            let session = "authenticated-session-\(index)"
            // Same protected, single-use receipt store as the NSE. APNs plaintext
            // is deliberately contradictory and must never supply route authority.
            XCTAssertTrue(routes.record(PreviewRouteRecord(event: event, wake: wake, sessionID: session,
                profile: "default", expiresAt: now + PreviewRouteStore.maxRetentionSeconds), now: now))
            let routed = expectation(description: "retired authenticated route settled")
            delegate.onPreviewRoute = { wake, session, profile in
                XCTAssertEqual(assertions, 1)
                await model.handlePushPreviewRoute(wake: wake, durableSessionID: session, profile: profile)
                routed.fulfill()
            }
            var completions = 0
            delegate.userNotificationCenter(.current(), didReceive: try response([
                "mercury_wake": wake, "mercury_event": event, "mercury.preview.sid": "untrusted"
            ])) { XCTAssertEqual(assertions, 1); completions += 1 }
            XCTAssertEqual(completions, 1)
            await fulfillment(of: [routed], timeout: 3)
            XCTAssertEqual(assertions, 0)
            XCTAssertEqual(model.activeRelayTarget?.id, target.id)
            XCTAssertEqual(model.notificationOpenRequest?.sessionID, session)
            XCTAssertNil(routes.peek(event: event, wake: wake, now: now), "Receipt remains single-use")
        }
        let old = registeredWakes[0]
        clock += 901
        XCTAssertEqual(cold.previewTarget(for: old, targets: [target])?.id, target.id)
        cold.setPreview(enabled: false, includeTitle: false, includeResponseExcerpt: false)
        XCTAssertEqual(cold.previewTarget(for: old, targets: [target])?.id, target.id, "Deleting keys is not revoking authenticated receipts")
        clock += PreviewRouteStore.maxRetentionSeconds
        let expired = RelayPushCoordinator(defaults: defaults, now: { clock })
        XCTAssertNil(expired.previewTarget(for: old, targets: [target]), "Cold reload prunes bounded route metadata without renewing it")
        XCTAssertEqual(expired.target(for: registeredWakes.last!, targets: [target])?.id, target.id, "Route expiry is not current delivery expiry")
    }

    func testRetiredScopeDoesNotOwnDeliveryAndRevocationSurvivesReload() async throws {
        for revoke in 0..<3 {
            let suite = "retired-revoke-\(UUID())"
            let defaults = UserDefaults(suiteName: suite)!
            defer { defaults.removePersistentDomain(forName: suite) }
            let target = makeTarget()
            let wake = RelayBase64.urlSafeEncode(Data(repeating: 35, count: 32))
            let binding = RelayPushCoordinator.Binding(scope: .init(target), wake: RelayBase64.urlSafeEncode(Data(repeating: 36, count: 32)), retiredWake: wake)
            defaults.set(try JSONEncoder().encode([binding]), forKey: "mercury.apns.wake-bindings.v1")
            let push = RelayPushCoordinator(defaults: defaults)
            let owner = NSObject()
            push.select(target); push.setEnabled(true); push.receivedToken(Data([1]))
            push.connected(target: target, identity: ObjectIdentifier(owner)) { _, _ in ["capabilities": [:]] }
            await push.waitForWork() // Unsupported host drops delivery binding, not older receipt scope.
            XCTAssertFalse(push.ownsDelivery(for: target))
            XCTAssertNil(push.target(for: wake, targets: [target]))
            XCTAssertEqual(push.previewTarget(for: wake, targets: [target])?.id, target.id)
            XCTAssertNil(push.previewTarget(for: wake, targets: [makeTarget()]), "Same origin is not the same pairing")
            XCTAssertNil(push.previewTarget(for: wake, targets: [makeTarget(id: target.id, origin: "https://other.test")]))
            XCTAssertNil(push.previewTarget(for: wake, targets: [makeTarget(id: target.id, hostByte: 9)]))
            var pending = target; pending.status = .pending
            XCTAssertNil(push.previewTarget(for: wake, targets: [pending]))
            switch revoke {
            case 0: push.remove(target)
            case 1: push.setEnabled(false)
            default: push.registrationFailed()
            }
            await push.waitForWork()
            let cold = RelayPushCoordinator(defaults: defaults)
            XCTAssertNil(cold.previewTarget(for: wake, targets: [target]), "Explicit revocation cannot resurrect on cold reload")
            XCTAssertFalse(cold.ownsDelivery(for: target))
        }
    }

    func testLegacyMigrationDoesNotRenewRetentionOnEveryColdLaunch() throws {
        let suite = "retired-migration-\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let target = makeTarget()
        let wake = RelayBase64.urlSafeEncode(Data(repeating: 37, count: 32))
        let binding = RelayPushCoordinator.Binding(scope: .init(target), wake: RelayBase64.urlSafeEncode(Data(repeating: 38, count: 32)), retiredWake: wake)
        defaults.set(try JSONEncoder().encode([binding]), forKey: "mercury.apns.wake-bindings.v1")
        var clock: Int64 = 1_000_000
        let first = RelayPushCoordinator(defaults: defaults, now: { clock })
        XCTAssertEqual(first.previewTarget(for: wake, targets: [target])?.id, target.id)
        clock += RelayPushCoordinator.retiredRouteRetention - 1
        let second = RelayPushCoordinator(defaults: defaults, now: { clock })
        XCTAssertEqual(second.previewTarget(for: wake, targets: [target])?.id, target.id)
        clock += 2
        let third = RelayPushCoordinator(defaults: defaults, now: { clock })
        XCTAssertNil(third.previewTarget(for: wake, targets: [target]))
    }

    private func response(_ info: [AnyHashable: Any]) throws -> UNNotificationResponse {
        let content = UNMutableNotificationContent(); content.userInfo = info
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        let notification = try XCTUnwrap(UNNotification(coder: RetiredRouteCoder(["request": request, "date": Date()])))
        return try XCTUnwrap(UNNotificationResponse(coder: RetiredRouteCoder([
            "notification": notification, "actionIdentifier": UNNotificationDefaultActionIdentifier])))
    }
    private func makeTarget(id: UUID = UUID(), origin: String = "https://relay.test", hostByte: UInt8 = 2) -> RelayPairedTarget {
        RelayPairedTarget(id: id, label: "Rotation fixture", relayOrigin: origin,
            installationID: Data(repeating: 1, count: 32), hostPublicKey: Data(repeating: hostByte, count: 32),
            deviceID: RelayBase64.urlSafeEncode(Data(repeating: 3, count: 16)),
            deviceStaticPrivateKey: Data(repeating: 4, count: 32), fingerprint: String(repeating: "a", count: 16),
            status: .approved, createdAtEpochSeconds: 1, lastUsedEpochSeconds: nil)
    }
    private static var capabilities: [String: Any] { ["capabilities": ["push_notifications_v1": true, "push_previews": [
        "version": 1, "register_method": "relay.push.preview.register", "unregister_method": "relay.push.unregister",
        "aead": "CHACHA20-POLY1305", "max_plaintext_bytes": 1280, "max_title_utf8_bytes": 160, "max_body_utf8_bytes": 640
    ]]] }
}
private final class RetiredRouteCoder: NSCoder {
    let values: [String: Any]
    init(_ values: [String: Any]) { self.values = values; super.init() }
    override var allowsKeyedCoding: Bool { true }
    override func containsValue(forKey key: String) -> Bool { values[key] != nil }
    override func decodeObject(forKey key: String) -> Any? { values[key] }
}
private actor RetiredRouteNotificationClient: LocalNotificationScheduling {
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus { .authorized }
    func requestAuthorization() async -> Bool { true }
    func authorizationGranted() async -> Bool { true }
    func post(_ notification: PendingNotification) async {}
    func cancel(sessionID: String) async {}
}
