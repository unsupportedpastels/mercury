import Foundation
import XCTest
@testable import Mercury

@MainActor
final class ProfileDiscoveryTests: XCTestCase {
    private let directOrigin = "https://profiles-hermes.test"

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
    }

    override func tearDown() {
        MockURLProtocol.reset()
        super.tearDown()
    }

    // MARK: Direct discovery

    func testChangingProfileClearsPreviousRowsBeforeLoadingReplacement() {
        let model = makeModel(session: makeSession())
        model.sessions = [SessionRow(id: "previous-profile", profile: "default")]
        model.setCanLoadMoreSessions(true)
        model.setIsLoadingMoreSessions(true)
        model.setActiveProfile("work")
        XCTAssertTrue(model.sessions.isEmpty)
        XCTAssertFalse(model.canLoadMoreSessions)
        XCTAssertFalse(model.isLoadingMoreSessions)
    }

    func testDirectAuthDiscoversRestNamesAndKeepsAuthenticatedConnectionOnProfileError() async {
        MockURLProtocol.handler = { request in
            let body: String
            let status: Int
            switch request.url?.path {
            case "/api/status":
                status = 200
                body = #"{"version":"test","auth_required":false}"#
            case "/api/profiles":
                status = 200
                body = #"{"profiles":[{"name":"default"},{"name":" work "},{"name":"work"},{"name":"../invalid"},{"name":""},{"name":"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}]}"#
            default:
                throw URLError(.unsupportedURL)
            }
            return (
                HTTPURLResponse(
                    url: request.url!,
                    statusCode: status,
                    httpVersion: nil,
                    headerFields: ["Content-Type": "application/json"]
                )!,
                Data(body.utf8)
            )
        }

        let session = makeSession()
        let client = ProfilesClient(
            httpClient: HermesHTTPClient(origin: directOrigin, session: HermesURLSession.make(session.configuration))
        )
        let model = makeModel(
            session: session,
            directProfilesClientFactory: { _ in client }
        )

        await model.controller.probeSelfHosted(origin: directOrigin)

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertEqual(model.profiles, ["default", "work", "../invalid"])

        // A later unsupported/error response is a catalog fallback, not an
        // authentication failure and never resurrects an older host catalog.
        let errorClient = FakeProfilesListing(error: ProfilesClientError.unsupported)
        let errorModel = makeModel(
            session: makeSession(),
            directProfilesClientFactory: { _ in errorClient }
        )
        MockURLProtocol.handler = { request in
            guard request.url?.path == "/api/status" else { throw URLError(.unsupportedURL) }
            return (
                HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!,
                Data(#"{"version":"test","auth_required":false}"#.utf8)
            )
        }

        await errorModel.controller.probeSelfHosted(origin: directOrigin)

        XCTAssertEqual(errorModel.connectionPhase, .connected)
        XCTAssertEqual(errorModel.profiles, ["default"])
    }

    // MARK: Relay discovery

    func testBootstrapRestoresSavedRelayInsteadOfConfiguredDirectServer() async throws {
        let catalog = ServerCatalogStore(persistence: ProfileTestCatalogPersistence(), legacyOrigin: nil)
        _ = try await catalog.add(origin: directOrigin, label: "Direct")
        let target = makeRelayTarget()
        let relays = RelayTargetStore(persistence: InMemoryRelayTargetPersistence())
        try await relays.add(target)
        let choices = StartupConnectionChoiceStore(persistence: MemoryStartupChoicePersistence())
        let savedChoice = StartupConnectionIdentity(kind: .relay, id: target.id)
        try await choices.save(savedChoice)
        let model = makeModel(
            session: makeSession(),
            catalogStore: catalog,
            relayTargetStore: relays,
            startupChoiceStore: choices,
            relayProfilesClientFactory: { _, _ in FakeProfilesListing(names: ["default", "work"]) },
            relaySessionsPageLoader: { _, _, _, _ in
                SessionPage(rows: [SessionRow(id: "restored-relay")], total: 1, hasMore: false)
            }
        )

        await model.bootstrapSavedServer()

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertEqual(model.activeRelayTarget?.id, target.id)
        XCTAssertNil(model.serverOrigin)
        XCTAssertEqual(model.startupLastSuccessfulChoice, savedChoice)
        XCTAssertEqual(model.sessions.map(\.id), ["restored-relay"])
        model.disconnect()
    }

    func testRelayDiscoveryUsesOfficialProfilesListAndSafeRpcAdmissionNames() async throws {
        var requestedMethod: String?
        var requestedParams: [String: Any]?
        let client = ProfilesClient(rpcRequest: { method, params in
            requestedMethod = method
            requestedParams = params
            return [
                "profiles": [
                    ["name": "default"],
                    ["name": "work"],
                    ["name": "work"],
                    ["name": "../invalid"],
                    ["name": "_invalid"],
                    ["name": "Work"],
                    ["name": "safe_name"],
                ],
            ]
        })
        let target = makeRelayTarget()
        let relayStore = RelayTargetStore(persistence: InMemoryRelayTargetPersistence())
        try await relayStore.add(target)
        let model = makeModel(
            session: makeSession(),
            relayTargetStore: relayStore,
            relayProfilesClientFactory: { _, _ in client },
            relaySessionsPageLoader: { _, _, _, _ in
                SessionPage(
                    rows: [SessionRow(id: "relay-session", profile: "work")],
                    total: 1,
                    hasMore: false
                )
            }
        )

        await model.connectRelay(target)

        XCTAssertEqual(requestedMethod, "profiles.list")
        XCTAssertEqual(requestedParams?["include_sessions"] as? Bool, false)
        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertEqual(model.activeRelayTarget?.id, target.id)
        XCTAssertEqual(model.profiles, ["default", "work", "safe_name"])
        XCTAssertEqual(model.sessions.map(\.id), ["relay-session"])

        model.disconnect()
    }

    func testUnsupportedRelayProfilesDoNotDropAuthenticatedConnection() async throws {
        let client = ProfilesClient(rpcRequest: { _, _ in
            throw ChatMethodNotFoundError(method: "profiles.list")
        })
        let target = makeRelayTarget()
        let relayStore = RelayTargetStore(persistence: InMemoryRelayTargetPersistence())
        try await relayStore.add(target)
        let model = makeModel(
            session: makeSession(),
            relayTargetStore: relayStore,
            relayProfilesClientFactory: { _, _ in client },
            relaySessionsPageLoader: { _, _, _, _ in
                SessionPage(
                    rows: [SessionRow(id: "relay-session")],
                    total: 1,
                    hasMore: false
                )
            }
        )

        await model.connectRelay(target)

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertEqual(model.profiles, ["default"])
        XCTAssertEqual(model.sessions.map(\.id), ["relay-session"])

        model.disconnect()
    }

    // MARK: Stale asynchronous work

    func testStalePreviousHostProfileResponseCannotOverwriteCurrentCatalog() async {
        let oldHost = "https://old-profiles.test"
        let newHost = "https://new-profiles.test"
        let oldClient = DeferredProfilesListing()
        let newClient = FakeProfilesListing(names: ["default", "new-host"])
        let model = makeModel(
            session: makeSession(),
            directProfilesClientFactory: { origin in
                if origin == oldHost { return oldClient }
                return newClient
            }
        )
        MockURLProtocol.handler = { request in
            guard request.url?.path == "/api/status" else { throw URLError(.unsupportedURL) }
            return (
                HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!,
                Data(#"{"version":"test","auth_required":false}"#.utf8)
            )
        }

        let oldProbe = Task { @MainActor in
            await model.controller.probeSelfHosted(origin: oldHost)
        }
        var oldRequested = false
        for _ in 0..<100 where !oldRequested {
            oldRequested = await oldClient.requested
            if !oldRequested { await Task.yield() }
        }
        XCTAssertTrue(oldRequested)

        await model.controller.probeSelfHosted(origin: newHost)
        XCTAssertEqual(model.profiles, ["default", "new-host"])

        await oldClient.release(["default", "old-host"])
        await oldProbe.value

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertEqual(model.profiles, ["default", "new-host"])
    }

    func testStaleProfileSessionRefreshCannotMixRowsAfterProfileSwitch() async {
        let pages = DeferredSessionPages()
        let model = makeModel(
            session: makeSession(),
            sessionPageLoader: { _, profile, _, _ in
                try await pages.load(profile: profile)
            }
        )
        model.setServerOrigin(directOrigin)
        model.setPhase(.connected)

        let oldRefresh = Task { @MainActor in
            await model.controller.refreshSessions()
        }
        let defaultRequested = await pages.waitUntilRequested("default")
        XCTAssertTrue(defaultRequested)

        model.setActiveProfile("work")
        let newRefresh = Task { @MainActor in
            await model.controller.refreshSessions()
        }
        let workRequested = await pages.waitUntilRequested("work")
        XCTAssertTrue(workRequested)

        await pages.release(
            "work",
            page: SessionPage(
                rows: [SessionRow(id: "work-row", profile: "work")],
                total: 1,
                hasMore: false
            )
        )
        await newRefresh.value

        await pages.release(
            "default",
            page: SessionPage(
                rows: [SessionRow(id: "default-row", profile: "default")],
                total: 1,
                hasMore: false
            )
        )
        await oldRefresh.value

        XCTAssertEqual(model.sessions.map(\.id), ["work-row"])
        XCTAssertEqual(model.sessions.first?.profile, "work")
    }

    // MARK: Test seams

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func makeModel(
        session: URLSession,
        catalogStore: ServerCatalogStore = ServerCatalogStore(persistence: ProfileTestCatalogPersistence(), legacyOrigin: nil),
        relayTargetStore: RelayTargetStore = RelayTargetStore(persistence: InMemoryRelayTargetPersistence()),
        startupChoiceStore: StartupConnectionChoiceStore = StartupConnectionChoiceStore(persistence: MemoryStartupChoicePersistence()),
        directProfilesClientFactory: (@MainActor (String) -> any ProfilesListing)? = nil,
        relayProfilesClientFactory: (@MainActor (RelayPairedTarget, String) async throws -> any ProfilesListing)? = nil,
        relaySessionsPageLoader: (@MainActor (RelayPairedTarget, String, Int, Int) async throws -> SessionPage)? = nil,
        sessionPageLoader: (@MainActor (String, String, Int, Int) async throws -> SessionPage)? = nil
    ) -> AppModel {
        let model = AppModel(
            serverCatalogStore: catalogStore,
            offlineCacheStore: OfflineCacheStore(
                backend: ProfileTestCacheBackend(),
                cipher: ProfileTestCacheCipher()
            ),
            relayTargetStore: relayTargetStore,
            startupChoiceStore: startupChoiceStore
        )
        let controller = ConnectionController(
            appModel: model,
            urlSession: session,
            credentialStore: ProfileTestCredentialStore(),
            directProfilesClientFactory: directProfilesClientFactory,
            relayProfilesClientFactory: relayProfilesClientFactory,
            relaySessionsPageLoader: relaySessionsPageLoader,
            sessionPageLoader: sessionPageLoader
        )
        model.injectController(controller)
        return model
    }

    private func makeRelayTarget() -> RelayPairedTarget {
        RelayPairedTarget(
            id: UUID(),
            label: "test relay",
            relayOrigin: "https://relay.test",
            installationID: Data(repeating: 0x01, count: 32),
            hostPublicKey: Data(repeating: 0x02, count: 32),
            deviceID: RelayBase64.urlSafeEncode(Data(repeating: 0x03, count: 16)),
            deviceStaticPrivateKey: Data(repeating: 0x04, count: 32),
            fingerprint: String(repeating: "a", count: 16),
            status: .approved,
            createdAtEpochSeconds: 1,
            lastUsedEpochSeconds: nil
        )
    }
}

private final class FakeProfilesListing: ProfilesListing {
    private let result: Result<[String], Error>

    init(names: [String]) { result = .success(names) }
    init(error: Error) { result = .failure(error) }

    func list() async throws -> [String] { try result.get() }
}

private actor DeferredProfilesListing: ProfilesListing {
    private(set) var requested = false
    private var continuation: CheckedContinuation<[String], Error>?

    func list() async throws -> [String] {
        requested = true
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
        }
    }

    func release(_ names: [String]) {
        continuation?.resume(returning: names)
        continuation = nil
    }
}

private actor DeferredSessionPages {
    private var continuations: [String: CheckedContinuation<SessionPage, Error>] = [:]
    private(set) var requests: [String] = []

    func load(profile: String) async throws -> SessionPage {
        requests.append(profile)
        return try await withCheckedThrowingContinuation { continuation in
            continuations[profile] = continuation
        }
    }

    func waitUntilRequested(_ profile: String) async -> Bool {
        for _ in 0..<100 {
            if requests.contains(profile) { return true }
            await Task.yield()
        }
        return false
    }

    func release(_ profile: String, page: SessionPage) {
        continuations.removeValue(forKey: profile)?.resume(returning: page)
    }
}

private final class ProfileTestCredentialStore: CredentialStoring, @unchecked Sendable {
    private var values: [String: TokenPair] = [:]

    func tokens(for origin: String) -> TokenPair? { values[origin] }
    func setTokens(_ tokens: TokenPair, for origin: String) { values[origin] = tokens }
    func clearTokens(for origin: String) { values.removeValue(forKey: origin) }
}

private final class ProfileTestCatalogPersistence: ServerCatalogPersisting, @unchecked Sendable {
    private var data: Data?

    func readCatalogData() throws -> Data? { data }
    func writeCatalogData(_ data: Data) throws { self.data = data }
}

private final class ProfileTestCacheBackend: OfflineCacheBacking, @unchecked Sendable {
    private var rows: [String: Data] = [:]
    private var enabled = false

    func listRowKeys(limit: Int) throws -> [String] { Array(rows.keys.sorted().prefix(limit)) }
    func readRow(key: String) throws -> Data? { rows[key] }
    func writeRow(_ data: Data, key: String) throws { rows[key] = data }
    func deleteRow(key: String) throws { rows.removeValue(forKey: key) }
    func readTranscriptCachingEnabled() -> Bool { enabled }
    func writeTranscriptCachingEnabled(_ enabled: Bool) throws { self.enabled = enabled }
}

private struct ProfileTestCacheCipher: OfflineCacheCrypting {
    func seal(_ plaintext: Data, authenticating associatedData: Data) throws -> Data { plaintext }
    func open(_ ciphertext: Data, authenticating associatedData: Data) throws -> Data { ciphertext }
}
