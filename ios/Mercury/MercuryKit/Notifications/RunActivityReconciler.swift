import Foundation
import MercuryRunActivityKit

/// A persisted local Live Activity whose owning process may no longer exist.
///
/// `activityID` is intentionally opaque: ActivityKit owns its shape and the
/// orchestrator is responsible for adapting persisted ActivityKit records into
/// this pure value.
struct OrphanedRunActivity: Equatable, Sendable {
    var activityID: String
    var serverID: UUID
    var profile: String
    var durableSessionID: String
    var startedAt: Date
    var baselineMessageCount: Int
    var lastKnownState: MercuryRunActivityContentState
}

enum RunActivityReconcileAction: Equatable, Sendable {
    case ignore
    case markStale(activityID: String)
    case end(activityID: String, status: MercuryRunActivityStatus)
}

/// Pure recovery policy for persisted Live Activities after process death.
///
/// This type does not access ActivityKit, storage, networking, or notifications.
/// The caller applies the returned commands to the real activities.
enum RunActivityReconciler {
    static func reconcile(
        orphans: [OrphanedRunActivity],
        activeServerID: UUID,
        activeProfile: String,
        knownServerIDs: Set<UUID>,
        sessions: [SessionRow]?,
        liveOwnedSessionIDs: Set<String>,
        now: Date,
        hardStaleAge: TimeInterval = 1800
    ) -> [RunActivityReconcileAction] {
        orphans.map { orphan in
            if orphan.lastKnownState.isFinal {
                return .ignore
            }

            // A live coordinator still owns this session; recovery must never
            // race or interfere with its in-process ActivityKit updates.
            if liveOwnedSessionIDs.contains(orphan.durableSessionID) {
                return .ignore
            }

            if !knownServerIDs.contains(orphan.serverID) {
                return .end(activityID: orphan.activityID, status: .statusUnavailable)
            }

            let isInactiveScope = orphan.serverID != activeServerID || orphan.profile != activeProfile
            if isInactiveScope {
                return age(of: orphan, now: now) > hardStaleAge
                    ? .end(activityID: orphan.activityID, status: .statusUnavailable)
                    : .ignore
            }

            // A failed fetch is not evidence that a recent run disappeared.
            if sessions == nil {
                return age(of: orphan, now: now) > hardStaleAge
                    ? .end(activityID: orphan.activityID, status: .statusUnavailable)
                    : .ignore
            }

            guard let row = sessions?.first(where: { $0.id == orphan.durableSessionID }) else {
                return .end(activityID: orphan.activityID, status: .statusUnavailable)
            }

            if row.messageCount > orphan.baselineMessageCount {
                // The count advance proves completion. Banner ownership stays
                // with NotificationReconciler; this path only ends the activity
                // and must not create a duplicate completion notification.
                return .end(activityID: orphan.activityID, status: .complete)
            }

            if age(of: orphan, now: now) > hardStaleAge {
                return .end(activityID: orphan.activityID, status: .statusUnavailable)
            }

            return orphan.lastKnownState.isStale
                ? .ignore
                : .markStale(activityID: orphan.activityID)
        }
    }

    /// Builds the final content state for a supported reconciliation end.
    ///
    /// Only a REST-proven completion may retain the last response excerpt.
    /// An unavailable status deliberately exposes no unproven response text.
    static func endState(
        for orphan: OrphanedRunActivity,
        status: MercuryRunActivityStatus,
        now: Date
    ) -> MercuryRunActivityContentState {
        let activityLine: String
        switch status {
        case .complete:
            activityLine = "Finished"
        case .statusUnavailable:
            activityLine = "Status unknown"
        default:
            // Reconciliation never emits failed/cancelled (or a non-final
            // status), but keep this pure adapter total for defensive callers.
            activityLine = "Status unknown"
        }

        let responseExcerpt = status == .complete && !orphan.lastKnownState.responseExcerpt.isEmpty
            ? orphan.lastKnownState.responseExcerpt
            : ""

        return MercuryRunActivityContentState(
            status: status,
            activityLine: activityLine,
            responseExcerpt: responseExcerpt,
            updatedAt: now,
            isStale: false,
            isFinal: true
        )
    }

    private static func age(of orphan: OrphanedRunActivity, now: Date) -> TimeInterval {
        now.timeIntervalSince(orphan.startedAt)
    }
}
