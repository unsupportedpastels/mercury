import XCTest
import UserNotifications
@testable import Mercury

@MainActor
final class RelayPushTests: XCTestCase {
    private let wake = String(repeating: "w", count: 43)
    private func target() -> RelayPairedTarget {
        RelayPairedTarget(id: UUID(), label: "Push fixture", relayOrigin: "https://relay.example.test",
            installationID: Data(repeating: 1, count: 16), hostPublicKey: Data(repeating: 2, count: 32),
            deviceID: "fixture-device", deviceStaticPrivateKey: Data(repeating: 3, count: 32),
            fingerprint: "fixture", status: .approved, createdAtEpochSeconds: 0, lastUsedEpochSeconds: nil)
    }
    private func coordinator(sandbox: Bool = true) -> RelayPushCoordinator {
        RelayPushCoordinator(defaults: UserDefaults(suiteName: "push-tests-\(UUID())")!, sandbox: sandbox)
    }
    func testTokenHexAndWakeValidation() {
        XCTAssertEqual(RelayPushCoordinator.tokenHex(Data([0, 1, 10, 255])), "00010aff")
        XCTAssertTrue(RelayPushCoordinator.validWake(wake))
        for invalid in ["", wake + "w", String(wake.dropLast()), String(repeating: "/", count: 43)] {
            XCTAssertFalse(RelayPushCoordinator.validWake(invalid))
        }
    }
    func testGenericPushForegroundSuppressedAndDirectLocalPreserved() {
        let payload: [AnyHashable: Any] = ["aps": ["alert": ["title": "Mercury", "body": "An update is available. Open Mercury to continue."], "sound": "default"], "mercury_wake": wake]
        XCTAssertEqual(RelayPushCoordinator.wake(from: payload), wake)
        XCTAssertTrue(NotificationDelegate.presentationOptions(userInfo: payload).isEmpty)
        XCTAssertTrue(NotificationDelegate.presentationOptions(userInfo: ["mercury.sessionID": "local"]).contains(.banner))
    }
    func testCapabilityMustBeExplicitBoolean() {
        XCTAssertTrue(RelayPushCoordinator.supports(["capabilities": ["push_notifications_v1": true]]))
        for value in [false, 1, "true", ["enabled": true]] as [Any] {
            XCTAssertFalse(RelayPushCoordinator.supports(["capabilities": ["push_notifications_v1": value]]))
        }
        XCTAssertFalse(RelayPushCoordinator.supports([:]))
    }
    func testRegistrationWireAndScopedTap() async {
        let c = coordinator(), t = target(), owner = NSObject()
        var calls: [String] = []
        c.select(t); c.setEnabled(true); c.receivedToken(Data([0xab, 0, 0xff]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, params in
            calls.append(method)
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            XCTAssertEqual(method, "relay.push.register")
            XCTAssertEqual(params["device_token"] as? String, "ab00ff")
            XCTAssertEqual(params["environment"] as? String, "sandbox")
            XCTAssertEqual(Set(params.keys), ["device_token", "environment"])
            return ["registered": true, "wake_handle": self.wake]
        }
        await c.waitForWork()
        XCTAssertEqual(calls, ["relay.status", "relay.push.register"])
        XCTAssertEqual(c.target(for: wake, targets: [t])?.id, t.id)
        XCTAssertNil(c.target(for: wake, targets: [target()]))
        var pending = t; pending.status = .pending
        XCTAssertNil(c.target(for: wake, targets: [pending]))
        XCTAssertTrue(c.ownsDelivery(for: t)); XCTAssertFalse(c.ownsDelivery(for: nil))
    }
    func testUnsupportedHostNeverRegisters() async {
        let c = coordinator(), t = target(), owner = NSObject()
        var calls: [String] = []
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, _ in calls.append(method); return [:] }
        await c.waitForWork()
        XCTAssertEqual(calls, ["relay.status"])
        XCTAssertTrue(c.status.contains("does not support"))
        XCTAssertFalse(c.ownsDelivery(for: t))
    }
    func testReleaseNeverSendsProductionTokenToSandbox() async {
        let c = coordinator(sandbox: false), t = target(), owner = NSObject()
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { _, _ in XCTFail("production token must not be sent"); return [:] }
        await c.waitForWork()
        XCTAssertTrue(c.status.contains("Production tokens are not sent"))
    }
    func testDisableUnregistersEmptyParamsAndClearsTap() async {
        let c = coordinator(), t = target(), owner = NSObject()
        var unregisters = 0
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, params in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            if method == "relay.push.unregister" { unregisters += 1; XCTAssertTrue(params.isEmpty); return ["registered": false] }
            return ["registered": true, "wake_handle": self.wake]
        }
        await c.waitForWork(); c.setEnabled(false)
        XCTAssertNil(c.target(for: wake, targets: [t]))
        await c.waitForWork(); XCTAssertEqual(unregisters, 1)
        c.setEnabled(true); await c.waitForWork(); c.remove(t)
        XCTAssertNil(c.target(for: wake, targets: [t]))
        await c.waitForWork(); XCTAssertEqual(unregisters, 2)
    }
    func testTokenRotationAndReconnectReregister() async {
        let c = coordinator(), t = target(), a = NSObject(), b = NSObject()
        var tokens: [String] = []
        let rpc: RelayPushCoordinator.Request = { method, params in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            tokens.append(params["device_token"] as! String)
            return ["registered": true, "wake_handle": self.wake]
        }
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(a), request: rpc); await c.waitForWork()
        c.receivedToken(Data([2])); await c.waitForWork()
        c.connected(target: t, identity: ObjectIdentifier(b), request: rpc); await c.waitForWork()
        XCTAssertEqual(tokens, ["01", "02", "02"])
    }
    func testStaleCompletionCannotRestoreDisabledBinding() async {
        let c = coordinator(), t = target(), owner = NSObject()
        let started = expectation(description: "register began")
        var continuation: CheckedContinuation<Void, Never>?
        var unregisters = 0
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, _ in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            if method == "relay.push.unregister" { unregisters += 1; return ["registered": false] }
            await withCheckedContinuation { continuation = $0; started.fulfill() }
            return ["registered": true, "wake_handle": self.wake]
        }
        await fulfillment(of: [started], timeout: 2)
        c.setEnabled(false); continuation?.resume(); await c.waitForWork()
        XCTAssertNil(c.target(for: wake, targets: [t])); XCTAssertFalse(c.ownsDelivery(for: t))
        XCTAssertEqual(unregisters, 1)
    }
    func testGenericDeliveryRespectsCategoryPreferencesAndFailureFallback() {
        XCTAssertTrue(RelayPushCoordinator.genericPushEnabled(.permissiveAll, authorized: true))
        var selected = MercuryNotificationPreferences.permissiveAll
        selected.completionEnabled = false
        XCTAssertFalse(RelayPushCoordinator.genericPushEnabled(selected, authorized: true))
        selected = .permissiveAll; selected.attentionEnabled = false
        XCTAssertFalse(RelayPushCoordinator.genericPushEnabled(selected, authorized: true))
        XCTAssertFalse(RelayPushCoordinator.genericPushEnabled(.permissiveAll, authorized: false))
        for status in ["error", "interrupted"] {
            let event = ChatEvent.messageComplete(sessionID: "fixture", text: "Outcome", status: status,
                error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil)
            XCTAssertFalse(RelayPushCoordinator.replacesLocalDelivery(for: event))
        }
        let complete = ChatEvent.messageComplete(sessionID: "fixture", text: "Done", status: "complete",
            error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil)
        XCTAssertTrue(RelayPushCoordinator.replacesLocalDelivery(for: complete))
    }
    func testColdTokenCallbackPreservesPersistedWakeRoute() async {
        let defaults = UserDefaults(suiteName: "cold-push-\(UUID())")!
        let first = RelayPushCoordinator(defaults: defaults), t = target(), owner = NSObject()
        first.select(t); first.setEnabled(true); first.receivedToken(Data([1]))
        first.connected(target: t, identity: ObjectIdentifier(owner)) { method, _ in
            method == "relay.status" ? ["capabilities": ["push_notifications_v1": true]]
                : ["registered": true, "wake_handle": self.wake]
        }
        await first.waitForWork()
        let cold = RelayPushCoordinator(defaults: defaults)
        cold.setEnabled(true); cold.receivedToken(Data([1]))
        XCTAssertEqual(cold.target(for: wake, targets: [t])?.id, t.id)
    }
    func testFailedReregistrationRestoresLocalFallback() async {
        let c = coordinator(), t = target(), first = NSObject(), next = NSObject()
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(first)) { method, _ in
            method == "relay.status" ? ["capabilities": ["push_notifications_v1": true]]
                : ["registered": true, "wake_handle": self.wake]
        }
        await c.waitForWork()
        c.connected(target: t, identity: ObjectIdentifier(next)) { _, _ in throw URLError(.notConnectedToInternet) }
        await c.waitForWork()
        XCTAssertFalse(c.ownsDelivery(for: t))
    }
    func testSelectionChangeRejectsStaleResponse() async {
        let c = coordinator(), t = target(), owner = NSObject()
        let started = expectation(description: "register began")
        var continuation: CheckedContinuation<Void, Never>?
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, _ in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            await withCheckedContinuation { continuation = $0; started.fulfill() }
            return ["registered": true, "wake_handle": self.wake]
        }
        await fulfillment(of: [started], timeout: 2)
        c.select(target()); continuation?.resume(); await c.waitForWork()
        XCTAssertNil(c.target(for: wake, targets: [t]))
    }
}
