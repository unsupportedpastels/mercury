import Foundation
import UserNotifications

/// Bridges `UNUserNotificationCenter` callbacks into `AppModel`.
///
/// - A notification tap publishes its `mercury.sessionID` as an open request
///   that RootView/SessionListView navigate to.
/// - While Mercury is foregrounded, a delivered notification still presents as
///   a banner (the decision to post at all was already gated by the coordinator
///   against the visible/foreground session, so anything that reaches here is
///   for a session the user is *not* looking at and should surface).
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    /// Set after init (the SwiftUI App can't safely capture its `@State`
    /// AppModel during `init`). Invoked on the main actor for each tap.
    var onWake: (@MainActor (String) async -> Void)?

    var onOpenSession: (@MainActor (String) -> Void)?

    /// Multi-server route handler: taps whose payload carries a canonical
    /// mercury://session route go here so they can cross server/profile
    /// boundaries. Falls back to `onOpenSession` when no route is present.
    var onOpenRoute: (@MainActor (SessionOpenRoute) -> Void)?

    static func presentationOptions(userInfo: [AnyHashable: Any]) -> UNNotificationPresentationOptions {
        // Reserved push envelope never falls through to content-bearing local routes.
        userInfo["mercury_wake"] != nil ? [] : [.banner, .sound, .list]
    }

    override init() {
        super.init()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler(Self.presentationOptions(userInfo: notification.request.content.userInfo))
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        handlePayload(response.notification.request.content.userInfo, completionHandler: completionHandler)
    }

    func handlePayload(_ userInfo: [AnyHashable: Any], completionHandler: @escaping () -> Void) {
        // System acknowledgement is independent of disk access and network routing.
        // The unstructured task strongly retains this delegate and its callbacks
        // until routing finishes, even if the notification response is released.
        completionHandler()
        Task { @MainActor [self] in await routePayload(userInfo) }
    }

    @MainActor
    private func routePayload(_ userInfo: [AnyHashable: Any]) async {
        if userInfo["mercury_wake"] == nil,
           let routeString = userInfo["mercury.route"] as? String,
           let url = URL(string: routeString),
           let route = MercuryDeepLink.parse(url),
           let routeHandler = onOpenRoute {
            routeHandler(route)
            return
        }
        if userInfo["mercury_wake"] != nil {
            if let wake = RelayPushCoordinator.wake(from: userInfo), let onWake {
                await onWake(wake)
            }
            return
        }
        if let sessionID = userInfo["mercury.sessionID"] as? String,
           !sessionID.isEmpty, let handler = onOpenSession {
            handler(sessionID)
        }
    }
}
