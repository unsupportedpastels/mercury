import XCTest
@testable import Mercury

final class ActivityPresentationPolicyTests: XCTestCase {
    func testProcessOnlyRowsUseNeutralPresentationAndLastReportedCounts() {
        let decision = SharedActivityPresentationPolicy.decide(
            assistantActivityPresent: false,
            turnActive: false,
            toolCount: 0,
            runningToolCount: 0,
            completedTodoCount: 0,
            todoCount: 0,
            activeTodoCount: 0,
            loopCount: 0,
            activeLoopCount: 0,
            processStatuses: ["running", "exited", "exited", "future-state"]
        )

        XCTAssertTrue(decision.activityPresent)
        XCTAssertFalse(decision.assistantActive)
        XCTAssertTrue(decision.processOnly)
        XCTAssertEqual(
            decision.summary,
            "Processes · last reported: 1 running · 2 exited · 1 unknown"
        )
    }

    func testProcessRowsDoNotChangeGenuineAssistantWorkIndicator() {
        let decision = SharedActivityPresentationPolicy.decide(
            assistantActivityPresent: true,
            turnActive: false,
            toolCount: 1,
            runningToolCount: 1,
            completedTodoCount: 0,
            todoCount: 0,
            activeTodoCount: 0,
            loopCount: 0,
            activeLoopCount: 0,
            processStatuses: ["running", "exited"]
        )

        XCTAssertTrue(decision.assistantActive)
        XCTAssertFalse(decision.processOnly)
        XCTAssertEqual(
            decision.summary,
            "Activity · 1 tool · 0/0 tasks · Processes · last reported: 1 running · 1 exited"
        )
    }

    func testProcessOnlyActivityStateIsNotRunning() {
        let state = ActivityStackState(processes: [
            ActivityProcess(id: "process", command: "worker", status: "running")
        ])

        XCTAssertFalse(state.isRunning)
    }
}

final class BackgroundTaskPresentationPolicyTests: XCTestCase {
    func testHistoricalIdentityLessEvidenceIsUnavailableAndDismissible() {
        var tasks = BackgroundTasks()
        let event = ChatEvent.backgroundTask(
            sessionID: "runtime",
            evidence: BackgroundTaskEvidence(
                kind: .start,
                childID: nil,
                goal: "Historical child",
                action: nil,
                terminalStatus: .unknown,
                eventID: "snapshot:1",
                historical: true
            )
        )
        tasks.apply(event, runtime: "runtime", now: 0)

        let row = tasks.rows.first!
        XCTAssertFalse(row.terminal)
        XCTAssertEqual(row.label(now: 1), "Historical · status unavailable")
        XCTAssertTrue(row.isDismissible(now: 1))
        XCTAssertEqual(tasks.presentation(rows: tasks.rows, now: 1).headline,
                       "Background tasks · status unavailable")
        XCTAssertEqual(tasks.secondaryLabel(rows: tasks.rows, now: 1),
                       "Some background task status is unavailable")
    }

    func testNewEvidenceDoesNotReuseAnOlderDismissalKey() {
        var tasks = BackgroundTasks()
        tasks.apply(.backgroundTask(
            sessionID: "runtime",
            evidence: BackgroundTaskEvidence(
                kind: .start,
                childID: nil,
                goal: "Historical child",
                action: nil,
                terminalStatus: .unknown,
                historical: true
            )
        ), runtime: "runtime", now: 0)
        let firstKey = tasks.rows.first!.dismissalKey

        tasks.apply(.backgroundTask(
            sessionID: "runtime",
            evidence: BackgroundTaskEvidence(
                kind: .tool,
                childID: nil,
                goal: "Historical child",
                action: "New observed evidence",
                terminalStatus: .unknown
            )
        ), runtime: "runtime", now: 1_000)

        XCTAssertNotEqual(firstKey, tasks.rows.first!.dismissalKey)
        XCTAssertEqual(tasks.rows.first?.label(now: 1_000), "Status unavailable")
        XCTAssertFalse(tasks.rows.first!.terminal)
    }
}
