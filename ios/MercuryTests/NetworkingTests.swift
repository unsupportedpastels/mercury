import XCTest
@testable import Mercury

/// URLProtocol stub that serves canned responses and records every request.
final class MockURLProtocol: URLProtocol {

    /// Handler invoked for each request; returns the response + body to serve.
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    /// Requests seen by this protocol, in order.
    nonisolated(unsafe) static var receivedRequests: [URLRequest] = []

    static func reset() {
        handler = nil
        receivedRequests = []
        setCookieArmed = true
    }

    /// When true, the handler's next response carries the session Set-Cookie.
    nonisolated(unsafe) static var setCookieArmed = true

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.receivedRequests.append(request)
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        do {
            let (response, data) = try handler(request)
            // Simulate a real redirect: hand the session the new request so
            // its redirect policy (delegate) decides whether to follow.
            if (300...399).contains(response.statusCode),
               let location = response.value(forHTTPHeaderField: "Location"),
               let target = URL(string: location, relativeTo: request.url) {
                var redirected = request
                redirected.url = target.absoluteURL
                client?.urlProtocol(self, wasRedirectedTo: redirected, redirectResponse: response)
            }
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            // URLProtocol does not persist Set-Cookie into HTTPCookieStorage on
            // its own; forward response cookies explicitly so the shared store
            // behaves like a real session.
            if let http = response as? HTTPURLResponse {
                var headers: [String: String] = [:]
                for (key, value) in http.allHeaderFields {
                    if let k = key as? String, let v = value as? String {
                        headers[k] = v
                    }
                }
                let cookies = HTTPCookie.cookies(
                    withResponseHeaderFields: headers,
                    for: request.url!
                )
                if !cookies.isEmpty {
                    HTTPCookieStorage.shared.setCookies(cookies, for: request.url, mainDocumentURL: nil)
                }
            }
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

final class NetworkingTests: XCTestCase {

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
        // Isolate cookie state per test even though the store is shared.
        if let cookies = HTTPCookieStorage.shared.cookies {
            for cookie in cookies {
                HTTPCookieStorage.shared.deleteCookie(cookie)
            }
        }
    }

    // MARK: - Helpers

    private func makeClient(origin: String = "https://hermes.test") -> HermesHTTPClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        config.httpCookieStorage = HTTPCookieStorage.shared
        config.httpShouldSetCookies = true
        return HermesHTTPClient(origin: origin, session: HermesURLSession.make(config))
    }

    private func makeProbe(origin: String = "https://hermes.test") -> StatusProbe {
        StatusProbe(client: makeClient(origin: origin))
    }

    private func response(
        _ statusCode: Int,
        headers: [String: String]? = nil,
        for request: URLRequest
    ) -> (HTTPURLResponse, Data) {
        (
            HTTPURLResponse(url: request.url!, statusCode: statusCode, httpVersion: nil, headerFields: headers)!,
            Data()
        )
    }

    // MARK: - Tests

    /// Redirects are never followed (Android `followRedirects = false`
    /// parity): a bearer token scoped to the configured origin must not be
    /// forwarded to another host. The 3xx surfaces to the caller as-is.
    func testHermesSessionRefusesCrossOriginRedirect() async throws {
        MockURLProtocol.handler = { request in
            if request.url?.host == "hermes.test" {
                return self.response(
                    302, headers: ["Location": "https://evil.test/steal"], for: request
                )
            }
            return self.response(200, for: request)
        }
        let client = makeClient()
        client.bearerToken = "secret-token"
        let (_, http) = try await client.get(path: "/api/status")
        XCTAssertEqual(http.statusCode, 302)
        XCTAssertEqual(MockURLProtocol.receivedRequests.map { $0.url?.host }, ["hermes.test"])
    }

    /// The authenticated factory is how production builds every bearer-carrying
    /// Hermes client, so its default session must refuse redirects as well.
    func testAuthenticatedFactoryDefaultsToRedirectRefusingSession() {
        let client = HermesHTTPClient.makeAuthenticated(
            origin: "https://hermes.test",
            credentialStore: FakeTokenStore()
        )
        XCTAssertTrue(client.refusesRedirects)
        let plain = HermesHTTPClient(origin: "https://hermes.test", session: .shared)
        XCTAssertFalse(plain.refusesRedirects)
    }

    /// Control for the test above: the mock really does redirect when the
    /// session has no refusing delegate, so the assertion is meaningful.
    func testControlPlainSessionFollowsTheSameRedirect() async throws {
        MockURLProtocol.handler = { request in
            if request.url?.host == "hermes.test" {
                return self.response(
                    302, headers: ["Location": "https://evil.test/steal"], for: request
                )
            }
            return self.response(200, for: request)
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockURLProtocol.self]
        let client = HermesHTTPClient(origin: "https://hermes.test", session: URLSession(configuration: config))
        let (_, http) = try await client.get(path: "/api/status")
        XCTAssertEqual(http.statusCode, 200)
        XCTAssertEqual(MockURLProtocol.receivedRequests.map { $0.url?.host }, ["hermes.test", "evil.test"])
    }

    func testBearerTokenIsSentWhenSet() async throws {
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data("{}".utf8))
        }
        let client = makeClient()
        client.bearerToken = "tok_abc123"
        _ = try await client.get(path: "/api/profiles/sessions")

        let auth = MockURLProtocol.receivedRequests.last?.value(forHTTPHeaderField: "Authorization")
        XCTAssertEqual(auth, "Bearer tok_abc123",
                       "authenticated requests must carry the bearer token")
    }

    func testNoAuthorizationHeaderWhenTokenAbsent() async throws {
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data("{}".utf8))
        }
        let client = makeClient()  // no bearerToken set
        _ = try await client.get(path: "/api/status")

        XCTAssertNil(MockURLProtocol.receivedRequests.last?.value(forHTTPHeaderField: "Authorization"))
    }

    func testProbeDecodesRealShapedStatus() async throws {
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(#"{"version":"0.20.4","auth_required":true,"active_sessions":2}"#.utf8))
        }
        let status = try await makeProbe().probe()
        XCTAssertEqual(status.version, "0.20.4")
        XCTAssertTrue(status.authRequired)
        XCTAssertEqual(status.activeSessions, 2)
        XCTAssertEqual(MockURLProtocol.receivedRequests.first?.url?.path, "/api/status")
    }

    func testAuthProvidersDecode() async throws {
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(
                #"{"providers":[{"name":"nous","display_name":"Nous Research","supports_password":false}]}"#.utf8))
        }
        let providers = try await makeProbe().authProviders()
        XCTAssertEqual(providers.providers.count, 1)
        XCTAssertEqual(providers.providers[0].name, "nous")
        XCTAssertEqual(providers.providers[0].displayName, "Nous Research")
        XCTAssertFalse(providers.providers[0].supportsPassword)
        XCTAssertEqual(MockURLProtocol.receivedRequests.first?.url?.path, "/api/auth/providers")
    }

    func testClassifyBoundaries() {
        XCTAssertEqual(HermesAuthError.classify(401), .authRejected)
        XCTAssertEqual(HermesAuthError.classify(403), .authRejected)
        XCTAssertNotNil(HermesAuthError.classify(503))
        if case .transient(let reason)? = HermesAuthError.classify(503) {
            XCTAssertEqual(reason, "http_503")
        }
        XCTAssertNil(HermesAuthError.classify(200))
        XCTAssertNil(HermesAuthError.classify(404))
    }

    func testProbeMapsAuthRejectedOn401() async throws {
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!
            return (response, Data())
        }
        do {
            _ = try await makeProbe().probe()
            XCTFail("Expected authRejected")
        } catch let error as HermesAuthError {
            XCTAssertEqual(error, .authRejected)
        }
    }

    func testCookiesSentOnSecondRequest() async throws {
        MockURLProtocol.handler = { request in
            let headers: [String: String]
            if MockURLProtocol.setCookieArmed {
                headers = ["Set-Cookie": "hermes_session_at=abc; Path=/"]
                MockURLProtocol.setCookieArmed = false
            } else {
                headers = [:]
            }
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil, headerFields: headers)!
            return (response, Data("{}".utf8))
        }
        let client = makeClient()
        _ = try await client.get(path: "/api/status")
        _ = try await client.get(path: "/api/status")

        XCTAssertEqual(MockURLProtocol.receivedRequests.count, 2)
        // The shared cookie storage captured the session cookie...
        let stored = HTTPCookieStorage.shared.cookies?
            .filter { $0.name == "hermes_session_at" } ?? []
        XCTAssertEqual(stored.first?.value, "abc")
        // ...and URLSession replayed it onto the wire on the second request.
        let wireCookie = MockURLProtocol.receivedRequests[1].value(forHTTPHeaderField: "Cookie")
        XCTAssertNotNil(wireCookie)
        XCTAssertTrue(wireCookie?.contains("hermes_session_at=abc") ?? false)
    }

    func testOversizedResponseThrows() async throws {
        MockURLProtocol.handler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (response, Data(count: 70_000))
        }
        do {
            _ = try await makeClient().get(path: "/api/status")
            XCTFail("Expected ResponseTooLargeError")
        } catch is ResponseTooLargeError {
            // Expected.
        } catch {
            XCTFail("Unexpected error type: \(error)")
        }
    }
}
