import XCTest
@testable import Mercury

/// End-to-end AppModel/ConnectionController flow tests against a mock
/// `URLProtocol` session (shared `MockURLProtocol` from NetworkingTests).
///
/// Coverage:
/// - probe failure classification maps to friendly `.failed` messages
/// - successful unauthenticated probe sets `.connected`
/// - authRequired + "nous" provider sets `.signInRequired`
/// - loadSessions populates rows from fixture JSON
/// - state mismatch during native sign-in surfaces `.failed` without crashing
@MainActor
final class AppFlowTests: XCTestCase {

    private let origin = "https://hermes.test"

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
    }

    // MARK: - Helpers

    private func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        return URLSession(configuration: config)
    }

    private func jsonHandler(_ routes: [String: (Int, String)]) -> (URLRequest) throws -> (HTTPURLResponse, Data) {
        { request in
            let path = request.url?.path ?? ""
            guard let (status, body) = routes[path] else {
                throw URLError(.unsupportedURL)
            }
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: status,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data(body.utf8))
        }
    }

    /// Builds an app model whose controller talks through the given session.
    private func makeModel(
        session: URLSession,
        credentialStore: CredentialStoring = KeychainCredentialStore(),
        signInFlowFactory: @escaping @MainActor (String) -> SelfHostedSignInFlowing = { NativePKCEFlow(origin: $0) }
    ) -> AppModel {
        let model = AppModel(
            serverCatalogStore: ServerCatalogStore(
                persistence: AppFlowCatalogPersistence(),
                legacyOrigin: nil
            ),
            offlineCacheStore: OfflineCacheStore(
                backend: AppFlowCacheBackend(),
                cipher: AppFlowCacheCipher()
            )
        )
        let controller = ConnectionController(
            appModel: model,
            urlSession: session,
            credentialStore: credentialStore,
            signInFlowFactory: signInFlowFactory
        )
        // Never launch Safari from unit tests.
        controller.openExternalURL = { _ in }
        model.injectController(controller)
        return model
    }

    // MARK: - Probe failure classification

    func testTransientProbeFailureMapsToFriendlyReachabilityMessage() async {
        MockURLProtocol.handler = jsonHandler([
            "/api/status": (500, #"{"error":"internal"}"#),
        ])
        let model = makeModel(session: makeSession())

        await model.controller.probeSelfHosted(origin: origin)

        XCTAssertEqual(
            model.connectionPhase,
            .failed("Could not reach \(origin) — check the address and your network")
        )
    }

    func testAuthRejectedProbeMapsToRejectedMessage() async {
        MockURLProtocol.handler = jsonHandler([
            "/api/status": (401, #"{"error":"unauthorized"}"#),
        ])
        let model = makeModel(session: makeSession())

        await model.controller.probeSelfHosted(origin: origin)

        XCTAssertEqual(model.connectionPhase, .failed("Server rejected the connection"))
    }

    func testInvalidOriginFailsWithoutAnyNetworkCall() async {
        MockURLProtocol.handler = { _ in
            XCTFail("No request should be made for an invalid origin")
            throw URLError(.badURL)
        }
        let model = makeModel(session: makeSession())

        await model.controller.probeSelfHosted(origin: "https://bad host/path")

        XCTAssertEqual(
            model.connectionPhase,
            .failed("Enter a valid server address, e.g. hermes.example.com")
        )
        XCTAssertNil(model.serverOrigin)
    }

    // MARK: - Successful probes

    func testUnauthenticatedProbeSetsConnectedAndStoresVersion() async {
        MockURLProtocol.handler = jsonHandler([
            "/api/status": (200, #"{"version":"v0.9.3","auth_required":false}"#),
        ])
        let model = makeModel(session: makeSession())

        await model.controller.probeSelfHosted(origin: origin)

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertEqual(model.hermesVersion, "v0.9.3")
        XCTAssertEqual(model.serverOrigin, origin)
    }

    func testAuthRequiredWithNousProviderSetsSignInRequired() async {
        MockURLProtocol.handler = jsonHandler([
            "/api/status": (200, #"{"version":"v0.9.3","auth_required":true}"#),
            "/api/auth/providers": (200, #"{"providers":[{"name":"nous","display_name":"Nous"}]}"#),
        ])
        let model = makeModel(session: makeSession())

        await model.controller.probeSelfHosted(origin: origin)

        XCTAssertEqual(model.connectionPhase, .signInRequired)
        XCTAssertEqual(model.serverOrigin, origin)
    }

    func testAuthRequiredWithPasswordProviderSetsSignInRequiredAndPublishesProvider() async {
        MockURLProtocol.handler = jsonHandler([
            "/api/status": (200, #"{"version":"v0.9.3","auth_required":true}"#),
            "/api/auth/providers": (200, #"{"providers":[{"name":"basic","display_name":"Username & Password","supports_password":true}]}"#),
        ])
        let model = makeModel(session: makeSession())

        await model.controller.probeSelfHosted(origin: origin)

        XCTAssertEqual(model.connectionPhase, .signInRequired)
        XCTAssertEqual(model.authProviders, [
            AuthProvider(name: "basic", displayName: "Username & Password", supportsPassword: true)
        ])
    }

    func testAuthRequiredWithValidStoredTokenRestoresConnectedWithoutProviderPrompt() async {
        let store = FakeCredentialStore()
        store.setTokens(TokenPair(accessToken: Data("stored-access".utf8)), for: origin)
        MockURLProtocol.handler = { request in
            let path = request.url?.path ?? ""
            if path == "/api/auth/providers" {
                XCTFail("valid restored authentication must not reopen provider sign-in")
            }
            let body: String
            switch path {
            case "/api/status": body = #"{"version":"v0.9.3","auth_required":true}"#
            case "/api/auth/me": body = #"{"user_id":"user"}"#
            default: throw URLError(.unsupportedURL)
            }
            return (
                HTTPURLResponse(
                    url: request.url!,
                    statusCode: 200,
                    httpVersion: nil,
                    headerFields: ["Content-Type": "application/json"]
                )!,
                Data(body.utf8)
            )
        }
        let model = makeModel(session: makeSession(), credentialStore: store)

        await model.controller.probeSelfHosted(origin: origin)

        XCTAssertEqual(model.connectionPhase, .connected)
        let identity = MockURLProtocol.receivedRequests.first { $0.url?.path == "/api/auth/me" }
        XCTAssertEqual(identity?.value(forHTTPHeaderField: "Authorization"), "Bearer stored-access")
    }

    func testAuthRequiredWithoutNousProviderFailsWithExplanation() async {
        MockURLProtocol.handler = jsonHandler([
            "/api/status": (200, #"{"version":"v0.9.3","auth_required":true}"#),
            "/api/auth/providers": (200, #"{"providers":[{"name":"password"}]}"#),
        ])
        let model = makeModel(session: makeSession())

        await model.controller.probeSelfHosted(origin: origin)

        XCTAssertEqual(
            model.connectionPhase,
            .failed("This server requires an authentication method Mercury does not support.")
        )
    }

    // MARK: - Sessions

    func testLoadSessionsPopulatesRowsFromFixtureJSON() async {
        let sessionsJSON = """
        {
          "sessions": [
            {"id": "s1", "title": "Refactor auth flow", "preview": "Splitting token refresh out", "message_count": 42, "profile": "default"},
            {"id": "s2", "title": "Weekly review", "message_count": 3}
          ],
          "total": 2
        }
        """
        MockURLProtocol.handler = { request in
            let path = request.url?.path ?? ""
            if path == "/api/profiles/sessions" {
                let response = HTTPURLResponse(
                    url: request.url!,
                    statusCode: 200,
                    httpVersion: nil,
                    headerFields: ["Content-Type": "application/json"]
                )!
                return (response, Data(sessionsJSON.utf8))
            }
            // Everything else (the initial probe) looks connected & open.
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            return (response, Data(#"{"version":"v0.9.3","auth_required":false}"#.utf8))
        }
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)
        XCTAssertEqual(model.connectionPhase, .connected)

        await model.loadSessions()

        XCTAssertNil(model.sessionsError)
        XCTAssertEqual(model.sessions.map(\.id), ["s1", "s2"])
        XCTAssertEqual(model.sessions.first?.messageCount, 42)
    }

    func testLoadSessionsFailureKeepsOldRowsAndSetsErrorBanner() async {
        var callCount = 0
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: 200,
                httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let path = request.url?.path ?? ""
            if path == "/api/profiles/sessions" {
                callCount += 1
                if callCount > 1 {
                    return (HTTPURLResponse(url: request.url!, statusCode: 500, httpVersion: nil, headerFields: nil)!,
                            Data("{}".utf8))
                }
                return (response, Data(#"{"sessions":[{"id":"old"}]}"#.utf8))
            }
            return (response, Data(#"{"version":"v0.9.3","auth_required":false}"#.utf8))
        }
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)
        await model.loadSessions()
        XCTAssertEqual(model.sessions.map(\.id), ["old"])

        await model.loadSessions()

        XCTAssertEqual(model.sessions.map(\.id), ["old"], "previous rows survive a failed refresh")
        XCTAssertNotNil(model.sessionsError)
    }

    // MARK: - Native sign-in

    func testStateMismatchDuringSignInSurfacesFailedWithoutCrashing() async {
        final class MismatchFlow: SelfHostedSignInFlowing {
            func begin() async throws -> URL {
                URL(string: "https://hermes.test/auth/native/authorize")!
            }
            func awaitCallback() async throws -> CallbackResult {
                throw FlowError.stateMismatch
            }
            func exchange(code: String) async throws -> TokenOutcome {
                XCTFail("exchange must not run when the callback state mismatches")
                throw FlowError.badResponse
            }
            func cancel() {}
        }

        MockURLProtocol.handler = jsonHandler([
            "/api/status": (200, #"{"version":"v0.9.3","auth_required":false}"#),
        ])
        let model = makeModel(session: makeSession(), signInFlowFactory: { _ in MismatchFlow() })

        // Establish an origin (normally done by the probe before sign-in).
        await model.controller.probeSelfHosted(origin: origin)
        XCTAssertEqual(model.connectionPhase, .connected)

        await model.beginSelfHostedSignInAndAwaitBrowser()

        XCTAssertEqual(model.connectionPhase, .failed("Sign-in failed"))
        XCTAssertFalse(model.isSigningIn, "signing flag must reset even on failure")
    }

    func testSuccessfulCookieSignInConnects() async {
        final class CookieFlow: SelfHostedSignInFlowing {
            func begin() async throws -> URL {
                URL(string: "https://hermes.test/auth/native/authorize")!
            }
            func awaitCallback() async throws -> CallbackResult {
                CallbackResult(code: "abc", state: "ok")
            }
            func exchange(code: String) async throws -> TokenOutcome {
                .cookies(["hermes_session": "not-a-real-secret"])
            }
            func cancel() {}
        }

        MockURLProtocol.handler = jsonHandler([
            "/api/status": (200, #"{"version":"v0.9.3","auth_required":true}"#),
            "/api/auth/providers": (200, #"{"providers":[{"name":"nous"}]}"#),
        ])

        let model = makeModel(session: makeSession(), signInFlowFactory: { _ in CookieFlow() })
        await model.controller.probeSelfHosted(origin: origin)
        XCTAssertEqual(model.connectionPhase, .signInRequired)

        await model.beginSelfHostedSignInAndAwaitBrowser()

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertNil(model.sessionsError)
    }

    func testSuccessfulPasswordSignInValidatesCookieAndConnects() async throws {
        let cookieStore = HTTPCookieStorage.shared
        func clearFixtureCookies() {
            for cookie in cookieStore.cookies ?? []
            where ConnectionController.cookie(cookie, coversHost: "hermes.test") {
                cookieStore.deleteCookie(cookie)
            }
        }
        clearFixtureCookies()
        defer { clearFixtureCookies() }

        var loginBody: [String: String]?
        MockURLProtocol.handler = { request in
            let path = request.url?.path ?? ""
            let status: Int
            let body: String
            var headers = ["Content-Type": "application/json"]
            switch path {
            case "/api/status":
                status = 200
                body = #"{"version":"v0.9.3","auth_required":true}"#
            case "/api/auth/providers":
                status = 200
                body = #"{"providers":[{"name":"basic","supports_password":true}]}"#
            case "/auth/password-login":
                status = 200
                body = #"{"ok":true}"#
                headers["Set-Cookie"] = "hermes_session=test-only; Path=/; HttpOnly; Secure"
                loginBody = try JSONSerialization.jsonObject(
                    with: self.capturedHTTPBody(of: request)
                ) as? [String: String]
            case "/api/auth/me":
                status = 200
                body = #"{"user_id":"admin"}"#
            default:
                throw URLError(.unsupportedURL)
            }
            return (
                HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: headers)!,
                Data(body.utf8)
            )
        }
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)

        await model.signInWithPassword(username: " admin ", password: "fixture-password")

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertNil(model.authenticationError)
        XCTAssertEqual(loginBody, [
            "provider": "basic",
            "username": "admin",
            "password": "fixture-password",
            "next": "/",
        ])
        XCTAssertTrue(MockURLProtocol.receivedRequests.contains { $0.url?.path == "/api/auth/me" })
    }

    func testPasswordSignInClearsStaleBearerBeforeCookieValidation() async throws {
        let store = FakeCredentialStore()
        store.setTokens(TokenPair(accessToken: Data("stale-oauth-token".utf8)), for: origin)

        MockURLProtocol.handler = { request in
            let path = request.url?.path ?? ""
            let status: Int
            let body: String
            var headers = ["Content-Type": "application/json"]
            switch path {
            case "/api/status":
                status = 200
                body = #"{"version":"v0.9.3","auth_required":true}"#
            case "/api/auth/me" where request.value(forHTTPHeaderField: "Authorization") != nil:
                // Initial probe rejects the stale stored bearer and publishes
                // SignInRequired. Password validation must not repeat it.
                status = 401
                body = #"{"detail":"Unauthorized"}"#
            case "/api/auth/providers":
                status = 200
                body = #"{"providers":[{"name":"basic","supports_password":true}]}"#
            case "/auth/password-login":
                status = 200
                body = #"{"ok":true}"#
                headers["Set-Cookie"] = "hermes_session_at=test-only; Path=/; HttpOnly; Secure"
            case "/api/auth/me":
                XCTAssertNil(
                    request.value(forHTTPHeaderField: "Authorization"),
                    "fresh basic-auth cookie must not be overridden by a stale OAuth bearer"
                )
                status = 200
                body = #"{"user_id":"admin","provider":"basic"}"#
            default:
                throw URLError(.unsupportedURL)
            }
            return (
                HTTPURLResponse(
                    url: request.url!,
                    statusCode: status,
                    httpVersion: nil,
                    headerFields: headers
                )!,
                Data(body.utf8)
            )
        }

        let model = makeModel(session: makeSession(), credentialStore: store)
        await model.controller.probeSelfHosted(origin: origin)
        XCTAssertEqual(model.connectionPhase, .signInRequired)

        await model.signInWithPassword(username: "admin", password: "fixture-password")

        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertNil(store.tokens(for: origin))
        XCTAssertTrue(store.clearedOrigins.contains(origin))
    }

    // MARK: - Pagination (loadNextSessionsPage / canLoadMoreSessions)

    /// Serves page 1 (offset 0) then page 2 (offset 2) with an overlapping id.
    private func makePagingHandler() -> (URLRequest) throws -> (HTTPURLResponse, Data) {
        { request in
            let path = request.url?.path ?? ""
            guard path == "/api/profiles/sessions" else {
                // The probe: connected and open.
                return (
                    HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                    Data(#"{"version":"v0.9.3","auth_required":false}"#.utf8)
                )
            }
            let components = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)
            let offset = components?.queryItems?.first(where: { $0.name == "offset" })?.value ?? "0"
            let body: String
            if offset == "0" {
                // 2 rows, total 4 → hasMore true.
                body = #"{"sessions": [{"id": "s1"}, {"id": "s2"}], "total": 4}"#
            } else {
                // s2 repeats the tail of page 1; total reached → hasMore false.
                body = #"{"sessions": [{"id": "s2"}, {"id": "s3"}, {"id": "s4"}], "total": 4}"#
            }
            return (
                HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                Data(body.utf8)
            )
        }
    }

    private func sessionsRequestCount() -> Int {
        MockURLProtocol.receivedRequests.filter { $0.url?.path == "/api/profiles/sessions" }.count
    }

    /// URLSession turns request bodies into streams under URLProtocol; drain
    /// the stream (falling back to `httpBody`) to inspect what was sent.
    private func capturedHTTPBody(of request: URLRequest) -> Data {
        if let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            let bufferSize = 4096
            let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
            defer { buffer.deallocate() }
            while stream.hasBytesAvailable {
                let read = stream.read(buffer, maxLength: bufferSize)
                if read <= 0 { break }
                data.append(buffer, count: read)
            }
            stream.close()
            return data
        }
        return request.httpBody ?? Data()
    }

    func testLoadNextPageAppendsDedupesAndPreservesOrder() async {
        MockURLProtocol.handler = makePagingHandler()
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)

        await model.loadSessions()
        XCTAssertEqual(model.sessions.map(\.id), ["s1", "s2"])
        XCTAssertTrue(model.canLoadMoreSessions)

        await model.loadNextSessionsPage()

        XCTAssertEqual(model.sessions.map(\.id), ["s1", "s2", "s3", "s4"], "overlapping id dropped, order preserved")
        XCTAssertFalse(model.canLoadMoreSessions, "hasMore=false must clear the flag")
        XCTAssertFalse(model.isLoadingMoreSessions)
        XCTAssertNil(model.sessionsError)
    }

    func testLoadNextPageFetchesOffsetMatchingCurrentRowCount() async {
        MockURLProtocol.handler = makePagingHandler()
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)
        await model.loadSessions()

        await model.loadNextSessionsPage()

        let sessionRequests = MockURLProtocol.receivedRequests.filter { $0.url?.path == "/api/profiles/sessions" }
        let offsets = sessionRequests.compactMap { request in
            URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "offset" })?.value
        }
        XCTAssertEqual(offsets, ["0", "2"], "second page must fetch offset = current row count")
    }

    func testLoadNextPageIsNoOpWhenNoMorePages() async {
        MockURLProtocol.handler = { request in
            let path = request.url?.path ?? ""
            if path == "/api/profiles/sessions" {
                return (
                    HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                    Data(#"{"sessions": [{"id": "s1"}], "total": 1}"#.utf8)
                )
            }
            return (
                HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                Data(#"{"version":"v0.9.3","auth_required":false}"#.utf8)
            )
        }
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)
        await model.loadSessions()
        XCTAssertFalse(model.canLoadMoreSessions)

        await model.loadNextSessionsPage()

        XCTAssertEqual(sessionsRequestCount(), 1, "no fetch when canLoadMoreSessions is false")
        XCTAssertEqual(model.sessions.map(\.id), ["s1"])
    }

    // MARK: - Profile switcher

    func testSwitchProfileSetsActiveProfileAndReloadsWithProfileQueryItem() async {
        MockURLProtocol.handler = { request in
            let path = request.url?.path ?? ""
            if path == "/api/profiles/sessions" {
                return (
                    HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                    Data(#"{"sessions": [{"id": "work-1", "profile": "work"}], "total": 1}"#.utf8)
                )
            }
            return (
                HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                Data(#"{"version":"v0.9.3","auth_required":false}"#.utf8)
            )
        }
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)

        await model.switchProfile("work")

        XCTAssertEqual(model.activeProfile, "work")
        XCTAssertEqual(model.sessions.map(\.id), ["work-1"])
        let lastSessionsRequest = MockURLProtocol.receivedRequests.last { $0.url?.path == "/api/profiles/sessions" }
        let profileItem = lastSessionsRequest.flatMap { request in
            URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "profile" })?.value
        }
        XCTAssertEqual(profileItem, "work", "reload must carry the new profile as a query item")
    }

    // MARK: - Optimistic lifecycle

    private func makeLifecycleHandler(
        patchStatus: Int,
        deleteStatus: Int
    ) -> (URLRequest) throws -> (HTTPURLResponse, Data) {
        { request in
            let path = request.url?.path ?? ""
            if path == "/api/profiles/sessions" {
                return (
                    HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                    Data(#"{"sessions": [{"id": "s1"}], "total": 1}"#.utf8)
                )
            }
            let status: Int
            if path == "/api/sessions/s1", request.httpMethod == "PATCH" {
                status = patchStatus
            } else if path == "/api/sessions/s1", request.httpMethod == "DELETE" {
                status = deleteStatus
            } else {
                // The probe.
                status = 200
            }
            let body = path.hasPrefix("/api/sessions/")
                ? #"{"ok": true}"#
                : #"{"version":"v0.9.3","auth_required":false}"#
            return (
                HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!,
                Data(body.utf8)
            )
        }
    }

    private func connectedModel(handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)) async -> AppModel {
        MockURLProtocol.handler = handler
        let model = makeModel(session: makeSession())
        await model.controller.probeSelfHosted(origin: origin)
        await model.loadSessions()
        return model
    }

    func testUpdateSessionAppliesOptimisticallyAndPersists() async throws {
        let model = await connectedModel(
            handler: makeLifecycleHandler(patchStatus: 200, deleteStatus: 200)
        )
        XCTAssertEqual(model.sessions.map(\.id), ["s1"])
        XCTAssertEqual(model.sessions.first?.title, "")

        await model.updateSession(id: "s1", pinned: true, title: "Renamed")

        XCTAssertEqual(model.sessions.first?.title, "Renamed")
        XCTAssertEqual(model.sessionPinned["s1"], true)
        XCTAssertNil(model.sessionsError)

        let patch = try XCTUnwrap(
            MockURLProtocol.receivedRequests.last { $0.url?.path == "/api/sessions/s1" && $0.httpMethod == "PATCH" }
        )
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: capturedHTTPBody(of: patch)) as? [String: Any])
        XCTAssertEqual(body["pinned"] as? Bool, true)
        XCTAssertEqual(body["title"] as? String, "Renamed")
    }

    func testUpdateSessionRevertsOnFailure() async {
        let model = await connectedModel(
            handler: makeLifecycleHandler(patchStatus: 500, deleteStatus: 200)
        )
        model.setSessionsError(nil)

        await model.updateSession(id: "s1", pinned: true, title: "Renamed")

        XCTAssertEqual(model.sessions.first?.title, "", "title must revert after a failed update")
        XCTAssertNil(model.sessionPinned["s1"], "pin mirror must revert to unset")
        XCTAssertNotNil(model.sessionsError)
    }

    func testDeleteSessionRemovesRowOnSuccess() async {
        let model = await connectedModel(
            handler: makeLifecycleHandler(patchStatus: 200, deleteStatus: 200)
        )

        await model.deleteSession(id: "s1")

        XCTAssertEqual(model.sessions.map(\.id), [])
        XCTAssertNil(model.sessionsError)
    }

    func testDeleteSessionRestoresRowOnFailure() async {
        let model = await connectedModel(
            handler: makeLifecycleHandler(patchStatus: 200, deleteStatus: 500)
        )
        model.setSessionsError(nil)

        await model.deleteSession(id: "s1")

        XCTAssertEqual(model.sessions.map(\.id), ["s1"], "row must be restored when deletion fails")
        XCTAssertNotNil(model.sessionsError)
    }

    // MARK: - Sign-out (origin-scoped credential clear + cookie purge + reset)

    /// In-memory `CredentialStoring` fake so tests never touch the Keychain.
    private final class FakeCredentialStore: CredentialStoring {
        private var storage: [String: TokenPair] = [:]

        var clearedOrigins: [String] = []

        func tokens(for origin: String) -> TokenPair? { storage[origin] }

        func setTokens(_ tokens: TokenPair, for origin: String) { storage[origin] = tokens }

        func clearTokens(for origin: String) {
            clearedOrigins.append(origin)
            storage.removeValue(forKey: origin)
        }
    }

    /// Like the shared helper, but with an injected credential store.
    private func makeModel(
        session: URLSession,
        credentialStore: CredentialStoring
    ) -> AppModel {
        let model = AppModel(
            serverCatalogStore: ServerCatalogStore(
                persistence: AppFlowCatalogPersistence(),
                legacyOrigin: nil
            ),
            offlineCacheStore: OfflineCacheStore(
                backend: AppFlowCacheBackend(),
                cipher: AppFlowCacheCipher()
            )
        )
        let controller = ConnectionController(
            appModel: model,
            urlSession: session,
            credentialStore: credentialStore,
            signInFlowFactory: { NativePKCEFlow(origin: $0) }
        )
        controller.openExternalURL = { _ in }
        model.injectController(controller)
        return model
    }

    private let otherOrigin = "https://api.other.test"

    /// Installs cookies for two hosts and returns a cleanup closure that
    /// removes exactly what was installed.
    @discardableResult
    private func seedCookies() -> () -> Void {
        func cookie(_ name: String, domain: String) -> HTTPCookie {
            HTTPCookie(
                properties: [
                    .domain: domain,
                    .path: "/",
                    .name: name,
                    .value: "test-only",
                ]
            )!
        }
        let installed = [
            cookie("hermes_session", domain: "hermes.test"),
            cookie("other_session", domain: "api.other.test"),
        ]
        for cookie in installed { HTTPCookieStorage.shared.setCookie(cookie) }
        return {
            for cookie in installed { HTTPCookieStorage.shared.deleteCookie(cookie) }
        }
    }

    private func cookieCount(host: String) -> Int {
        (HTTPCookieStorage.shared.cookies ?? []).filter { cookie in
            ConnectionController.cookie(cookie, coversHost: host)
        }.count
    }

    /// Connects a model backed by a seeded fake store, then signs out.
    private func signOutConnectedModel(
        store: FakeCredentialStore
    ) async -> AppModel {
        MockURLProtocol.handler = jsonHandler([
            "/api/status": (200, #"{"version":"v0.9.3","auth_required":false}"#),
            "/api/profiles/sessions": (200, #"{"sessions": [{"id": "s1"}], "total": 2}"#),
        ])
        let model = makeModel(session: makeSession(), credentialStore: store)
        await model.controller.probeSelfHosted(origin: origin)
        await model.loadSessions()
        XCTAssertEqual(model.connectionPhase, .connected)
        XCTAssertTrue(model.canLoadMoreSessions)

        await model.signOut()
        return model
    }

    func testSignOutClearsTokensOnlyForThatOrigin() async {
        let store = FakeCredentialStore()
        store.setTokens(TokenPair(accessToken: Data("a".utf8)), for: origin)
        store.setTokens(TokenPair(accessToken: Data("b".utf8)), for: otherOrigin)

        _ = await signOutConnectedModel(store: store)

        XCTAssertNil(store.tokens(for: origin), "signed-out origin's keychain item must be gone")
        XCTAssertEqual(store.clearedOrigins, [origin], "clear must be scoped to exactly this origin")
        XCTAssertNotNil(store.tokens(for: otherOrigin), "other origins' credentials survive")
    }

    func testSignOutPurgesMatchingHostCookiesAndLeavesOthers() async {
        let cleanupCookies = seedCookies()
        defer { cleanupCookies() }
        XCTAssertEqual(cookieCount(host: "hermes.test"), 1)
        XCTAssertEqual(cookieCount(host: "api.other.test"), 1)

        let store = FakeCredentialStore()
        store.setTokens(TokenPair(accessToken: Data("a".utf8)), for: origin)
        _ = await signOutConnectedModel(store: store)

        XCTAssertEqual(cookieCount(host: "hermes.test"), 0, "the origin host's cookies must be purged")
        XCTAssertEqual(cookieCount(host: "api.other.test"), 1, "other hosts' cookies must be intact")
    }

    func testSignOutClearsSessionStateButPreservesServerForQuickSignIn() async {
        let store = FakeCredentialStore()
        store.setTokens(TokenPair(accessToken: Data("a".utf8)), for: origin)

        let model = await signOutConnectedModel(store: store)

        XCTAssertEqual(model.connectionPhase, .signInRequired)
        XCTAssertEqual(model.serverOrigin, origin)
        XCTAssertEqual(model.sessions, [], "session rows must be cleared on sign-out")
        XCTAssertNil(model.sessionsError)
        XCTAssertFalse(model.canLoadMoreSessions)
        XCTAssertNil(model.hermesVersion)
    }

    func testAppModelSignOutWithNoOriginIsNoOp() async {
        let cleanupCookies = seedCookies()
        defer { cleanupCookies() }

        let store = FakeCredentialStore()
        store.setTokens(TokenPair(accessToken: Data("a".utf8)), for: otherOrigin)
        let model = makeModel(session: makeSession(), credentialStore: store)
        // No probe/sign-in: serverOrigin is nil.

        await model.signOut()

        XCTAssertTrue(store.clearedOrigins.isEmpty, "no clear may run without an origin")
        XCTAssertNotNil(store.tokens(for: otherOrigin))
        XCTAssertEqual(cookieCount(host: "api.other.test"), 1)
        XCTAssertEqual(model.connectionPhase, .disconnected)
    }

    func testSubdomainCookieIsPurgedWithParentOriginSignOut() {
        let cookie = HTTPCookie(
            properties: [.domain: ".hermes.test", .path: "/", .name: "sid", .value: "t"]
        )!
        XCTAssertTrue(ConnectionController.cookie(cookie, coversHost: "hermes.test"))
        let subdomainCookie = HTTPCookie(
            properties: [.domain: ".api.hermes.test", .path: "/", .name: "sid", .value: "t"]
        )!
        XCTAssertTrue(ConnectionController.cookie(subdomainCookie, coversHost: "hermes.test"))
        let foreign = HTTPCookie(
            properties: [.domain: ".not-hermes.test", .path: "/", .name: "sid", .value: "t"]
        )!
        XCTAssertFalse(ConnectionController.cookie(foreign, coversHost: "hermes.test"))
    }
}

private final class AppFlowCatalogPersistence: ServerCatalogPersisting, @unchecked Sendable {
    private var data: Data?
    func readCatalogData() throws -> Data? { data }
    func writeCatalogData(_ data: Data) throws { self.data = data }
}

private final class AppFlowCacheBackend: OfflineCacheBacking, @unchecked Sendable {
    private var rows: [String: Data] = [:]
    private var enabled = false

    func listRowKeys(limit: Int) throws -> [String] { Array(rows.keys.sorted().prefix(limit)) }
    func readRow(key: String) throws -> Data? { rows[key] }
    func writeRow(_ data: Data, key: String) throws { rows[key] = data }
    func deleteRow(key: String) throws { rows.removeValue(forKey: key) }
    func readTranscriptCachingEnabled() -> Bool { enabled }
    func writeTranscriptCachingEnabled(_ enabled: Bool) throws { self.enabled = enabled }
}

private struct AppFlowCacheCipher: OfflineCacheCrypting {
    func seal(_ plaintext: Data, authenticating associatedData: Data) throws -> Data { plaintext }
    func open(_ ciphertext: Data, authenticating associatedData: Data) throws -> Data { ciphertext }
}
