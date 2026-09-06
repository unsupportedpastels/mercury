import XCTest
@testable import Mercury

final class BackgroundTasksTests: XCTestCase {
    func testGlobalRegistryAbsenceNeverMeansSuccessOrOwnership() {
        var tasks = BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        tasks.markUnavailable()
        let stale = tasks
        tasks.reconcile(["foreign": "running"], runtime: "runtime")
        XCTAssertEqual(tasks, stale)
        tasks.reconcile([:], runtime: "runtime")
        XCTAssertEqual(tasks, stale)
    }
    func testFailureAndStopAreDistinctExplicitTerminals() {
        for (wire, expected) in [("failed", BackgroundTaskStatus.failed), ("timeout", .failed), ("interrupted", .stopped)] {
            var tasks = BackgroundTasks()
            tasks.apply(event("subagent.complete", status: wire), runtime: "runtime", now: 1000)
            XCTAssertEqual(tasks.rows.first?.status, expected)
        }
    }
    private func event(_ type: String, status: String? = nil, session: String = "runtime") -> ChatEvent {
        var payload: [String: Any] = ["subagent_id": "child", "goal": "Review tests"]
        if let status { payload["status"] = status }
        return .backgroundTask(sessionID: session, evidence: BackgroundTaskEvidence.decode(type: type, payload: payload)!)
    }
    func testParentCompletionAndNewPromptPreserveChild() {
        var tasks = BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        let started = tasks
        tasks.apply(.messageStart(sessionID: "runtime", text: nil), runtime: "runtime", now: 2000)
        tasks.apply(.messageComplete(sessionID: "runtime", text: "Done", status: "ok", error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil), runtime: "runtime", now: 3000)
        XCTAssertEqual(started, tasks)
        XCTAssertEqual(tasks.activeCount(now: 3000), 1)
    }
    func testStaleIdentityAndExplicitTerminal() {
        var tasks = BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        tasks.apply(event("subagent.complete", status: "completed", session: "old"), runtime: "runtime", now: 2000)
        XCTAssertEqual(tasks.activeCount(now: 2000), 1)
        tasks.apply(event("subagent.complete", status: "completed"), runtime: "runtime", now: 3000)
        XCTAssertEqual(tasks.rows.first?.status, .finished)
        tasks.apply(event("subagent.tool"), runtime: "runtime", now: 4000)
        XCTAssertEqual(tasks.rows.first?.status, .finished)
    }
    func testUnavailableNeverClaimsRunningAndPreservesObservationTime() {
        var tasks = BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        tasks.markUnavailable()
        XCTAssertEqual(tasks.activeCount(now: 2000), 0)
        XCTAssertEqual(tasks.rows.first?.observedAtMillis, 1000)
        XCTAssertEqual(tasks.rows.count, 1)
    }
}
