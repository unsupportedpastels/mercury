import Foundation
import XCTest
@testable import Mercury

final class NotificationDeliveryTests: XCTestCase {
    private let origin = "https://Mercury.example/"
    private let sessionID = "session-1"
    private let sessionTitle = "A useful session"
    private let background = SessionNotificationVisibility()

    func testWatermarkStoreRoundTripsCodableState() {
        let (_, store) = makeWatermarkStore()
        let expected = [
            sessionID: SessionWatermark(
                sessionID: sessionID,
                lastCompletedTurnSignature: "turn-1",
                lastMessageCount: 2,
                hasOpenApproval: true,
                hasOpenClarify: false,
                hasOpenSecure: true
            )
        ]

        store.save(expected, origin: origin)

        XCTAssertEqual(store.load(origin: "https://mercury.example"), expected)
    }

    func testWatermarkStoreIsolatesNormalizedOrigins() {
        let (_, store) = makeWatermarkStore()
        let first = [sessionID: SessionWatermark(sessionID: sessionID)]
        let second = ["session-2": SessionWatermark(sessionID: "session-2")]

        store.save(first, origin: "HTTPS://one.example/")
        store.save(second, origin: "https://two.example")

        XCTAssertEqual(store.load(origin: "https://one.example"), first)
        XCTAssertEqual(store.load(origin: "https://two.example/"), second)
        XCTAssertTrue(store.load(origin: "https://three.example").isEmpty)
    }

    func testWatermarkStoreClearOnlyRemovesRequestedOrigin() {
        let (_, store) = makeWatermarkStore()
        let first = [sessionID: SessionWatermark(sessionID: sessionID)]
        let second = ["session-2": SessionWatermark(sessionID: "session-2")]
        store.save(first, origin: "https://one.example")
        store.save(second, origin: "https://two.example")

        store.clear(origin: "HTTPS://one.example/")

        XCTAssertTrue(store.load(origin: "https://one.example").isEmpty)
        XCTAssertEqual(store.load(origin: "https://two.example"), second)
    }

    func testWatermarkStoreInvalidAndUnknownOriginsLoadEmpty() {
        let (_, store) = makeWatermarkStore()
        let state = [sessionID: SessionWatermark(sessionID: sessionID)]
        store.save(state, origin: "https://one.example")

        XCTAssertTrue(store.load(origin: "not a valid origin").isEmpty)
        XCTAssertTrue(store.load(origin: "https://unknown.example").isEmpty)
    }

    func testWatermarkStoreMalformedJSONLoadsEmpty() {
        let (defaults, store) = makeWatermarkStore()
        defaults.set(Data("not-json".utf8), forKey: "mercury.notif.watermarks.https://one.example")

        XCTAssertTrue(store.load(origin: "https://one.example").isEmpty)
    }

    func testNotificationIdentifierHelpers() {
        XCTAssertEqual(
            NotificationIdentifier.inputKeys(sessionID: sessionID),
            ["session-1|approval", "session-1|clarification", "session-1|secure"]
        )
        XCTAssertTrue(NotificationIdentifier.belongs(identifier: "session-1|completion|turn-1", toSession: sessionID))
        XCTAssertTrue(NotificationIdentifier.belongs(identifier: "session-1|approval", toSession: sessionID))
        XCTAssertFalse(NotificationIdentifier.belongs(identifier: "session-10|approval", toSession: sessionID))
        XCTAssertFalse(NotificationIdentifier.belongs(identifier: "other|completion|turn-1", toSession: sessionID))
        XCTAssertFalse(NotificationIdentifier.belongs(identifier: "|approval", toSession: ""))
    }

    func testBackgroundRefreshEarliestBeginDateHelper() {
        let now = Date(timeIntervalSince1970: 1_000)

        XCTAssertEqual(
            BackgroundReconciliationScheduler.earliestBeginDate(now: now, seconds: 900),
            Date(timeIntervalSince1970: 1_900)
        )
    }

    @MainActor
    func testCoordinatorPostsBackgroundCompletionOnceAcrossReload() async {
        let client = FakeLocalNotificationScheduling()
        let store = InMemoryWatermarkStore()
        let event = complete()

        let firstCoordinator = NotificationCoordinator(client: client, store: store)
        firstCoordinator.configure(origin: origin)
        await firstCoordinator.handleLive(event: event, sessionTitle: sessionTitle, visibility: background)

        let reloadedCoordinator = NotificationCoordinator(client: client, store: store)
        reloadedCoordinator.configure(origin: origin)
        await reloadedCoordinator.handleLive(event: event, sessionTitle: sessionTitle, visibility: background)

        XCTAssertEqual(client.posts.count, 1)
        XCTAssertEqual(store.load(origin: origin)[sessionID]?.lastMessageCount, 1)
    }

    @MainActor
    func testCoordinatorSuppressesVisibleForegroundCompletionButPersistsWatermark() async {
        let client = FakeLocalNotificationScheduling()
        let store = InMemoryWatermarkStore()
        let coordinator = NotificationCoordinator(client: client, store: store)
        let visible = SessionNotificationVisibility(appForeground: true, visibleSessionID: sessionID)
        coordinator.configure(origin: origin)

        await coordinator.handleLive(event: complete(), sessionTitle: sessionTitle, visibility: visible)

        XCTAssertTrue(client.posts.isEmpty)
        XCTAssertEqual(store.load(origin: origin)[sessionID]?.lastMessageCount, 1)
    }

    @MainActor
    func testCoordinatorAdvancesWatermarkWhenAuthorizationDenied() async {
        let client = FakeLocalNotificationScheduling(authorized: false)
        let store = InMemoryWatermarkStore()
        let coordinator = NotificationCoordinator(client: client, store: store)
        coordinator.configure(origin: origin)

        await coordinator.handleLive(event: approval(), sessionTitle: sessionTitle, visibility: background)

        XCTAssertTrue(client.posts.isEmpty)
        XCTAssertTrue(store.load(origin: origin)[sessionID]?.hasOpenApproval == true)
    }

    @MainActor
    func testCoordinatorReconcileIsIdempotent() async {
        let client = FakeLocalNotificationScheduling()
        let store = InMemoryWatermarkStore()
        let coordinator = NotificationCoordinator(client: client, store: store)
        coordinator.configure(origin: origin)
        let delta = completionDelta()

        await coordinator.handleReconcile(deltas: [delta], visibility: background)
        await coordinator.handleReconcile(deltas: [delta], visibility: background)

        XCTAssertEqual(client.posts.count, 1)
        XCTAssertEqual(store.load(origin: origin)[sessionID]?.lastCompletedTurnSignature, "turn-1")
    }

    @MainActor
    func testCoordinatorResetClearsPersistedOriginState() async {
        let client = FakeLocalNotificationScheduling()
        let store = InMemoryWatermarkStore()
        let coordinator = NotificationCoordinator(client: client, store: store)
        coordinator.configure(origin: origin)
        await coordinator.handleLive(event: complete(), sessionTitle: sessionTitle, visibility: background)

        coordinator.reset(origin: origin)

        XCTAssertTrue(store.load(origin: origin).isEmpty)
        let reloaded = NotificationCoordinator(client: client, store: store)
        reloaded.configure(origin: origin)
        await reloaded.handleLive(event: complete(), sessionTitle: sessionTitle, visibility: background)
        XCTAssertEqual(client.posts.count, 2)
    }

    private func complete(
        sessionID: String = "session-1",
        text: String? = "The response is complete.",
        status: String? = nil
    ) -> ChatEvent {
        .messageComplete(
            sessionID: sessionID,
            text: text,
            status: status,
            error: nil,
            reasoning: nil,
            warning: nil,
            failureReason: nil,
            recoverable: false,
            billing: nil
        )
    }

    private func approval() -> ChatEvent {
        .approvalRequest(
            sessionID: sessionID,
            requestID: "approval-1",
            command: "do-the-thing",
            description: "Allow this command?",
            choices: ["allow", "deny"]
        )
    }

    private func completionDelta() -> ReconciliationDelta {
        ReconciliationDelta(
            sessionID: sessionID,
            sessionTitle: sessionTitle,
            serverMessageCount: 2,
            newCompletion: CompletionOutcome(text: "done", status: .finished, turnSignature: "turn-1"),
            openedApproval: false,
            openedClarify: false,
            openedSecure: false
        )
    }

    private func makeWatermarkStore() -> (UserDefaults, UserDefaultsWatermarkStore) {
        let suiteName = UUID().uuidString
        let defaults = UserDefaults(suiteName: suiteName)!
        return (defaults, UserDefaultsWatermarkStore(userDefaults: defaults))
    }
}

private final class FakeLocalNotificationScheduling: LocalNotificationScheduling, @unchecked Sendable {
    var authorized: Bool
    var posts: [PendingNotification] = []
    var cancellations: [String] = []

    init(authorized: Bool = true) {
        self.authorized = authorized
    }

    func requestAuthorization() async -> Bool { authorized }
    func authorizationGranted() async -> Bool { authorized }
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus {
        authorized ? .authorized : .denied
    }

    func post(_ notification: PendingNotification) async {
        posts.append(notification)
    }

    func cancel(sessionID: String) async {
        cancellations.append(sessionID)
    }
}

private final class InMemoryWatermarkStore: WatermarkStoring, @unchecked Sendable {
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
