import XCTest
@testable import Mercury

/// URLProtocol stub serving canned refresh responses and recording requests.
/// Namespaced per file so it cannot collide with other test mocks.
final class RefreshMockURLProtocol: URLProtocol {

    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    /// Requests seen by this protocol, in order.
    nonisolated(unsafe) static var receivedRequests: [URLRequest] = []

    static func reset() {
        handler = nil
        receivedRequests = []
    }

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
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    // MARK: - Body access

    /// `URLSession` converts `httpBody` into a stream; recover either form.
    static func bodyData(of request: URLRequest) -> Data {
        if let body = request.httpBody {
            return body
        }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 4096
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let read = stream.read(buffer, maxLength: bufferSize)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}

final class RefreshTests: XCTestCase {

    private var session: URLSession!

    override func setUp() {
        super.setUp()
        RefreshMockURLProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RefreshMockURLProtocol.self]
        session = URLSession(configuration: config)
    }

    override func tearDown() {
        RefreshMockURLProtocol.reset()
        session = nil
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeResponse(
        status: Int,
        body: Data,
        url: URL = URL(string: "https://hermes.example.com/auth/native/refresh")!
    ) -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: url,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, body)
    }

    /// Fully valid snake_case response payload.
    private func validBody(
        accessToken: String = "at-1",
        refreshToken: String = "rt-1",
        expiresAt: Int64 = 1_900_000_000,
        provider: String = "cloud",
        userID: String = "user-42"
    ) -> Data {
        let escaped: (String) -> String = { value in
            value
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\\"")
        }
        return Data("""
        {"access_token":"\(escaped(accessToken))","refresh_token":"\(escaped(refreshToken))","expires_at":\(expiresAt),"provider":"\(escaped(provider))","user_id":"\(escaped(userID))"}
        """.utf8)
    }

    private func makeClient() -> NativeRefreshClient {
        NativeRefreshClient(session: session)
    }

    private func assertRefreshError(
        _ error: Error,
        equals expected: RefreshError,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        guard let refreshError = error as? RefreshError else {
            XCTFail("Expected RefreshError, got \(error)", file: file, line: line)
            return
        }
        XCTAssertEqual(refreshError, expected, file: file, line: line)
    }

    // MARK: - Happy path

    func testHappyPathDecodesSnakeCaseFieldsIntoNativeTokenSet() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: self.validBody())
        }

        let tokens = try await makeClient().refresh(
            origin: "https://hermes.example.com",
            refreshToken: "rt-old",
            provider: "cloud"
        )

        XCTAssertEqual(tokens.accessToken, "at-1")
        XCTAssertEqual(tokens.refreshToken, "rt-1")
        XCTAssertEqual(tokens.expiresAt, 1_900_000_000)
        XCTAssertEqual(tokens.provider, "cloud")
        XCTAssertEqual(tokens.userID, "user-42")
    }

    // MARK: - Request shape

    func testRequestIsBearerLessPOSTToRefreshPathWithSnakeCaseKeys() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: self.validBody())
        }

        _ = try await makeClient().refresh(
            origin: "https://hermes.example.com",
            refreshToken: "rt-secret",
            provider: "cloud"
        )

        let request = try XCTUnwrap(RefreshMockURLProtocol.receivedRequests.last)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/auth/native/refresh")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

        let body = RefreshMockURLProtocol.bodyData(of: request)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["refresh_token"] as? String, "rt-secret")
        XCTAssertEqual(json["provider"] as? String, "cloud")
        XCTAssertNil(json["refreshToken"], "wire keys must be snake_case")
        XCTAssertNil(json["accessToken"])
    }

    // MARK: - Status mapping

    func testHTTP401MapsToExpired() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 401, body: Data("{}".utf8))
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected expired")
        } catch {
            assertRefreshError(error, equals: .expired)
        }
    }

    func testHTTP503MapsToTransient() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 503, body: Data())
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected transient")
        } catch {
            assertRefreshError(error, equals: .transient)
        }
    }

    func testHTTP500MapsToFailedMentioning500() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 500, body: Data())
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected failed")
        } catch {
            assertRefreshError(error, equals: .failed("Hermes native refresh returned HTTP 500"))
        }
    }

    // MARK: - Response validation

    func testBlankAccessTokenInResponseIsRejected() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: self.validBody(accessToken: ""))
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected failure for blank access_token")
        } catch {
            assertRefreshError(error, equals: .failed("Hermes native refresh returned blank access_token"))
        }
    }

    func testBlankUserIDInResponseIsRejected() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: self.validBody(userID: "   "))
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected failure for blank user_id")
        } catch {
            assertRefreshError(error, equals: .failed("Hermes native refresh returned blank user_id"))
        }
    }

    func testExpiresAtZeroInResponseIsRejected() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: self.validBody(expiresAt: 0))
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected failure for expires_at=0")
        } catch {
            assertRefreshError(error, equals: .failed("Hermes native refresh returned invalid expires_at"))
        }
    }

    func testOversizedResponseFieldIsRejected() async throws {
        let oversized = String(repeating: "a", count: NativeRefreshClient.maxFieldBytes + 1)
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: self.validBody(accessToken: oversized))
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected failure for oversized field")
        } catch {
            XCTAssertTrue(
                "\(error as? RefreshError ?? error)".contains("exceeded"),
                "Expected bounded-field failure, got \(error)"
            )
        }
    }

    func testMalformedResponseBodyFails() async throws {
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: Data("not json".utf8))
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected decode failure")
        } catch {
            assertRefreshError(error, equals: .failed("Hermes native token refresh failed"))
        }
    }

    func testOversizedResponseBodyFails() async throws {
        let huge = Data(repeating: UInt8(ascii: "{"), count: NativeRefreshClient.maxBodyBytes + 1)
        RefreshMockURLProtocol.handler = { _ in
            self.makeResponse(status: 200, body: huge)
        }

        do {
            _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: "rt", provider: "p")
            XCTFail("Expected too-large response failure")
        } catch {
            XCTAssertTrue(
                "\(error)".contains("response exceeded"),
                "Expected bounded-body failure, got \(error)"
            )
        }
    }

    func testBlankRequestInputsAreRejectedBeforeNetworkCall() async throws {
        RefreshMockURLProtocol.handler = { _ in
            XCTFail("Network must not be reached for unusable inputs")
            return self.makeResponse(status: 200, body: self.validBody())
        }

        for (token, provider) in [("", "p"), ("   ", "p"), ("rt", ""), ("rt", "  ")] {
            do {
                _ = try await makeClient().refresh(origin: "https://hermes.example.com", refreshToken: token, provider: provider)
                XCTFail("Expected failure for blank input (\(token), \(provider))")
            } catch {
                XCTAssertEqual(error as? RefreshError, .failed("Hermes native refresh request was incomplete"))
            }
        }
        XCTAssertTrue(RefreshMockURLProtocol.receivedRequests.isEmpty)
    }

    // MARK: - TokenRefreshPolicy

    func testPolicyRefreshesAtExactlySkewBoundary() {
        let now: Int64 = 1_000_000
        XCTAssertTrue(TokenRefreshPolicy.needsRefresh(expiresAt: now + 30, now: now, refreshToken: "rt", provider: "p"))
    }

    func testPolicyDoesNotRefreshOneSecondPastSkewBoundary() {
        let now: Int64 = 1_000_000
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: now + 31, now: now, refreshToken: "rt", provider: "p"))
    }

    func testPolicyNeverRefreshesWithoutExpiry() {
        let now: Int64 = 1_000_000
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: 0, now: now, refreshToken: "rt", provider: "p"))
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: -5, now: now, refreshToken: "rt", provider: "p"))
    }

    func testPolicyNeverRefreshesWithMissingOrBlankCredentials() {
        let now: Int64 = 1_000_000
        let expiring = now + 30

        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: expiring, now: now, refreshToken: nil, provider: "p"))
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: expiring, now: now, refreshToken: "", provider: "p"))
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: expiring, now: now, refreshToken: "   ", provider: "p"))
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: expiring, now: now, refreshToken: "rt", provider: nil))
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: expiring, now: now, refreshToken: "rt", provider: ""))
    }

    func testPolicyFarFutureExpiryNeedsNoRefresh() {
        let now: Int64 = 1_000_000
        XCTAssertFalse(TokenRefreshPolicy.needsRefresh(expiresAt: now + 3_600, now: now, refreshToken: "rt", provider: "p"))
    }

    // MARK: - TokenPair backward compatibility

    func testTokenPairDecodesLegacyPayloadWithDefaultsForNewFields() throws {
        let legacyJSON = Data("""
        {"accessToken":"YWNjZXNz","refreshToken":"cmVmcmVzaA=="}
        """.utf8)

        let pair = try JSONDecoder().decode(TokenPair.self, from: legacyJSON)

        XCTAssertEqual(pair.accessToken, Data("access".utf8))
        XCTAssertEqual(pair.refreshToken, Data("refresh".utf8))
        XCTAssertEqual(pair.expiresAt, 0)
        XCTAssertEqual(pair.provider, "")
    }

    func testTokenPairRoundTripsNewFieldsAndStillDecodesOwnOutput() throws {
        let pair = TokenPair(
            accessToken: Data("access".utf8),
            refreshToken: Data("refresh".utf8),
            expiresAt: 1_900_000_000,
            provider: "cloud"
        )

        let encoded = try JSONEncoder().encode(pair)
        let decoded = try JSONDecoder().decode(TokenPair.self, from: encoded)

        XCTAssertEqual(decoded, pair)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        XCTAssertNil(json["expires_at"], "storage keys remain camelCase (existing keychain format)")
        XCTAssertEqual(json["expiresAt"] as? Int64, 1_900_000_000)
        XCTAssertEqual(json["provider"] as? String, "cloud")
    }

    func testTokenPairLegacyPayloadWithoutRefreshTokenDecodes() throws {
        let minimalJSON = Data("""
        {"accessToken":"YWNjZXNz"}
        """.utf8)

        let pair = try JSONDecoder().decode(TokenPair.self, from: minimalJSON)

        XCTAssertEqual(pair.accessToken, Data("access".utf8))
        XCTAssertNil(pair.refreshToken)
        XCTAssertEqual(pair.expiresAt, 0)
        XCTAssertEqual(pair.provider, "")
    }
}
