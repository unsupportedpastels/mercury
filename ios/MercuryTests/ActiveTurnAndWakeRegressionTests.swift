import XCTest
import UserNotifications
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
                    ? ["capabilities": ["push_notifications_v1": true]] : ["status": "queued"]
                return String(data: try! JSONSerialization.data(withJSONObject: ["jsonrpc": "2.0", "id": id, "result": result]), encoding: .utf8)
            }
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            let route = await RelayPushCoordinator.resolveSessionRoute(wake: String(repeating: "a", count: 43)) { method, params in
                try await connection.relayRequest(method, params: params, timeoutNanoseconds: 20_000_000)
            }
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
        delegate?.userNotificationCenter(.current(), didReceive: response) { completions += 1 }
        XCTAssertEqual(completions, 1, "System completion must not await network routing")
        delegate = nil
        await fulfillment(of: [started], timeout: 2)
        XCTAssertNotNil(retained, "Routing owns delegate until completion")
        release?.resume()
        await fulfillment(of: [routed], timeout: 2)
        XCTAssertEqual(completions, 1)
    }
}

private final class NotificationResponseFixtureCoder: NSCoder {
    let values: [String: Any]
    init(_ values: [String: Any]) { self.values = values; super.init() }
    override var allowsKeyedCoding: Bool { true }
    override func containsValue(forKey key: String) -> Bool { values[key] != nil }
    override func decodeObject(forKey key: String) -> Any? { values[key] }
}
