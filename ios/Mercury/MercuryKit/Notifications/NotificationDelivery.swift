import Foundation

@MainActor
final class NotificationCoordinator {
    private let client: LocalNotificationScheduling
    private let store: WatermarkStoring
    /// App wiring replaces the permissive compatibility default with the
    /// device-wide persisted preferences provider. Invoked only on the main
    /// actor (this class is @MainActor), so it may read main-actor state.
    var preferencesProvider: () -> MercuryNotificationPreferences
    /// Multi-server route context for notification taps: the active server's
    /// local catalog UUID + profile. When set, each posted notification embeds
    /// a canonical mercury://session route so a tap can cross server/profile
    /// boundaries. Nil (e.g. legacy call sites/tests) falls back to the plain
    /// same-server session-ID deep link.
    var routeContext: (serverID: UUID, profile: String)?
    private var currentOrigin: String?
    private var watermarks: [String: SessionWatermark] = [:]

    init(
        client: LocalNotificationScheduling,
        store: WatermarkStoring,
        preferencesProvider: @escaping @Sendable () -> MercuryNotificationPreferences = {
            MercuryNotificationPreferences.permissiveAll
        }
    ) {
        self.client = client
        self.store = store
        self.preferencesProvider = preferencesProvider
    }

    func configure(origin: String) {
        guard let normalized = ServerOrigin.normalize(origin) else {
            currentOrigin = nil
            watermarks = [:]
            return
        }

        currentOrigin = normalized
        watermarks = store.load(origin: normalized)
    }

    /// Snapshot of the current per-session watermarks, so the reconciler's
    /// advance-detection can read `lastServerMessageCount` before building
    /// deltas. Read-only; mutation stays inside the coordinator.
    func currentWatermarks() -> [String: SessionWatermark] {
        watermarks
    }

    /// Requests local-notification authorization (system prompt on first call).
    func requestAuthorization() async -> Bool {
        await client.requestAuthorization()
    }

    /// Whether the app currently holds notification authorization.
    func authorizationGranted() async -> Bool {
        await client.authorizationGranted()
    }

    /// Full system authorization status, for Settings display and migration.
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus {
        await client.authorizationStatus()
    }

    func handleLive(
        event: ChatEvent,
        sessionTitle: String,
        visibility: SessionNotificationVisibility
    ) async {
        guard currentOrigin != nil else {
            return
        }

        var watermark = watermarks[event.sessionID]
            ?? SessionWatermark(sessionID: event.sessionID)
        let notification = NotificationDecisionReducer.decide(
            event: event,
            sessionTitle: sessionTitle,
            visibility: visibility,
            watermark: &watermark
        )
        watermarks[event.sessionID] = watermark
        persist()

        await postIfAuthorized(notification.map { [$0] } ?? [])
    }

    func handleReconcile(
        deltas: [ReconciliationDelta],
        visibility: SessionNotificationVisibility
    ) async {
        guard currentOrigin != nil else {
            return
        }

        let notifications = ReconciliationEngine.reconcile(
            deltas: deltas,
            visibility: visibility,
            watermarks: &watermarks
        )
        persist()

        await postIfAuthorized(notifications)
    }

    /// Silently advances watermarks for the given deltas WITHOUT posting.
    ///
    /// Used on app foreground/reopen: the user is opening Mercury, so a
    /// catch-up banner is pointless and risks double-notifying a turn the
    /// live socket already announced. Advancing the dedupe state here keeps a
    /// later background reconcile from re-firing the same completions.
    func catchUp(
        deltas: [ReconciliationDelta],
        visibility: SessionNotificationVisibility
    ) {
        guard currentOrigin != nil else {
            return
        }

        _ = ReconciliationEngine.reconcile(
            deltas: deltas,
            visibility: visibility,
            watermarks: &watermarks
        )
        persist()
    }

    func clearSession(sessionID: String) async {
        await client.cancel(sessionID: sessionID)
    }

    func reset(origin: String) {
        store.clear(origin: origin)
        currentOrigin = nil
        watermarks = [:]
    }

    private func persist() {
        guard let currentOrigin else {
            return
        }
        store.save(watermarks, origin: currentOrigin)
    }

    private func postIfAuthorized(_ notifications: [PendingNotification]) async {
        guard !notifications.isEmpty else {
            return
        }
        guard await client.authorizationGranted() else {
            return
        }

        for notification in notifications {
            let preferences = preferencesProvider().normalized()
            guard preferences.notificationsEnabled,
                  shouldPost(notification, preferences: preferences)
            else {
                continue
            }
            let route = routeContext.map {
                SessionOpenRoute(
                    durableSessionID: notification.sessionID,
                    serverID: $0.serverID,
                    profile: $0.profile
                )
            }
            await client.post(notification, route: route)
        }
    }

    private func shouldPost(
        _ notification: PendingNotification,
        preferences: MercuryNotificationPreferences
    ) -> Bool {
        switch notification.kind {
        case .completion(let status):
            switch status {
            case .finished:
                return preferences.completionEnabled
            case .failed, .cancelled:
                return preferences.failureAndCancellationEnabled
            }
        case .approval, .clarification, .secureInput:
            return preferences.attentionEnabled
        }
    }
}
