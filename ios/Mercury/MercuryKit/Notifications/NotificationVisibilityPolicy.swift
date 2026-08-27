import Foundation

enum NotificationVisibilityPolicy {
    static func shouldPost(
        sessionID: String,
        visibility: SessionNotificationVisibility
    ) -> Bool {
        !(visibility.appForeground && visibility.visibleSessionID == sessionID)
    }
}
