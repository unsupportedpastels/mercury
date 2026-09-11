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
    func testAppleFailureAfterSuccessClearsPersistedOwnershipAndUnregisters() async {
        let defaults = UserDefaults(suiteName: "failed-push-\(UUID())")!
        let c = RelayPushCoordinator(defaults: defaults), t = target(), owner = NSObject()
        var unregisters = 0
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, params in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            if method == "relay.push.unregister" {
                unregisters += 1; XCTAssertTrue(params.isEmpty); return ["registered": false]
            }
            return ["registered": true, "wake_handle": self.wake]
        }
        await c.waitForWork(); XCTAssertTrue(c.ownsDelivery(for: t))
        c.registrationFailed()
        XCTAssertFalse(c.ownsDelivery(for: t))
        XCTAssertNil(c.target(for: wake, targets: [t]))
        XCTAssertTrue(c.status.contains("Apple push is unavailable"))
        let cold = RelayPushCoordinator(defaults: defaults); cold.setEnabled(true)
        XCTAssertFalse(cold.ownsDelivery(for: t))
        await c.waitForWork(); XCTAssertEqual(unregisters, 1)
    }

    func testAppleFailureDuringDelayedRegistrationRejectsStaleCompletion() async {
        let c = coordinator(), t = target(), owner = NSObject()
        let started = expectation(description: "register began")
        var continuation: CheckedContinuation<Void, Never>?
        var calls: [String] = []
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, _ in
            calls.append(method)
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            if method == "relay.push.unregister" { return ["registered": false] }
            await withCheckedContinuation { continuation = $0; started.fulfill() }
            return ["registered": true, "wake_handle": self.wake]
        }
        await fulfillment(of: [started], timeout: 2)
        c.registrationFailed()
        XCTAssertFalse(c.ownsDelivery(for: t)) // Immediate fallback; no wait for RPC.
        continuation?.resume(); await c.waitForWork()
        XCTAssertFalse(c.ownsDelivery(for: t))
        XCTAssertNil(c.target(for: wake, targets: [t]))
        XCTAssertTrue(c.status.contains("Apple push is unavailable"))
        XCTAssertEqual(calls, ["relay.status", "relay.push.register", "relay.status", "relay.push.unregister"])
    }

    func testAppleFailureDoesNotReuseStaleTokenOnReconnectOrEnable() async {
        let c = coordinator(), t = target(), first = NSObject(), next = NSObject()
        var tokens: [String] = []
        let rpc: RelayPushCoordinator.Request = { method, params in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            if method == "relay.push.unregister" { return ["registered": false] }
            tokens.append(params["device_token"] as! String)
            return ["registered": true, "wake_handle": self.wake]
        }
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(first), request: rpc); await c.waitForWork()
        c.registrationFailed(); await c.waitForWork()
        c.connected(target: t, identity: ObjectIdentifier(next), request: rpc); await c.waitForWork()
        c.setEnabled(false); c.setEnabled(true); await c.waitForWork()
        XCTAssertEqual(tokens, ["01"])
        XCTAssertFalse(c.ownsDelivery(for: t))
    }

    func testFreshAppleCallbackRecoversAfterFailureEvenWithSameToken() async {
        let c = coordinator(), t = target(), owner = NSObject(), next = NSObject()
        var tokens: [String] = [], operations: [String] = []
        let rpc: RelayPushCoordinator.Request = { method, params in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            operations.append(method)
            if method == "relay.push.unregister" { return ["registered": false] }
            tokens.append(params["device_token"] as! String)
            return ["registered": true, "wake_handle": self.wake]
        }
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner), request: rpc); await c.waitForWork()
        c.registrationFailed(); c.receivedToken(Data([1])); await c.waitForWork()
        XCTAssertTrue(c.ownsDelivery(for: t))
        XCTAssertEqual(tokens, ["01", "01"])
        XCTAssertEqual(operations, ["relay.push.register", "relay.push.unregister", "relay.push.register"])
        c.registrationFailed(); await c.waitForWork()
        c.receivedToken(Data([2])); await c.waitForWork()
        c.connected(target: t, identity: ObjectIdentifier(next), request: rpc); await c.waitForWork()
        XCTAssertEqual(tokens, ["01", "01", "02", "02"])
        XCTAssertEqual(c.target(for: wake, targets: [t])?.id, t.id)
    }

    func testFreshTokenQueuedDuringStaleRegistrationRunsAfterCleanup() async {
        let c = coordinator(), t = target(), owner = NSObject()
        let started = expectation(description: "old register began")
        var continuation: CheckedContinuation<Void, Never>?
        var operations: [String] = []
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, params in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            if method == "relay.push.unregister" { operations.append("unregister"); return ["registered": false] }
            let token = params["device_token"] as! String
            operations.append(token)
            if token == "01" { await withCheckedContinuation { continuation = $0; started.fulfill() } }
            return ["registered": true, "wake_handle": self.wake]
        }
        await fulfillment(of: [started], timeout: 2)
        c.registrationFailed(); c.receivedToken(Data([2]))
        XCTAssertFalse(c.ownsDelivery(for: t))
        continuation?.resume(); await c.waitForWork()
        XCTAssertEqual(operations, ["01", "unregister", "02"])
        XCTAssertTrue(c.ownsDelivery(for: t))
    }

    func testAppleFailureOfflineCleanupRetriesOnReconnectWithoutRegistering() async {
        let c = coordinator(), t = target(), first = NSObject(), next = NSObject()
        var offline = false, unregisters = 0
        let rpc: RelayPushCoordinator.Request = { method, _ in
            if offline { throw URLError(.notConnectedToInternet) }
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            if method == "relay.push.unregister" { unregisters += 1; return ["registered": false] }
            return ["registered": true, "wake_handle": self.wake]
        }
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(first), request: rpc); await c.waitForWork()
        offline = true; c.registrationFailed(); await c.waitForWork()
        XCTAssertFalse(c.ownsDelivery(for: t))
        XCTAssertTrue(c.status.contains("Apple push is unavailable"))
        offline = false
        c.connected(target: t, identity: ObjectIdentifier(next), request: rpc); await c.waitForWork()
        XCTAssertEqual(unregisters, 1)
        XCTAssertFalse(c.ownsDelivery(for: t))
    }

    #if DEBUG && targetEnvironment(simulator)
    func testDiagnosticFileContainsOnlyNonsensitiveStateAndReplacesLegacySecrets() async throws {
        let c = coordinator(), t = target(), owner = NSObject()
        let token = Data([0xde, 0xad, 0xbe, 0xef])
        c.select(t); c.setEnabled(true); c.receivedToken(token)
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, _ in
            method == "relay.status" ? ["capabilities": ["push_notifications_v1": true]]
                : ["registered": true, "wake_handle": self.wake]
        }
        await c.waitForWork()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("apns-diagnostics.json")
        try JSONSerialization.data(withJSONObject: ["device_token": RelayPushCoordinator.tokenHex(token), "wake_handle": wake]).write(to: url)
        c.writeDiagnostic(to: directory)
        let data = try Data(contentsOf: url)
        let state = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(Set(state.keys), ["status", "environment", "registered", "enabled", "has_token"])
        XCTAssertEqual(state["environment"] as? String, "sandbox")
        XCTAssertEqual(state["registered"] as? Bool, true)
        XCTAssertEqual(state["has_token"] as? Bool, true)
        let text = String(decoding: data, as: UTF8.self)
        for secret in [RelayPushCoordinator.tokenHex(token), wake, t.relayOrigin, t.deviceID] {
            XCTAssertFalse(text.contains(secret))
        }
        c.registrationFailed(); c.writeDiagnostic(to: directory)
        let failed = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(failed["registered"] as? Bool, false)
        XCTAssertEqual(failed["has_token"] as? Bool, false)
        await c.waitForWork()
    }
    #endif

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
    func testSessionResolutionRequiresV2AndUsesExactEncryptedRPC() async {
        var calls: [(String, [String: Any])] = []
        let route = await RelayPushCoordinator.resolveSessionRoute(wake: wake) { method, params in
            calls.append((method, params))
            if method == "relay.status" {
                return ["capabilities": ["push_notifications_v1": true, "push_notifications_v2": true]]
            }
            return ["resolved": true, "durable_session_id": "durable-session", "profile": "researcher"]
        }
        XCTAssertEqual(route, .init(durableSessionID: "durable-session", profile: "researcher"))
        XCTAssertEqual(calls.map(\.0), ["relay.status", "relay.push.resolve"])
        XCTAssertTrue(calls[0].1.isEmpty)
        XCTAssertEqual(calls[1].1 as NSDictionary, ["wake_handle": wake] as NSDictionary)

        calls.removeAll()
        let legacy = await RelayPushCoordinator.resolveSessionRoute(wake: wake) { method, params in
            calls.append((method, params))
            return ["capabilities": ["push_notifications_v1": true]]
        }
        XCTAssertNil(legacy)
        XCTAssertEqual(calls.map(\.0), ["relay.status"])
    }
    func testSessionResolutionRejectsMalformedOrUnresolvedResponses() {
        XCTAssertNil(RelayPushCoordinator.resolvedSessionRoute(["resolved": false]))
        XCTAssertNil(RelayPushCoordinator.resolvedSessionRoute([
            "resolved": true, "durable_session_id": "session", "profile": String(repeating: "p", count: 65)
        ]))
        XCTAssertNil(RelayPushCoordinator.resolvedSessionRoute([
            "resolved": true, "durable_session_id": "bad\n", "profile": "default"
        ]))
        XCTAssertTrue(RelayPushCoordinator.supportsSessionResolution([
            "capabilities": ["push_notifications_v2": true]
        ]))
        XCTAssertFalse(RelayPushCoordinator.supportsSessionResolution([
            "capabilities": ["push_notifications_v2": 1]
        ]))
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
