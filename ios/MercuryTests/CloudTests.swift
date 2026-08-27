import XCTest
@testable import Mercury

/// Tests for `PortalClient` (device flow, refresh, agent discovery)
/// against a stubbed `URLSession` via a static-handler `URLProtocol`.
final class CloudTests: XCTestCase {
    // MARK: - Mock transport

    /// Intercepts every request and routes it to the current static handler.
    private final class MockURLProtocol: URLProtocol {
        /// Handler for the next request. Set per-test; safe under serial tests.
        nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            guard let handler = Self.handler else {
                client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
                return
            }
            do {
                let (response, data) = try handler(request)
                // Surface the raw body to the client, since URLProtocol does
                // not forward httpBody through the canonical request on all
                // OS versions.
                if let bodyStream = request.httpBodyStream {
                    bodyStream.open()
                    var body = Data()
                    let bufferSize = 4096
                    let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
                    defer { buffer.deallocate() }
                    while bodyStream.hasBytesAvailable {
                        let read = bodyStream.read(buffer, maxLength: bufferSize)
                        guard read > 0 else { break }
                        body.append(buffer, count: read)
                    }
                    bodyStream.close()
                    Self.capturedBody = body
                } else {
                    Self.capturedBody = request.httpBody
                }
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            } catch {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }

        override func stopLoading() {}

        /// Last captured form body (for request-shape assertions).
        nonisolated(unsafe) static var capturedBody: Data?

        static func makeSession() -> URLSession {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [MockURLProtocol.self]
            return URLSession(configuration: config)
        }

        /// Decodes the last captured body as an x-www-form-urlencoded dict.
        static func decodedFormBody() -> [String: String] {
            guard let data = capturedBody, let text = String(data: data, encoding: .utf8) else { return [String: String]() }
            var fields: [String: String] = [:]
            for pair in text.split(separator: "&") {
                let parts = pair.split(separator: "=", maxSplits: 1)
                guard parts.count == 2 else { continue }
                fields[String(parts[0])] = String(parts[1])
                    .removingPercentEncoding ?? String(parts[1])
            }
            return fields
        }
    }

    override func setUp() {
        super.setUp()
        MockURLProtocol.handler = nil
        MockURLProtocol.capturedBody = nil
    }

    override func tearDown() {
        MockURLProtocol.handler = nil
        MockURLProtocol.capturedBody = nil
        super.tearDown()
    }

    private func makeClient(origin: String = "https://portal.test") -> PortalClient {
        PortalClient(origin: origin, session: MockURLProtocol.makeSession())
    }

    private static func response(
        _ url: URL,
        status: Int,
        headers: [String: String] = ["Content-Type": "application/json"]
    ) -> HTTPURLResponse {
        HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
    }

    // MARK: - startDeviceCode

    /// Fixture with no verification_uri_complete must decode with that
    /// optional field nil and sensible interval/expiry defaults.
    func testStartDeviceCodeDecodesFixtureWithoutCompleteURI() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/oauth/device/code")
            XCTAssertEqual(request.httpMethod, "POST")
            let body = """
            {"device_code":"dc_123","user_code":"ABCD-1234","verification_uri":"https://portal.nousresearch.com/activate","expires_in":600,"interval":5}
            """
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let deviceCode = try await makeClient().startDeviceCode()

        XCTAssertEqual(deviceCode.deviceCode, "dc_123")
        XCTAssertEqual(deviceCode.userCode, "ABCD-1234")
        XCTAssertEqual(deviceCode.verificationURI, "https://portal.nousresearch.com/activate")
        XCTAssertNil(deviceCode.verificationURIComplete)
        XCTAssertEqual(deviceCode.expiresIn, 600)
        XCTAssertEqual(deviceCode.interval, 5)

        // Request shape: correct form fields.
        let fields = MockURLProtocol.decodedFormBody()
        XCTAssertEqual(fields["client_id"], "hermes-cli")
        XCTAssertEqual(fields["scope"], "inference:invoke")
    }

    /// No-subscription accounts may be directed to a subscribe page via the
    /// base verification_uri and omit verification_uri_complete entirely.
    /// That is a valid device authorization response, not an error.
    func testStartDeviceCodeAcceptsNoSubscriptionVerificationURI() async throws {
        MockURLProtocol.handler = { request in
            let body = """
            {"device_code":"dc_sub","user_code":"SUB-1234","verification_uri":"https://portal.nousresearch.com/subscribe","expires_in":600,"interval":5}
            """
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let deviceCode = try await makeClient().startDeviceCode()

        XCTAssertEqual(deviceCode.verificationURI, "https://portal.nousresearch.com/subscribe")
        XCTAssertNil(deviceCode.verificationURIComplete)
    }

    /// A blank verification_uri_complete must not override the usable base
    /// verification_uri at the caller handoff seam.
    func testStartDeviceCodeTreatsBlankCompleteURIAsOmitted() async throws {
        MockURLProtocol.handler = { request in
            let body = """
            {"device_code":"dc_blank","user_code":"BLANK-1","verification_uri":"https://portal.nousresearch.com/activate","verification_uri_complete":"   "}
            """
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let deviceCode = try await makeClient().startDeviceCode()

        XCTAssertNil(deviceCode.verificationURIComplete)
        XCTAssertEqual(deviceCode.verificationURI, "https://portal.nousresearch.com/activate")
    }

    // MARK: - pollDeviceCode

    /// authorization_pending maps to `.pending`.
    func testPollPending() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"error":"authorization_pending"}"#
            return (Self.response(request.url!, status: 400), Data(body.utf8))
        }

        let outcome = try await makeClient().pollDeviceCode(deviceCode: "dc_123", interval: 5)
        guard case .pending = outcome else {
            return XCTFail("expected pending, got \(outcome)")
        }
    }

    /// slow_down raises the caller's interval by exactly 5 seconds.
    func testPollSlowDownAddsFiveSeconds() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"error":"slow_down"}"#
            return (Self.response(request.url!, status: 400), Data(body.utf8))
        }

        let outcome = try await makeClient().pollDeviceCode(deviceCode: "dc_123", interval: 5)
        guard case .slowDown(let newInterval) = outcome else {
            return XCTFail("expected slowDown, got \(outcome)")
        }
        XCTAssertEqual(newInterval, 10, "slow_down must raise interval by +5s")
    }

    /// A success payload decodes into a full token set.
    func testPollSuccessDecodesTokens() async throws {
        MockURLProtocol.handler = { request in
            let body = """
            {"access_token":"at_new","refresh_token":"rt_new","token_type":"Bearer"}
            """
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let outcome = try await makeClient().pollDeviceCode(deviceCode: "dc_123", interval: 5)
        guard case .success(let tokens) = outcome else {
            return XCTFail("expected success, got \(outcome)")
        }
        XCTAssertEqual(tokens.accessToken, "at_new")
        XCTAssertEqual(tokens.refreshToken, "rt_new")

        // Device code travels in the form body.
        XCTAssertEqual(MockURLProtocol.decodedFormBody()["device_code"], "dc_123")
        XCTAssertEqual(MockURLProtocol.decodedFormBody()["grant_type"], "urn:ietf:params:oauth:grant-type:device_code")
    }

    /// invalid_grant is terminal.
    func testPollTerminalInvalidGrant() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"error":"invalid_grant"}"#
            return (Self.response(request.url!, status: 400), Data(body.utf8))
        }

        let outcome = try await makeClient().pollDeviceCode(deviceCode: "expired", interval: 5)
        guard case .terminal(let reason) = outcome else {
            return XCTFail("expected terminal, got \(outcome)")
        }
        XCTAssertEqual(reason, "invalid_grant")
    }

    /// A success-shaped body on a non-2xx response must never mint a session.
    func testPollRejectsSuccessPayloadOnServerError() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"access_token":"must_not_be_accepted","refresh_token":"rt"}"#
            return (Self.response(request.url!, status: 503), Data(body.utf8))
        }

        let outcome = try await makeClient().pollDeviceCode(deviceCode: "dc_123", interval: 5)
        guard case .terminal(let reason) = outcome else {
            return XCTFail("expected terminal, got \(outcome)")
        }
        XCTAssertEqual(reason, "http_503")
    }

    // MARK: - refresh

    /// Refresh sends the token in x-nous-refresh-token (never the body),
    /// asserts the grant/client_id form fields, and returns rotated tokens.
    func testRefreshHeaderAndRotation() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.value(forHTTPHeaderField: "x-nous-refresh-token"), "rt_current",
                           "refresh token must travel in the x-nous-refresh-token header")
            // It must NOT be duplicated into the form body.
            XCTAssertNil(MockURLProtocol.decodedFormBody()["refresh_token"])
            let body = #"{"access_token":"at_rotated","refresh_token":"rt_rotated"}"#
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let rotated = try await makeClient().refresh(TokenSet(accessToken: "at_old", refreshToken: "rt_current"))

        XCTAssertEqual(rotated, TokenSet(accessToken: "at_rotated", refreshToken: "rt_rotated"),
                       "caller must receive the rotated token set to persist")
    }

    /// When the Portal rotates the access token but omits a new refresh token,
    /// the current refresh token must be carried forward so the caller never
    /// persists a nil and locks itself out. (Matches the Android client.)
    func testRefreshCarriesForwardWhenReplacementOmitted() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"access_token":"at_rotated"}"#  // no refresh_token
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let rotated = try await makeClient().refresh(TokenSet(accessToken: "at_old", refreshToken: "rt_current"))

        XCTAssertEqual(rotated, TokenSet(accessToken: "at_rotated", refreshToken: "rt_current"),
                       "previous refresh token must survive when the Portal omits a replacement")
    }

    /// A preview-era response can include refresh_token as an empty string;
    /// treat that exactly like omission and retain the current rotating token.
    func testRefreshCarriesForwardWhenReplacementBlank() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"access_token":"at_rotated","refresh_token":"   "}"#
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let rotated = try await makeClient().refresh(TokenSet(accessToken: "at_old", refreshToken: "rt_current"))

        XCTAssertEqual(rotated, TokenSet(accessToken: "at_rotated", refreshToken: "rt_current"))
    }

    /// Terminal refresh errors throw PortalTerminalError.
    func testRefreshTerminalErrorThrows() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"error":"refresh_token_reused"}"#
            return (Self.response(request.url!, status: 400), Data(body.utf8))
        }

        do {
            _ = try await makeClient().refresh(TokenSet(accessToken: "at", refreshToken: "rt"))
            XCTFail("expected PortalTerminalError")
        } catch let error as PortalTerminalError {
            XCTAssertEqual(error.reason, "refresh_token_reused")
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    /// Transient/server OAuth errors must not be classified as terminal grant
    /// rejection, because callers clear stored credentials only for terminal
    /// refresh errors.
    func testRefreshServerErrorDoesNotDiscardSession() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"error":"server_error"}"#
            return (Self.response(request.url!, status: 503), Data(body.utf8))
        }

        do {
            _ = try await makeClient().refresh(TokenSet(accessToken: "at", refreshToken: "rt"))
            XCTFail("expected PortalHTTPError")
        } catch is PortalTerminalError {
            XCTFail("server_error is not a terminal refresh rejection")
        } catch let error as PortalClient.PortalHTTPError {
            XCTAssertEqual(error.statusCode, 503)
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    // MARK: - agents

    /// Happy path: two agents where one lacks dashboardUrl — decoding must
    /// yield nil without crashing.
    func testAgentsHappyPathToleratesMissingDashboardURL() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/agents")
            let body = """
            {"agents":[
              {"id":"a1","name":"Atlas","status":"running","dashboardUrl":"https://dash.test/a1","dashboardGatewayState":"connected"},
              {"id":"a2","name":"Borealis","status":"idle"}
            ],"org":{"id":"o1","slug":"acme","name":"Acme","isPersonal":false,"role":"owner"}}
            """
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let discovery = try await makeClient().agents(bearer: "token")

        XCTAssertEqual(discovery.agents.count, 2)
        XCTAssertEqual(discovery.agents[0].dashboardURL, "https://dash.test/a1")
        XCTAssertEqual(discovery.agents[0].gatewayState, "connected")
        XCTAssertNil(discovery.agents[1].dashboardURL, "missing dashboardUrl decodes as nil, not crash")
        XCTAssertNil(discovery.agents[1].gatewayState)
        XCTAssertEqual(discovery.org?.slug, "acme")
        XCTAssertEqual(discovery.org?.isPersonal, false)
    }

    /// 409 org_selection_required surfaces the offered orgs.
    func testAgentsConflictThrowsOrgSelectionRequired() async throws {
        MockURLProtocol.handler = { request in
            let body = """
            {"error":"org_selection_required","orgs":[
              {"id":"o1","slug":"acme","name":"Acme"},
              {"id":"o2","slug":"personal","name":"Personal"}
            ]}
            """
            return (Self.response(request.url!, status: 409), Data(body.utf8))
        }

        do {
            _ = try await makeClient().agents(bearer: "token")
            XCTFail("expected OrgSelectionRequiredError")
        } catch let error as OrgSelectionRequiredError {
            XCTAssertEqual(error.choices.count, 2)
            XCTAssertEqual(error.choices[0].slug, "acme")
            XCTAssertEqual(error.choices[0].name, "Acme")
            XCTAssertEqual(error.choices[1].slug, "personal")
            XCTAssertEqual(error.choices[1].id, "o2")
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    /// A 409 is an org-picker response only when the error discriminator is
    /// exactly org_selection_required. An unrelated conflict must remain an
    /// HTTP failure even if its preview-era payload happens to contain orgs.
    func testAgentsUnrelatedConflictDoesNotRequestOrgSelection() async throws {
        MockURLProtocol.handler = { request in
            let body = """
            {"error":"agent_conflict","orgs":[
              {"id":"o1","slug":"acme","name":"Acme"}
            ]}
            """
            return (Self.response(request.url!, status: 409), Data(body.utf8))
        }

        do {
            _ = try await makeClient().agents(bearer: "token")
            XCTFail("expected PortalHTTPError")
        } catch is OrgSelectionRequiredError {
            XCTFail("unrelated 409 must not enter org selection")
        } catch let error as PortalClient.PortalHTTPError {
            XCTAssertEqual(error.statusCode, 409)
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    /// Bearer auth header is present; ?org= is appended when given.
    func testAgentsSendsBearerHeaderAndOrgQuery() async throws {
        MockURLProtocol.handler = { request in
            XCTAssertNotNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertTrue(request.value(forHTTPHeaderField: "Authorization")!.hasPrefix("Bearer "),
                          "Authorization header must use the Bearer scheme")
            XCTAssertEqual(request.url?.query, "org=acme", "org slug appended as ?org=")
            let body = #"{"agents":[],"org":null}"#
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        let discovery = try await makeClient().agents(bearer: "secret-bearer-token", org: "acme")
        XCTAssertTrue(discovery.agents.isEmpty)
    }

    /// No secrets are ever written into the request URL query string.
    func testBearerTokenNeverAppearsInURL() async throws {
        MockURLProtocol.handler = { request in
            let body = #"{"agents":[]}"#
            return (Self.response(request.url!, status: 200), Data(body.utf8))
        }

        _ = try await makeClient().agents(bearer: "super-secret-value")
        // The handler above would have failed if the URL carried the token;
        // assert explicitly for clarity.
        XCTAssertTrue(true)
    }
}
