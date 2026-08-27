import Foundation
import XCTest
@testable import Mercury
import MercuryRunActivityKit

@MainActor
final class RunActivityCoordinatorTests: XCTestCase {
    private static let date = Date(timeIntervalSince1970: 1_700_000_000)
    private static let serverID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
    private static let otherServerID = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!

    @MainActor
    private final class FakeRunActivityClient: RunActivityScheduling {
        struct UpdateCall: Equatable {
            let activityID: String
            let state: MercuryRunActivityContentState
        }

        struct EndCall: Equatable {
            let activityID: String
            let state: MercuryRunActivityContentState
            let dismissal: RunActivityDismissal
        }

        var enabled = true
        var starts: [RunActivitySeed] = []
        var updates: [UpdateCall] = []
        var ends: [EndCall] = []
        var persisted: [PersistedRunActivity] = []
        var blockNextStart = false

        private var nextID = 1
        private var blockedStart: CheckedContinuation<Void, Never>?

        func activitiesEnabled() -> Bool { enabled }

        func start(seed: RunActivitySeed) async throws -> String {
            starts.append(seed)
            if blockNextStart {
                blockNextStart = false
                await withCheckedContinuation { continuation in
                    blockedStart = continuation
                }
            }
            defer { nextID += 1 }
            return "activity-\(nextID)"
        }

        func update(activityID: String, state: MercuryRunActivityContentState) async {
            updates.append(UpdateCall(activityID: activityID, state: state))
        }

        func end(
            activityID: String,
            state: MercuryRunActivityContentState,
            dismissal: RunActivityDismissal
        ) async {
            ends.append(EndCall(activityID: activityID, state: state, dismissal: dismissal))
        }

        func persistedActivities() -> [PersistedRunActivity] { persisted }

        func resumeBlockedStart() {
            let continuation = blockedStart
            blockedStart = nil
            continuation?.resume()
        }
    }

    @MainActor
    private final class FlushCapture {
        var operation: (@MainActor () async -> Void)?
    }

    private func preferences(excerptsEnabled: Bool = false) -> MercuryNotificationPreferences {
        MercuryNotificationPreferences(
            liveActivitiesEnabled: true,
            liveActivityResponseExcerptsEnabled: excerptsEnabled
        )
    }

    private func makeCoordinator(
        client: FakeRunActivityClient,
        preferences: MercuryNotificationPreferences,
        now: @escaping () -> Date = { RunActivityCoordinatorTests.date },
        throttleInterval: TimeInterval = 1
    ) -> (RunActivityCoordinator, FlushCapture) {
        let flush = FlushCapture()
        let coordinator = RunActivityCoordinator(
            client: client,
            preferencesProvider: { preferences },
            now: now,
            throttleInterval: throttleInterval
        )
        coordinator.scheduleFlush = { operation in
            flush.operation = operation
        }
        return (coordinator, flush)
    }

    private func seed(
        sessionID: String,
        serverID: UUID = RunActivityCoordinatorTests.serverID,
        excerpt: String = ""
    ) -> RunActivitySeed {
        RunActivitySeed(
            serverID: serverID,
            profile: "default",
            durableSessionID: sessionID,
            sessionTitle: "Session \(sessionID)",
            startedAt: Self.date,
            baselineMessageCount: 3,
            initialState: state(status: .starting, excerpt: excerpt)
        )
    }

    private func state(
        status: MercuryRunActivityStatus,
        excerpt: String = "",
        updatedAt: Date = RunActivityCoordinatorTests.date,
        isStale: Bool = false,
        isFinal: Bool = false
    ) -> MercuryRunActivityContentState {
        MercuryRunActivityContentState(
            status: status,
            activityLine: RunActivityPolicy.displayName(for: status),
            responseExcerpt: excerpt,
            updatedAt: updatedAt,
            isStale: isStale,
            isFinal: isFinal
        )
    }

    private func orphan(
        activityID: String = "activity-1",
        serverID: UUID = RunActivityCoordinatorTests.serverID,
        sessionID: String = "session-1",
        lastKnownState: MercuryRunActivityContentState? = nil
    ) -> OrphanedRunActivity {
        OrphanedRunActivity(
            activityID: activityID,
            serverID: serverID,
            profile: "default",
            durableSessionID: sessionID,
            startedAt: Self.date,
            baselineMessageCount: 3,
            lastKnownState: lastKnownState ?? state(status: .responding)
        )
    }

    func testDisabledPreferenceBlocksStart() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(
            client: client,
            preferences: MercuryNotificationPreferences(liveActivitiesEnabled: false)
        )

        await coordinator.apply(.start(seed(sessionID: "session-1")))

        XCTAssertTrue(client.starts.isEmpty)
        XCTAssertNil(coordinator.currentDurableSessionID())
    }

    func testDisabledSystemAuthorizationBlocksStart() async {
        let client = FakeRunActivityClient()
        client.enabled = false
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())

        await coordinator.apply(.start(seed(sessionID: "session-1")))

        XCTAssertTrue(client.starts.isEmpty)
        XCTAssertNil(coordinator.currentDurableSessionID())
    }

    func testSameSessionStartIsIdempotent() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())
        let first = seed(sessionID: "session-1")

        await coordinator.apply(.start(first))
        await coordinator.apply(.start(first))

        XCTAssertEqual(client.starts.count, 1)
        XCTAssertEqual(coordinator.currentDurableSessionID(), "session-1")
        XCTAssertTrue(client.ends.isEmpty)
    }

    func testDifferentSessionEndsPriorAsUnavailableBeforeStartingNew() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())

        await coordinator.apply(.start(seed(sessionID: "session-1")))
        await coordinator.apply(.start(seed(sessionID: "session-2")))

        XCTAssertEqual(client.starts.count, 2)
        XCTAssertEqual(coordinator.currentDurableSessionID(), "session-2")
        XCTAssertEqual(client.ends.count, 1)
        XCTAssertEqual(client.ends[0].activityID, "activity-1")
        XCTAssertEqual(client.ends[0].state.status, .statusUnavailable)
        XCTAssertEqual(client.ends[0].state.activityLine, "Status unknown")
        XCTAssertTrue(client.ends[0].state.isFinal)
        XCTAssertEqual(client.ends[0].dismissal, .afterFailure)
    }

    func testStaleStartResultIsDiscardedByGeneration() async {
        let client = FakeRunActivityClient()
        client.blockNextStart = true
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())

        let oldTask = Task { @MainActor in
            await coordinator.apply(.start(seed(sessionID: "old-session")))
        }
        for _ in 0..<100 where client.starts.count < 1 {
            await Task.yield()
        }
        guard client.starts.count == 1 else {
            XCTFail("the first start did not reach the fake client")
            client.resumeBlockedStart()
            await oldTask.value
            return
        }

        let newTask = Task { @MainActor in
            await coordinator.apply(.start(seed(sessionID: "new-session")))
        }
        for _ in 0..<100 where client.starts.count < 2 {
            await Task.yield()
        }
        await newTask.value
        client.resumeBlockedStart()
        await oldTask.value

        XCTAssertEqual(coordinator.currentDurableSessionID(), "new-session")
        XCTAssertEqual(client.starts.count, 2)
        XCTAssertEqual(client.ends.count, 1)
        XCTAssertEqual(client.ends[0].activityID, "activity-2")
        XCTAssertEqual(client.ends[0].state.status, .statusUnavailable)
    }

    func testRapidNonCriticalUpdatesCoalesceToLatestStateAfterFlush() async {
        let client = FakeRunActivityClient()
        let (coordinator, flush) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1")))

        await coordinator.apply(.update(state(status: .thinking)))
        await coordinator.apply(.update(state(status: .responding, excerpt: "latest")))
        XCTAssertTrue(client.updates.isEmpty)
        XCTAssertNotNil(flush.operation)

        await flush.operation?()

        XCTAssertEqual(client.updates.count, 1)
        XCTAssertEqual(client.updates[0].state.status, .responding)
        XCTAssertEqual(client.updates[0].state.responseExcerpt, "")
    }

    func testTerminalUpdateSendsImmediatelyAndClearsPendingFlush() async {
        let client = FakeRunActivityClient()
        let (coordinator, flush) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1")))
        await coordinator.apply(.update(state(status: .thinking)))
        let terminal = state(status: .complete, excerpt: "done", isFinal: true)

        await coordinator.apply(.update(terminal))
        XCTAssertEqual(client.updates.count, 1)
        XCTAssertEqual(client.updates[0].state.status, .complete)

        await flush.operation?()
        XCTAssertEqual(client.updates.count, 1)
    }

    func testWaitingForInputTransitionsSendImmediately() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1")))

        await coordinator.apply(.update(state(status: .waitingForApproval)))
        await coordinator.apply(.update(state(status: .responding)))

        XCTAssertEqual(client.updates.count, 2)
        XCTAssertEqual(client.updates[0].state.status, .waitingForApproval)
        XCTAssertEqual(client.updates[1].state.status, .responding)
    }

    func testEndFinalizesExactlyOnce() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1")))
        let finalState = state(status: .complete, isFinal: true)

        await coordinator.apply(.end(finalState))
        await coordinator.apply(.end(finalState))

        XCTAssertEqual(client.ends.count, 1)
        XCTAssertEqual(client.ends[0].dismissal, .afterCompletion)
        XCTAssertNil(coordinator.currentDurableSessionID())
    }

    func testExcerptIsScrubbedWhenPreferenceIsOff() async {
        let client = FakeRunActivityClient()
        let (coordinator, flush) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1", excerpt: "secret start")))
        XCTAssertEqual(client.starts[0].initialState.responseExcerpt, "")

        await coordinator.apply(.update(state(status: .responding, excerpt: "secret response")))
        await flush.operation?()

        XCTAssertEqual(client.updates.count, 1)
        XCTAssertEqual(client.updates[0].state.responseExcerpt, "")
    }

    func testExcerptIsPreservedWhenPreferenceIsOn() async {
        let client = FakeRunActivityClient()
        let (coordinator, flush) = makeCoordinator(
            client: client,
            preferences: preferences(excerptsEnabled: true)
        )
        await coordinator.apply(.start(seed(sessionID: "session-1", excerpt: "visible start")))
        await coordinator.apply(.update(state(status: .responding, excerpt: "visible response")))
        await flush.operation?()

        XCTAssertEqual(client.starts[0].initialState.responseExcerpt, "visible start")
        XCTAssertEqual(client.updates[0].state.responseExcerpt, "visible response")
    }

    func testEndAllForSignOutEndsCurrentActivity() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1")))

        await coordinator.endAllForSignOut()

        XCTAssertEqual(client.ends.count, 1)
        XCTAssertEqual(client.ends[0].state.status, .statusUnavailable)
        XCTAssertNil(coordinator.currentDurableSessionID())
    }

    func testEndActivityOnlyMatchesItsServer() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1", serverID: Self.serverID)))

        await coordinator.endActivity(forServerID: Self.otherServerID)
        XCTAssertTrue(client.ends.isEmpty)
        XCTAssertEqual(coordinator.currentDurableSessionID(), "session-1")

        await coordinator.endActivity(forServerID: Self.serverID)
        XCTAssertEqual(client.ends.count, 1)
        XCTAssertNil(coordinator.currentDurableSessionID())
    }

    func testMarkStaleFlipsOnceAndNeverEndsActivity() async {
        var clock = Self.date
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(
            client: client,
            preferences: preferences(),
            now: { clock }
        )
        await coordinator.apply(.start(seed(sessionID: "session-1")))
        clock = clock.addingTimeInterval(10)

        await coordinator.markStaleForBackgroundExpiration()
        await coordinator.markStaleForBackgroundExpiration()

        XCTAssertEqual(client.updates.count, 1)
        XCTAssertTrue(client.updates[0].state.isStale)
        XCTAssertEqual(client.updates[0].state.status, .starting)
        XCTAssertEqual(client.updates[0].state.updatedAt, clock)
        XCTAssertTrue(client.ends.isEmpty)
        XCTAssertEqual(coordinator.currentDurableSessionID(), "session-1")
    }

    func testMarkStaleNoOpsWhenAlreadyStale() async {
        let client = FakeRunActivityClient()
        let (coordinator, flush) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(
            .start(seed(sessionID: "session-1"))
        )
        await coordinator.apply(
            .update(state(status: .thinking, isStale: true))
        )
        // The non-critical stale update is coalesced; flush it so it becomes
        // the last SENT state before asserting the no-op.
        await flush.operation?()
        client.updates.removeAll()

        await coordinator.markStaleForBackgroundExpiration()

        XCTAssertTrue(client.updates.isEmpty)
        XCTAssertTrue(client.ends.isEmpty)
    }

    func testMarkStaleNoOpsWhenLastStateIsFinal() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1")))
        await coordinator.apply(.update(state(status: .complete, isFinal: true)))

        await coordinator.markStaleForBackgroundExpiration()

        XCTAssertEqual(client.updates.count, 1)
        XCTAssertEqual(client.updates[0].state.status, .complete)
        XCTAssertTrue(client.ends.isEmpty)
    }

    func testPersistedOrphansMapsClientRecords() async {
        let client = FakeRunActivityClient()
        let persistedState = state(status: .responding, excerpt: "safe")
        client.persisted = [
            PersistedRunActivity(
                activityID: "activity-9",
                serverID: Self.serverID,
                profile: "default",
                durableSessionID: "session-9",
                startedAt: Self.date,
                baselineMessageCount: 8,
                lastKnownState: persistedState
            )
        ]
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())

        let orphans = coordinator.persistedOrphans()

        XCTAssertEqual(orphans.count, 1)
        XCTAssertEqual(orphans[0].activityID, "activity-9")
        XCTAssertEqual(orphans[0].serverID, Self.serverID)
        XCTAssertEqual(orphans[0].durableSessionID, "session-9")
        XCTAssertEqual(orphans[0].baselineMessageCount, 8)
        XCTAssertEqual(orphans[0].lastKnownState, persistedState)
    }

    func testReconcileMarkStaleRebuildsState() async {
        var clock = Self.date
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(
            client: client,
            preferences: preferences(),
            now: { clock }
        )
        let sourceState = state(status: .responding, excerpt: "safe")
        let source = orphan(lastKnownState: sourceState)
        clock = clock.addingTimeInterval(20)

        await coordinator.applyReconcileActions(
            [.markStale(activityID: source.activityID)],
            orphans: [source]
        )

        XCTAssertEqual(client.updates.count, 1)
        XCTAssertEqual(client.updates[0].activityID, source.activityID)
        XCTAssertEqual(client.updates[0].state.status, sourceState.status)
        XCTAssertEqual(client.updates[0].state.activityLine, sourceState.activityLine)
        XCTAssertEqual(client.updates[0].state.responseExcerpt, sourceState.responseExcerpt)
        XCTAssertTrue(client.updates[0].state.isStale)
        XCTAssertEqual(client.updates[0].state.updatedAt, clock)
        XCTAssertTrue(client.ends.isEmpty)
    }

    func testReconcileEndUsesEndStateAndClearsMatchingCurrent() async {
        var clock = Self.date
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(
            client: client,
            preferences: preferences(),
            now: { clock }
        )
        await coordinator.apply(.start(seed(sessionID: "session-1")))
        let source = orphan(
            activityID: "activity-1",
            sessionID: "session-1",
            lastKnownState: state(status: .responding, excerpt: "final answer")
        )
        clock = clock.addingTimeInterval(5)

        await coordinator.applyReconcileActions(
            [.end(activityID: "activity-1", status: .complete)],
            orphans: [source]
        )

        XCTAssertEqual(client.ends.count, 1)
        XCTAssertEqual(client.ends[0].state.status, .complete)
        XCTAssertEqual(client.ends[0].state.activityLine, "Finished")
        XCTAssertEqual(client.ends[0].state.responseExcerpt, "final answer")
        XCTAssertTrue(client.ends[0].state.isFinal)
        XCTAssertEqual(client.ends[0].state.updatedAt, clock)
        XCTAssertEqual(client.ends[0].dismissal, .afterCompletion)
        XCTAssertNil(coordinator.currentDurableSessionID())
    }

    func testReconcileIgnoreDoesNothing() async {
        let client = FakeRunActivityClient()
        let (coordinator, _) = makeCoordinator(client: client, preferences: preferences())
        await coordinator.apply(.start(seed(sessionID: "session-1")))
        let source = orphan(activityID: "activity-1", sessionID: "session-1")

        await coordinator.applyReconcileActions([.ignore], orphans: [source])

        XCTAssertTrue(client.updates.isEmpty)
        XCTAssertTrue(client.ends.isEmpty)
        XCTAssertEqual(coordinator.currentDurableSessionID(), "session-1")
    }
}
