import Foundation
import MercuryCore

/// Facade over the shared KMP core's visibility policy. iOS has no separate
/// window-focus state, so windowFocused stays pinned to the shared default
/// (true) — semantics are unchanged from the previous two-field rule.
enum NotificationVisibilityPolicy {
    static func shouldPost(
        sessionID: String,
        visibility: SessionNotificationVisibility
    ) -> Bool {
        MercuryCore.NotificationVisibilityPolicy.shared.shouldPost(
            sessionId: sessionID,
            visibility: MercuryCore.SessionNotificationVisibility(
                appForeground: visibility.appForeground,
                windowFocused: true,
                visibleSessionId: visibility.visibleSessionID
            )
        )
    }
}
