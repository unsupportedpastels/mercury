import XCTest
@testable import Mercury

final class NotificationReconcilerTests: XCTestCase {

    // Convenience: run the async reconciler with a canned tail per session.
    private func deltas(
        rows: [SessionRow],
        engaged: Set<String>,
        watermarks: [String: SessionWatermark] = [:],
        tails: [String: NotificationReconciler.SessionTail]
    ) async -> [ReconciliationDelta] {
        await NotificationReconciler.deltas(
            from: rows,
            engagedIDs: engaged,
            watermarks: watermarks,
            fetchTail: { tails[$0] }
        )
    }

    private func assistantTail(_ text: String) -> NotificationReconciler.SessionTail {
        NotificationReconciler.SessionTail(endedOnAssistant: true, assistantText: text)
    }

    private func toolTail() -> NotificationReconciler.SessionTail {
        NotificationReconciler.SessionTail(endedOnAssistant: false, assistantText: "")
    }

    // MARK: Scope (engaged sessions only)

    func testUnengagedSessionsProduceNoDelta() async {
        let rows = [SessionRow(id: "a", title: "Someone else", preview: "prompt", messageCount: 4)]
        let result = await deltas(rows: rows, engaged: [], tails: ["a": assistantTail("done")])
        XCTAssertTrue(result.isEmpty, "must not notify for a session the app never opened")
    }

    func testEmptySessionsProduceNoDelta() async {
        let rows = [SessionRow(id: "a", title: "Empty", preview: "", messageCount: 0)]
        let result = await deltas(rows: rows, engaged: ["a"], tails: ["a": assistantTail("x")])
        XCTAssertTrue(result.isEmpty)
    }

    // MARK: Advance gating

    func testEngagedSessionAtSameCountProducesNoDelta() async {
        let rows = [SessionRow(id: "a", title: "Work", preview: "prompt", messageCount: 4)]
        let watermarks = ["a": SessionWatermark(sessionID: "a", lastServerMessageCount: 4)]
        let result = await deltas(rows: rows, engaged: ["a"], watermarks: watermarks, tails: ["a": assistantTail("done")])
        XCTAssertTrue(result.isEmpty, "no advance since last reconcile → no delta")
    }

    func testEngagedSessionThatAdvancedProducesCompletionFromAssistantTail() async {
        let rows = [SessionRow(id: "a", title: "Work", preview: "the first prompt", messageCount: 6)]
        let watermarks = ["a": SessionWatermark(sessionID: "a", lastServerMessageCount: 4)]
        let result = await deltas(
            rows: rows, engaged: ["a"], watermarks: watermarks,
            tails: ["a": assistantTail("Here is the answer.")]
        )
        XCTAssertEqual(result.count, 1)
        let delta = result.first
        XCTAssertEqual(delta?.sessionID, "a")
        XCTAssertEqual(delta?.serverMessageCount, 6)
        // Body is the ASSISTANT response, never the REST preview (first prompt).
        XCTAssertEqual(delta?.newCompletion?.text, "Here is the answer.")
        XCTAssertNotEqual(delta?.newCompletion?.text, "the first prompt")
        XCTAssertEqual(delta?.newCompletion?.status, .finished)
        XCTAssertTrue(delta?.newCompletion?.turnSignature.hasPrefix("rest#6#") ?? false)
    }

    // MARK: Tool-tail suppression (never notify on a tool call)

    func testAdvancedSessionEndingOnToolCallPostsNothingButStillEmitsAdvanceDelta() async {
        let rows = [SessionRow(id: "a", title: "Work", preview: "prompt", messageCount: 5)]
        let watermarks = ["a": SessionWatermark(sessionID: "a", lastServerMessageCount: 4)]
        let result = await deltas(rows: rows, engaged: ["a"], watermarks: watermarks, tails: ["a": toolTail()])
        // A delta is emitted (so the engine advances the count watermark) but it
        // carries no completion, so nothing is posted.
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result.first?.serverMessageCount, 5)
        XCTAssertNil(result.first?.newCompletion)
    }

    func testNilTailPostsNothingButAdvances() async {
        let rows = [SessionRow(id: "a", title: "Work", preview: "prompt", messageCount: 5)]
        let watermarks = ["a": SessionWatermark(sessionID: "a", lastServerMessageCount: 4)]
        let result = await NotificationReconciler.deltas(
            from: rows, engagedIDs: ["a"], watermarks: watermarks, fetchTail: { _ in nil }
        )
        XCTAssertEqual(result.count, 1)
        XCTAssertNil(result.first?.newCompletion)
    }

    // MARK: tail(fromMessages:) reduction

    func testTailReductionRecognizesAssistantAndTool() {
        let assistant = NotificationReconciler.tail(fromMessages: [
            TranscriptMessage(role: "user", content: "hi"),
            TranscriptMessage(role: "assistant", content: "hello there"),
        ])
        XCTAssertTrue(assistant.endedOnAssistant)
        XCTAssertEqual(assistant.assistantText, "hello there")

        let tool = NotificationReconciler.tail(fromMessages: [
            TranscriptMessage(role: "assistant", content: "let me check"),
            TranscriptMessage(role: "tool", content: "{...}", toolName: "search"),
        ])
        XCTAssertFalse(tool.endedOnAssistant)
        XCTAssertEqual(tool.assistantText, "")
    }

    func testTailReductionTreatsInterruptSentinelAsNonAssistant() {
        let sentinel = "Operation interrupted: waiting for model response ("
        let tail = NotificationReconciler.tail(fromMessages: [
            TranscriptMessage(role: "assistant", content: sentinel),
        ])
        XCTAssertFalse(tail.endedOnAssistant, "interrupt sentinel must never be quoted as a response")
    }

    func testEmptyMessagesReduceToNonAssistantTail() {
        let tail = NotificationReconciler.tail(fromMessages: [])
        XCTAssertFalse(tail.endedOnAssistant)
    }
}
