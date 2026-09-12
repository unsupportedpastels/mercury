import Foundation
import UIKit
import MercuryShareKit

// SwiftUI's Observable macro requires iOS 17+; no Combine needed.

/// Connection lifecycle phases. Full definition lives here so tests and the
/// networking milestone can compile against it unchanged.
enum ConnectionPhase: Equatable {
    case disconnected
    case probing
    case connecting
    case connected
    case signInRequired
    case failed(String)
}

/// Root observable state for the app.
///
/// All real networking lives in `ConnectionController`; this type is the
/// observable façade the views bind to. Methods either delegate directly to
/// the controller or spawn a controller task off the calling context.
@MainActor
@Observable
final class AppModel {
    /// In-memory last-known child evidence, isolated by origin/profile/durable session.
    var backgroundTasksBySession: [String: BackgroundTasks] = [:]
    let queuedPromptStates = QueuedPromptStateStore()

    // MARK: - Published state

    private(set) var connectionPhase: ConnectionPhase = .disconnected
    private(set) var serverOrigin: String?

    /// Non-nil while the app is connected through Mercury Relay instead of a
    /// direct HTTPS origin. Relay mode reuses the normal connected UI; the
    /// origin-scoped REST features guard on `serverOrigin` and quietly stand
    /// down because it stays nil.
    private(set) var activeRelayTarget: RelayPairedTarget?
    /// The relay the user selected, kept across a failed or pending connect so
    /// Settings can still disconnect from it and remove its pairing.
    private(set) var selectedRelayTarget: RelayPairedTarget?
    private(set) var relaySelectionGeneration: UInt64 = 0
    private(set) var hermesVersion: String?
    var profiles: [String] = ["default"]
    var sessions: [SessionRow] = []

    /// Friendly message when the last session load failed (rows are kept).
    private(set) var sessionsError: String?

    /// True when the server reported more sessions past the loaded window.
    private(set) var canLoadMoreSessions = false

    /// True while a next-page append is in flight (drives the footer spinner).
    private(set) var isLoadingMoreSessions = false

    /// Profile whose sessions are listed and mutated. Defaults to "default";
    /// populated from richer status probes once servers advertise profiles.
    private(set) var activeProfile = "default"

    /// Optimistic archive-state mirror keyed by session id.
    ///
    /// `SessionRow` carries no archived/pinned fields, so lifecycle flags
    /// applied by `updateSession` live here until the model grows columns for
    /// them. Entries are removed when a value returns to "unset".
    private(set) var sessionArchived: [String: Bool] = [:]

    /// Optimistic pin-state mirror keyed by session id (see `sessionArchived`).
    private(set) var sessionPinned: [String: Bool] = [:]

    /// Agents discovered after Portal sign-in (cloud path).
    private(set) var cloudAgents: [CloudAgent] = []
    private(set) var cloudOrganizations: [OrgChoice] = []
    private(set) var cloudDiscoveryComplete = false

    /// Device code for an in-flight Portal device sign-in.
    private(set) var pendingPortalDeviceCode: DeviceCode?

    /// True while the native PKCE sign-in is awaiting browser + callback.
    private(set) var isSigningIn = false
    /// Providers advertised by the active server. Password UI is shown only
    /// when one explicitly sets `supports_password`.
    private(set) var authProviders: [AuthProvider] = []
    /// Friendly, credential-free error shown on the sign-in screen.
    private(set) var authenticationError: String?

    // MARK: - Local server and cache state

    private let serverCatalogStore: ServerCatalogStore
    let offlineCacheStore: OfflineCacheStore
    private let relayTargetStore: RelayTargetStore
    private let startupChoiceStore: StartupConnectionChoiceStore
    private var relayPairingModel: RelayAppModel?
    private(set) var serverCatalog: ServerCatalog = .empty {
        didSet { startupCatalogsLoaded = true; startupCatalogLoadFailed = false }
    }
    private(set) var relayTargets: [RelayPairedTarget] = []
    private(set) var relayTargetsError: String?
    private(set) var startupState: StartupConnectionState = .loading
    private(set) var startupLastSuccessfulChoice: StartupConnectionIdentity?
    private(set) var transcriptCachingEnabled = false
    private(set) var localSettingsError: String?
    private var serverSwitchGeneration: UInt64 = 0
    private var startupDecisionGeneration: UInt64 = 0
    private var startupBootstrapStarted = false
    /// Distinguishes a genuinely empty saved-server catalog from the initial
    /// placeholder used before asynchronous startup restoration completes.
    private var startupCatalogsLoaded = false
    private var startupCatalogLoadFailed = false
    #if DEBUG
    private var startupUIFixtureActive = false
    #endif
    private var startupUserInteraction = false
    private var startupAttemptIdentity: StartupConnectionIdentity?
    private var activeConnectionAttempt: Task<Void, Never>?
    private(set) var pendingShareEntries: [ShareInboxEntry] = []
    private var shareInboxStore: ShareInboxStore?

    // MARK: - Notifications (best-effort, client-only)

    /// The live delivery brain. Injected in tests; created on first use in the
    /// app with the real UNUserNotificationCenter-backed client + persisted
    /// watermark store.
    private var injectedNotificationCoordinator: NotificationCoordinator?
    var notificationCoordinator: NotificationCoordinator {
        if let injectedNotificationCoordinator { return injectedNotificationCoordinator }
        let created = NotificationCoordinator(
            client: LocalNotificationClient(),
            store: UserDefaultsWatermarkStore()
        )
        injectedNotificationCoordinator = created
        return created
    }

    func injectNotificationCoordinator(_ coordinator: NotificationCoordinator) {
        injectedNotificationCoordinator = coordinator
        coordinator.preferencesProvider = { [weak self] in
            self?.notificationPreferences ?? .newInstallDefaults
        }
    }

    // MARK: - Notification & Live Activity preferences (device-wide)

    let relayPush: RelayPushCoordinator
    var pushHomeRevision = UUID()
    private let notificationPreferencesStore: NotificationPreferencesStore

    /// Sessions this install has opened (Android-parity notification scope for
    /// the background REST reconciler).
    private let engagedSessionStore = EngagedSessionStore()

    /// Records that the app opened a session, so background reconciliation is
    /// allowed to notify about it (mirrors Android only notifying for sessions
    /// its live collector is subscribed to). Called from ChatView on appear.
    func markSessionEngaged(_ sessionID: String) {
        guard let origin = serverOrigin, !sessionID.isEmpty else { return }
        engagedSessionStore.markEngaged(
            sessionID,
            origin: origin,
            profile: activeProfile
        )
    }

    /// Device-wide notification/Live Activity choices. Loaded (with one-time
    /// migration) on launch; every mutation persists and re-normalizes.
    private(set) var notificationPreferences: MercuryNotificationPreferences = .newInstallDefaults

    /// Current system-level notification authorization, for Settings display.
    private(set) var notificationAuthorizationStatus: MercuryNotificationAuthorizationStatus = .unknown

    /// Loads persisted preferences, running the one-time migration so an
    /// installation that was already authorized keeps its deployed behavior.
    func loadNotificationPreferences() async {
        let status = await notificationCoordinator.authorizationStatus()
        notificationAuthorizationStatus = status
        notificationPreferences = await notificationPreferencesStore.migrateIfNeeded(systemStatus: status)
        syncPushPreferences()
        notificationCoordinator.preferencesProvider = { [weak self] in
            self?.notificationPreferences ?? .newInstallDefaults
        }
    }

    /// Applies and persists a preference change.
    func updateNotificationPreferences(_ transform: (inout MercuryNotificationPreferences) -> Void) {
        var updated = notificationPreferences
        transform(&updated)
        notificationPreferences = updated
        notificationPreferencesStore.save(updated)
        syncPushPreferences()
    }

    /// Refreshes the cached system authorization status (Settings on-appear).
    func refreshNotificationAuthorizationStatus() async {
        notificationAuthorizationStatus = await notificationCoordinator.authorizationStatus()
        syncPushPreferences()
    }

    /// True while Mercury is the foreground app. The scene phase drives this;
    /// it gates completion/attention suppression for the visible session.
    private(set) var appIsForeground = true

    /// The durable id of the session currently on screen, if any. A chat view
    /// publishes this on appear and clears it on disappear.
    private(set) var visibleSessionID: String?
    private(set) var visibleNotificationSession: NotificationSessionIdentity?
    private var notificationVisibilityOwner: UUID?
    private var notificationVisibilityScope: NotificationSourceScope?

    var notificationVisibility: SessionNotificationVisibility {
        SessionNotificationVisibility(
            appForeground: appIsForeground,
            visibleSessionID: visibleNotificationSession?.scope == notificationSourceScope ? visibleSessionID : nil
        )
    }

    func setAppForeground(_ foreground: Bool) { appIsForeground = foreground }
    var notificationSourceScope: NotificationSourceScope? {
        NotificationSourceScope(origin: activeRelayTarget?.relayOrigin ?? serverOrigin,
                                relayTargetID: activeRelayTarget?.id, profile: activeProfile)
    }

    func setVisibleSession(_ sessionID: String?) {
        visibleSessionID = sessionID
        notificationVisibilityScope = notificationSourceScope
        visibleNotificationSession = sessionID.flatMap { sid in
            notificationSourceScope.map { NotificationSessionIdentity(scope: $0, sessionID: sid) }
        }
        notificationVisibilityOwner = nil
    }

    func showNotificationSession(_ sessionID: String?, scope: NotificationSourceScope?, owner: UUID) {
        guard scope == notificationSourceScope else { return }
        setVisibleSession(sessionID)
        notificationVisibilityOwner = owner
    }

    func updateNotificationSession(_ sessionID: String?, owner: UUID) {
        guard notificationVisibilityOwner == owner else { return }
        // A delayed connection callback may update the durable ID, but never
        // reclaim visibility after navigation or relabel it with a new scope.
        guard notificationVisibilityScope == notificationSourceScope else { return }
        setVisibleSession(sessionID)
        notificationVisibilityOwner = owner
    }

    func hideNotificationSession(owner: UUID) {
        guard notificationVisibilityOwner == owner else { return }
        setVisibleSession(nil)
    }

    func shouldPresentNotification(_ identity: NotificationSessionIdentity, foreground: Bool? = nil) -> Bool {
        let visible = visibleNotificationSession
        let matchesScope = visible?.scope == identity.scope && visible?.scope == notificationSourceScope
        return NotificationVisibilityPolicy.shouldPost(sessionID: identity.sessionID, visibility: .init(
            appForeground: foreground ?? appIsForeground,
            visibleSessionID: matchesScope ? visible?.sessionID : nil))
    }

    func configureNotificationPresentation(_ delegate: NotificationDelegate) {
        delegate.shouldPresent = { [weak self] identity in
            // Read UIKit at arrival as well as scene-driven selection. A selected
            // chat behind a locked/background app is not visible.
            self?.shouldPresentNotification(identity, foreground: UIApplication.shared.applicationState == .active) ?? true
        }
        delegate.localRouteIdentity = { [weak self] route in
            guard let entry = self?.serverCatalog.entries.first(where: { $0.id == route.serverID }),
                  let scope = NotificationSourceScope(origin: entry.origin, profile: route.profile) else { return nil }
            return NotificationSessionIdentity(scope: scope, sessionID: route.durableSessionID)
        }
        delegate.previewIdentity = { [weak self] route in
            guard let self, let target = self.relayPush.previewTarget(for: route.wake, targets: self.relayTargets),
                  let scope = NotificationSourceScope(origin: target.relayOrigin, relayTargetID: target.id, profile: route.profile) else { return nil }
            return NotificationSessionIdentity(scope: scope, sessionID: route.sessionID)
        }
        delegate.remoteIdentity = { [weak self] wake, event in
            guard let self, UIApplication.shared.applicationState == .active,
                  let target = self.relayPush.target(for: wake, targets: self.relayTargets),
                  target.id == self.activeRelayTarget?.id,
                  let connection = await RelayConnectionPool.shared.existingConnection(target: target, profile: self.activeProfile) else { return nil }
            do {
                let status = try await connection.relayRequest("relay.status", timeoutNanoseconds: 750_000_000)
                guard RelayPushCoordinator.supportsArrivalInspection(status) else { return nil }
                let result = try await connection.relayRequest("relay.push.inspect",
                    params: ["wake_handle": wake, "event_id": event], timeoutNanoseconds: 750_000_000)
                guard self.relayPush.target(for: wake, targets: self.relayTargets) == target,
                      let route = RelayPushCoordinator.resolvedSessionRoute(result),
                      let scope = NotificationSourceScope(origin: target.relayOrigin, relayTargetID: target.id, profile: route.profile) else { return nil }
                return NotificationSessionIdentity(scope: scope, sessionID: route.durableSessionID)
            } catch { return nil }
        }
    }

    #if DEBUG
    func notificationVisibilityOwned(by owner: UUID) -> Bool { notificationVisibilityOwner == owner }
    #endif

    /// A session the user asked to open by tapping a notification. RootView /
    /// SessionListView observe this and navigate, then clear it. The counter
    /// makes repeated taps on the same session distinct navigation requests.
    private(set) var notificationOpenRequest: NotificationOpenRequest?

    struct NotificationOpenRequest: Equatable, Hashable {
        let sessionID: String
        let token: Int
    }

    private var notificationOpenToken = 0

    func requestOpenSession(_ sessionID: String) {
        guard !sessionID.isEmpty else { return }
        notificationOpenToken += 1
        notificationOpenRequest = NotificationOpenRequest(sessionID: sessionID, token: notificationOpenToken)
    }

    func clearOpenSessionRequest() {
        notificationOpenRequest = nil
    }

    // MARK: - Session deep-link routing (notifications, Live Activities, URLs)

    /// A parsed route waiting for the right server/profile/connection state.
    /// Kept in memory only — never persisted. Survives the sign-in flow: the
    /// route completes once the connection reaches `.connected`.
    private(set) var pendingSessionRoute: SessionOpenRoute?

    /// Routes a session open request that may cross server/profile boundaries.
    ///
    /// Same server + profile + connected → navigate immediately. A different
    /// saved server switches intentionally (user tapped that activity). An
    /// unknown server surfaces an honest error instead of opening the same
    /// session ID on the wrong origin.
    func handleSessionRoute(_ route: SessionOpenRoute) {
        // During a cold launch `.empty` is only a placeholder, not evidence
        // that the route's server was removed. Retain the route for bootstrap.
        guard startupCatalogsLoaded else {
            pendingSessionRoute = route
            localSettingsError = startupCatalogLoadFailed ? "Saved connection settings could not be loaded." : nil
            return
        }
        if activeRelayTarget == nil, let active = serverCatalog.activeEntry,
           serverOrigin == active.origin, active.id == route.serverID,
           case .connected = connectionPhase {
            if route.profile == activeProfile {
                pendingSessionRoute = nil
                requestOpenSession(route.durableSessionID)
            } else {
                pendingSessionRoute = route
                Task {
                    await switchProfile(route.profile)
                    completePendingRouteIfReady()
                }
            }
            return
        }

        guard let entry = serverCatalog.entries.first(where: { $0.id == route.serverID }) else {
            localSettingsError = "The server for that notification is no longer saved."
            return
        }
        pendingSessionRoute = route
        if activeRelayTarget == nil, serverOrigin == entry.origin,
           let active = serverCatalog.activeEntry, active.id == entry.id {
            // Right server, not connected yet (probing / sign-in required).
            // The route completes when the phase reaches .connected.
            return
        }
        Task {
            await switchServer(entry)
            completePendingRouteIfReady()
        }
    }

    /// Completes a retained route once server, profile, and connection line up.
    func completePendingRouteIfReady() {
        guard activeRelayTarget == nil, let route = pendingSessionRoute,
              let active = serverCatalog.activeEntry,
              serverOrigin == active.origin,
              active.id == route.serverID,
              case .connected = connectionPhase else { return }
        if route.profile != activeProfile {
            Task {
                await switchProfile(route.profile)
                completePendingRouteIfReady()
            }
            return
        }
        pendingSessionRoute = nil
        requestOpenSession(route.durableSessionID)
    }

    init(
        serverCatalogStore: ServerCatalogStore = ServerCatalogStore(),
        offlineCacheStore: OfflineCacheStore = OfflineCacheStore(),
        relayTargetStore: RelayTargetStore = RelayTargetStore(),
        startupChoiceStore: StartupConnectionChoiceStore = StartupConnectionChoiceStore(),
        relayPush: RelayPushCoordinator? = nil,
        notificationPreferencesStore: NotificationPreferencesStore = NotificationPreferencesStore()
    ) {
        self.relayPush = relayPush ?? RelayPushCoordinator()
        self.notificationPreferencesStore = notificationPreferencesStore
        self.serverCatalogStore = serverCatalogStore
        self.offlineCacheStore = offlineCacheStore
        self.relayTargetStore = relayTargetStore
        self.startupChoiceStore = startupChoiceStore
        Task { [weak self] in
            await RelayConnectionPool.shared.setTaskSink { target, profile, durable, tasks in
                Task { @MainActor in
                    self?.observeRelayBackgroundTasks(target: target, profile: profile,
                                                      durable: durable, tasks: tasks)
                }
            }
        }
    }

    // MARK: - Background tasks

    /// One key per (transport, profile, durable session) for `backgroundTasksBySession`.
    static func backgroundTaskScope(relayTarget: RelayPairedTarget?, serverOrigin: String?,
                                    profile: String, durable: String) -> String {
        let origin = relayTarget.map { "relay:\($0.relayOrigin)|\($0.id)" }
            ?? "direct:\(serverOrigin ?? "unconfigured")"
        return "\(origin)|\(profile)|\(durable)"
    }

    /// A pooled relay reader applied child evidence for a durable session,
    /// whether or not that chat is open. Home reads this dictionary, so the
    /// copy must follow the owner or a completion would strand a stale row.
    func observeRelayBackgroundTasks(target: RelayPairedTarget, profile: String,
                                     durable: String, tasks: BackgroundTasks) {
        guard activeRelayTarget?.id == target.id else { return }
        let scope = Self.backgroundTaskScope(relayTarget: target, serverOrigin: nil,
                                             profile: profile, durable: durable)
        if backgroundTasksBySession[scope] != tasks {
            backgroundTasksBySession[scope] = tasks
        }
    }

    /// The currently live, successfully selected identity. It is derived from
    /// the active transport and never from a catalog's selection marker.
    var activeStartupIdentity: StartupConnectionIdentity? {
        if let target = activeRelayTarget {
            return StartupConnectionIdentity(kind: .relay, id: target.id)
        }
        guard let origin = serverOrigin,
              let entry = serverCatalog.entries.first(where: { $0.origin == origin }) else {
            return nil
        }
        return StartupConnectionIdentity(kind: .direct, id: entry.id)
    }

    /// Secret-free rows used by the startup picker. Relay key material stays in
    /// `relayTargets` and is resolved only after an explicit approved selection.
    var startupTargetRows: [StartupConnectionTargetRow] {
        let direct = serverCatalog.entries.map {
            StartupConnectionTargetRow(
                identity: StartupConnectionIdentity(kind: .direct, id: $0.id),
                title: $0.displayLabel,
                detail: $0.origin,
                isUsable: true,
                isPendingRelay: false
            )
        }
        let relay = relayTargets.map {
            StartupConnectionTargetRow(
                identity: StartupConnectionIdentity(kind: .relay, id: $0.id),
                title: $0.displayLabel,
                detail: $0.status == .approved
                    ? "Mercury Relay · Ready"
                    : "Mercury Relay · Waiting for host approval",
                isUsable: $0.status == .approved,
                isPendingRelay: $0.status != .approved
            )
        }
        return direct + relay
    }

    private var startupCandidates: [StartupConnectionCandidate] {
        startupTargetRows.map {
            StartupConnectionCandidate(identity: $0.identity, isUsable: $0.isUsable)
        }
    }

    /// Creates one relay pairing model over the same store actor used by
    /// startup. Keeping this instance shared prevents a pairing write from
    /// being hidden behind another actor's stale in-memory catalog cache.
    func makeRelayAppModel() -> RelayAppModel {
        if let relayPairingModel { return relayPairingModel }
        let model = RelayAppModel(store: relayTargetStore)
        relayPairingModel = model
        return model
    }

    // MARK: - Controller

    private var injectedController: ConnectionController?

    /// The orchestration engine, created on first access so `self` is fully
    /// initialized by then. Tests can swap it via `injectController`.
    var controller: ConnectionController {
        if let injectedController { return injectedController }
        let created = ConnectionController(appModel: self)
        injectedController = created
        return created
    }

    /// Swaps in a differently-configured controller (test seam).
    func injectController(_ newController: ConnectionController) {
        injectedController = newController
    }

    func bootstrapSavedServer() async {
        guard !startupBootstrapStarted else { return }
        startupBootstrapStarted = true
        let decisionGeneration = startupDecisionGeneration
        do {
            // Both independent catalogs and the last-successful preference are
            // loaded before the shared policy is consulted. A pending relay is
            // therefore visible in the same decision as direct targets.
            async let catalogResult: ServerCatalog? = try? serverCatalogStore.load()
            async let relayResult: [RelayPairedTarget]? = try? relayTargetStore.load()
            async let cacheEnabled = offlineCacheStore.isTranscriptCachingEnabled()
            async let savedChoice = startupChoiceStore.load()
            let (catalog, relays, caching, choice) = try await (
                catalogResult,
                relayResult,
                cacheEnabled,
                savedChoice
            )
            // Catalog readiness is independent of automatic target selection.
            // A Relay wake may invalidate selection, but must not strand later
            // direct links. Never overwrite a catalog already published by an
            // explicit add/remove/switch while this load was suspended.
            if !startupCatalogsLoaded {
                if let catalog {
                    serverCatalog = catalog
                } else {
                    startupCatalogLoadFailed = true
                    localSettingsError = "Saved connection settings could not be loaded."
                }
            }
            guard decisionGeneration == startupDecisionGeneration,
                  !startupUserInteraction else {
                if let route = pendingSessionRoute { handleSessionRoute(route) }
                return
            }

            relayTargets = relays ?? []
            relayTargetsError = relays == nil ? "Saved relay pairings could not be read." : nil
            transcriptCachingEnabled = caching
            startupLastSuccessfulChoice = choice
            guard catalog != nil, relays != nil else {
                localSettingsError = "Some saved connections could not be loaded. Choose an available connection or add one."
                startupState = .chooseTarget(savedChoiceUnavailable: choice != nil)
                return
            }

            // A notification can launch the process before SwiftUI's startup
            // task restores either catalog. Its explicit destination outranks
            // ordinary last-used auto-selection once the catalogs are ready.
            if let route = pendingSessionRoute {
                guard let entry = serverCatalog.entries.first(where: { $0.id == route.serverID }) else {
                    handleSessionRoute(route)
                    return
                }
                let identity = StartupConnectionIdentity(kind: .direct, id: entry.id)
                startupAttemptIdentity = identity
                startupState = .connecting(identity)
                let task = launchConnectionAttempt(identity, userInitiated: false)
                await task.value
                completePendingRouteIfReady()
                return
            }

            let decision = StartupConnectionDecisionPolicy.decide(
                candidates: startupCandidates,
                lastSuccessful: choice
            )
            switch decision.action {
            case .onboarding:
                startupState = .onboarding
            case .chooseTarget:
                startupState = .chooseTarget(
                    savedChoiceUnavailable: decision.savedChoiceUnavailable
                )
            case .autoConnect:
                guard let selected = decision.selected else {
                    startupState = .chooseTarget(savedChoiceUnavailable: true)
                    return
                }
                startupAttemptIdentity = selected
                startupState = .connecting(selected)
                let task = launchConnectionAttempt(selected, userInitiated: false)
                await task.value
            }
        } catch {
            guard decisionGeneration == startupDecisionGeneration,
                  !startupUserInteraction else { return }
            localSettingsError = "Saved connection settings could not be loaded."
            startupState = .onboarding
        }
    }

    /// Reloads only the relay catalog after pairing/removal. The startup
    /// preference remains untouched until a connection reaches `.connected`.
    func loadRelayTargets() async {
        #if DEBUG
        guard !startupUIFixtureActive else { return }
        #endif
        do {
            relayTargets = try await relayTargetStore.load()
            relayTargetsError = nil
        } catch {
            relayTargets = []
            relayTargetsError = "Saved relay pairings could not be read."
        }
    }

    func addServer(origin: String, label: String) async {
        // Adding a server is an explicit user boundary. Invalidate bootstrap
        // before the catalog write so a late load cannot replace this choice.
        markStartupInteraction()
        let generation = startupDecisionGeneration
        do {
            let entry = try await serverCatalogStore.add(origin: origin, label: label)
            let catalog = try await serverCatalogStore.load()
            guard generation == startupDecisionGeneration else { return }
            serverCatalog = catalog
            let task = launchConnectionAttempt(
                StartupConnectionIdentity(kind: .direct, id: entry.id),
                userInitiated: false
            )
            await task.value
        } catch let error as LocalizedError {
            localSettingsError = error.errorDescription
        } catch {
            localSettingsError = "The server could not be added."
        }
    }

    func renameServer(_ entry: ServerCatalogEntry, label: String) async {
        do {
            try await serverCatalogStore.updateLabel(id: entry.id, label: label)
            serverCatalog = try await serverCatalogStore.load()
            localSettingsError = nil
        } catch let error as LocalizedError {
            localSettingsError = error.errorDescription
        } catch {
            localSettingsError = "The server label could not be saved."
        }
    }

    func removeServer(_ entry: ServerCatalogEntry) async {
        do {
            guard try await serverCatalogStore.remove(id: entry.id) else {
                localSettingsError = "Switch servers before removing the active server."
                return
            }
            serverCatalog = try await serverCatalogStore.load()
            localSettingsError = nil
        } catch {
            localSettingsError = "The server could not be removed."
        }
    }

    func renameRelay(_ target: RelayPairedTarget, label: String) async {
        do {
            try await relayTargetStore.updateLabel(id: target.id, label: label)
            relayTargets = try await relayTargetStore.load()
            localSettingsError = nil
        } catch let error as LocalizedError {
            localSettingsError = error.errorDescription
        } catch {
            localSettingsError = "The relay label could not be saved."
        }
    }

    func removeRelay(_ target: RelayPairedTarget) async {
        guard activeRelayTarget?.id != target.id,
              selectedRelayTarget?.id != target.id else {
            localSettingsError = "Disconnect from this relay before removing its pairing."
            return
        }
        do {
            relayPush.remove(target)
            // An isolated cleanup admission never selects or resumes a session.
            await RelayPushCoordinator.unregisterPairedTarget(target)
            try await relayTargetStore.remove(id: target.id)
            queuedPromptStates.remove(origin: target.relayOrigin, relayTargetID: target.id)
            relayTargets = try await relayTargetStore.load()
            localSettingsError = nil
        } catch {
            localSettingsError = "The relay pairing could not be removed."
        }
    }

    /// Explicit server selection from Settings or the startup picker. The
    /// returned task is the single owner of the in-flight connection attempt.
    func switchServer(_ entry: ServerCatalogEntry) async {
        let task = launchConnectionAttempt(
            StartupConnectionIdentity(kind: .direct, id: entry.id),
            userInitiated: true
        )
        await task.value
    }

    private func performStartupConnection(
        _ identity: StartupConnectionIdentity,
        decisionGeneration: UInt64
    ) async {
        guard decisionGeneration == startupDecisionGeneration else { return }
        switch identity.kind {
        case .direct:
            do {
                let catalog = try await serverCatalogStore.load()
                guard decisionGeneration == startupDecisionGeneration, !Task.isCancelled else { return }
                serverCatalog = catalog
            } catch {
                guard decisionGeneration == startupDecisionGeneration else { return }
                setPhase(.failed("Saved server settings could not be loaded."))
                return
            }
            guard let entry = serverCatalog.entries.first(where: { $0.id == identity.id }) else {
                startupState = .chooseTarget(savedChoiceUnavailable: true)
                return
            }
            await performDirectConnection(entry)
        case .relay:
            guard let target = relayTargets.first(where: { $0.id == identity.id }),
                  target.status == .approved else {
                startupState = .chooseTarget(savedChoiceUnavailable: true)
                return
            }
            await performRelayConnection(target)
        }
    }

    /// Starts and owns exactly one startup/direct/relay attempt. User-facing
    /// entry points use this instead of creating detached Tasks themselves.
    @discardableResult
    private func launchConnectionAttempt(
        _ identity: StartupConnectionIdentity,
        userInitiated: Bool
    ) -> Task<Void, Never> {
        if userInitiated { markStartupInteraction() }
        activeConnectionAttempt?.cancel()
        let decisionGeneration = startupDecisionGeneration
        startupAttemptIdentity = identity
        startupState = .connecting(identity)
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.performStartupConnection(identity, decisionGeneration: decisionGeneration)
            guard self.startupDecisionGeneration == decisionGeneration else { return }
            self.activeConnectionAttempt = nil
        }
        activeConnectionAttempt = task
        return task
    }

    private func performDirectConnection(_ entry: ServerCatalogEntry) async {
        let identity = StartupConnectionIdentity(kind: .direct, id: entry.id)
        startupAttemptIdentity = identity
        startupState = .connecting(identity)
        serverSwitchGeneration &+= 1
        let generation = serverSwitchGeneration
        endRelaySelection()
        serverOrigin = entry.origin
        activeRelayTarget = nil
        selectedRelayTarget = nil
        hermesVersion = nil
        sessions = []
        sessionsError = nil
        authenticationError = nil
        authProviders = []
        canLoadMoreSessions = false
        isLoadingMoreSessions = false
        connectionPhase = .connecting
        await loadCachedSessions(origin: entry.origin, profile: activeProfile, generation: generation)
        guard generation == serverSwitchGeneration else { return }
        await controller.probeSelfHosted(origin: entry.origin)
        guard generation == serverSwitchGeneration else { return }
        if case .failed(let message) = connectionPhase, !sessions.isEmpty {
            sessionsError = message
        }
    }

    func rememberServer(origin: String) async {
        do {
            let catalog = try await serverCatalogStore.load()
            if let existing = catalog.entries.first(where: { $0.origin == origin }) {
                try await serverCatalogStore.select(id: existing.id)
            } else {
                _ = try await serverCatalogStore.add(origin: origin)
            }
            serverCatalog = try await serverCatalogStore.load()
        } catch {
            localSettingsError = "The connected server could not be saved."
        }
    }

    func setTranscriptCachingEnabled(_ enabled: Bool) async {
        do {
            try await offlineCacheStore.setTranscriptCachingEnabled(enabled)
            transcriptCachingEnabled = enabled
            localSettingsError = nil
        } catch {
            localSettingsError = "Offline privacy settings could not be saved."
        }
    }

    func clearOfflineCache() async {
        do {
            try await offlineCacheStore.clear()
            localSettingsError = nil
        } catch {
            localSettingsError = "The offline cache could not be cleared."
        }
    }

    func cacheSessionMetadata(origin: String, profile: String, rows: [SessionRow]) async {
        guard let scope = try? OfflineCacheScope(origin: origin, profile: profile) else { return }
        try? await offlineCacheStore.writeMetadata(
            scope: scope,
            sessions: rows,
            now: Int64(Date().timeIntervalSince1970)
        )
    }

    func cachedTranscript(origin: String, profile: String, sessionID: String) async -> [OfflineCachedMessage] {
        guard let scope = try? OfflineCacheScope(origin: origin, profile: profile),
              let snapshot = try? await offlineCacheStore.read(
                scope: scope,
                now: Int64(Date().timeIntervalSince1970)
              ) else { return [] }
        return snapshot.sessions.first(where: { $0.summary.id == sessionID })?.messages ?? []
    }

    func cacheTranscript(
        origin: String,
        profile: String,
        summary: SessionRow,
        messages: [OfflineCachedMessage]
    ) async {
        guard transcriptCachingEnabled,
              let scope = try? OfflineCacheScope(origin: origin, profile: profile) else { return }
        try? await offlineCacheStore.writeTranscript(
            scope: scope,
            summary: summary,
            messages: messages,
            now: Int64(Date().timeIntervalSince1970)
        )
    }

    private func loadCachedSessions(origin: String, profile: String, generation: UInt64) async {
        guard let scope = try? OfflineCacheScope(origin: origin, profile: profile),
              let snapshot = try? await offlineCacheStore.read(
                scope: scope,
                now: Int64(Date().timeIntervalSince1970)
              ),
              generation == serverSwitchGeneration,
              serverOrigin == origin else { return }
        sessions = snapshot.sessions.map(\.summary)
    }

    func loadSharedInbox() {
        do {
            let store: ShareInboxStore
            if let shareInboxStore {
                store = shareInboxStore
            } else {
                guard let identifier = Bundle.main.object(forInfoDictionaryKey: "MercuryAppGroupIdentifier") as? String,
                      !identifier.isEmpty else { return }
                store = try ShareInboxStore(appGroupIdentifier: identifier)
                shareInboxStore = store
            }
            pendingShareEntries = try store.peek()
        } catch {
            localSettingsError = "Shared items could not be opened."
        }
    }

    #if DEBUG
    func resetLocalStateForUITest() async {
        guard ProcessInfo.processInfo.arguments.contains("-uitest-reset-local-state") else { return }
        KeychainServerCatalogPersistence().clearCatalogData()
        UserDefaultsLegacyServerOrigin().clearLegacyOrigin()
        try? await relayTargetStore.removeAll()
        try? await startupChoiceStore.clear()
        try? await offlineCacheStore.clear()
        serverCatalog = .empty
        relayTargets = []
        relayTargetsError = nil
        startupLastSuccessfulChoice = nil
        startupState = .loading
        sessions = []
    }

    /// In-memory only startup fixtures. They use `.test` identities and empty
    /// key data, never real origins or credentials, and are not written to any
    /// catalog. The UI suite uses them to exercise picker/failure boundaries.
    func applyStartupUITestFixtureIfRequested() -> Bool {
        guard ProcessInfo.processInfo.arguments.contains(where: {
            $0 == "-uitest-startup-empty"
                || $0 == "-uitest-startup-multiple"
                || $0 == "-uitest-startup-failed"
        }) else { return false }
        startupBootstrapStarted = true
        startupUIFixtureActive = true
        startupUserInteraction = true
        startupLastSuccessfulChoice = nil
        let directID = UUID(uuidString: "00000000-0000-0000-0000-000000000701")!
        let relayID = UUID(uuidString: "00000000-0000-0000-0000-000000000702")!
        if ProcessInfo.processInfo.arguments.contains("-uitest-startup-empty") {
            serverCatalog = .empty
            relayTargets = []
            startupState = .onboarding
            connectionPhase = .disconnected
            return true
        }
        let direct = try! ServerCatalogEntry(
            id: directID,
            origin: "https://direct.test",
            label: "Direct test server"
        )
        let pendingRelay = RelayPairedTarget(
            id: relayID,
            label: "Pending relay test",
            relayOrigin: "wss://relay.test",
            installationID: Data(),
            hostPublicKey: Data(),
            deviceID: "fixture-device",
            deviceStaticPrivateKey: Data(),
            fingerprint: "fixture",
            status: .pending,
            createdAtEpochSeconds: 0,
            lastUsedEpochSeconds: nil
        )
        serverCatalog = ServerCatalog(entries: [direct], activeID: nil)
        relayTargets = [pendingRelay]
        if ProcessInfo.processInfo.arguments.contains("-uitest-startup-failed") {
            let identity = StartupConnectionIdentity(kind: .direct, id: directID)
            startupAttemptIdentity = identity
            startupState = .failed(identity, "Could not connect to the last server.")
            connectionPhase = .failed("Could not connect to the last server.")
        } else {
            startupState = .chooseTarget(savedChoiceUnavailable: false)
            connectionPhase = .disconnected
        }
        return true
    }

    func enqueueSharedTextForUITest(_ text: String) {
        guard ProcessInfo.processInfo.arguments.contains("-uitest-share-text") else { return }
        do {
            let store: ShareInboxStore
            if let shareInboxStore {
                store = shareInboxStore
            } else {
                guard let identifier = Bundle.main.object(forInfoDictionaryKey: "MercuryAppGroupIdentifier") as? String,
                      !identifier.isEmpty else { return }
                store = try ShareInboxStore(appGroupIdentifier: identifier)
                shareInboxStore = store
            }
            let payload = SharePayloadPolicy.build(text: text, candidates: []).payload
            try store.enqueue(payload)
        } catch {
            localSettingsError = "The share test fixture could not be staged."
        }
    }
    #endif

    func prepareIncomingShare(entryID: String) -> IncomingShareDraft? {
        guard let store = shareInboxStore,
              let entry = pendingShareEntries.first(where: { $0.id == entryID }) else { return nil }
        var staged: [IncomingShareAttachment] = []
        var notices = entry.payload.rejections
        var cumulative: Int64 = 0
        for attachment in entry.payload.attachments.prefix(AttachmentPolicy.maxAttachments) {
            do {
                let url = try store.stagedFileURL(for: attachment)
                let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
                guard let size = (attributes[.size] as? NSNumber)?.int64Value, size >= 0 else {
                    throw ShareInboxError.invalidEntry
                }
                let kind: AttachmentKind = attachment.kind == .image ? .image : .file
                let proposedTotal = cumulative + size
                try AttachmentPolicy.validateStagedBytes(
                    displayName: attachment.displayName,
                    kind: kind,
                    actualBytes: size,
                    cumulativeBytes: proposedTotal
                )
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                let data = try handle.read(
                    upToCount: Int(AttachmentPolicy.perKindCapBytes(kind)) + 1
                ) ?? Data()
                guard Int64(data.count) == size else { throw ShareInboxError.invalidEntry }
                cumulative = proposedTotal
                staged.append(IncomingShareAttachment(
                    id: attachment.id,
                    filename: attachment.displayName,
                    mimeType: attachment.mimeType,
                    data: data
                ))
            } catch {
                notices.append("\(attachment.displayName) could not be staged")
            }
        }
        guard let _ = try? store.consume(id: entryID) else { return nil }
        store.removeStagedFiles(for: entry)
        pendingShareEntries.removeAll { $0.id == entryID }
        return IncomingShareDraft(
            id: entry.id,
            text: entry.payload.text,
            attachments: staged,
            notice: notices.isEmpty ? nil : notices.joined(separator: "\n")
        )
    }

    // MARK: - Connection lifecycle

    /// Validates the entered origin synchronously and kicks off the probe in
    /// the model-owned connection task. Returns the normalized origin, or
    /// `nil` after setting a `.failed` phase for bad input.
    @discardableResult
    func beginProbe(origin rawOrigin: String) -> String? {
        guard let normalized = ServerOrigin.normalize(rawOrigin) else {
            connectionPhase = .failed("Enter a valid server address, e.g. hermes.example.com")
            return nil
        }
        launchManualProbe(normalized, userInitiated: true)
        return normalized
    }

    /// Runs a manually entered origin through the same single owned task used
    /// by startup and configured-target selection.
    func probeSelfHosted(origin: String) async {
        let task = launchManualProbe(origin, userInitiated: true)
        await task.value
    }

    @discardableResult
    private func launchManualProbe(
        _ origin: String,
        userInitiated: Bool
    ) -> Task<Void, Never> {
        if userInitiated { markStartupInteraction() }
        activeConnectionAttempt?.cancel()
        let decisionGeneration = startupDecisionGeneration
        startupAttemptIdentity = serverCatalog.entries
            .first(where: { $0.origin == origin })
            .map { StartupConnectionIdentity(kind: .direct, id: $0.id) }
        if let identity = startupAttemptIdentity {
            startupState = .connecting(identity)
        } else {
            // A manually entered origin has no local typed identity until the
            // controller successfully catalogs it. Never retain an older ID.
            startupState = .onboarding
        }
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.controller.probeSelfHosted(origin: origin)
            guard self.startupDecisionGeneration == decisionGeneration else { return }
            self.activeConnectionAttempt = nil
        }
        activeConnectionAttempt = task
        return task
    }

    private func cancelConnectionAttempt() {
        activeConnectionAttempt?.cancel()
        activeConnectionAttempt = nil
        serverSwitchGeneration &+= 1
        relaySelectionGeneration &+= 1
    }

    /// Opens the native sign-in browser flow and awaits callback + exchange.
    func beginSelfHostedSignInAndAwaitBrowser() async {
        await controller.startSelfHostedSignIn()
    }

    /// Runs Android-parity cookie-backed password authentication. The caller
    /// owns and clears the password field; this model never stores it.
    func signInWithPassword(username: String, password: String) async {
        await controller.startPasswordSignIn(username: username, password: password)
    }

    /// Loads recent sessions from the connected server (page 1 refresh).
    func loadSessions() async {
        await controller.loadSessions()
    }

    /// Full session refresh: the server list is authoritative.
    func refreshSessions() async {
        await controller.refreshSessions()
    }

    /// Appends the next page of sessions, deduped by id, order preserved.
    func loadNextSessionsPage() async {
        await controller.loadNextSessionsPage()
    }

    /// Switches the active profile and reloads the session list from offset 0.
    func switchProfile(_ profile: String) async {
        relaySelectionGeneration &+= 1
        let token = relaySelectionGeneration
        if let target = activeRelayTarget {
            await RelayConnectionPool.shared.select(target: target, profile: profile)
            guard relaySelectionGeneration == token else { return }
        }
        setActiveProfile(profile)
        await controller.loadSessions()
    }

    /// Optimistically applies a title/archive/pin change, then persists it.
    /// The local change reverts if the server rejects it.
    func updateSession(
        id: String,
        archived: Bool? = nil,
        pinned: Bool? = nil,
        title: String? = nil
    ) async {
        await controller.updateSession(id: id, archived: archived, pinned: pinned, title: title)
    }

    /// Removes a session locally immediately; restores it if deletion fails.
    func deleteSession(id: String) async {
        await controller.deleteSession(id: id)
    }

    /// Signs out of the current server: clears its stored credentials
    /// (origin-scoped Keychain delete) and its host's cookies, then resets
    /// transient connection state. No-op when no server origin is set.
    func signOut() async {
        if activeRelayTarget != nil || selectedRelayTarget != nil {
            // Relay "sign out" is a local disconnect. The pairing — and the
            // host-side authorization — stays until removed or revoked
            // explicitly from the pairing management surfaces.
            disconnect()
            return
        }
        guard let origin = serverOrigin else { return }
        notificationCoordinator.reset(origin: origin)
        await controller.signOut(origin: origin)
    }

    // MARK: - Notification delivery (best-effort)

    /// Binds the notification coordinator to a server origin's persisted
    /// dedupe state. Called when a connection reaches `.connected`.
    func configureNotifications(origin: String) {
        notificationCoordinator.configure(origin: origin)
        refreshNotificationContext()
    }

    private func refreshNotificationContext() {
        notificationCoordinator.sourceScope = notificationSourceScope
        notificationCoordinator.shouldDeliver = { [weak self] in self?.shouldPresentNotification($0) ?? true }
        // Multi-server tap routing: embed the active server's catalog UUID +
        // profile in each posted notification.
        if activeRelayTarget == nil, let active = serverCatalog.activeEntry,
           ServerOrigin.normalize(active.origin) == notificationSourceScope?.origin {
            notificationCoordinator.routeContext = (serverID: active.id, profile: activeProfile)
        } else {
            notificationCoordinator.routeContext = nil
        }
    }

    /// Requests local-notification authorization once, lazily (first connect).
    /// iOS only shows the system prompt the first time; later calls are cheap.
    @discardableResult
    func requestNotificationAuthorization() async -> Bool {
        await notificationCoordinator.requestAuthorization()
    }

    func notificationAuthorizationGranted() async -> Bool {
        await notificationCoordinator.authorizationGranted()
    }

    /// Feeds one live chat event to the delivery brain with the current
    /// visibility. Posts a local notification only when the reducer decides one
    /// is warranted and the session is not the visible/foreground one.
    func deliverLiveNotification(event: ChatEvent, sessionTitle: String, sourceScope: NotificationSourceScope? = nil) async {
        if let sourceScope, sourceScope != notificationSourceScope { return }
        refreshNotificationContext()
        guard !relayPush.ownsDelivery(for: activeRelayTarget, event: event) || !RelayPushCoordinator.replacesLocalDelivery(for: event) else { return }
        await notificationCoordinator.handleLive(
            event: event,
            sessionTitle: sessionTitle,
            visibility: notificationVisibility
        )
    }

    func catchUpNotifications() async {
        let deltas = await buildReconcileDeltas()
        guard !deltas.isEmpty else { return }
        notificationCoordinator.catchUp(deltas: deltas, visibility: notificationVisibility)
    }

    /// Builds Android-parity reconciliation deltas: scoped to engaged sessions,
    /// advance-gated, and excerpting the assistant response (fetched per changed
    /// session) rather than the REST `preview` (which is the first user prompt).
    private func buildReconcileDeltas() async -> [ReconciliationDelta] {
        guard let origin = serverOrigin else { return [] }
        let engaged = engagedSessionStore.engagedIDs(origin: origin, profile: activeProfile)
        guard !engaged.isEmpty else { return [] }
        let watermarks = notificationCoordinator.currentWatermarks()
        let profile = activeProfile
        let client = SessionsClient(
            client: HermesHTTPClient.makeAuthenticated(origin: origin),
            profile: profile
        )
        let scope = notificationSourceScope
        let deltas = await NotificationReconciler.deltas(
            from: sessions,
            engagedIDs: engaged,
            watermarks: watermarks,
            fetchTail: { sessionID in
                guard let messages = try? await client.transcript(sessionID: sessionID, limit: 5) else {
                    return nil
                }
                return NotificationReconciler.tail(
                    fromMessages: TranscriptPageOrdering.forDisplay(messages)
                )
            }
        )
        return notificationSourceScope == scope ? deltas : []
    }

    /// Best-effort background reconciliation (BGAppRefresh). iOS grants a short,
    /// system-chosen window: re-fetch the session list over the official REST
    /// endpoint, then let the coordinator post local notifications for any
    /// newly-completed turns it hasn't already announced. No server changes,
    /// no APNs — purely opportunistic.
    func performBackgroundReconciliation() async {
        guard let origin = serverOrigin else { return }
        configureNotifications(origin: origin)
        let scope = notificationSourceScope
        // Reload the newest sessions; reuse the normal authenticated path.
        await controller.loadSessions()
        guard notificationSourceScope == scope else { return }
        let deltas = await buildReconcileDeltas()
        guard !deltas.isEmpty else { return }
        await notificationCoordinator.handleReconcile(
            deltas: deltas,
            visibility: notificationVisibility
        )
    }

    /// Background grace-window reconciliation (short UIKit-assertion window after
    /// the app is backgrounded). Same as `performBackgroundReconciliation`, but
    /// SUPPRESSES the session whose ChatView is still on screen: its live socket
    /// may still be open and owns delivery for that session, so posting from here
    /// too would double-notify. All other sessions post normally.
    func performGraceReconciliation() async {
        guard let origin = serverOrigin else { return }
        configureNotifications(origin: origin)
        let scope = notificationSourceScope
        await controller.loadSessions()
        guard notificationSourceScope == scope else { return }
        let deltas = await buildReconcileDeltas()
        guard !deltas.isEmpty else { return }
        // Force-suppress the still-visible session by presenting it as the
        // foreground/visible one, regardless of the app's background state.
        let visibility = SessionNotificationVisibility(
            appForeground: true,
            visibleSessionID: visibleSessionID
        )
        await notificationCoordinator.handleReconcile(deltas: deltas, visibility: visibility)
    }

    /// Cancels any delivered/pending notifications for a session the user has
    /// just opened, so a stale banner doesn't linger over an active chat.
    func clearNotifications(sessionID: String) async {
        await notificationCoordinator.clearSession(sessionID: sessionID)
    }

    #if DEBUG
    /// Simulator/UI-test hook: drives the REAL delivery path (coordinator →
    /// LocalNotificationClient → UNUserNotificationCenter) with a synthetic
    /// backgrounded completion, so an XCUITest can prove an actual iOS banner
    /// renders — coverage the hermetic unit tests (which use a fake client)
    /// cannot provide. Not compiled into release builds.
    func fireTestNotification() async {
        let origin = serverOrigin ?? "https://simulator.test"
        notificationCoordinator.configure(origin: origin)
        // Each invocation represents a new synthetic session. Reusing the same
        // completed session correctly hits persisted notification deduplication
        // on subsequent UI-test runs and therefore produces no banner.
        let event = ChatEvent.messageComplete(
            sessionID: "sim-test-session-\(UUID().uuidString)",
            text: "Simulator test — your task finished.",
            status: "finished",
            error: nil,
            reasoning: nil,
            warning: nil,
            failureReason: nil,
            recoverable: false,
            billing: nil
        )
        // appForeground with no visible session → not suppressed → posts.
        await notificationCoordinator.handleLive(
            event: event,
            sessionTitle: "Simulator Test",
            visibility: SessionNotificationVisibility(appForeground: true, visibleSessionID: nil)
        )
    }
    #endif

    // MARK: - Cloud (Portal device flow)

    func startCloudSignIn() async throws -> PortalStart {
        try await controller.startCloudSignIn()
    }

    func pollCloudOnce(deviceCode: DeviceCode, interval: Int? = nil) async throws -> PortalClient.DevicePollOutcome {
        try await controller.pollCloudOnce(deviceCode: deviceCode, interval: interval)
    }

    func selectAgent(_ agent: CloudAgent) async {
        await controller.selectAgent(agent)
    }

    /// Completes device-code authorization and immediately discovers the
    /// account's agents, matching Android's sign-in → discovery state machine.
    func completeCloudSignIn(_ deviceCode: DeviceCode) async throws {
        let tokens = try await awaitCloudSignInTokens(deviceCode)
        try await discoverCloudAgents(accessToken: tokens.accessToken, org: nil)
    }

    func selectCloudOrganization(_ organization: OrgChoice) async throws {
        guard let tokens = controller.storedPortalTokens() else {
            throw PortalTerminalError(reason: "missing_portal_session")
        }
        try await discoverCloudAgents(accessToken: tokens.accessToken, org: organization.slug)
    }

    private func discoverCloudAgents(accessToken: String, org: String?) async throws {
        setCloudDiscoveryComplete(false)
        do {
            let discovery = try await controller.discoverCloudAgents(accessToken: accessToken, org: org)
            setCloudOrganizations([])
            setCloudAgents(discovery.agents)
            setCloudDiscoveryComplete(true)
        } catch let selection as OrgSelectionRequiredError {
            setCloudAgents([])
            setCloudOrganizations(selection.choices)
            setCloudDiscoveryComplete(false)
        }
    }

    /// True while `awaitCloudSignInTokens` drives the device-code poll loop.
    private(set) var isCloudPolling = false

    /// Friendly, token-free message when the last cloud poll loop failed.
    private(set) var cloudPollError: String?

    /// Drives the hardened Portal poll loop (`PortalPoller`) until the user
    /// authorizes, a terminal error arrives, or the task is cancelled.
    /// Tokens are persisted by `pollCloudOnce` on success.
    ///
    /// - Throws: `PortalTerminalError` / `CancellationError` from the loop;
    ///   `cloudPollError` always carries a user-safe message afterwards.
    @discardableResult
    func awaitCloudSignInTokens(_ deviceCode: DeviceCode) async throws -> TokenSet {
        setCloudPolling(true)
        defer { setCloudPolling(false) }
        do {
            let tokens = try await PortalPoller.run(
                deviceCode: deviceCode.deviceCode,
                initialInterval: deviceCode.interval
            ) { _, interval in
                try await self.pollCloudOnce(deviceCode: deviceCode, interval: interval)
            }
            setCloudPollError(nil)
            return tokens
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            setCloudPollError("Portal sign-in wasn't completed — start again to get a new code.")
            throw error
        }
    }

    // MARK: - Local transitions

    private func markStartupInteraction() {
        startupUserInteraction = true
        startupDecisionGeneration &+= 1
        activeConnectionAttempt?.cancel()
        activeConnectionAttempt = nil
        controller.resetProfileCatalogForConnectionBoundary()
    }

    /// Shows the configured-target list after an explicit user request. It
    /// never starts a connection itself, even when only one row is usable.
    func showStartupPicker() {
        markStartupInteraction()
        serverSwitchGeneration &+= 1
        endRelaySelection()
        activeRelayTarget = nil
        selectedRelayTarget = nil
        serverOrigin = nil
        hermesVersion = nil
        sessions = []
        sessionsError = nil
        authenticationError = nil
        authProviders = []
        startupAttemptIdentity = nil
        connectionPhase = .disconnected
        startupState = .chooseTarget(savedChoiceUnavailable: false)
    }

    func beginManualStartupSelection() {
        markStartupInteraction()
        serverSwitchGeneration &+= 1
        endRelaySelection()
        activeRelayTarget = nil
        selectedRelayTarget = nil
        serverOrigin = nil
        startupAttemptIdentity = nil
        startupState = .onboarding
        connectionPhase = .disconnected
    }

    /// Retries the exact failed identity. No other target is considered.
    func retryStartupConnection() {
        guard case .failed(let identity?, _) = startupState else { return }
        _ = launchConnectionAttempt(identity, userInitiated: true)
    }

    /// Starts one explicitly selected approved target. Pending relays are
    /// intentionally disabled by the picker and never enter this path.
    func selectStartupTarget(_ identity: StartupConnectionIdentity) {
        guard let row = startupTargetRows.first(where: { $0.identity == identity }),
              row.isUsable else {
            localSettingsError = "This relay is waiting for host approval."
            return
        }
        _ = launchConnectionAttempt(identity, userInitiated: true)
    }

    private func endRelaySelection() {
        relayPush.select(nil)
        relaySelectionGeneration &+= 1
        let token = relaySelectionGeneration
        Task { @MainActor in
            guard relaySelectionGeneration == token else { return }
            await RelayConnectionPool.shared.select(target: nil, profile: activeProfile)
        }
    }

    func connect() {
        connectionPhase = .connecting
    }

    func disconnect() {
        markStartupInteraction()
        cancelConnectionAttempt()
        endRelaySelection()
        activeRelayTarget = nil
        selectedRelayTarget = nil
        serverOrigin = nil
        sessionsError = nil
        connectionPhase = .disconnected
        startupAttemptIdentity = nil
        startupState = .chooseTarget(savedChoiceUnavailable: false)
    }

    /// Connects through a paired, approved Mercury Relay target and enters
    /// the normal connected experience using the same owned task as startup.
    func connectRelay(_ target: RelayPairedTarget) async {
        markStartupInteraction()
        let generation = startupDecisionGeneration
        await loadRelayTargets()
        guard generation == startupDecisionGeneration else { return }
        guard relayTargets.contains(where: { $0.id == target.id && $0.status == .approved }) else {
            localSettingsError = "This relay is no longer configured."
            return
        }
        let task = launchConnectionAttempt(
            StartupConnectionIdentity(kind: .relay, id: target.id),
            userInitiated: false
        )
        await task.value
    }

    private func performRelayConnection(_ target: RelayPairedTarget) async {
        guard target.status == .approved else {
            startupState = .chooseTarget(savedChoiceUnavailable: false)
            localSettingsError = "This relay is waiting for host approval."
            return
        }
        relaySelectionGeneration &+= 1
        let generation = relaySelectionGeneration
        let identity = StartupConnectionIdentity(kind: .relay, id: target.id)
        startupAttemptIdentity = identity
        startupState = .connecting(identity)
        selectedRelayTarget = target
        await controller.connectRelay(target: target)
        guard relaySelectionGeneration == generation else { return }
    }

    func signedOutPreservingServer(_ origin: String) {
        queuedPromptStates.remove(origin: origin)
        serverOrigin = origin
        hermesVersion = nil
        sessions = []
        sessionsError = nil
        authenticationError = nil
        canLoadMoreSessions = false
        isLoadingMoreSessions = false
        sessionArchived = [:]
        sessionPinned = [:]
        connectionPhase = .signInRequired
    }

    /// Clears transient connection state without touching stored credentials or
    /// the last successful startup identity. This is intentionally not a user
    /// interaction boundary: internal controller reset paths must not cancel
    /// their own active task or invalidate a newer selection.
    func reset() {
        relayPush.select(nil)
        controller.resetProfileCatalogForConnectionBoundary()
        activeRelayTarget = nil
        selectedRelayTarget = nil
        serverOrigin = nil
        hermesVersion = nil
        sessionsError = nil
        authenticationError = nil
        authProviders = []
        pendingPortalDeviceCode = nil
        connectionPhase = .disconnected
        startupState = .onboarding
        canLoadMoreSessions = false
        isLoadingMoreSessions = false
        sessionArchived = [:]
        sessionPinned = [:]
        cloudAgents = []
        cloudOrganizations = []
        cloudDiscoveryComplete = false
        isCloudPolling = false
        cloudPollError = nil
    }

    // MARK: - Controller-facing mutators
    //
    // The controller is a separate type, so it cannot touch these
    // `private(set)` properties directly. These internal setters keep the
    // properties read-only to the views while letting the controller drive
    // state. They are not part of the view-facing API.

    func setPhase(_ phase: ConnectionPhase) {
        if case .signInRequired = phase, let origin = serverOrigin { queuedPromptStates.remove(origin: origin) }
        connectionPhase = phase
        switch phase {
        case .connected:
            // The catalog/relay store has already been updated by the
            // controller before it emits `.connected`. Resolve the identity
            // from the live transport now so a newly entered origin gets its
            // newly generated catalog UUID, never a stale previous attempt.
            if let identity = activeStartupIdentity {
                startupLastSuccessfulChoice = identity
                startupState = .connected(identity)
                // Saving is deliberately triggered only by this successful
                // transition. A selected/added/paired row never writes here.
                Task { @MainActor [weak self] in
                    await self?.recordSuccessfulStartupChoice(identity)
                }
            }
            // Bind notification dedupe state to the live origin the moment a
            // connection is established, so live events can be deduped/persisted.
            if let origin = serverOrigin {
                configureNotifications(origin: origin)
            }
            // A retained deep-link route (notification/Live Activity tap that
            // arrived before this server finished connecting or signing in)
            // completes now.
            completePendingRouteIfReady()
        case .failed(let message):
            if let identity = startupAttemptIdentity {
                startupState = .failed(identity, message)
            }
        default:
            break
        }
    }

    private func recordSuccessfulStartupChoice(_ identity: StartupConnectionIdentity) async {
        guard startupLastSuccessfulChoice == identity else { return }
        try? await startupChoiceStore.save(identity)
    }

    func setServerOrigin(_ origin: String?) { serverOrigin = origin }
    func setActiveRelayTarget(_ target: RelayPairedTarget?) {
        activeRelayTarget = target
        relayPush.select(target)
        if let target, relayPush.needsConnection { Task { await synchronizeRelayPush(target) } }
    }

    func syncPushPreferences() {
        let authorized = notificationAuthorizationStatus.countsAsAuthorized
        let generic = RelayPushCoordinator.genericPushEnabled(notificationPreferences, authorized: authorized)
        let selectivePreview = relayPush.previewEnabled && authorized && notificationPreferences.notificationsEnabled && (notificationPreferences.completionEnabled || notificationPreferences.attentionEnabled)
        let enabled = generic || selectivePreview
        relayPush.setPreviewCategories(completion: notificationPreferences.completionEnabled, attention: notificationPreferences.attentionEnabled)
        let deliveryPermitted = authorized && notificationPreferences.notificationsEnabled
            && (notificationPreferences.completionEnabled || notificationPreferences.attentionEnabled)
        relayPush.setEnabled(enabled, retainOwnershipUntilUnregister: deliveryPermitted)
        // Keep APNs and reconnect cleanup alive for an acknowledged selective
        // registration; preview key revocation does not revoke the host's token.
        if enabled || (deliveryPermitted && relayPush.needsConnection) {
            UIApplication.shared.registerForRemoteNotifications()
            if let target = activeRelayTarget { Task { await synchronizeRelayPush(target) } }
        }
        else { UIApplication.shared.unregisterForRemoteNotifications() }
    }

    func synchronizeRelayPush(_ target: RelayPairedTarget) async {
        guard activeRelayTarget?.id == target.id else { return }
        guard let connection = try? await RelayConnectionPool.shared.acquire(target: target, profile: activeProfile),
              activeRelayTarget?.id == target.id else { return }
        relayPush.connected(target: target, identity: ObjectIdentifier(connection)) { method, params in
            try await connection.relayRequest(method, params: params)
        }
    }

    func handlePushWake(_ wake: String) async {
        guard RelayPushCoordinator.validWake(wake) else { return }
        markStartupInteraction()
        let generation = startupDecisionGeneration
        await loadNotificationPreferences()
        await loadRelayTargets()
        guard generation == startupDecisionGeneration,
              let target = relayPush.target(for: wake, targets: relayTargets) else { return }
        pendingSessionRoute = nil
        clearOpenSessionRequest()
        pushHomeRevision = UUID()
        await connectRelay(target)
        guard activeRelayTarget?.id == target.id,
              case .connected = connectionPhase,
              let connection = try? await RelayConnectionPool.shared.acquire(
                  target: target,
                  profile: activeProfile
              ) else { return }
        let route = await RelayPushCoordinator.resolveSessionRoute(
            wake: wake,
            request: { method, params in
                try await connection.relayRequest(method, params: params, timeoutNanoseconds: 5_000_000_000)
            }
        )
        guard let route else { return }
        if route.profile != activeProfile {
            await switchProfile(route.profile)
            guard activeRelayTarget?.id == target.id,
                  activeProfile == route.profile,
                  case .connected = connectionPhase else { return }
        }
        requestOpenSession(route.durableSessionID)
    }
    func handlePushPreviewRoute(wake: String, durableSessionID: String, profile: String) async {
        guard RelayPushCoordinator.validWake(wake), (1...256).contains(durableSessionID.utf8.count), (1...64).contains(profile.utf8.count) else { return }
        markStartupInteraction()
        let generation = startupDecisionGeneration
        await loadNotificationPreferences()
        await loadRelayTargets()
        guard generation == startupDecisionGeneration,
              let target = relayPush.previewTarget(for: wake, targets: relayTargets) else { return }
        pendingSessionRoute = nil; clearOpenSessionRequest(); pushHomeRevision = UUID()
        await connectRelay(target)
        guard activeRelayTarget?.id == target.id, case .connected = connectionPhase else { return }
        if profile != activeProfile { await switchProfile(profile) }
        guard activeRelayTarget?.id == target.id, activeProfile == profile, case .connected = connectionPhase else { return }
        requestOpenSession(durableSessionID)
    }
    func setHermesVersion(_ version: String?) { hermesVersion = version }
    func setSessionsError(_ message: String?) { sessionsError = message }
    func setAuthProviders(_ providers: [AuthProvider]) { authProviders = providers }
    func setAuthenticationError(_ message: String?) { authenticationError = message }
    func setPendingPortalDeviceCode(_ code: DeviceCode?) { pendingPortalDeviceCode = code }
    func setSigningIn(_ value: Bool) { isSigningIn = value }
    func setActiveProfile(_ profile: String) {
        guard activeProfile != profile else { return }
        activeProfile = profile
        sessions = []
        sessionsError = nil
        canLoadMoreSessions = false
        isLoadingMoreSessions = false
        sessionArchived = [:]
        sessionPinned = [:]
    }
    func setCanLoadMoreSessions(_ value: Bool) { canLoadMoreSessions = value }
    func setIsLoadingMoreSessions(_ value: Bool) { isLoadingMoreSessions = value }
    func setCloudPolling(_ value: Bool) { isCloudPolling = value }
    func setCloudPollError(_ message: String?) { cloudPollError = message }
    func setCloudAgents(_ agents: [CloudAgent]) { cloudAgents = agents }
    func setCloudOrganizations(_ organizations: [OrgChoice]) { cloudOrganizations = organizations }
    func setCloudDiscoveryComplete(_ value: Bool) { cloudDiscoveryComplete = value }

    /// Applies an optimistic lifecycle change. Only non-nil fields are
    /// touched, so a title-only update never disturbs pin/archive mirrors.
    func applyLifecycleUpdate(id: String, title: String?, archived: Bool?, pinned: Bool?) {
        if let title, let index = sessions.firstIndex(where: { $0.id == id }) {
            sessions[index].title = title
        }
        if let archived { sessionArchived[id] = archived }
        if let pinned { sessionPinned[id] = pinned }
    }

    /// Restores the exact pre-optimistic lifecycle state after a failed
    /// server call. A nil prior flag removes its mirror entry (back to unset).
    func restoreLifecycle(id: String, priorTitle: String?, priorArchived: Bool?, priorPinned: Bool?) {
        if let priorTitle, let index = sessions.firstIndex(where: { $0.id == id }) {
            sessions[index].title = priorTitle
        }
        sessionArchived[id] = priorArchived
        sessionPinned[id] = priorPinned
    }

    /// Drops mirror entries for sessions no longer present after a refresh.
    func pruneLifecycleMirrors(keeping ids: Set<String>) {
        sessionArchived = sessionArchived.filter { ids.contains($0.key) }
        sessionPinned = sessionPinned.filter { ids.contains($0.key) }
    }
}
