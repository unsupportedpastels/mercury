import XCTest
import UserNotifications
import MercuryNotificationPreviewKit
@testable import Mercury

@MainActor
final class NotificationArrivalTests: XCTestCase {
    func testPendingAuthorizationKeepsOriginalRouteAcrossSelectionSwitch() async {
        let client = PausedNotificationClient()
        let defaults = UserDefaults(suiteName: UUID().uuidString)!
        let coordinator = NotificationCoordinator(client: client, store: UserDefaultsWatermarkStore(userDefaults: defaults))
        let serverA = UUID(), serverB = UUID()
        coordinator.configure(origin: "https://one.example")
        coordinator.routeContext = (serverA, "default")
        let task = Task { await coordinator.handleLive(event: Self.complete("session"), sessionTitle: "Fixture", visibility: .init()) }
        await client.waitUntilAuthorization()
        coordinator.configure(origin: "https://two.example")
        coordinator.routeContext = (serverB, "other")
        client.releaseAuthorization()
        await task.value
        XCTAssertEqual(client.routes, [SessionOpenRoute(durableSessionID: "session", serverID: serverA, profile: "default")])
    }

    func testArrivalSuppressesOnlyExactVisibleScopeAndUsesCurrentState() {
        let model = AppModel()
        model.setServerOrigin("HTTPS://one.example/")
        model.setVisibleSession("session")
        model.setAppForeground(true)
        let scope = NotificationSourceScope(origin: "https://one.example", profile: "default")!
        let same = NotificationSessionIdentity(scope: scope, sessionID: "session")
        let delegate = NotificationDelegate(previewRoutes: nil)
        delegate.shouldPresent = { model.shouldPresentNotification($0) }
        func options(_ identity: NotificationSessionIdentity) -> UNNotificationPresentationOptions {
            delegate.optionsForArrival(userInfo: [NotificationSessionIdentity.payloadKey: identity.payload!], isRemote: false)
        }
        XCTAssertEqual(options(same), []) // banner, sound AND list must be absent
        XCTAssertEqual(options(.init(scope: scope, sessionID: "other")), [.banner, .sound, .list])
        XCTAssertEqual(options(.init(scope: NotificationSourceScope(origin: "https://two.example", profile: "default")!, sessionID: "session")), [.banner, .sound, .list])
        XCTAssertEqual(options(.init(scope: NotificationSourceScope(origin: "https://one.example", profile: "other")!, sessionID: "session")), [.banner, .sound, .list])
        XCTAssertEqual(options(.init(scope: NotificationSourceScope(origin: "https://one.example", relayTargetID: UUID(), profile: "default")!, sessionID: "session")), [.banner, .sound, .list])
        model.setAppForeground(false) // selected chat behind background/lock
        XCTAssertEqual(options(same), [.banner, .sound, .list])
        model.setAppForeground(true)
        model.setVisibleSession("other") // selection changed after enqueue
        XCTAssertEqual(options(same), [.banner, .sound, .list])
        model.setVisibleSession("session")
        XCTAssertEqual(options(same), [])
        model.setActiveProfile("other") // stale selected id is not visibility on new profile
        XCTAssertEqual(options(same), [.banner, .sound, .list])
    }

    func testLateDisappearanceAndConnectionCannotReplaceCurrentVisibility() {
        let model = AppModel()
        model.setServerOrigin("https://one.example")
        let scope = model.notificationSourceScope!
        let first = UUID(), second = UUID()
        model.showNotificationSession("first", scope: scope, owner: first)
        model.showNotificationSession("second", scope: scope, owner: second)
        model.hideNotificationSession(owner: first)
        model.updateNotificationSession("first-durable", owner: first)
        XCTAssertEqual(model.visibleSessionID, "second")
        model.hideNotificationSession(owner: second)
        model.updateNotificationSession("second-durable", owner: second)
        XCTAssertNil(model.visibleSessionID)
        model.showNotificationSession(nil, scope: scope, owner: first)
        model.updateNotificationSession("created-durable", owner: first)
        XCTAssertEqual(model.visibleSessionID, "created-durable")
    }

    func testAuthenticatedPreviewArrivalDoesNotConsumeTapReceiptOrTrustRawRoutes() {
        let now = Int64(Date().timeIntervalSince1970)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let store = PreviewRouteStore(url: url)
        let wake = PushPreviewEnvelope.encode(Data(repeating: 1, count: 32))
        let event = PushPreviewEnvelope.encode(Data(repeating: 2, count: 32))
        let record = PreviewRouteRecord(event: event, wake: wake, sessionID: "session", profile: "default", expiresAt: now + 60)
        let scope = NotificationSourceScope(origin: "https://relay.example", relayTargetID: UUID(), profile: "default")!
        let identity = NotificationSessionIdentity(scope: scope, sessionID: "session")
        let delegate = NotificationDelegate(previewRoutes: store)
        delegate.previewIdentity = { route in route == record ? identity : nil }
        delegate.shouldPresent = { $0 != identity }
        let payload: [AnyHashable: Any] = ["mercury_wake": wake, "mercury_event": event,
            "mercury.preview.sid": "session", "mercury.preview.profile": "default",
            NotificationSessionIdentity.payloadKey: identity.payload!]
        XCTAssertEqual(delegate.optionsForArrival(userInfo: payload, isRemote: true), [.banner, .sound, .list])
        store.record(record, now: now)
        XCTAssertEqual(delegate.optionsForArrival(userInfo: payload, isRemote: true), [])
        XCTAssertEqual(delegate.optionsForArrival(userInfo: payload, isRemote: true), [])
        XCTAssertEqual(store.consume(event: event, wake: wake, now: now), record)
        XCTAssertNil(store.consume(event: event, wake: wake, now: now))
        XCTAssertEqual(delegate.optionsForArrival(userInfo: payload, isRemote: true), [.banner, .sound, .list])
    }

    func testPendingAuthorizationRechecksVisibilityAndDoesNotReplaySuppressedCompletion() async {
        let model = AppModel()
        model.setServerOrigin("https://one.example")
        let client = PausedNotificationClient()
        let coordinator = NotificationCoordinator(client: client, store: UserDefaultsWatermarkStore(userDefaults: UserDefaults(suiteName: UUID().uuidString)!))
        coordinator.configure(origin: "https://one.example")
        coordinator.sourceScope = model.notificationSourceScope
        coordinator.shouldDeliver = { model.shouldPresentNotification($0) }
        let task = Task { await coordinator.handleLive(event: Self.complete("session"), sessionTitle: "Fixture", visibility: .init()) }
        await client.waitUntilAuthorization()
        model.setVisibleSession("session")
        client.releaseAuthorization()
        await task.value
        XCTAssertTrue(client.routes.isEmpty)
        model.setVisibleSession(nil)
        await coordinator.handleLive(event: Self.complete("session"), sessionTitle: "Fixture", visibility: .init())
        XCTAssertTrue(client.routes.isEmpty)
    }

    func testRemoteInspectionRechecksVisibilityAfterAwaitAndRejectsUntrustedRoutes() async {
        let model = AppModel()
        model.setServerOrigin("https://one.example")
        let owner = UUID()
        let scope = model.notificationSourceScope!
        model.showNotificationSession("first", scope: scope, owner: owner)
        let identity = NotificationSessionIdentity(scope: scope, sessionID: "first")
        let delegate = NotificationDelegate(previewRoutes: nil)
        model.configureNotificationPresentation(delegate)
        let started = expectation(description: "authenticated inspection started")
        var release: CheckedContinuation<NotificationSessionIdentity?, Never>?
        delegate.remoteIdentity = { _, _ in
            await withCheckedContinuation { continuation in release = continuation; started.fulfill() }
        }
        let payload: [AnyHashable: Any] = ["mercury_wake": PushPreviewEnvelope.encode(Data(repeating: 1, count: 32)),
            "mercury_event": PushPreviewEnvelope.encode(Data(repeating: 2, count: 32)),
            "mercury.preview.sid": "first", NotificationSessionIdentity.payloadKey: identity.payload!]
        let arrival = Task { await delegate.optionsForRemoteArrival(userInfo: payload, isRemote: true) }
        await fulfillment(of: [started], timeout: 2)
        model.showNotificationSession("second", scope: scope, owner: UUID())
        release?.resume(returning: identity)
        let switched = await arrival.value
        XCTAssertEqual(switched, [.banner, .sound, .list])
        delegate.remoteIdentity = { _, _ in identity }
        model.showNotificationSession("first", scope: scope, owner: owner)
        let current = await delegate.optionsForRemoteArrival(userInfo: payload, isRemote: true)
        XCTAssertEqual(current, [])
        delegate.remoteIdentity = { _, _ in nil }
        let unauthenticated = await delegate.optionsForRemoteArrival(userInfo: payload, isRemote: true)
        XCTAssertEqual(unauthenticated, [.banner, .sound, .list])
        let missingEvent = await delegate.optionsForRemoteArrival(userInfo: ["mercury_wake": payload["mercury_wake"]!, "mercury.route": "ignored"], isRemote: true)
        XCTAssertEqual(missingEvent, [.banner, .sound, .list])
    }

    static func complete(_ sid: String) -> ChatEvent {
        .messageComplete(sessionID: sid, text: "Synthetic completion", status: "finished", error: nil, reasoning: nil, warning: nil, failureReason: nil, recoverable: false, billing: nil)
    }
}

private final class PausedNotificationClient: LocalNotificationScheduling, @unchecked Sendable {
    private var continuation: CheckedContinuation<Bool, Never>?
    private var entered: CheckedContinuation<Void, Never>?
    private var reached = false
    var routes: [SessionOpenRoute?] = []
    func requestAuthorization() async -> Bool { true }
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus { .authorized }
    func authorizationGranted() async -> Bool {
        await withCheckedContinuation { continuation = $0; reached = true; entered?.resume(); entered = nil }
    }
    func waitUntilAuthorization() async {
        if reached { return }
        await withCheckedContinuation { entered = $0 }
    }
    func releaseAuthorization() { continuation?.resume(returning: true); continuation = nil }
    func post(_ notification: PendingNotification) async { }
    func post(_ notification: PendingNotification, route: SessionOpenRoute?) async { routes.append(route) }
    func cancel(sessionID: String) async { }
}
