import Foundation
import XCTest
@testable import Mercury

// Hermetic tests for the single-flight 401-refresh-and-retry behavior in
// HermesHTTPClient (M4a). Per-file namespaced mock to avoid duplicate-class
// clashes with other suites.

final class RetryMockURLProtocol: URLProtocol {

    /// Ordered script of (status, body) per request path; pops from the front.
    nonisolated(unsafe) static var responsesByPath: [String: [(Int, Data)]] = [:]
    nonisolated(unsafe) static var authorizationHeaders: [String] = []
    nonisolated(unsafe) static var requestCount = 0

    static func reset() {
        responsesByPath = [:]
        authorizationHeaders = []
        requestCount = 0
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requestCount += 1
        if let auth = request.value(forHTTPHeaderField: "Authorization") {
            Self.authorizationHeaders.append(auth)
        }
        let path = request.url?.path ?? ""
        guard var queue = Self.responsesByPath[path], !queue.isEmpty else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        let (status, body) = queue.removeFirst()
        Self.responsesByPath[path] = queue
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

final class HTTPRetryTests: XCTestCase {

    private var session: URLSession!
    private var store: FakeTokenStore!

    override func setUp() {
        super.setUp()
        RetryMockURLProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RetryMockURLProtocol.self]
        session = URLSession(configuration: config)
        store = FakeTokenStore()
    }

    override func tearDown() {
        RetryMockURLProtocol.reset()
        session = nil
        store = nil
        super.tearDown()
    }

    private func makeClient(origin: String = "https://hermes.example.com") -> HermesHTTPClient {
        let client = HermesHTTPClient(origin: origin, session: session)
        return client
    }

    private func enqueue(path: String, _ responses: (Int, Data)...) {
        RetryMockURLProtocol.responsesByPath[path] = Array(responses)
    }

    // MARK: - Happy path

    func test401ThenRefreshThenSingleRetrySucceeds() async throws {
        enqueue(path: "/api/things",
                (401, Data()),
                (200, Data(#"{"ok":true}"#.utf8)))

        let client = makeClient()
        client.bearerToken = "stale"
        var refreshCount = 0
        client.refreshTokenProvider = {
            refreshCount += 1
            return NativeTokenSet(
                accessToken: "fresh", refreshToken: "r2",
                expiresAt: 9_000_000_000, provider: "nous", userID: "u"
            )
        }

        let (data, response) = try await client.get(path: "/api/things")

        XCTAssertEqual(response.statusCode, 200)
        XCTAssertEqual(String(data: data, encoding: .utf8), #"{"ok":true}"#)
        XCTAssertEqual(refreshCount, 1)
        // Exactly two attempts: original + one retry.
        XCTAssertEqual(RetryMockURLProtocol.requestCount, 2)
        // The retry carried the fresh token.
        XCTAssertEqual(RetryMockURLProtocol.authorizationHeaders.last, "Bearer fresh")
        // The fresh token persists for subsequent requests.
        XCTAssertEqual(client.bearerToken, "fresh")
    }

    // MARK: - Single flight

    func testConcurrent401sTriggerOnlyOneRefresh() async throws {
        enqueue(path: "/api/a", (401, Data()), (200, Data("{}".utf8)))
        enqueue(path: "/api/b", (401, Data()), (200, Data("{}".utf8)))

        let client = makeClient()
        client.bearerToken = "stale"
        var refreshCount = 0
        client.refreshTokenProvider = {
            // Slight suspension so both 401s pile up while one is in flight.
            try? await Task.sleep(nanoseconds: 100_000_000)
            refreshCount += 1
            return NativeTokenSet(
                accessToken: "fresh-\(refreshCount)", refreshToken: "r",
                expiresAt: 9_000_000_000, provider: "nous", userID: "u"
            )
        }

        async let a: Void = {
            _ = try? await client.get(path: "/api/a")
        }()
        async let b: Void = {
            _ = try? await client.get(path: "/api/b")
        }()
        _ = await (a, b)

        XCTAssertEqual(refreshCount, 1, "concurrent 401s must share ONE refresh")
    }

    // MARK: - Failure modes

    func testRefreshReturningNilSurfacesOriginalFailureWithoutSecondAttempt() async throws {
        enqueue(path: "/api/x", (401, Data()))

        let client = makeClient()
        client.bearerToken = "stale"
        client.refreshTokenProvider = { nil }

        let (_, response) = try await client.get(path: "/api/x")
        XCTAssertEqual(response.statusCode, 401)
        XCTAssertEqual(RetryMockURLProtocol.requestCount, 1, "no retry without a fresh token")
    }

    func testNoProviderKeepsLegacyBehavior() async throws {
        enqueue(path: "/api/y", (401, Data()))

        let client = makeClient()
        client.bearerToken = "stale"

        let (_, response) = try await client.get(path: "/api/y")
        XCTAssertEqual(response.statusCode, 401)
        XCTAssertEqual(RetryMockURLProtocol.requestCount, 1)
    }

    func testStill401AfterRefreshDoesNotLoop() async throws {
        enqueue(path: "/api/z", (401, Data()), (401, Data()))

        let client = makeClient()
        client.bearerToken = "stale"
        var refreshCount = 0
        client.refreshTokenProvider = {
            refreshCount += 1
            return NativeTokenSet(
                accessToken: "also-bad", refreshToken: "r",
                expiresAt: 9_000_000_000, provider: "nous", userID: "u"
            )
        }

        let (_, response) = try await client.get(path: "/api/z")
        XCTAssertEqual(response.statusCode, 401)
        XCTAssertEqual(RetryMockURLProtocol.requestCount, 2, "original + exactly one retry")
        XCTAssertEqual(refreshCount, 1)
    }

    // MARK: - makeAuthenticated persistence

    func testMakeAuthenticatedPersistsRefreshedTokensIntoStore() async throws {
        enqueue(path: "/api/p", (401, Data()), (200, Data("{}".utf8)))
        enqueue(
            path: "/auth/native/refresh",
            (200, Data(#"{"access_token":"fresh","refresh_token":"rt2","expires_at":9000000000,"provider":"nous","user_id":"u"}"#.utf8))
        )

        let pair = TokenPair(
            accessToken: Data("stale".utf8),
            refreshToken: Data("rt".utf8),
            expiresAt: Int64(Date().timeIntervalSince1970) - 10,
            provider: "nous"
        )
        store.setTokens(pair, for: "https://hermes.example.com")

        let client = HermesHTTPClient.makeAuthenticated(
            origin: "https://hermes.example.com",
            urlSession: session,
            credentialStore: store
        )

        let (_, response) = try await client.get(path: "/api/p")
        XCTAssertEqual(response.statusCode, 200)

        // The refreshed pair was written back under the same origin key.
        let updated = try XCTUnwrap(store.tokens(for: "https://hermes.example.com"))
        XCTAssertEqual(String(data: updated.accessToken, encoding: .utf8), client.bearerToken)
        XCTAssertGreaterThan(updated.expiresAt, pair.expiresAt)
    }
}

/// Minimal in-memory CredentialStoring fake for retry tests.
final class FakeTokenStore: CredentialStoring {
    private var storage: [String: TokenPair] = [:]

    func tokens(for origin: String) -> TokenPair? { storage[origin] }
    func setTokens(_ tokens: TokenPair, for origin: String) { storage[origin] = tokens }
    func clearTokens(for origin: String) { storage.removeValue(forKey: origin) }
}
