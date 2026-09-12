import XCTest
import UserNotifications
import UIKit
@testable import Mercury

final class ActiveTurnAndWakeRegressionTests: XCTestCase {
    @MainActor
    func testUnansweredWakeReadsFallBackWithoutRetainingRPCOrClosingChannel() async throws {
        for stalledMethod in ["relay.status", "relay.push.resolve"] {
            let socket = ConnectionTestSocket(frames: [])
            socket.autoRespond = { sent in
                guard let data = sent.data(using: .utf8),
                      let frame = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let method = frame["method"] as? String, let id = frame["id"] else { return nil }
                guard method != stalledMethod else { return nil }
                let result: [String: Any] = method == "relay.status"
                    ? ["capabilities": ["push_notifications_v1": true, "push_notifications_v2": true]] : ["status": "queued"]
                return String(data: try! JSONSerialization.data(withJSONObject: ["jsonrpc": "2.0", "id": id, "result": result]), encoding: .utf8)
            }
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            let route = await RelayPushCoordinator.resolveSessionRoute(wake: String(repeating: "a", count: 43)) { method, params in
                try await connection.relayRequest(method, params: params, timeoutNanoseconds: 20_000_000)
            }
            let methods = socket.sent.compactMap { sent -> String? in
                guard let data = sent.data(using: .utf8),
                      let frame = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
                return frame["method"] as? String
            }
            XCTAssertEqual(methods, stalledMethod == "relay.status" ? ["relay.status"] : ["relay.status", "relay.push.resolve"])
            XCTAssertNil(route, "Unanswered \(stalledMethod) falls back to mapped Home")
            XCTAssertEqual(connection.pendingRequestCount, 0)
            XCTAssertFalse(socket.wasClosed)
            let next = try await connection.submitPrompt(runtimeSessionID: "runtime", text: "Synthetic follow-up")
            XCTAssertEqual(next.status, "queued")
            XCTAssertEqual(connection.pendingRequestCount, 0)
            await connection.close()
        }
    }
    @MainActor
    func testInterimToolGapKeepsQueueAndExplicitSteerAvailable() throws {
        let state = ChatSessionState(sessionID: "session", title: "", isNewSession: false, incomingShare: nil)
        state.connectionState = .live
        state.isSending = true
        state.isComposerActionPending = true
        XCTAssertTrue(state.composerIsBusy, "Submission admission remains locked")
        state.transcript.apply(.messageStart(sessionID: "session", text: "Checking"))
        state.isComposerActionPending = false // prompt.submit acknowledged
        state.transcript.apply(.messageInterim(sessionID: "session", text: "Checking tools", alreadyStreamed: true))
        state.transcript.apply(.toolStart(sessionID: "session", toolID: "tool-1", name: "read", context: nil))
        XCTAssertFalse(state.transcript.hasStreamingAssistant)
        XCTAssertTrue(state.turnInFlight, "An interim row is not terminal")
        XCTAssertFalse(state.composerIsBusy)
        XCTAssertEqual(M7ComposerPolicy.route(draft: "Next", turnActive: state.turnInFlight, hasAttachments: false), .queue(text: "Next"))
        XCTAssertEqual(M7ComposerPolicy.route(draft: "/steer Be brief", turnActive: state.turnInFlight, hasAttachments: false), .steer(text: "Be brief"))
        XCTAssertEqual(M7ComposerPolicy.route(draft: "/model", turnActive: state.turnInFlight, hasAttachments: false), .openModelPicker)
        _ = try XCTUnwrap(state.queuedPromptState.lifecycle.begin(draft: "Next"))
        XCTAssertTrue(state.composerIsBusy, "Unacknowledged queue cannot duplicate")
        state.queuedPromptState.lifecycle.discard()
        state.isComposerActionPending = true
        XCTAssertTrue(state.composerIsBusy, "Explicit steer RPC remains serialized")
        state.isComposerActionPending = false
        state.isSending = false // authoritative terminal event
        XCTAssertFalse(state.turnInFlight)
        XCTAssertEqual(M7ComposerPolicy.route(draft: "Next", turnActive: state.turnInFlight, hasAttachments: false), .submit(text: "Next"))
    }

    @MainActor
    func testWakeResponseCompletesImmediatelyAndRetainsRouting() async throws {
        let started = expectation(description: "routing entered")
        let routed = expectation(description: "routing finished after response release")
        var release: CheckedContinuation<Void, Never>?
        var delegate: NotificationDelegate? = NotificationDelegate()
        weak var retained = delegate
        var assertions = 0
        var ends = 0
        delegate?.beginTask = { _ in assertions += 1; return UIBackgroundTaskIdentifier(rawValue: 42) }
        delegate?.endTask = { id in
            XCTAssertEqual(id.rawValue, 42)
            assertions -= 1; ends += 1
        }
        delegate?.onWake = { wake in
            XCTAssertEqual(wake, String(repeating: "a", count: 43))
            await withCheckedContinuation { continuation in
                release = continuation
                started.fulfill()
            }
            routed.fulfill()
        }
        var completions = 0
        let content = UNMutableNotificationContent()
        content.userInfo = ["mercury_wake": String(repeating: "a", count: 43)]
        let request = UNNotificationRequest(identifier: "synthetic-wake", content: content, trigger: nil)
        let notification = try XCTUnwrap(UNNotification(coder: NotificationResponseFixtureCoder([
            "request": request, "date": Date()
        ])))
        let response = try XCTUnwrap(UNNotificationResponse(coder: NotificationResponseFixtureCoder([
            "notification": notification, "actionIdentifier": UNNotificationDefaultActionIdentifier
        ])))
        delegate?.userNotificationCenter(.current(), didReceive: response) {
            XCTAssertEqual(assertions, 1, "Acquire OS execution before releasing notification response")
            completions += 1
        }
        XCTAssertEqual(completions, 1, "System completion must not await network routing")
        delegate = nil
        await fulfillment(of: [started], timeout: 2)
        XCTAssertNotNil(retained, "Routing owns delegate until completion")
        release?.resume()
        await fulfillment(of: [routed], timeout: 2)
        XCTAssertEqual(completions, 1)
        XCTAssertEqual(ends, 1)
        XCTAssertEqual(assertions, 0)
    }

    @MainActor
    func testWakeExecutionExpiryAndDeadlineCancelOwnedRouteExactlyOnce() async throws {
        for useOSExpiry in [true, false] {
            let delegate = NotificationDelegate()
            var expire: (@MainActor () -> Void)?
            var releaseDeadline: CheckedContinuation<Void, Never>?
            let deadlineStarted = expectation(description: "deadline armed")
            let started = expectation(description: "route started under assertion")
            let cancelled = expectation(description: "owned route cancelled")
            var ends = 0
            delegate.beginTask = { handler in expire = handler; return UIBackgroundTaskIdentifier(rawValue: 43) }
            delegate.endTask = { _ in ends += 1 }
            delegate.routeSleep = {
                await withCheckedContinuation { releaseDeadline = $0; deadlineStarted.fulfill() }
            }
            delegate.onWake = { _ in
                XCTAssertEqual(ends, 0)
                started.fulfill()
                do { try await Task.sleep(nanoseconds: 60_000_000_000); XCTFail("Must cancel route") }
                catch { XCTAssertTrue(Task.isCancelled); cancelled.fulfill() }
            }
            var completions = 0
            delegate.userNotificationCenter(.current(), didReceive: try wakeResponse()) { completions += 1 }
            XCTAssertEqual(completions, 1)
            await fulfillment(of: [started, deadlineStarted], timeout: 2)
            if useOSExpiry { expire?() }
            releaseDeadline?.resume()
            await fulfillment(of: [cancelled], timeout: 2)
            expire?(); expire?()
            XCTAssertEqual(ends, 1)
            XCTAssertEqual(completions, 1)
        }
    }

    @MainActor
    func testInvalidOrSynchronouslyExpiredAssertionAcknowledgesWithoutUnprotectedRouting() async throws {
        for expiresDuringBegin in [false, true] {
            let delegate = NotificationDelegate()
            var ends = 0
            var completions = 0
            delegate.beginTask = { expire in
                if expiresDuringBegin { expire(); return UIBackgroundTaskIdentifier(rawValue: 44) }
                return .invalid
            }
            delegate.endTask = { _ in ends += 1 }
            delegate.onWake = { _ in XCTFail("No routing without execution lifetime") }
            delegate.userNotificationCenter(.current(), didReceive: try wakeResponse()) { completions += 1 }
            await Task.yield()
            XCTAssertEqual(completions, 1)
            XCTAssertEqual(ends, expiresDuringBegin ? 1 : 0)
        }
    }

    @MainActor
    func testProductionLocalCallbacksRecordIntentBeforeAcknowledgementWithoutAssertion() throws {
        for canonical in [false, true] {
            let model = AppModel()
            let delegate = NotificationDelegate()
            MercuryApp.configureNotificationRouting(delegate, model: model)
            delegate.beginTask = { _ in .invalid }
            delegate.endTask = { _ in XCTFail("Local intent needs no assertion") }
            let route = SessionOpenRoute(durableSessionID: "local-session", serverID: UUID(), profile: "default")
            let content = UNMutableNotificationContent()
            content.userInfo = canonical
                ? ["mercury.route": MercuryDeepLink.sessionURL(durableSessionID: route.durableSessionID, serverID: route.serverID, profile: route.profile)!.absoluteString]
                : ["mercury.sessionID": "local-session"]
            let request = UNNotificationRequest(identifier: "local", content: content, trigger: nil)
            let notification = try XCTUnwrap(UNNotification(coder: NotificationResponseFixtureCoder(["request": request, "date": Date()])))
            let response = try XCTUnwrap(UNNotificationResponse(coder: NotificationResponseFixtureCoder([
                "notification": notification, "actionIdentifier": UNNotificationDefaultActionIdentifier
            ])))
            var completions = 0
            delegate.userNotificationCenter(.current(), didReceive: response) {
                completions += 1
                if canonical { XCTAssertEqual(model.pendingSessionRoute, route) }
                else { XCTAssertEqual(model.notificationOpenRequest?.sessionID, "local-session") }
            }
            XCTAssertEqual(completions, 1)
        }
    }

    @MainActor
    func testProductionDirectCallbacksAreSynchronousAndRemoteLocalInjectionIsIgnored() async throws {
        for remote in [false, true] {
            for denied in [false, true] {
                let model = AppModel()
                let delegate = NotificationDelegate()
                MercuryApp.configureNotificationRouting(delegate, model: model)
                var assertions = 0
                var ends = 0
                delegate.beginTask = { _ in
                    assertions += 1
                    return denied ? .invalid : UIBackgroundTaskIdentifier(rawValue: 55)
                }
                delegate.endTask = { _ in ends += 1 }
                let route = SessionOpenRoute(durableSessionID: "canonical", serverID: UUID(), profile: "work")
                // Also call the exact closures installed by MercuryApp: a new
                // scheduling hop here must fail even if delegate routing changes.
                delegate.onOpenSession?("session")
                XCTAssertEqual(model.notificationOpenRequest?.sessionID, "session")
                model.clearOpenSessionRequest()
                let content = UNMutableNotificationContent()
                content.userInfo = ["mercury.route": MercuryDeepLink.sessionURL(durableSessionID: route.durableSessionID, serverID: route.serverID, profile: route.profile)!.absoluteString,
                                    "mercury.sessionID": "untrusted-fallback"]
                let trigger: UNNotificationTrigger? = remote
                    ? try XCTUnwrap(UNPushNotificationTrigger(coder: NotificationResponseFixtureCoder([:]))) : nil
                let request = UNNotificationRequest(identifier: "trust", content: content, trigger: trigger)
                let notification = try XCTUnwrap(UNNotification(coder: NotificationResponseFixtureCoder(["request": request, "date": Date()])))
                let response = try XCTUnwrap(UNNotificationResponse(coder: NotificationResponseFixtureCoder([
                    "notification": notification, "actionIdentifier": UNNotificationDefaultActionIdentifier
                ])))
                var completions = 0
                delegate.userNotificationCenter(.current(), didReceive: response) {
                    completions += 1
                    XCTAssertEqual(model.pendingSessionRoute, remote ? nil : route)
                    XCTAssertNil(model.notificationOpenRequest)
                }
                for _ in 0..<10 { await Task.yield() }
                XCTAssertEqual(model.pendingSessionRoute, remote ? nil : route)
                XCTAssertNil(model.notificationOpenRequest)
                XCTAssertEqual(assertions, remote ? 1 : 0)
                XCTAssertEqual(ends, remote && !denied ? 1 : 0)
                XCTAssertEqual(completions, 1)
            }
        }
    }

    private func wakeResponse() throws -> UNNotificationResponse {
        let content = UNMutableNotificationContent()
        content.userInfo = ["mercury_wake": String(repeating: "a", count: 43)]
        let request = UNNotificationRequest(identifier: "synthetic-lifetime", content: content, trigger: nil)
        let notification = try XCTUnwrap(UNNotification(coder: NotificationResponseFixtureCoder(["request": request, "date": Date()])))
        return try XCTUnwrap(UNNotificationResponse(coder: NotificationResponseFixtureCoder([
            "notification": notification, "actionIdentifier": UNNotificationDefaultActionIdentifier
        ])))
    }
}

private final class NotificationResponseFixtureCoder: NSCoder {
    let values: [String: Any]
    init(_ values: [String: Any]) { self.values = values; super.init() }
    override var allowsKeyedCoding: Bool { true }
    override func containsValue(forKey key: String) -> Bool { values[key] != nil }
    override func decodeBool(forKey key: String) -> Bool { values[key] as? Bool ?? false }
    override func decodeObject(forKey key: String) -> Any? { values[key] }
}
