import XCTest
@testable import Mercury

@MainActor
final class PreviewRouteStartupRegressionTests: XCTestCase {
    func testManualChoicePreemptsPreviewDuringPreferenceLoad() async throws {
        try await exercise(delayedCatalog: false, manualChoice: true)
    }

    func testManualChoicePreemptsPreviewDuringRelayCatalogLoad() async throws {
        try await exercise(delayedCatalog: true, manualChoice: true)
    }

    func testCurrentPreviewStillConnectsAndOpensAuthenticatedRoute() async throws {
        try await exercise(delayedCatalog: false, manualChoice: false)
    }

    private func exercise(delayedCatalog: Bool, manualChoice: Bool) async throws {
        let defaults = UserDefaults(suiteName: "preview-startup-\(UUID())")!
        let wake = String(repeating: "w", count: 43)
        let target = RelayPairedTarget(
            id: UUID(), label: "Preview fixture", relayOrigin: "https://relay.test",
            installationID: Data(repeating: 1, count: 32), hostPublicKey: Data(repeating: 2, count: 32),
            deviceID: RelayBase64.urlSafeEncode(Data(repeating: 3, count: 16)),
            deviceStaticPrivateKey: Data(repeating: 4, count: 32), fingerprint: String(repeating: "a", count: 16),
            status: .approved, createdAtEpochSeconds: 1, lastUsedEpochSeconds: nil)
        let seed = InMemoryRelayTargetPersistence()
        try await RelayTargetStore(persistence: seed).add(target)
        let suspended = expectation(description: "preview load suspended")
        let persistence = PreviewStartupPersistence(data: seed.stored!, entered: delayedCatalog ? suspended : nil)
        let client = PreviewStartupNotificationClient(entered: delayedCatalog ? nil : suspended)
        let push = RelayPushCoordinator(defaults: defaults)
        let owner = NSObject()
        push.select(target); push.setEnabled(true); push.receivedToken(Data([1]))
        push.connected(target: target, identity: ObjectIdentifier(owner)) { method, _ in
            if method == "relay.status" { return ["capabilities": ["push_notifications_v1": true]] }
            return ["registered": true, "wake_handle": wake]
        }
        await push.waitForWork()
        XCTAssertEqual(push.target(for: wake, targets: [target])?.id, target.id)
        let preferences = NotificationPreferencesStore(userDefaults: defaults)
        preferences.save(.permissiveAll)
        let model = AppModel(
            relayTargetStore: RelayTargetStore(persistence: persistence),
            startupChoiceStore: StartupConnectionChoiceStore(persistence: MemoryStartupChoicePersistence()),
            relayPush: push, notificationPreferencesStore: preferences)
        model.injectNotificationCoordinator(NotificationCoordinator(
            client: client, store: UserDefaultsWatermarkStore(userDefaults: defaults)))
        var admissions = 0
        model.injectController(ConnectionController(
            appModel: model,
            relayProfilesClientFactory: { _, _ in ProfilesClient(rpcRequest: { _, _ in ["profiles": [["name": "default"]]] }) },
            relaySessionsPageLoader: { _, _, _, _ in
                admissions += 1
                return SessionPage(rows: [], total: 0, hasMore: false)
            }))
        let task = Task { await model.handlePushPreviewRoute(wake: wake, durableSessionID: "preview-session", profile: "default") }
        await fulfillment(of: [suspended], timeout: 3)
        if manualChoice {
            // The actual entry point for choosing a different host invalidates startup work.
            model.beginManualStartupSelection()
            model.requestOpenSession("newer-session")
        }
        let revision = model.pushHomeRevision
        let request = model.notificationOpenRequest
        persistence.release.signal()
        await client.resume()
        await task.value
        if manualChoice {
            XCTAssertEqual(admissions, 0, "A stale tap must not reconnect the notification host")
            XCTAssertNil(model.selectedRelayTarget)
            XCTAssertNil(model.activeRelayTarget)
            XCTAssertEqual(model.startupState, .onboarding)
            XCTAssertEqual(model.pushHomeRevision, revision, "A stale tap must not reset navigation")
            XCTAssertEqual(model.notificationOpenRequest, request, "A stale tap must not clear or replace a newer route")
        } else {
            XCTAssertEqual(admissions, 1)
            XCTAssertEqual(model.activeRelayTarget?.id, target.id)
            XCTAssertEqual(model.connectionPhase, .connected)
            XCTAssertNotEqual(model.pushHomeRevision, revision)
            XCTAssertEqual(model.notificationOpenRequest?.sessionID, "preview-session")
        }
        model.disconnect()
    }
}

private final class PreviewStartupPersistence: RelayTargetPersisting, @unchecked Sendable {
    let data: Data
    let entered: XCTestExpectation?
    let release = DispatchSemaphore(value: 0)
    init(data: Data, entered: XCTestExpectation?) { self.data = data; self.entered = entered }
    func readRelayTargetData() throws -> Data? {
        if let entered {
            entered.fulfill()
            guard release.wait(timeout: .now() + 5) == .success else { throw URLError(.timedOut) }
        }
        return data
    }
    func writeRelayTargetData(_ data: Data) throws {}
}

private actor PreviewStartupNotificationClient: LocalNotificationScheduling {
    let entered: XCTestExpectation?
    var continuation: CheckedContinuation<Void, Never>?
    init(entered: XCTestExpectation?) { self.entered = entered }
    func authorizationStatus() async -> MercuryNotificationAuthorizationStatus {
        if let entered { await withCheckedContinuation { continuation = $0; entered.fulfill() } }
        return .authorized
    }
    func resume() { continuation?.resume(); continuation = nil }
    func requestAuthorization() async -> Bool { true }
    func authorizationGranted() async -> Bool { true }
    func post(_ notification: PendingNotification) async {}
    func cancel(sessionID: String) async {}
}
