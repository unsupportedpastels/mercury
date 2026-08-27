import Foundation
import XCTest
@testable import Mercury
import MercuryRunActivityKit

final class RunActivityReconcilerTests: XCTestCase {
    private static let now = Date(timeIntervalSince1970: 1_000_000)
    private static let serverID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private static let otherServerID = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    private func state(
        status: MercuryRunActivityStatus = .starting,
        excerpt: String = "",
        updatedAt: Date = RunActivityReconcilerTests.now,
        isStale: Bool = false,
        isFinal: Bool = false
    ) -> MercuryRunActivityContentState {
        MercuryRunActivityContentState(
            status: status,
            activityLine: "Working",
            responseExcerpt: excerpt,
            updatedAt: updatedAt,
            isStale: isStale,
            isFinal: isFinal
        )
    }

    private func orphan(
        activityID: String = "activity-1",
        serverID: UUID? = nil,
        profile: String = "default",
        sessionID: String = "session-1",
        age: TimeInterval = 100,
        baselineMessageCount: Int = 1,
        lastKnownState: MercuryRunActivityContentState? = nil
    ) -> OrphanedRunActivity {
        OrphanedRunActivity(
            activityID: activityID,
            serverID: serverID ?? Self.serverID,
            profile: profile,
            durableSessionID: sessionID,
            startedAt: Self.now.addingTimeInterval(-age),
            baselineMessageCount: baselineMessageCount,
            lastKnownState: lastKnownState ?? state()
        )
    }

    private func row(
        id: String = "session-1",
        messageCount: Int = 1,
        preview: String = ""
    ) -> SessionRow {
        SessionRow(id: id, preview: preview, messageCount: messageCount)
    }

    private func reconcile(
        _ orphans: [OrphanedRunActivity],
        activeServerID: UUID? = nil,
        activeProfile: String = "default",
        knownServerIDs: Set<UUID>? = nil,
        sessions: [SessionRow]? = [],
        liveOwnedSessionIDs: Set<String> = [],
        now: Date? = nil,
        hardStaleAge: TimeInterval = 1800
    ) -> [RunActivityReconcileAction] {
        RunActivityReconciler.reconcile(
            orphans: orphans,
            activeServerID: activeServerID ?? Self.serverID,
            activeProfile: activeProfile,
            knownServerIDs: knownServerIDs ?? Set([Self.serverID]),
            sessions: sessions,
            liveOwnedSessionIDs: liveOwnedSessionIDs,
            now: now ?? Self.now,
            hardStaleAge: hardStaleAge
        )
    }

    func testAlreadyFinalActivityIsIgnoredBeforeOtherEvidence() {
        let finalOrphan = orphan(
            age: 10_000,
            lastKnownState: state(status: .complete, excerpt: "done", isFinal: true)
        )

        let actions = reconcile(
            [finalOrphan],
            knownServerIDs: [],
            sessions: [row(messageCount: 99)],
            liveOwnedSessionIDs: ["session-1"]
        )

        XCTAssertEqual(actions, [.ignore])
    }

    func testLiveOwnedActivityIsIgnoredBeforeRecoveryEvidence() {
        let actions = reconcile(
            [orphan(age: 10_000)],
            knownServerIDs: [],
            sessions: nil,
            liveOwnedSessionIDs: ["session-1"]
        )

        XCTAssertEqual(actions, [.ignore])
    }

    func testRemovedServerEndsUnavailable() {
        let actions = reconcile([orphan()], knownServerIDs: [])

        XCTAssertEqual(actions, [.end(activityID: "activity-1", status: .statusUnavailable)])
    }

    func testInactiveServerRecentActivityIsLeftAlone() {
        let actions = reconcile(
            [orphan(age: 1799)],
            activeServerID: Self.otherServerID
        )

        XCTAssertEqual(actions, [.ignore])
    }

    func testInactiveServerOldActivityEndsUnavailable() {
        let actions = reconcile(
            [orphan(age: 1801)],
            activeServerID: Self.otherServerID
        )

        XCTAssertEqual(actions, [.end(activityID: "activity-1", status: .statusUnavailable)])
    }

    func testInactiveProfileRecentActivityIsLeftAlone() {
        let actions = reconcile([orphan(age: 1799)], activeProfile: "other")

        XCTAssertEqual(actions, [.ignore])
    }

    func testFailedSessionFetchLeavesRecentActivityAlone() {
        let actions = reconcile([orphan(age: 1799)], sessions: nil)

        XCTAssertEqual(actions, [.ignore])
    }

    func testFailedSessionFetchEndsOldActivityUnavailable() {
        let actions = reconcile([orphan(age: 1801)], sessions: nil)

        XCTAssertEqual(actions, [.end(activityID: "activity-1", status: .statusUnavailable)])
    }

    func testMissingSessionEndsUnavailable() {
        let actions = reconcile([orphan()], sessions: [])

        XCTAssertEqual(actions, [.end(activityID: "activity-1", status: .statusUnavailable)])
    }

    func testAdvancedMessageCountEndsComplete() {
        let actions = reconcile(
            [orphan(baselineMessageCount: 2)],
            sessions: [row(messageCount: 3, preview: "The response")]
        )

        XCTAssertEqual(actions, [.end(activityID: "activity-1", status: .complete)])
    }

    func testUnchangedRecentActivityIsMarkedStaleExactlyOnce() {
        var unproven = orphan(age: 1799)
        let first = reconcile([unproven], sessions: [row(messageCount: 1)])
        XCTAssertEqual(first, [.markStale(activityID: "activity-1")])

        unproven.lastKnownState = state(isStale: true)
        let second = reconcile([unproven], sessions: [row(messageCount: 1)])
        XCTAssertEqual(second, [.ignore])
    }

    func testUnchangedOldActivityEndsUnavailable() {
        let actions = reconcile(
            [orphan(age: 1801)],
            sessions: [row(messageCount: 1)]
        )

        XCTAssertEqual(actions, [.end(activityID: "activity-1", status: .statusUnavailable)])
    }

    func testEndStateForCompletePreservesExcerpt() {
        let timestamp = Self.now.addingTimeInterval(12)
        let source = orphan(lastKnownState: state(status: .responding, excerpt: "final answer"))

        let endState = RunActivityReconciler.endState(for: source, status: .complete, now: timestamp)

        XCTAssertEqual(endState.status, .complete)
        XCTAssertEqual(endState.activityLine, "Finished")
        XCTAssertEqual(endState.responseExcerpt, "final answer")
        XCTAssertEqual(endState.updatedAt, timestamp)
        XCTAssertTrue(endState.isFinal)
        XCTAssertFalse(endState.isStale)
    }

    func testEndStateForUnavailableDoesNotPreserveExcerpt() {
        let timestamp = Self.now.addingTimeInterval(12)
        let source = orphan(lastKnownState: state(status: .responding, excerpt: "not proven"))

        let endState = RunActivityReconciler.endState(
            for: source,
            status: .statusUnavailable,
            now: timestamp
        )

        XCTAssertEqual(endState.status, .statusUnavailable)
        XCTAssertEqual(endState.activityLine, "Status unknown")
        XCTAssertEqual(endState.responseExcerpt, "")
        XCTAssertEqual(endState.updatedAt, timestamp)
        XCTAssertTrue(endState.isFinal)
        XCTAssertFalse(endState.isStale)
    }

    func testEndStateForCompleteLeavesEmptyExcerptEmpty() {
        let source = orphan(lastKnownState: state(status: .responding, excerpt: ""))
        let endState = RunActivityReconciler.endState(for: source, status: .complete, now: Self.now)

        XCTAssertEqual(endState.responseExcerpt, "")
    }

    func testMultipleOrphansAreProcessedIndependentlyInInputOrder() {
        let final = orphan(
            activityID: "final",
            lastKnownState: state(status: .complete, isFinal: true)
        )
        let advanced = orphan(
            activityID: "advanced",
            sessionID: "advanced-session",
            baselineMessageCount: 2
        )
        let unchangedRecent = orphan(activityID: "recent", sessionID: "recent-session", age: 100)
        let missing = orphan(activityID: "missing", sessionID: "missing-session")

        let actions = reconcile(
            [final, advanced, unchangedRecent, missing],
            sessions: [
                row(id: "advanced-session", messageCount: 3),
                row(id: "recent-session", messageCount: 1)
            ]
        )

        XCTAssertEqual(
            actions,
            [
                .ignore,
                .end(activityID: "advanced", status: .complete),
                .markStale(activityID: "recent"),
                .end(activityID: "missing", status: .statusUnavailable)
            ]
        )
    }

    func testReconcileNeverEndsFailedOrCancelledAcrossInputGrid() {
        let statuses: [MercuryRunActivityStatus] = [
            .starting,
            .thinking,
            .responding,
            .usingTool,
            .waitingForApproval,
            .waitingForClarification,
            .waitingForSecureInput,
            .reconnecting,
            .complete,
            .failed,
            .cancelled,
            .statusUnavailable
        ]
        let activeServerIDs = [Self.serverID, Self.otherServerID]
        let knownServerIDOptions: [Set<UUID>] = [
            Set([Self.serverID]),
            Set<UUID>()
        ]
        let liveOwnedSessionIDOptions: [Set<String>] = [
            Set<String>(),
            Set(["session-1"])
        ]
        let sessionOptions: [[SessionRow]?] = [
            nil,
            [],
            [row(messageCount: 1)],
            [row(messageCount: 2)]
        ]

        for status in statuses {
            for isFinal in [false, true] {
                for isStale in [false, true] {
                    for age in [0.0, 1801.0] {
                        for activeServerID in activeServerIDs {
                            for knownServerIDs in knownServerIDOptions {
                                for liveOwnedSessionIDs in liveOwnedSessionIDOptions {
                                    for sessions in sessionOptions {
                                        let candidate = orphan(
                                            age: age,
                                            lastKnownState: state(
                                                status: status,
                                                isStale: isStale,
                                                isFinal: isFinal
                                            )
                                        )
                                        let actions = reconcile(
                                            [candidate],
                                            activeServerID: activeServerID,
                                            knownServerIDs: knownServerIDs,
                                            sessions: sessions,
                                            liveOwnedSessionIDs: liveOwnedSessionIDs
                                        )

                                        for action in actions {
                                            guard case let .end(_, endStatus) = action else { continue }
                                            XCTAssertNotEqual(endStatus, .failed)
                                            XCTAssertNotEqual(endStatus, .cancelled)
                                            XCTAssertTrue(
                                                endStatus == .complete || endStatus == .statusUnavailable,
                                                "Unexpected reconciliation end status: \(endStatus)"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
