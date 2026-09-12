import Foundation
import UserNotifications
import MercuryNotificationPreviewKit

/// Bridges `UNUserNotificationCenter` callbacks into `AppModel`.
///
/// - A notification tap publishes its `mercury.sessionID` as an open request
///   that RootView/SessionListView navigate to.
/// - Re-check visibility at delivery, not only when a local request was queued.
///   Remote display routing comes only from authenticated local preview receipts.
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    private let previewRoutes: PreviewRouteConsuming?
    /// Set after init (the SwiftUI App can't safely capture its `@State`
    /// AppModel during `init`). Invoked on the main actor for each tap.
    var onWake: (@MainActor (String) async -> Void)?
    var onPreviewRoute: (@MainActor (String, String, String) async -> Void)?

    var onOpenSession: (@MainActor (String) -> Void)?
    var shouldPresent: (@MainActor (NotificationSessionIdentity) -> Bool)?
    var localRouteIdentity: (@MainActor (SessionOpenRoute) -> NotificationSessionIdentity?)?
    var previewIdentity: (@MainActor (PreviewRouteRecord) -> NotificationSessionIdentity?)?
    var remoteIdentity: (@MainActor (String, String) async -> NotificationSessionIdentity?)?

    /// Multi-server route handler: taps whose payload carries a canonical
    /// mercury://session route go here so they can cross server/profile
    /// boundaries. Falls back to `onOpenSession` when no route is present.
    var onOpenRoute: (@MainActor (SessionOpenRoute) -> Void)?

    static func presentationOptions(userInfo: [AnyHashable: Any]) -> UNNotificationPresentationOptions {
        // Without an authenticated route we cannot selectively identify a session.
        // Do not silently suppress every other session on older generic-push hosts.
        [.banner, .sound, .list]
    }

    override init() {
        previewRoutes = PreviewRouteStore()
        super.init()
    }

    init(previewRoutes: PreviewRouteConsuming?) {
        self.previewRoutes = previewRoutes
        super.init()
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let content = notification.request.content
        let remote = notification.request.trigger is UNPushNotificationTrigger
        Task { @MainActor in
            completionHandler(await optionsForRemoteArrival(userInfo: content.userInfo, isRemote: remote))
        }
    }

    @MainActor
    func optionsForRemoteArrival(userInfo: [AnyHashable: Any], isRemote: Bool) async -> UNNotificationPresentationOptions {
        guard isRemote || userInfo["mercury_wake"] != nil else {
            return optionsForArrival(userInfo: userInfo, isRemote: false)
        }
        guard let wake = RelayPushCoordinator.wake(from: userInfo),
              let event = userInfo["mercury_event"] as? String,
              RelayPushCoordinator.validWake(event) else { return Self.presentationOptions(userInfo: userInfo) }
        let receipt = (previewRoutes as? PreviewRouteReading)?.peek(event: event, wake: wake, now: Int64(Date().timeIntervalSince1970))
        var identity = receipt.flatMap { previewIdentity?($0) }
        if identity == nil { identity = await remoteIdentity?(wake, event) }
        // Recheck current visibility after the encrypted read, not at enqueue
        // or before suspension. Neither route source consumes a tap receipt.
        if let identity, shouldPresent?(identity) == false { return [] }
        return Self.presentationOptions(userInfo: userInfo)
    }

    @MainActor
    func optionsForArrival(userInfo: [AnyHashable: Any], isRemote: Bool) -> UNNotificationPresentationOptions {
        let identity: NotificationSessionIdentity?
        if isRemote || userInfo["mercury_wake"] != nil {
            // Never trust transport-supplied local routes, including preview.sid.
            // A read does not consume the route needed by a later notification tap.
            if let wake = RelayPushCoordinator.wake(from: userInfo),
               let event = userInfo["mercury_event"] as? String,
               let route = (previewRoutes as? PreviewRouteReading)?.peek(event: event, wake: wake, now: Int64(Date().timeIntervalSince1970)) {
                identity = previewIdentity?(route)
            } else { identity = nil }
        } else if let local = NotificationSessionIdentity.fromLocalPayload(userInfo[NotificationSessionIdentity.payloadKey]) {
            identity = local
        } else if let raw = userInfo["mercury.route"] as? String,
                  let url = URL(string: raw), let route = MercuryDeepLink.parse(url) {
            identity = localRouteIdentity?(route)
        } else { identity = nil }
        if let identity, shouldPresent?(identity) == false { return [] }
        return Self.presentationOptions(userInfo: userInfo)
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
        if let wake = RelayPushCoordinator.wake(from: userInfo),
           let event = userInfo["mercury_event"] as? String,
           let onPreviewRoute,
           let route = previewRoutes?.consume(event: event, wake: wake, now: Int64(Date().timeIntervalSince1970)) {
            await onPreviewRoute(wake, route.sessionID, route.profile)
            return
        }
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
