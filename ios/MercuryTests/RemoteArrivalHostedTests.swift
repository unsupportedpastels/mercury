import XCTest
import SwiftUI
import UIKit
import UserNotifications
import MercuryNotificationPreviewKit
@testable import Mercury

private final class RemoteArrivalProbe: NSObject, UNUserNotificationCenterDelegate {
    let production: NotificationDelegate
    let model: AppModel
    var rows: [[String: Any]] = []
    var arrived: XCTestExpectation?
    var clearReceipt = false
    var phase = "new_preview"
    var owner = UUID()
    var observedIdentity: NotificationSessionIdentity?
    init(production: NotificationDelegate, model: AppModel) {
        self.production = production; self.model = model
        super.init()
        let policy = production.shouldPresent
        production.shouldPresent = { [weak self] identity in
            self?.observedIdentity = identity
            return policy?(identity) ?? true
        }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        Task { @MainActor in
            if self.clearReceipt { PreviewRouteStore()?.clear() }
            self.observedIdentity = nil
            self.production.userNotificationCenter(center, willPresent: notification) { options in
                Task { @MainActor in
                    let info = notification.request.content.userInfo
                    let wake = RelayPushCoordinator.wake(from: info)
                    let event = info["mercury_event"] as? String
                    let route = wake.flatMap { w in event.flatMap { PreviewRouteStore()?.peek(event: $0, wake: w, now: Int64(Date().timeIntervalSince1970)) } }
                    let identity = self.observedIdentity
                    self.rows.append([
                        "phase": self.phase, "receipt_fault_injected": self.clearReceipt,
                        "remote": notification.request.trigger is UNPushNotificationTrigger,
                        "receipt_present": route != nil, "identity_present": identity != nil,
                        "scope_present": self.model.notificationSourceScope != nil,
                        "visible_present": self.model.visibleNotificationSession != nil,
                        "visible_owner_match": self.model.notificationVisibilityOwned(by: self.owner),
                        "identity_match": identity != nil && identity == self.model.visibleNotificationSession,
                        "foreground": UIApplication.shared.applicationState == .active,
                        "banner": options.contains(.banner), "list": options.contains(.list), "sound": options.contains(.sound)
                    ])
                    completionHandler(options)
                    self.arrived?.fulfill()
                }
            }
        }
    }
}

/// Operator-only real sandbox APNs, real Hermes turns, real mounted ChatView.
/// Only the missing-receipt case injects a fault (deletes the protected receipt).
@MainActor
final class RemoteArrivalHostedTests: XCTestCase {
    func testRealAPNsCurrentChat() async throws {
        #if targetEnvironment(simulator)
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let fixture = directory.appendingPathComponent("apns-arrival.json")
        guard FileManager.default.fileExists(atPath: fixture.path) else { throw XCTSkip("Operator-only private APNs arrival pairing required") }
        continueAfterFailure = false
        let raw = try Data(contentsOf: fixture)
        let values = try XCTUnwrap(try JSONSerialization.jsonObject(with: raw) as? [String: String])
        let run = try XCTUnwrap(values["run_id"])
        let target = RelayPairedTarget(id: UUID(uuidString: values["id"]!)!, label: "APNs arrival gate", relayOrigin: values["origin"]!,
            installationID: Data(base64Encoded: values["installation"]!)!, hostPublicKey: Data(base64Encoded: values["host"]!)!,
            deviceID: values["device"]!, deviceStaticPrivateKey: Data(base64Encoded: values["private_key"]!)!, fingerprint: "0000000000000000",
            status: .approved, createdAtEpochSeconds: Int64(Date().timeIntervalSince1970), lastUsedEpochSeconds: nil, relayRoutingToken: values["routing_token"]!)
        let store = RelayTargetStore(persistence: InMemoryRelayTargetPersistence())
        _ = try await store.add(target)
        let model = AppModel(relayTargetStore: store)
        await model.loadRelayTargets()
        await model.connectRelay(target)
        XCTAssertEqual(model.activeRelayTarget?.id, target.id, "production target selection gate")
        let production = NotificationDelegate()
        model.configureNotificationPresentation(production)
        let probe = RemoteArrivalProbe(production: production, model: model)
        let center = UNUserNotificationCenter.current()
        let previousDelegate = center.delegate
        center.delegate = probe
        defer { center.delegate = previousDelegate }
        let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
        XCTAssertTrue(granted, "permission gate")
        let tokenReady = expectation(description: "Apple token")
        var token: Data?
        MercuryApplicationDelegate.onToken = { token = $0; tokenReady.fulfill() }
        MercuryApplicationDelegate.onFailure = { tokenReady.fulfill() }
        UIApplication.shared.registerForRemoteNotifications()
        await fulfillment(of: [tokenReady], timeout: 30)
        let appleToken = try XCTUnwrap(token, "Apple token gate")
        let connection = try await RelayConnectionPool.shared.acquire(target: target, profile: "default", channel: "arrival-registration")
        model.relayPush.setPreview(enabled: true, includeTitle: true, includeResponseExcerpt: true)
        model.relayPush.setPreviewCategories(completion: true, attention: true)
        model.relayPush.setEnabled(true)
        model.relayPush.receivedToken(appleToken)
        model.relayPush.connected(target: target, identity: ObjectIdentifier(connection)) { method, params in
            try await connection.relayRequest(method, params: params)
        }
        await model.relayPush.waitForWork()
        XCTAssertTrue(model.relayPush.status.contains("Encrypted previews active"), "registration gate")
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        let chat = ChatView.newSession()
        let state = chat.state
        let host = UIHostingController(rootView: NavigationStack { chat.environment(model).id(state.notificationVisibilityOwner) })
        window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        probe.owner = state.notificationVisibilityOwner
        var backgroundDelivered = false
        func save() throws {
            let receipt: [String: Any] = ["run_id": run, "chat_mounted": window.rootViewController?.view.window != nil,
                "delegate_retained": center.delegate === probe, "app_foreground": UIApplication.shared.applicationState == .active,
                "new_durable_adopted": state.durableID != nil, "background_delivered": backgroundDelivered, "rows": probe.rows]
            try JSONSerialization.data(withJSONObject: receipt, options: [.sortedKeys]).write(to: directory.appendingPathComponent("apns-arrival-result.json"), options: [.atomic, .completeFileProtection])
        }
        func ready(_ state: ChatSessionState, stage: String) async throws {
            let deadline = Date().addingTimeInterval(45)
            func liveReady() -> Bool { if case .live = state.connectionState { return state.runtimeSessionID != nil }; return false }
            while !liveReady() && Date() < deadline { try await Task.sleep(nanoseconds: 100_000_000) }
            let lifecycle: [String: Any] = ["run_id": run, "stage": stage,
                "did_open": state.didOpen, "closed": state.closedByUs, "establishing": state.establishing,
                "runtime_present": state.runtimeSessionID != nil, "durable_present": state.durableID != nil,
                "owner_match": model.notificationVisibilityOwned(by: state.notificationVisibilityOwner)]
            try JSONSerialization.data(withJSONObject: lifecycle).write(to: directory.appendingPathComponent("arrival-lifecycle.json"), options: .atomic)
            XCTAssertTrue(liveReady(), "production ChatView live gate " + stage)
            XCTAssertNotNil(state.runtimeSessionID, "production ChatView runtime gate " + stage)
            XCTAssertNotNil(state.connection, "production ChatView connection gate " + stage)
        }
        func deliver(_ phase: String, using live: ChatConnection, runtime: String, present: Bool) async throws {
            probe.phase = phase
            probe.arrived = expectation(description: "real APNs " + phase)
            _ = try await live.submitPrompt(runtimeSessionID: runtime, text: "Reply with exactly ARRIVAL GATE OK. Do not call tools.")
            await fulfillment(of: [probe.arrived!], timeout: 75)
            try save()
            let row = try XCTUnwrap(probe.rows.last, "remote delegate gate " + phase)
            XCTAssertEqual(row["phase"] as? String, phase)
            XCTAssertEqual(row["remote"] as? Bool, true)
            XCTAssertEqual(row["banner"] as? Bool, present, "REMOTE banner gate " + phase)
            XCTAssertEqual(row["list"] as? Bool, present, "REMOTE list gate " + phase)
            XCTAssertEqual(row["sound"] as? Bool, present, "REMOTE sound gate " + phase)
            if !present {
                let delivered = await center.deliveredNotifications()
                XCTAssertTrue(delivered.isEmpty, "suppressed remote notification must not enter Notification Center")
            }
        }
        await center.removeAllDeliveredNotifications()
        try await ready(state, stage: "new")
        let runtime = try XCTUnwrap(state.runtimeSessionID)
        let live = try XCTUnwrap(state.connection)
        if values["remaining"] != "1" {
        try await deliver("new_preview", using: live, runtime: runtime, present: false)
        XCTAssertTrue(model.notificationVisibilityOwned(by: state.notificationVisibilityOwner))
        XCTAssertEqual(model.visibleSessionID, state.durableID)
        let liveDeadline = Date().addingTimeInterval(10)
        func isLive() -> Bool { if case .live = state.connectionState { return true }; return false }
        while !isLive() && Date() < liveDeadline { try await Task.sleep(nanoseconds: 100_000_000) }
        XCTAssertTrue(isLive(), "durable adoption must not invalidate connection admission")
        try await Task.sleep(nanoseconds: 11_000_000_000)
        probe.clearReceipt = true
        try await deliver("missing_receipt", using: live, runtime: runtime, present: false)
        probe.clearReceipt = false
        try await Task.sleep(nanoseconds: 11_000_000_000)
        model.relayPush.setPreview(enabled: false, includeTitle: false, includeResponseExcerpt: false)
        await model.relayPush.waitForWork()
        try await deliver("generic_current", using: live, runtime: runtime, present: false)
        try await Task.sleep(nanoseconds: 11_000_000_000)
        } else {
            model.relayPush.setPreview(enabled: false, includeTitle: false, includeResponseExcerpt: false)
            await model.relayPush.waitForWork()
        }
        let other = ChatView.newSession()
        let otherState = other.state
        window.rootViewController = UIHostingController(rootView: NavigationStack { other.environment(model) })
        probe.owner = otherState.notificationVisibilityOwner
        try await ready(otherState, stage: "other")
        try await deliver("generic_other_session", using: live, runtime: runtime, present: true)
        await center.removeAllDeliveredNotifications()
        try await Task.sleep(nanoseconds: 11_000_000_000)
        let restored = ChatView(sessionID: state.durableID!, title: "Restored arrival gate")
        let restoredState = restored.state
        window.rootViewController = UIHostingController(rootView: NavigationStack { restored.environment(model) })
        probe.owner = restoredState.notificationVisibilityOwner
        try await ready(restoredState, stage: "restored")
        try await deliver("generic_restored_current", using: restoredState.connection!, runtime: restoredState.runtimeSessionID!, present: false)
        // The private Mac driver backgrounds the app and captures the real OS banner.
        try await Task.sleep(nanoseconds: 11_000_000_000)
        try Data(run.utf8).write(to: directory.appendingPathComponent("arrival-background-ready"), options: .atomic)
        let backgroundDeadline = Date().addingTimeInterval(15)
        while UIApplication.shared.applicationState == .active && Date() < backgroundDeadline { try await Task.sleep(nanoseconds: 100_000_000) }
        XCTAssertNotEqual(UIApplication.shared.applicationState, .active, "operator background driver gate")
        probe.phase = "background"
        probe.arrived = nil
        _ = try await restoredState.connection!.submitPrompt(runtimeSessionID: restoredState.runtimeSessionID!, text: "Reply with exactly ARRIVAL GATE OK. Do not call tools.")
        let deliveryDeadline = Date().addingTimeInterval(45)
        while !backgroundDelivered && Date() < deliveryDeadline {
            backgroundDelivered = !(await center.deliveredNotifications()).isEmpty
            if !backgroundDelivered { try await Task.sleep(nanoseconds: 100_000_000) }
        }
        try save()
        try Data(run.utf8).write(to: directory.appendingPathComponent("arrival-background-delivered"), options: .atomic)
        XCTAssertTrue(backgroundDelivered, "real background APNs Notification Center positive control")
        let returnDeadline = Date().addingTimeInterval(15)
        while UIApplication.shared.applicationState != .active && Date() < returnDeadline { try await Task.sleep(nanoseconds: 100_000_000) }
        try save()
        #else
        throw XCTSkip("Simulator only; physical requires a separate explicit deployment gate")
        #endif
    }
}
