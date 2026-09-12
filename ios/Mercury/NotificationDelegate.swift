import Foundation
import UserNotifications
import MercuryNotificationPreviewKit
import UIKit

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

    // Platform seams keep assertion ordering and expiry testable through didReceive.
    var beginTask: @MainActor (@escaping @MainActor () -> Void) -> UIBackgroundTaskIdentifier = { expire in
        UIApplication.shared.beginBackgroundTask(withName: "Notification routing") {
            MainActor.assumeIsolated { expire() }
        }
    }
    var endTask: @MainActor (UIBackgroundTaskIdentifier) -> Void = { UIApplication.shared.endBackgroundTask($0) }
    var routeSleep: @MainActor () async throws -> Void = { try await Task.sleep(nanoseconds: 15_000_000_000) }

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
        handlePayload(response.notification.request.content.userInfo,
                      isRemote: response.notification.request.trigger is UNPushNotificationTrigger,
                      completionHandler: completionHandler)
    }

    func handlePayload(_ userInfo: [AnyHashable: Any], isRemote: Bool = false, completionHandler: @escaping () -> Void) {
        // Record trusted local intent synchronously before acknowledging. Only
        // Relay resolution needs an OS assertion; local taps survive its denial.
        // Delegate callbacks need not arrive on main; never block that queue.
        let start: @MainActor () -> Void = { [self] in
            if !isRemote && userInfo["mercury_wake"] == nil {
                routeLocalPayload(userInfo)
                completionHandler()
                return
            }
            let lifetime = NotificationRouteLifetime(endTask: endTask)
            lifetime.start(beginTask: beginTask, sleep: routeSleep,
                           completion: completionHandler) { [self] in await routePayload(userInfo) }
        }
        if Thread.isMainThread { MainActor.assumeIsolated { start() } }
        else { DispatchQueue.main.async { start() } }
    }

    @MainActor
    private func routeLocalPayload(_ userInfo: [AnyHashable: Any]) {
        if let routeString = userInfo["mercury.route"] as? String,
           let url = URL(string: routeString),
           let route = MercuryDeepLink.parse(url),
           let routeHandler = onOpenRoute {
            routeHandler(route)
            return
        }
        if let sessionID = userInfo["mercury.sessionID"] as? String,
           !sessionID.isEmpty, let handler = onOpenSession {
            handler(sessionID)
        }
    }

    @MainActor
    private func routePayload(_ userInfo: [AnyHashable: Any]) async {
        // Remote payloads can never supply a trusted local route. Only the
        // authenticated local receipt can select a preview session/profile.
        if let wake = RelayPushCoordinator.wake(from: userInfo),
           let event = userInfo["mercury_event"] as? String,
           let onPreviewRoute,
           let route = previewRoutes?.consume(event: event, wake: wake, now: Int64(Date().timeIntervalSince1970)) {
            await onPreviewRoute(wake, route.sessionID, route.profile)
            return
        }
        if let wake = RelayPushCoordinator.wake(from: userInfo), let onWake {
            await onWake(wake)
        }
    }
}

/// One assertion per response; the watchdog is independent of routing, so even
/// non-cooperative work cannot hold OS execution beyond the 15-second budget.
@MainActor
private final class NotificationRouteLifetime {
    private var identifier: UIBackgroundTaskIdentifier = .invalid
    private var finished = false
    private var route: Task<Void, Never>?
    private var deadline: Task<Void, Never>?
    private let endTask: (UIBackgroundTaskIdentifier) -> Void

    init(endTask: @escaping (UIBackgroundTaskIdentifier) -> Void) { self.endTask = endTask }

    func start(
        beginTask: (@escaping @MainActor () -> Void) -> UIBackgroundTaskIdentifier,
        sleep: @escaping @MainActor () async throws -> Void,
        completion: () -> Void,
        operation: @escaping @MainActor () async -> Void
    ) {
        let acquired = beginTask { [weak self] in self?.finish() }
        // Expiry may race acquisition. End the returned valid ID once, even
        // when the expiry callback fired synchronously inside beginTask.
        if finished {
            if acquired != .invalid { endTask(acquired) }
            completion()
            return
        }
        identifier = acquired
        guard acquired != .invalid else {
            finished = true
            completion() // Never launch unprotected network routing on denial.
            return
        }
        route = Task { [self] in
            guard !Task.isCancelled else { finish(); return }
            await operation()
            finish()
        }
        deadline = Task { [self] in
            do { try await sleep() } catch { return }
            guard !Task.isCancelled else { return }
            finish()
        }
        completion()
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        route?.cancel(); route = nil
        deadline?.cancel(); deadline = nil
        let acquired = identifier
        identifier = .invalid
        if acquired != .invalid { endTask(acquired) }
    }
}
