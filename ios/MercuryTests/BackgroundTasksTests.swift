import XCTest
import MercuryCore
@testable import Mercury

final class BackgroundTasksTests: XCTestCase {
    func testGlobalRegistryAbsenceNeverMeansSuccessOrOwnership() {
        var tasks = Mercury.BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        tasks.markUnavailable()
        let stale = tasks
        tasks.reconcile(["foreign": "running"], runtime: "runtime")
        XCTAssertEqual(tasks, stale)
        tasks.reconcile([:], runtime: "runtime")
        XCTAssertEqual(tasks, stale)
    }
    func testFailureAndStopAreDistinctExplicitTerminals() {
        for (wire, expected) in [("failed", Mercury.BackgroundTaskStatus.failed), ("timeout", .failed), ("interrupted", .stopped)] {
            var tasks = Mercury.BackgroundTasks()
            tasks.apply(event("subagent.complete", status: wire), runtime: "runtime", now: 1000)
            XCTAssertEqual(tasks.rows.first?.status, expected)
        }
    }
    private func event(_ type: String, status: String? = nil, session: String = "runtime") -> Mercury.ChatEvent {
        var payload: [String: Any] = ["subagent_id": "child", "goal": "Review tests"]
        if let status { payload["status"] = status }
        return .backgroundTask(sessionID: session, evidence: BackgroundTaskEvidence.decode(type: type, payload: payload)!)
    }
    func testParentCompletionAndNewPromptPreserveChild() {
        var tasks = Mercury.BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        let started = tasks
        tasks.apply(.messageStart(sessionID: "runtime", text: nil), runtime: "runtime", now: 2000)
        tasks.apply(.messageComplete(sessionID: "runtime", text: "Done", status: "ok", error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil), runtime: "runtime", now: 3000)
        XCTAssertEqual(started, tasks)
        XCTAssertEqual(tasks.activeCount(now: 3000), 1)
    }
    func testStaleIdentityAndExplicitTerminal() {
        var tasks = Mercury.BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        tasks.apply(event("subagent.complete", status: "completed", session: "old"), runtime: "runtime", now: 2000)
        XCTAssertEqual(tasks.activeCount(now: 2000), 1)
        tasks.apply(event("subagent.complete", status: "completed"), runtime: "runtime", now: 3000)
        XCTAssertEqual(tasks.rows.first?.status, .finished)
        tasks.apply(event("subagent.tool"), runtime: "runtime", now: 4000)
        XCTAssertEqual(tasks.rows.first?.status, .finished)
    }
    func testRecoveredSnapshotCountsActiveOnlyWhileHostRegistryConfirmsRunning() throws {
        // Cold reopen mid-delegation: the relay snapshot proves the child existed; the
        // gateway registry answering "running" is the only fresh liveness evidence.
        let snapshot = MercuryCore.RelayLeaseSnapshot(
            leaseId: "lease", lastSeq: 8, gap: false, reset: false,
            bindings: [MercuryCore.RelayLeaseBinding(runtimeId: "runtime", durableId: "durable", profile: "default", live: true)],
            tasks: ["""
                {"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime","type":"subagent.start",
                "payload":{"subagent_id":"child","goal":"Write a poem","status":"running"},"recovery_revision":1,
                "recovery_binding":{"runtime_session_id":"runtime","durable_session_id":"durable","profile":"default","live":true}}}
                """],
            truncated: false, routingToken: nil)
        var tasks = Mercury.BackgroundTasks()
        tasks.recover(snapshot: snapshot, durableID: "durable", profile: "default", runtime: "runtime")
        XCTAssertEqual(tasks.rows.first?.status, .active)
        XCTAssertEqual(tasks.activeCount(now: 50_000), 0, "recovered evidence alone never claims a running child")
        XCTAssertTrue(tasks.hasUnresolvedIdentifiedChildren)
        tasks.reconcile(["child": "running"], runtime: "runtime", now: 50_000)
        XCTAssertEqual(tasks.activeCount(now: 50_001), 1)
        XCTAssertEqual(tasks.rows.first?.observedAtMillis, 0, "registry polling is not worker activity")
        XCTAssertEqual(tasks.rows.first?.registryConfirmedAtMillis, 50_000)
        XCTAssertEqual(tasks.rows.first?.label(now: 50_001), "Active · host reports running")
        XCTAssertEqual(tasks.activeCount(now: 170_000), 0, "one confirmation is one activity window")
        tasks.apply(event("subagent.tool"), runtime: "runtime", now: 80_000)
        XCTAssertEqual(tasks.activeCount(now: 190_000), 1, "a live child event refreshes activity")
        tasks.reconcile(["child": "completed"], runtime: "runtime", now: 300_000)
        XCTAssertEqual(tasks.activeCount(now: 300_001), 0)
        XCTAssertFalse(try XCTUnwrap(tasks.rows.first).terminal, "a non-running registry status is not terminal evidence")
        tasks.markUnavailable()
        tasks.reconcile(["child": "running"], runtime: "runtime", now: 400_000)
        XCTAssertEqual(tasks.activeCount(now: 400_001), 1, "a live registry answer restores availability")
    }
    func testUnavailableNeverClaimsRunningAndPreservesObservationTime() {
        var tasks = Mercury.BackgroundTasks()
        tasks.apply(event("subagent.start"), runtime: "runtime", now: 1000)
        tasks.markUnavailable()
        XCTAssertEqual(tasks.activeCount(now: 2000), 0)
        XCTAssertEqual(tasks.rows.first?.observedAtMillis, 1000)
        XCTAssertEqual(tasks.rows.count, 1)
    }
}
