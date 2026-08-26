import SwiftUI
import BackgroundTasks
import UserNotifications
#if canImport(UIKit)
import UIKit
#endif

/// Reference holder for the UIKit background-task assertion id, so the App
/// struct's grace-window closures can begin/end it across scene transitions.
@MainActor
private final class BackgroundTaskToken {
    #if canImport(UIKit)
    private var identifier: UIBackgroundTaskIdentifier = .invalid

    func begin() {
        guard identifier == .invalid else { return }
        identifier = UIApplication.shared.beginBackgroundTask(withName: "MercuryNotificationGrace") { [weak self] in
            self?.end()
        }
    }

    func end() {
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
    #else
    func begin() {}
    func end() {}
    #endif
}

@main
struct MercuryApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var appModel = AppModel()
    private let notificationDelegate: NotificationDelegate
    private let graceRunner: BackgroundGraceRunner

    init() {
        // Assign ALL stored properties before any `self`/@State access (reading
        // `appModel` counts as self access). The model-dependent closures are
        // wired immediately after, once `appModel` is legal to read.
        let delegate = NotificationDelegate()
        notificationDelegate = delegate

        let token = BackgroundTaskToken()
        let runner = BackgroundGraceRunner(
            beginTask: { token.begin() },
            endTask: { token.end() }
        )
        graceRunner = runner

        // Stored props are initialized — `appModel` is now readable.
        let model = appModel
        // Bounded background grace window: after the app is backgrounded, keep a
        // short UIKit background-task assertion alive and re-run the official
        // REST reconcile a few times, so an in-flight turn can still deliver a
        // notification before iOS suspends the app. No server changes, no new
        // socket — best-effort widening only.
        runner.reconcile = { await model.performGraceReconciliation() }
        // When the bounded background window expires with a run still live, the
        // Live Activity flips to an honest stale/reconnecting presentation
        // exactly once instead of pretending to be current.
        runner.onExpire = { await model.runActivityCoordinator.markStaleForBackgroundExpiration() }
        delegate.onOpenSession = { sessionID in
            Task { @MainActor in model.requestOpenSession(sessionID) }
        }
        delegate.onOpenRoute = { route in
            Task { @MainActor in model.handleSessionRoute(route) }
        }
        UNUserNotificationCenter.current().delegate = delegate
        Self.applyLaunchArgOverrides(to: model)
        Self.registerBackgroundReconciliation(for: model)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appModel)
                .preferredColorScheme(.dark)
                .task {
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("-uitest-reset-local-state") {
                        await appModel.resetLocalStateForUITest()
                    }
                    if let sharedText = Self.launchArgumentValue("-uitest-share-text") {
                        appModel.enqueueSharedTextForUITest(sharedText)
                    }
                    #endif
                    appModel.loadSharedInbox()
                    // Load persisted notification/Live Activity preferences and
                    // run the one-time migration. Production launches never
                    // show the permission prompt — that is Settings-driven.
                    await appModel.loadNotificationPreferences()
                    #if DEBUG
                    // UI-test hook: notification delivery tests explicitly
                    // request permission (production no longer prompts at
                    // launch), then drive a real banner through
                    // UNUserNotificationCenter so an XCUITest can assert an
                    // actual iOS notification renders.
                    if ProcessInfo.processInfo.arguments.contains("-uitest-fire-notification") {
                        await appModel.requestNotificationAuthorization()
                        // Mirror the Settings Enable action: a fresh install's
                        // preferences are all-off (no launch prompt anymore), so
                        // grant the master + completion categories before firing.
                        await appModel.refreshNotificationAuthorizationStatus()
                        appModel.updateNotificationPreferences { prefs in
                            prefs.notificationsEnabled = true
                            prefs.completionEnabled = true
                            prefs.attentionEnabled = true
                            prefs.failureAndCancellationEnabled = true
                        }
                        await appModel.fireTestNotification()
                    }
                    #endif
                    // UI-test hook: `-uitest-probe <origin>` kicks off the
                    // self-hosted probe automatically so XCUITests can reach
                    // the sign-in/session screens without typing.
                    if let origin = Self.launchArgumentValue("-uitest-probe") {
                        await appModel.probeSelfHosted(origin: origin)
                    } else {
                        await appModel.bootstrapSavedServer()
                        // Cold-launch orphan reconciliation: finalize persisted
                        // Live Activities honestly once the active server's
                        // sessions are conclusive.
                        await appModel.reconcileRunActivities(
                            sessionsAvailable: appModel.sessionsError == nil && !appModel.sessions.isEmpty
                        )
                    }
                }
                .onOpenURL { url in
                    // Canonical mercury://session deep link (Live Activity tap,
                    // future shortcuts). Strictly parsed; invalid links no-op.
                    if let route = MercuryDeepLink.parse(url) {
                        appModel.handleSessionRoute(route)
                    }
                }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        appModel.setAppForeground(true)
                        graceRunner.end()
                        appModel.loadSharedInbox()
                        // Reopen catch-up: refresh sessions, then silently
                        // advance dedupe watermarks so a later background
                        // reconcile never re-announces turns seen here.
                        Task {
                            await appModel.loadSessions()
                            await appModel.catchUpNotifications()
                            // Foreground catch-up for persisted Live Activities:
                            // ends stale UI honestly without posting banners.
                            await appModel.reconcileRunActivities(
                                sessionsAvailable: appModel.sessionsError == nil && !appModel.sessions.isEmpty
                            )
                        }
                    case .background:
                        appModel.setAppForeground(false)
                        // Ask iOS to opportunistically wake us to reconcile.
                        BackgroundReconciliationScheduler.submitRefreshRequest()
                        // Bounded grace window: keep reconciling for a few more
                        // seconds so a just-finished turn can still notify.
                        graceRunner.begin()
                    default:
                        appModel.setAppForeground(false)
                    }
                }
        }
    }

    /// Registers the BGAppRefresh handler. Must run before the app finishes
    /// launching (BGTaskScheduler requirement), hence the `init` call site.
    private static func registerBackgroundReconciliation(for appModel: AppModel) {
        BackgroundReconciliationScheduler.register { task in
            // Always reschedule the next opportunity first.
            BackgroundReconciliationScheduler.submitRefreshRequest()
            let work = Task { @MainActor in
                await appModel.performBackgroundReconciliation()
            }
            task.expirationHandler = { work.cancel() }
            Task {
                _ = await work.value
                task.setTaskCompleted(success: !Task.isCancelled)
            }
        }
    }

    /// Applies launch-argument overrides for UI testing. Test infrastructure,
    /// not throwaway: every milestone's simulator verification uses these.
    private static func applyLaunchArgOverrides(to appModel: AppModel) {
        if let origin = launchArgumentValue("-uitest-origin") {
            appModel.setServerOrigin(origin)
        }
    }

    private static func launchArgumentValue(_ flag: String) -> String? {
        guard let index = ProcessInfo.processInfo.arguments.firstIndex(of: flag),
              index + 1 < ProcessInfo.processInfo.arguments.count else { return nil }
        return ProcessInfo.processInfo.arguments[index + 1]
    }
}
