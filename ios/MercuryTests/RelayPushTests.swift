import XCTest
import UserNotifications
import MercuryNotificationPreviewKit
@testable import Mercury

@MainActor
final class RelayPushTests: XCTestCase {
    private final class RouteReader: PreviewRouteConsuming {
        var route: PreviewRouteRecord?
        init(_ route: PreviewRouteRecord?) { self.route = route }
        func consume(event: String, wake: String, now: Int64) -> PreviewRouteRecord? {
            defer { route = nil }; return route
        }
    }
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
    func testGenericPushWithoutAuthenticatedRouteAndDirectLocalPreserved() {
        let payload: [AnyHashable: Any] = ["aps": ["alert": ["title": "Mercury", "body": "An update is available. Open Mercury to continue."], "sound": "default"], "mercury_wake": wake]
        XCTAssertEqual(RelayPushCoordinator.wake(from: payload), wake)
        XCTAssertEqual(NotificationDelegate.presentationOptions(userInfo: payload), [.banner, .sound, .list])
        XCTAssertTrue(NotificationDelegate.presentationOptions(userInfo: ["mercury.sessionID": "local"]).contains(.banner))
    }
    func testSystemResponseCompletesBeforeIndependentWakeRouting() async {
        let delegate = NotificationDelegate(previewRoutes: RouteReader(nil))
        var completed = false
        let routed = expectation(description: "retained route task")
        delegate.onWake = { _ in
            XCTAssertTrue(completed)
            routed.fulfill()
        }
        delegate.handlePayload(["mercury_wake": wake]) { completed = true }
        XCTAssertTrue(completed, "System completion must not wait for routing or the network")
        await fulfillment(of: [routed], timeout: 2)
    }

    func testPreviewTapUsesOnlyAuthenticatedRouteStoreNotTransportFields() async {
        let event = String(repeating: "e", count: 43)
        let spoofed: [AnyHashable: Any] = ["mercury_wake": wake, "mercury_event": event,
            "mercury.preview.sid": "spoof", "mercury.preview.profile": "spoof"]
        let noRoute = NotificationDelegate(previewRoutes: RouteReader(nil))
        var fallbackWake: String?
        let fallbackRouted = expectation(description: "fallback routed")
        noRoute.onWake = { fallbackWake = $0; fallbackRouted.fulfill() }
        noRoute.handlePayload(spoofed) {}
        await fulfillment(of: [fallbackRouted], timeout: 2)
        XCTAssertEqual(fallbackWake, wake)

        let stored = PreviewRouteRecord(event: event, wake: wake, sessionID: "durable", profile: "default", expiresAt: Int64(Date().timeIntervalSince1970) + 60)
        let delegate = NotificationDelegate(previewRoutes: RouteReader(stored))
        var opened: (String, String, String)?
        let previewRouted = expectation(description: "preview routed")
        delegate.onPreviewRoute = { opened = ($0, $1, $2); previewRouted.fulfill() }
        delegate.handlePayload(spoofed) {}
        await fulfillment(of: [previewRouted], timeout: 2)
        XCTAssertEqual(opened?.0, wake); XCTAssertEqual(opened?.1, "durable"); XCTAssertEqual(opened?.2, "default")
    }
    func testCapabilityMustBeExplicitBoolean() {
        XCTAssertTrue(RelayPushCoordinator.supports(["capabilities": ["push_notifications_v1": true]]))
        for value in [false, 1, "true", ["enabled": true]] as [Any] {
            XCTAssertFalse(RelayPushCoordinator.supports(["capabilities": ["push_notifications_v1": value]]))
        }
        XCTAssertFalse(RelayPushCoordinator.supports([:]))
    }
    func testPreviewCapabilityRequiresExactMethodsAlgorithmAndClientBounds() {
        let exact: [String: Any] = ["capabilities": ["push_previews": [
            "version": 1, "register_method": "relay.push.preview.register", "unregister_method": "relay.push.unregister",
            "aead": "CHACHA20-POLY1305", "max_plaintext_bytes": 1280, "max_title_utf8_bytes": 160, "max_body_utf8_bytes": 640
        ]]]
        XCTAssertNotNil(RelayPushCoordinator.previewCapability(exact))
        var cap = (exact["capabilities"] as! [String: Any])["push_previews"] as! [String: Any]
        for (field, value) in [("version", "1"), ("aead", "AES-GCM"), ("max_plaintext_bytes", 1281)] as [(String, Any)] {
            var invalid = cap; invalid[field] = value
            XCTAssertNil(RelayPushCoordinator.previewCapability(["capabilities": ["push_previews": invalid]]))
        }
        cap["future"] = true
        XCTAssertNotNil(RelayPushCoordinator.previewCapability(["capabilities": ["push_previews": cap]]))
    }
    func testPreviewRegistrationUsesExactAuthenticatedRPCAndSelectivePreferences() async {
        let c = coordinator(), t = target(), owner = NSObject()
        c.setPreview(enabled: true, includeTitle: true, includeResponseExcerpt: false)
        c.setPreviewCategories(completion: true, attention: false)
        c.select(t); c.setEnabled(true); c.receivedToken(Data([0xab]))
        var captured: [String: Any]?, provisionedKeyIDs: [String] = []
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, params in
            if method == "relay.status" { return ["capabilities": ["push_previews": [
                "version": 1, "register_method": "relay.push.preview.register", "unregister_method": "relay.push.unregister",
                "aead": "CHACHA20-POLY1305", "max_plaintext_bytes": 1280, "max_title_utf8_bytes": 160, "max_body_utf8_bytes": 640
            ]]] }
            XCTAssertEqual(method, "relay.push.preview.register"); captured = params
            let preview = params["preview"] as! [String: Any]
            provisionedKeyIDs.append(preview["key_id"] as! String)
            return ["registered": true, "wake_handle": self.wake, "preview": ["version": 1, "key_id": preview["key_id"]!]]
        }
        await c.waitForWork()
        let params = try? XCTUnwrap(captured), preview = params.flatMap { $0["preview"] as? [String: Any] }
        XCTAssertEqual(Set(params?.keys.map { $0 } ?? []), ["device_token", "environment", "preview"])
        XCTAssertEqual(Set(preview?.keys.map { $0 } ?? []), ["version", "key_id", "key", "completion", "attention", "include_title", "include_response_excerpt"])
        XCTAssertEqual(preview?["completion"] as? Bool, true); XCTAssertEqual(preview?["attention"] as? Bool, false)
        XCTAssertEqual(preview?["include_title"] as? Bool, true); XCTAssertEqual(preview?["include_response_excerpt"] as? Bool, false)
        XCTAssertEqual(PushPreviewEnvelope.decode(preview?["key"] as? String ?? "")?.count, 32)
        XCTAssertEqual(PushPreviewEnvelope.decode(preview?["key_id"] as? String ?? "")?.count, 16)
        XCTAssertTrue(c.ownsDelivery(for: t))
        let completion = ChatEvent.messageComplete(sessionID: "fixture", text: "done", status: "complete", error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil)
        let attention = ChatEvent.clarifyRequest(sessionID: "fixture", requestID: "request", question: "prompt", choices: [], multiSelect: false)
        XCTAssertTrue(c.ownsDelivery(for: t, event: completion))
        XCTAssertFalse(c.ownsDelivery(for: t, event: attention))
        c.receivedToken(Data([0xac])); await c.waitForWork()
        XCTAssertEqual(provisionedKeyIDs.count, 2)
        XCTAssertEqual(provisionedKeyIDs[0], provisionedKeyIDs[1], "APNs token rotation must reuse the independent preview key")
        c.setPreview(enabled: false, includeTitle: false, includeResponseExcerpt: false)
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
    func testDisablingPreviewsOfflinePreservesRemoteOwnershipAndTapRoute() throws {
        let defaults = UserDefaults(suiteName: "preview-offline-\(UUID())")!
        let target = target()
        let binding = RelayPushCoordinator.Binding(scope: .init(target), wake: wake, previewKeyID: "fixture-key", completion: true, attention: false)
        defaults.set(try JSONEncoder().encode([binding]), forKey: "mercury.apns.wake-bindings.v1")
        defaults.set(true, forKey: "mercury.apns.preview.enabled.v1")
        let coordinator = RelayPushCoordinator(defaults: defaults)
        coordinator.setEnabled(true)
        coordinator.setPreview(enabled: false, includeTitle: false, includeResponseExcerpt: false)
        XCTAssertTrue(coordinator.ownsDelivery(for: target))
        XCTAssertEqual(coordinator.target(for: wake, targets: [target])?.id, target.id)
        let cold = RelayPushCoordinator(defaults: defaults)
        cold.setEnabled(true)
        XCTAssertTrue(cold.ownsDelivery(for: target))
    }

    func testReusedPreviewKeyMovesWakeWithBoundedRetirementAndCleanup() async throws {
        let defaults = UserDefaults(suiteName: "preview-wake-\(UUID())")!
        let c = RelayPushCoordinator(defaults: defaults), t = target(), owner = NSObject()
        let firstWake = PushPreviewEnvelope.encode(Data(repeating: 8, count: 32))
        let secondWake = PushPreviewEnvelope.encode(Data(repeating: 9, count: 32))
        let keys = PreviewKeychainRepository()
        var identifiers: [String] = []
        defer {
            for id in identifiers {
                keys.delete(environment: "sandbox", wake: firstWake, keyID: id)
                keys.delete(environment: "sandbox", wake: secondWake, keyID: id)
            }
        }
        c.setPreview(enabled: true, includeTitle: true, includeResponseExcerpt: false)
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(owner)) { method, params in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true, "push_previews": [
                "version": 1, "register_method": "relay.push.preview.register", "unregister_method": "relay.push.unregister",
                "aead": "CHACHA20-POLY1305", "max_plaintext_bytes": 1280, "max_title_utf8_bytes": 160, "max_body_utf8_bytes": 640
            ]]] }
            if method == "relay.push.register" { return ["registered": true, "wake_handle": secondWake] }
            let preview = try XCTUnwrap(params["preview"] as? [String: Any])
            let id = try XCTUnwrap(preview["key_id"] as? String)
            identifiers.append(id)
            return ["registered": true, "wake_handle": identifiers.count == 2 ? secondWake : firstWake,
                    "preview": ["version": 1, "key_id": id]]
        }
        await c.waitForWork()
        c.receivedToken(Data([2]))
        await c.waitForWork()
        XCTAssertEqual(identifiers.count, 2)
        XCTAssertEqual(identifiers.first, identifiers.last)
        let bindings = try JSONDecoder().decode([RelayPushCoordinator.Binding].self,
            from: XCTUnwrap(defaults.data(forKey: "mercury.apns.wake-bindings.v1")))
        XCTAssertEqual(bindings.first?.retiredWake, firstWake)
        let id = try XCTUnwrap(identifiers.first)
        let now = Int64(Date().timeIntervalSince1970)
        XCTAssertNotNil(try keys.load(environment: "sandbox", wake: firstWake, keyID: id, now: now)?.retireAt)
        c.receivedToken(Data([3]))
        await c.waitForWork()
        XCTAssertNotNil(try keys.load(environment: "sandbox", wake: firstWake, keyID: id, now: now), "Returning to a retired wake must not delete the newly active key")
        c.setPreview(enabled: false, includeTitle: false, includeResponseExcerpt: false)
        XCTAssertNil(try keys.load(environment: "sandbox", wake: firstWake, keyID: id, now: now))
        XCTAssertNil(try keys.load(environment: "sandbox", wake: secondWake, keyID: id, now: now))
        await c.waitForWork()
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
    func testFailedReregistrationPreservesPreviousGoodBinding() async {
        let c = coordinator(), t = target(), first = NSObject(), next = NSObject()
        c.select(t); c.setEnabled(true); c.receivedToken(Data([1]))
        c.connected(target: t, identity: ObjectIdentifier(first)) { method, _ in
            method == "relay.status" ? ["capabilities": ["push_notifications_v1": true]]
                : ["registered": true, "wake_handle": self.wake]
        }
        await c.waitForWork()
        c.connected(target: t, identity: ObjectIdentifier(next)) { _, _ in throw URLError(.notConnectedToInternet) }
        await c.waitForWork()
        XCTAssertTrue(c.ownsDelivery(for: t))
        XCTAssertEqual(c.target(for: wake, targets: [t])?.id, t.id)
        XCTAssertTrue(c.status.contains("previous registration remains active"))
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
