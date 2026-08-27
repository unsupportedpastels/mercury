import Foundation
import XCTest
@testable import Mercury

final class NotificationPreferencesTests: XCTestCase {
    func testMigrationRunsOnceAndPersistsAuthorizedCompatibilityDefaults() async {
        let defaults = makeDefaults()
        let store = NotificationPreferencesStore(userDefaults: defaults)

        let first = await store.migrateIfNeeded(systemStatus: .authorized)
        let second = await store.migrateIfNeeded(systemStatus: .denied)

        XCTAssertEqual(first, second)
        XCTAssertTrue(first.notificationsEnabled)
        XCTAssertTrue(first.completionEnabled)
        XCTAssertTrue(first.attentionEnabled)
        XCTAssertTrue(first.failureAndCancellationEnabled)
        XCTAssertFalse(first.liveActivitiesEnabled)
        XCTAssertFalse(first.liveActivityResponseExcerptsEnabled)
        XCTAssertTrue(store.hasStoredPreferences)
    }

    func testDeniedAndNotDeterminedNeverMigrateToEnabled() async {
        for status in [
            MercuryNotificationAuthorizationStatus.denied,
            MercuryNotificationAuthorizationStatus.notDetermined
        ] {
            let store = NotificationPreferencesStore(userDefaults: makeDefaults())
            let migrated = await store.migrateIfNeeded(systemStatus: status)

            XCTAssertEqual(migrated, .newInstallDefaults)
            XCTAssertFalse(migrated.notificationsEnabled)
            XCTAssertFalse(migrated.completionEnabled)
            XCTAssertFalse(migrated.attentionEnabled)
            XCTAssertFalse(migrated.failureAndCancellationEnabled)
        }
    }

    func testCorruptPayloadLoadsNewInstallDefaults() {
        let defaults = makeDefaults()
        defaults.set(Data("not-json".utf8), forKey: NotificationPreferencesStore.storageKey)
        let store = NotificationPreferencesStore(userDefaults: defaults)

        XCTAssertEqual(store.load(), .newInstallDefaults)
        XCTAssertTrue(store.hasStoredPreferences)
    }

    func testOversizedPayloadLoadsNewInstallDefaults() {
        let defaults = makeDefaults()
        defaults.set(
            Data(repeating: 0x20, count: 4 * 1024 + 1),
            forKey: NotificationPreferencesStore.storageKey
        )
        let store = NotificationPreferencesStore(userDefaults: defaults)

        XCTAssertEqual(store.load(), .newInstallDefaults)
    }

    func testNormalizedClearsResponseExcerptsWithoutLiveActivities() {
        let preferences = MercuryNotificationPreferences(
            liveActivitiesEnabled: false,
            liveActivityResponseExcerptsEnabled: true
        )

        let normalized = preferences.normalized()

        XCTAssertFalse(normalized.liveActivityResponseExcerptsEnabled)
        XCTAssertTrue(preferences.liveActivityResponseExcerptsEnabled)
    }

    func testLoadAndSaveNormalizeResponseExcerpts() {
        let defaults = makeDefaults()
        let store = NotificationPreferencesStore(userDefaults: defaults)
        let preferences = MercuryNotificationPreferences(
            liveActivitiesEnabled: false,
            liveActivityResponseExcerptsEnabled: true
        )

        store.save(preferences)

        XCTAssertFalse(store.load().liveActivityResponseExcerptsEnabled)
    }

    @MainActor
    func testCategoryFilterSuppressesCompletionButAdvancesWatermark() async {
        let client = PreferencesTestNotificationClient()
        let store = PreferencesTestWatermarkStore()
        let preferences = MercuryNotificationPreferences(
            notificationsEnabled: true,
            completionEnabled: false,
            attentionEnabled: true,
            failureAndCancellationEnabled: true
        )
        let coordinator = NotificationCoordinator(
            client: client,
            store: store,
            preferencesProvider: { preferences }
        )
        coordinator.configure(origin: "https://mercury.example")

        let delta = ReconciliationDelta(
            sessionID: "session-1",
            sessionTitle: "A session",
            serverMessageCount: 2,
            newCompletion: CompletionOutcome(
                text: "finished",
                status: .finished,
                turnSignature: "turn-1"
            ),
            openedApproval: false,
            openedClarify: false,
            openedSecure: false
        )

        await coordinator.handleReconcile(deltas: [delta], visibility: SessionNotificationVisibility())
        await coordinator.handleReconcile(deltas: [delta], visibility: SessionNotificationVisibility())

        XCTAssertTrue(client.posts.isEmpty)
        XCTAssertEqual(
            store.load(origin: "https://mercury.example")["session-1"]?.lastCompletedTurnSignature,
            "turn-1"
        )
        XCTAssertEqual(store.load(origin: "https://mercury.example")["session-1"]?.lastMessageCount, 1)
    }

    func testLiveActivitiesRemainIndependentOfMasterNotifications() {
        let store = NotificationPreferencesStore(userDefaults: makeDefaults())
        store.save(
            MercuryNotificationPreferences(
                notificationsEnabled: false,
                liveActivitiesEnabled: true,
                liveActivityResponseExcerptsEnabled: true
            )
        )

        let loaded = store.load()

        XCTAssertFalse(loaded.notificationsEnabled)
        XCTAssertTrue(loaded.liveActivitiesEnabled)
        XCTAssertTrue(loaded.liveActivityResponseExcerptsEnabled)
    }

    private func makeDefaults() -> UserDefaults {
        let suiteName = "NotificationPreferencesTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

private final class PreferencesTestNotificationClient: LocalNotificationScheduling, @unchecked Sendable {
    var posts: [PendingNotification] = []

    func requestAuthorization() async -> Bool { true }
    func authorizationGranted() async -> Bool { true }
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus { .authorized }

    func post(_ notification: PendingNotification) async {
        posts.append(notification)
    }

    func cancel(sessionID: String) async {}
}

private final class PreferencesTestWatermarkStore: WatermarkStoring, @unchecked Sendable {
    private var values: [String: [String: SessionWatermark]] = [:]

    func load(origin: String) -> [String: SessionWatermark] {
        values[ServerOrigin.normalize(origin) ?? origin] ?? [:]
    }

    func save(_ watermarks: [String: SessionWatermark], origin: String) {
        values[ServerOrigin.normalize(origin) ?? origin] = watermarks
    }

    func clear(origin: String) {
        values.removeValue(forKey: ServerOrigin.normalize(origin) ?? origin)
    }
}
