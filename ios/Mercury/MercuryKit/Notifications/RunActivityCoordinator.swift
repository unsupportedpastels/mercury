import Foundation
import MercuryRunActivityKit

/// Owns the one ActivityKit run that Mercury is currently driving.
///
/// All lifecycle decisions enter through `apply(_:)`. ActivityKit is kept
/// behind `RunActivityScheduling`, which also makes the generation checks and
/// throttling deterministic in unit tests.
@MainActor
final class RunActivityCoordinator {
    private struct CurrentActivityRecord {
        let serverID: UUID
        let durableSessionID: String
        let activityID: String
        let generation: Int
    }

    private let client: RunActivityScheduling
    private let preferencesProvider: () -> MercuryNotificationPreferences
    private let now: () -> Date
    private let throttleInterval: TimeInterval

    private var current: CurrentActivityRecord?
    private var generationCounter = 0
    private var lastSentStateByActivityID: [String: MercuryRunActivityContentState] = [:]
    private var pendingStateByActivityID: [String: MercuryRunActivityContentState] = [:]
    private var finalizedActivityIDs = Set<String>()

    private var flushScheduled = false
    private var flushToken = 0

    /// The scheduler receives the already-bound flush operation. The default
    /// waits for the throttle interval in a Task; tests replace this property
    /// with a closure that captures the operation and invokes it directly.
    var scheduleFlush: (@escaping @MainActor () async -> Void) -> Void

    init(
        client: RunActivityScheduling,
        preferencesProvider: @escaping () -> MercuryNotificationPreferences,
        now: @escaping () -> Date = Date.init,
        throttleInterval: TimeInterval = 1.0
    ) {
        self.client = client
        self.preferencesProvider = preferencesProvider
        self.now = now
        self.throttleInterval = max(0, throttleInterval)
        let interval = self.throttleInterval
        self.scheduleFlush = { operation in
            Task { @MainActor in
                if interval > 0 {
                    let nanoseconds = UInt64(interval * 1_000_000_000)
                    try? await Task.sleep(nanoseconds: nanoseconds)
                }
                await operation()
            }
        }
    }

    /// Applies one reducer command. This is the only command-driven entry
    /// point for the in-process activity lifecycle.
    func apply(_ command: RunActivityCommand) async {
        switch command {
        case .none:
            return

        case .start(let seed):
            await applyStart(seed)

        case .update(let state):
            await applyUpdate(state)

        case .end(let state):
            await applyEnd(state)
        }
    }

    /// The durable session currently owned by this coordinator, if any.
    func currentDurableSessionID() -> String? {
        current?.durableSessionID
    }

    /// Adapts ActivityKit-owned records for the pure reconciler.
    func persistedOrphans() -> [OrphanedRunActivity] {
        client.persistedActivities().map { activity in
            OrphanedRunActivity(
                activityID: activity.activityID,
                serverID: activity.serverID,
                profile: activity.profile,
                durableSessionID: activity.durableSessionID,
                startedAt: activity.startedAt,
                baselineMessageCount: activity.baselineMessageCount,
                lastKnownState: activity.lastKnownState
            )
        }
    }

    /// Applies process-death reconciliation decisions without taking over a
    /// session that this coordinator currently owns.
    func applyReconcileActions(
        _ actions: [RunActivityReconcileAction],
        orphans: [OrphanedRunActivity]
    ) async {
        for action in actions {
            switch action {
            case .ignore:
                continue

            case .markStale(let activityID):
                guard let orphan = orphans.first(where: { $0.activityID == activityID }) else {
                    continue
                }
                let staleState = MercuryRunActivityContentState(
                    status: orphan.lastKnownState.status,
                    activityLine: orphan.lastKnownState.activityLine,
                    responseExcerpt: orphan.lastKnownState.responseExcerpt,
                    updatedAt: now(),
                    isStale: true,
                    isFinal: orphan.lastKnownState.isFinal
                )
                if let record = current,
                   record.activityID == activityID {
                    lastSentStateByActivityID[activityID] = staleState
                    clearPending(for: record)
                }
                await client.update(activityID: activityID, state: staleState)

            case .end(let activityID, let status):
                guard let orphan = orphans.first(where: { $0.activityID == activityID }) else {
                    continue
                }
                let endState = RunActivityReconciler.endState(
                    for: orphan,
                    status: status,
                    now: now()
                )
                let dismissal = RunActivityPolicy.dismissal(for: status) ?? .afterFailure
                if let record = current,
                   record.activityID == activityID {
                    detachCurrent(record)
                    finalizedActivityIDs.insert(activityID)
                }
                await client.end(
                    activityID: activityID,
                    state: endState,
                    dismissal: dismissal
                )
            }
        }
    }

    /// Ends the current activity when it belongs to the removed server.
    func endActivity(forServerID serverID: UUID) async {
        guard let record = current, record.serverID == serverID else { return }
        await endCurrentAsUnavailable(record)
    }

    /// Sign-out is unconditional: no activity may remain owned by this
    /// coordinator after the account has gone away.
    func endAllForSignOut() async {
        guard let record = current else { return }
        await endCurrentAsUnavailable(record)
    }

    /// Marks the last delivered non-final state stale exactly once. This is an
    /// ActivityKit update, not an end; background/foreground transitions do not
    /// otherwise alter activity ownership.
    func markStaleForBackgroundExpiration() async {
        guard let record = current,
              let lastState = lastSentStateByActivityID[record.activityID],
              !lastState.isFinal,
              !lastState.status.isFinal,
              !lastState.isStale
        else {
            return
        }

        let staleState = MercuryRunActivityContentState(
            status: lastState.status,
            activityLine: lastState.activityLine,
            responseExcerpt: lastState.responseExcerpt,
            updatedAt: now(),
            isStale: true,
            isFinal: lastState.isFinal
        )
        await sendImmediate(staleState, for: record)
    }

    private func applyStart(_ seed: RunActivitySeed) async {
        let preferences = preferencesProvider()
        guard preferences.liveActivitiesEnabled, client.activitiesEnabled() else {
            return
        }

        let safeSeed = seedByScrubbingExcerpt(seed, preferences: preferences)

        if let existing = current {
            if existing.durableSessionID == safeSeed.durableSessionID {
                return
            }

            let generationBeforeReplacement = generationCounter
            await endCurrentAsUnavailable(existing)

            // A start issued while the previous ActivityKit end was awaiting is
            // newer and owns the lifecycle now. Do not let this older command
            // replace it when its end call returns.
            guard current == nil, generationCounter == generationBeforeReplacement else {
                return
            }
        }

        generationCounter += 1
        let requestGeneration = generationCounter

        do {
            let activityID = try await client.start(seed: safeSeed)
            guard generationCounter == requestGeneration, current == nil else {
                // ActivityKit may have created the candidate before a newer
                // start moved the generation. Clean that candidate up without
                // publishing it as the current activity.
                let unavailable = unavailableState()
                await client.end(
                    activityID: activityID,
                    state: unavailable,
                    dismissal: .afterFailure
                )
                finalizedActivityIDs.insert(activityID)
                return
            }

            let record = CurrentActivityRecord(
                serverID: safeSeed.serverID,
                durableSessionID: safeSeed.durableSessionID,
                activityID: activityID,
                generation: requestGeneration
            )
            current = record
            lastSentStateByActivityID[activityID] = safeSeed.initialState
            pendingStateByActivityID.removeValue(forKey: activityID)
            finalizedActivityIDs.remove(activityID)
        } catch {
            // ActivityKit start failures leave no current record. The reducer
            // and the next command remain the source of truth for retrying.
        }
    }

    private func applyUpdate(_ state: MercuryRunActivityContentState) async {
        let preferences = preferencesProvider()
        let safeState = stateByScrubbingExcerpt(state, preferences: preferences)
        guard let record = current,
              !finalizedActivityIDs.contains(record.activityID)
        else {
            return
        }

        let previousStatus = lastSentStateByActivityID[record.activityID]?.status
        let waitingTransition = previousStatus.map {
            $0.isWaitingForInput != safeState.status.isWaitingForInput
        } ?? safeState.status.isWaitingForInput
        let isImmediate = safeState.isFinal || safeState.status.isFinal || waitingTransition

        if isImmediate {
            await sendImmediate(safeState, for: record)
            return
        }

        pendingStateByActivityID[record.activityID] = safeState
        guard !flushScheduled else { return }

        flushScheduled = true
        flushToken += 1
        let token = flushToken
        let activityID = record.activityID
        let generation = record.generation
        scheduleFlush { [weak self] in
            await self?.flushPending(
                activityID: activityID,
                generation: generation,
                token: token
            )
        }
    }

    private func applyEnd(_ state: MercuryRunActivityContentState) async {
        let preferences = preferencesProvider()
        let safeState = stateByScrubbingExcerpt(state, preferences: preferences)
        guard let record = current,
              !finalizedActivityIDs.contains(record.activityID)
        else {
            return
        }

        let dismissal = RunActivityPolicy.dismissal(for: safeState.status) ?? .afterFailure
        detachCurrent(record)
        finalizedActivityIDs.insert(record.activityID)
        await client.end(
            activityID: record.activityID,
            state: safeState,
            dismissal: dismissal
        )
    }

    private func flushPending(
        activityID: String,
        generation: Int,
        token: Int
    ) async {
        guard flushScheduled,
              token == flushToken,
              let record = current,
              record.activityID == activityID,
              record.generation == generation,
              let pending = pendingStateByActivityID.removeValue(forKey: activityID)
        else {
            return
        }

        flushScheduled = false
        lastSentStateByActivityID[activityID] = pending
        await client.update(activityID: activityID, state: pending)
    }

    private func sendImmediate(
        _ state: MercuryRunActivityContentState,
        for record: CurrentActivityRecord
    ) async {
        guard current?.activityID == record.activityID,
              current?.generation == record.generation
        else {
            return
        }

        clearPending(for: record)
        lastSentStateByActivityID[record.activityID] = state
        await client.update(activityID: record.activityID, state: state)
    }

    private func endCurrentAsUnavailable(_ record: CurrentActivityRecord) async {
        guard current?.activityID == record.activityID,
              current?.generation == record.generation
        else {
            return
        }

        let state = unavailableState()
        detachCurrent(record)
        finalizedActivityIDs.insert(record.activityID)
        await client.end(
            activityID: record.activityID,
            state: state,
            dismissal: .afterFailure
        )
    }

    private func detachCurrent(_ record: CurrentActivityRecord) {
        guard current?.activityID == record.activityID,
              current?.generation == record.generation
        else {
            return
        }
        current = nil
        lastSentStateByActivityID.removeValue(forKey: record.activityID)
        clearPending(for: record)
    }

    private func clearPending(for record: CurrentActivityRecord) {
        pendingStateByActivityID.removeValue(forKey: record.activityID)
        flushScheduled = false
        flushToken += 1
    }

    private func unavailableState() -> MercuryRunActivityContentState {
        MercuryRunActivityContentState(
            status: .statusUnavailable,
            activityLine: RunActivityPolicy.displayName(for: .statusUnavailable),
            responseExcerpt: "",
            updatedAt: now(),
            isStale: false,
            isFinal: true
        )
    }

    private func seedByScrubbingExcerpt(
        _ seed: RunActivitySeed,
        preferences: MercuryNotificationPreferences
    ) -> RunActivitySeed {
        RunActivitySeed(
            serverID: seed.serverID,
            profile: seed.profile,
            durableSessionID: seed.durableSessionID,
            sessionTitle: seed.sessionTitle,
            startedAt: seed.startedAt,
            baselineMessageCount: seed.baselineMessageCount,
            initialState: stateByScrubbingExcerpt(seed.initialState, preferences: preferences)
        )
    }

    private func stateByScrubbingExcerpt(
        _ state: MercuryRunActivityContentState,
        preferences: MercuryNotificationPreferences
    ) -> MercuryRunActivityContentState {
        guard !preferences.liveActivityResponseExcerptsEnabled,
              !state.responseExcerpt.isEmpty
        else {
            return state
        }
        return MercuryRunActivityContentState(
            status: state.status,
            activityLine: state.activityLine,
            responseExcerpt: "",
            updatedAt: state.updatedAt,
            isStale: state.isStale,
            isFinal: state.isFinal
        )
    }
}
