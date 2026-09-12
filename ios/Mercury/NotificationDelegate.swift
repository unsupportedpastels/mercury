import Foundation
import UserNotifications
import UIKit

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

    // Platform seams keep assertion ordering and expiry testable through didReceive.
    var beginTask: @MainActor (@escaping @MainActor () -> Void) -> UIBackgroundTaskIdentifier = { expire in
        UIApplication.shared.beginBackgroundTask(withName: "Notification routing") {
            MainActor.assumeIsolated { expire() }
        }
    }
    var endTask: @MainActor (UIBackgroundTaskIdentifier) -> Void = { UIApplication.shared.endBackgroundTask($0) }
    var routeSleep: @MainActor () async throws -> Void = { try await Task.sleep(nanoseconds: 15_000_000_000) }

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
        // Acquire an OS execution assertion on main BEFORE acknowledging the
        // response. Reachability alone cannot keep a cold/background tap alive.
        // Delegate callbacks need not arrive on main; never block that queue.
        let start: @MainActor () -> Void = { [self] in
            let lifetime = NotificationRouteLifetime(endTask: endTask)
            lifetime.start(beginTask: beginTask, sleep: routeSleep,
                           completion: completionHandler) { [self] in await routePayload(userInfo) }
        }
        if Thread.isMainThread { MainActor.assumeIsolated { start() } }
        else { DispatchQueue.main.async { start() } }
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
