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
    @UIApplicationDelegateAdaptor(MercuryApplicationDelegate.self) private var applicationDelegate
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
        delegate.onOpenSession = { sessionID in
            Task { @MainActor in model.requestOpenSession(sessionID) }
        }
        delegate.onOpenRoute = { route in
            Task { @MainActor in model.handleSessionRoute(route) }
        }
        delegate.onWake = { wake in await model.handlePushWake(wake) }
        MercuryApplicationDelegate.onToken = { model.relayPush.receivedToken($0) }
        MercuryApplicationDelegate.onFailure = { model.relayPush.registrationFailed() }
        Task {
            await RelayConnectionPool.shared.setPushConnectionSink { target, connection in
                await MainActor.run {
                    guard model.activeRelayTarget?.id == target.id else { return }
                    model.relayPush.connected(target: target, identity: ObjectIdentifier(connection)) { method, params in
                        try await connection.relayRequest(method, params: params)
                    }
                }
            }
        }
        UNUserNotificationCenter.current().delegate = delegate
        Self.applyLaunchArgOverrides(to: model)
        Self.registerBackgroundReconciliation(for: model)
    }

    var body: some Scene {
        WindowGroup {
            Group {
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("-uitest-push") {
                    RelayPushFixtureView()
                } else if ProcessInfo.processInfo.arguments.contains("-uitest-background-tasks") {
                    BackgroundTaskFixtureView()
                } else if ProcessInfo.processInfo.arguments.contains("-uitest-chat-scroll") {
                    ChatTranscriptScrollFixtureView()
                } else if ProcessInfo.processInfo.arguments.contains("-uitest-managed-images") {
                    ManagedImageFixtureView()
                } else { RootView() }
                #else
                RootView()
                #endif
            }
                .environment(appModel)
                .task {
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("-uitest-push")
                        || ProcessInfo.processInfo.arguments.contains("-uitest-background-tasks")
                        || ProcessInfo.processInfo.arguments.contains("-uitest-chat-scroll")
                        || ProcessInfo.processInfo.arguments.contains("-uitest-managed-images") { return }
                    if ProcessInfo.processInfo.arguments.contains("-uitest-reset-local-state") {
                        await appModel.resetLocalStateForUITest()
                    }
                    let startupFixtureApplied = appModel.applyStartupUITestFixtureIfRequested()
                    if let sharedText = Self.launchArgumentValue("-uitest-share-text") {
                        appModel.enqueueSharedTextForUITest(sharedText)
                    }
                    #else
                    let startupFixtureApplied = false
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
                    if ProcessInfo.processInfo.arguments.contains("-debug-apns-register") {
                        await appModel.requestNotificationAuthorization()
                        await appModel.refreshNotificationAuthorizationStatus()
                        appModel.updateNotificationPreferences {
                            $0.notificationsEnabled = true
                            $0.completionEnabled = true
                            $0.attentionEnabled = true
                        }
                    }
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
                    } else if !startupFixtureApplied {
                        await appModel.bootstrapSavedServer()
                    }
                }
                .onOpenURL { url in
                    // Canonical mercury://session deep link (notification tap,
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
                            await appModel.refreshNotificationAuthorizationStatus()
                            if let target = appModel.activeRelayTarget { await appModel.synchronizeRelayPush(target) }
                            await appModel.loadSessions()
                            await appModel.catchUpNotifications()
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
        #if DEBUG
        // The async RootView reset task races the first screen; relay targets
        // must be gone before the Relay tab can load them, so clear the
        // persisted envelope synchronously at init.
        if ProcessInfo.processInfo.arguments.contains("-uitest-reset-local-state"),
           let empty = try? JSONEncoder().encode(EmptyPersistedRelayTargets()) {
            try? KeychainRelayTargetPersistence().writeRelayTargetData(empty)
        }
        #endif
        if let origin = launchArgumentValue("-uitest-origin") {
            appModel.setServerOrigin(origin)
        }
    }

    #if DEBUG
    /// Mirrors PersistedRelayTargets' empty envelope without widening the
    /// store's private persistence types.
    private struct EmptyPersistedRelayTargets: Encodable {
        var version = RelayTargetPolicy.persistedVersion
        var targets: [String] = []
    }
    #endif

    private static func launchArgumentValue(_ flag: String) -> String? {
        guard let index = ProcessInfo.processInfo.arguments.firstIndex(of: flag),
              index + 1 < ProcessInfo.processInfo.arguments.count else { return nil }
        return ProcessInfo.processInfo.arguments[index + 1]
    }
}
