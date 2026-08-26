import Foundation
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

    // MARK: - Published state

    private(set) var connectionPhase: ConnectionPhase = .disconnected
    private(set) var serverOrigin: String?
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
    private(set) var serverCatalog: ServerCatalog = .empty
    private(set) var transcriptCachingEnabled = false
    private(set) var localSettingsError: String?
    private var serverSwitchGeneration: UInt64 = 0
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

    private let notificationPreferencesStore = NotificationPreferencesStore()

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
        notificationCoordinator.preferencesProvider = { [weak self] in
            self?.notificationPreferences ?? .newInstallDefaults
        }
    }

    /// Applies and persists a preference change (normalized: excerpts cannot
    /// stay on while Live Activities are off).
    func updateNotificationPreferences(_ transform: (inout MercuryNotificationPreferences) -> Void) {
        var updated = notificationPreferences
        transform(&updated)
        let normalized = updated.normalized()
        notificationPreferences = normalized
        notificationPreferencesStore.save(normalized)
    }

    /// Refreshes the cached system authorization status (Settings on-appear).
    func refreshNotificationAuthorizationStatus() async {
        notificationAuthorizationStatus = await notificationCoordinator.authorizationStatus()
    }

    /// True while Mercury is the foreground app. The scene phase drives this;
    /// it gates completion/attention suppression for the visible session.
    private(set) var appIsForeground = true

    /// The durable id of the session currently on screen, if any. A chat view
    /// publishes this on appear and clears it on disappear.
    private(set) var visibleSessionID: String?

    var notificationVisibility: SessionNotificationVisibility {
        SessionNotificationVisibility(
            appForeground: appIsForeground,
            visibleSessionID: visibleSessionID
        )
    }

    func setAppForeground(_ foreground: Bool) { appIsForeground = foreground }
    func setVisibleSession(_ sessionID: String?) { visibleSessionID = sessionID }

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
        if let active = serverCatalog.activeEntry,
           active.id == route.serverID,
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
        if let active = serverCatalog.activeEntry, active.id == entry.id {
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
        guard let route = pendingSessionRoute,
              let active = serverCatalog.activeEntry,
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
        offlineCacheStore: OfflineCacheStore = OfflineCacheStore()
    ) {
        self.serverCatalogStore = serverCatalogStore
        self.offlineCacheStore = offlineCacheStore
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
        do {
            serverCatalog = try await serverCatalogStore.load()
            transcriptCachingEnabled = await offlineCacheStore.isTranscriptCachingEnabled()
            guard let active = serverCatalog.activeEntry else { return }
            await switchServer(active)
        } catch {
            localSettingsError = "Saved server settings could not be loaded."
        }
    }

    func addServer(origin: String, label: String) async {
        do {
            let entry = try await serverCatalogStore.add(origin: origin, label: label)
            serverCatalog = try await serverCatalogStore.load()
            await switchServer(entry)
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
            // A removed server's Live Activity can never update again.
            await runActivityCoordinator.endActivity(forServerID: entry.id)
        } catch {
            localSettingsError = "The server could not be removed."
        }
    }

    func switchServer(_ entry: ServerCatalogEntry) async {
        do {
            try await serverCatalogStore.select(id: entry.id)
            serverCatalog = try await serverCatalogStore.load()
        } catch {
            localSettingsError = "The selected server could not be saved."
            return
        }
        serverSwitchGeneration &+= 1
        let generation = serverSwitchGeneration
        serverOrigin = entry.origin
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
        // Explicit server switch: reconcile this scope's persisted Live
        // Activities now that its session list state is conclusive.
        await reconcileRunActivities(
            sessionsAvailable: sessionsError == nil && !sessions.isEmpty
        )
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
        try? await offlineCacheStore.clear()
        serverCatalog = .empty
        sessions = []
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
    /// a task. Returns the normalized origin, or `nil` after setting a
    /// `.failed` phase for bad input.
    @discardableResult
    func beginProbe(origin rawOrigin: String) -> String? {
        guard let normalized = ServerOrigin.normalize(rawOrigin) else {
            connectionPhase = .failed("Enter a valid server address, e.g. hermes.example.com")
            return nil
        }
        Task { await controller.probeSelfHosted(origin: normalized) }
        return normalized
    }

    /// Runs the probe inline; prefer `beginProbe` from synchronous call sites.
    func probeSelfHosted(origin: String) async {
        await controller.probeSelfHosted(origin: origin)
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
        guard let origin = serverOrigin else { return }
        notificationCoordinator.reset(origin: origin)
        await runActivityCoordinator.endAllForSignOut()
        await controller.signOut(origin: origin)
    }

    // MARK: - Notification delivery (best-effort)

    /// Binds the notification coordinator to a server origin's persisted
    /// dedupe state. Called when a connection reaches `.connected`.
    func configureNotifications(origin: String) {
        notificationCoordinator.configure(origin: origin)
        // Multi-server tap routing: embed the active server's catalog UUID +
        // profile in each posted notification.
        if let active = serverCatalog.activeEntry {
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
    func deliverLiveNotification(event: ChatEvent, sessionTitle: String) async {
        await notificationCoordinator.handleLive(
            event: event,
            sessionTitle: sessionTitle,
            visibility: notificationVisibility
        )
    }

    // MARK: - Live Activity coordination (local ActivityKit, pushType nil)

    private var injectedRunActivityCoordinator: RunActivityCoordinator?

    /// The Live Activity brain. Created lazily with the real ActivityKit-backed
    /// client; tests inject a coordinator over a fake client.
    var runActivityCoordinator: RunActivityCoordinator {
        if let injectedRunActivityCoordinator { return injectedRunActivityCoordinator }
        let created = RunActivityCoordinator(
            client: ActivityKitRunActivityClient(),
            preferencesProvider: { [weak self] in
                self?.notificationPreferences ?? .newInstallDefaults
            }
        )
        injectedRunActivityCoordinator = created
        return created
    }

    func injectRunActivityCoordinator(_ coordinator: RunActivityCoordinator) {
        injectedRunActivityCoordinator = coordinator
    }

    /// Per-session reduction state for the run-activity stream. Keyed by
    /// durable session ID; reset when a new run starts for that session.
    private var runActivityReductionStates: [String: RunActivityReductionState] = [:]

    /// Fans one live, durable-ID-re-keyed chat event out to BOTH delivery
    /// surfaces: the notification brain (dedupe + banner) and the Live
    /// Activity coordinator. One surface failing must not block the other.
    func deliverLiveSurfaces(event: ChatEvent, sessionTitle: String) async {
        // Surface 1: local notifications (existing behavior, unchanged).
        await deliverLiveNotification(event: event, sessionTitle: sessionTitle)

        // Surface 2: Live Activity (local ActivityKit). Skip all reduction work
        // when the master toggle is off.
        guard notificationPreferences.liveActivitiesEnabled,
              let activeEntry = serverCatalog.activeEntry else { return }

        let sessionID = event.sessionID
        var state = runActivityReductionStates[sessionID] ?? RunActivityReductionState()
        let context = RunActivityReducerContext(
            serverID: activeEntry.id,
            profile: activeProfile,
            durableSessionID: sessionID,
            sessionTitle: sessionTitle,
            baselineMessageCount: sessions.first(where: { $0.id == sessionID })?.messageCount ?? 0,
            excerptsEnabled: notificationPreferences.liveActivityResponseExcerptsEnabled
        )
        let command = RunActivityReducer.reduce(event: event, state: &state, context: context)
        // A fresh messageStart after finalization begins a new run: reset the
        // reduction state so the next turn starts a new activity.
        if case .messageStart = event, state.finalized {
            state = RunActivityReductionState()
            _ = RunActivityReducer.reduce(event: event, state: &state, context: context)
        }
        runActivityReductionStates[sessionID] = state
        await runActivityCoordinator.apply(command)
        if state.finalized {
            runActivityReductionStates[sessionID] = nil
        }
    }

    /// Reconciles orphaned persisted Live Activities against the freshly
    /// loaded session list. Never fabricates success: unproven outcomes end as
    /// status-unavailable, and banner ownership stays with the notification
    /// reconciler. Call after sessions are loaded for the ACTIVE server.
    func reconcileRunActivities(sessionsAvailable: Bool) async {
        guard let activeEntry = serverCatalog.activeEntry else { return }
        let persisted = runActivityCoordinator.persistedOrphans()
        guard !persisted.isEmpty else { return }
        var liveOwned: Set<String> = []
        if let current = runActivityCoordinator.currentDurableSessionID() {
            liveOwned.insert(current)
        }
        let actions = RunActivityReconciler.reconcile(
            orphans: persisted,
            activeServerID: activeEntry.id,
            activeProfile: activeProfile,
            knownServerIDs: Set(serverCatalog.entries.map(\.id)),
            sessions: sessionsAvailable ? sessions : nil,
            liveOwnedSessionIDs: liveOwned,
            now: Date()
        )
        await runActivityCoordinator.applyReconcileActions(actions, orphans: persisted)
    }

    /// Silent reopen catch-up: advances dedupe watermarks from the freshly
    /// loaded session list WITHOUT posting (the user is already here).
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
        return await NotificationReconciler.deltas(
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
    }

    /// Best-effort background reconciliation (BGAppRefresh). iOS grants a short,
    /// system-chosen window: re-fetch the session list over the official REST
    /// endpoint, then let the coordinator post local notifications for any
    /// newly-completed turns it hasn't already announced. No server changes,
    /// no APNs — purely opportunistic.
    func performBackgroundReconciliation() async {
        guard let origin = serverOrigin else { return }
        notificationCoordinator.configure(origin: origin)
        // Reload the newest sessions; reuse the normal authenticated path.
        await controller.loadSessions()
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
        notificationCoordinator.configure(origin: origin)
        await controller.loadSessions()
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
        let event = ChatEvent.messageComplete(
            sessionID: "sim-test-session",
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

    func connect() {
        connectionPhase = .connecting
    }

    func disconnect() {
        connectionPhase = .disconnected
    }

    func signedOutPreservingServer(_ origin: String) {
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

    /// Clears transient connection state without touching stored credentials.
    func reset() {
        serverOrigin = nil
        hermesVersion = nil
        sessionsError = nil
        authenticationError = nil
        authProviders = []
        pendingPortalDeviceCode = nil
        connectionPhase = .disconnected
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
        connectionPhase = phase
        // Bind notification dedupe state to the live origin the moment a
        // connection is established, so live events can be deduped/persisted.
        if case .connected = phase, let origin = serverOrigin {
            configureNotifications(origin: origin)
            // A retained deep-link route (notification/Live Activity tap that
            // arrived before this server finished connecting or signing in)
            // completes now.
            completePendingRouteIfReady()
        }
    }
    func setServerOrigin(_ origin: String?) { serverOrigin = origin }
    func setHermesVersion(_ version: String?) { hermesVersion = version }
    func setSessionsError(_ message: String?) { sessionsError = message }
    func setAuthProviders(_ providers: [AuthProvider]) { authProviders = providers }
    func setAuthenticationError(_ message: String?) { authenticationError = message }
    func setPendingPortalDeviceCode(_ code: DeviceCode?) { pendingPortalDeviceCode = code }
    func setSigningIn(_ value: Bool) { isSigningIn = value }
    func setActiveProfile(_ profile: String) { activeProfile = profile }
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
