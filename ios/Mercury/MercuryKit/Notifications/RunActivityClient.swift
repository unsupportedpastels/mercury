import Foundation
import MercuryRunActivityKit

/// The ActivityKit data that survives a Mercury process lifetime.
struct PersistedRunActivity: Equatable, Sendable {
    var activityID: String
    var serverID: UUID
    var profile: String
    var durableSessionID: String
    var startedAt: Date
    var baselineMessageCount: Int
    var lastKnownState: MercuryRunActivityContentState
}

/// The small platform seam used by the coordinator. Unit tests use a fake so
/// they never need a real ActivityKit process or widget extension.
@MainActor
protocol RunActivityScheduling {
    func activitiesEnabled() -> Bool
    func start(seed: RunActivitySeed) async throws -> String
    func update(activityID: String, state: MercuryRunActivityContentState) async
    func end(
        activityID: String,
        state: MercuryRunActivityContentState,
        dismissal: RunActivityDismissal
    ) async
    func persistedActivities() -> [PersistedRunActivity]
}

#if canImport(ActivityKit)
import ActivityKit

/// ActivityKit-backed implementation for the Mercury application target.
///
/// This adapter is deliberately local-only. In particular, every request uses
/// `pushType: nil`; no push token or remote update path belongs in Mercury.
@MainActor
final class ActivityKitRunActivityClient: RunActivityScheduling {
    func activitiesEnabled() -> Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    func start(seed: RunActivitySeed) async throws -> String {
        let attributes = MercuryRunActivityAttributes(
            serverID: seed.serverID,
            profile: seed.profile,
            durableSessionID: seed.durableSessionID,
            sessionTitle: seed.sessionTitle,
            startedAt: seed.startedAt,
            baselineMessageCount: seed.baselineMessageCount
        )
        let activity = try await Activity<MercuryRunActivityAttributes>.request(
            attributes: attributes,
            content: content(for: seed.initialState),
            pushType: nil
        )
        return activity.id
    }

    func update(activityID: String, state: MercuryRunActivityContentState) async {
        guard let activity = activity(withID: activityID) else { return }
        await activity.update(content(for: state))
    }

    func end(
        activityID: String,
        state: MercuryRunActivityContentState,
        dismissal: RunActivityDismissal
    ) async {
        guard let activity = activity(withID: activityID) else { return }
        await activity.end(
            ActivityContent(state: state, staleDate: nil),
            dismissalPolicy: .after(Date().addingTimeInterval(dismissal.interval))
        )
    }

    func persistedActivities() -> [PersistedRunActivity] {
        Activity<MercuryRunActivityAttributes>.activities.map { activity in
            let attributes = activity.attributes
            return PersistedRunActivity(
                activityID: activity.id,
                serverID: attributes.serverID,
                profile: attributes.profile,
                durableSessionID: attributes.durableSessionID,
                startedAt: attributes.startedAt,
                baselineMessageCount: attributes.baselineMessageCount,
                lastKnownState: activity.content.state
            )
        }
    }

    private func activity(withID activityID: String) -> Activity<MercuryRunActivityAttributes>? {
        Activity<MercuryRunActivityAttributes>.activities.first { $0.id == activityID }
    }

    private func content(
        for state: MercuryRunActivityContentState
    ) -> ActivityContent<MercuryRunActivityContentState> {
        ActivityContent(
            state: state,
            staleDate: state.isFinal ? nil : Date().addingTimeInterval(60)
        )
    }
}
#else

/// Foundation-only fallback used by non-iOS builds and source-level tests.
@MainActor
final class ActivityKitRunActivityClient: RunActivityScheduling {
    func activitiesEnabled() -> Bool { false }

    func start(seed: RunActivitySeed) async throws -> String { "" }

    func update(activityID: String, state: MercuryRunActivityContentState) async {}

    func end(
        activityID: String,
        state: MercuryRunActivityContentState,
        dismissal: RunActivityDismissal
    ) async {}

    func persistedActivities() -> [PersistedRunActivity] { [] }
}
#endif
