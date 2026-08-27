import Foundation
import XCTest
@testable import Mercury

private final class PasswordAuthMockURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    static var requests: [URLRequest] = []

    static func reset() {
        handler = nil
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        do {
            Self.requests.append(request)
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

final class PasswordAuthTests: XCTestCase {
    override func setUp() {
        super.setUp()
        PasswordAuthMockURLProtocol.reset()
    }

    override func tearDown() {
        PasswordAuthMockURLProtocol.reset()
        super.tearDown()
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [PasswordAuthMockURLProtocol.self]
        configuration.httpCookieStorage = HTTPCookieStorage.shared
        configuration.httpShouldSetCookies = true
        return URLSession(configuration: configuration)
    }

    private func response(_ request: URLRequest, status: Int, headers: [String: String] = [:]) -> HTTPURLResponse {
        var allHeaders = ["Content-Type": "application/json"]
        allHeaders.merge(headers) { _, new in new }
        return HTTPURLResponse(
            url: request.url!, statusCode: status, httpVersion: nil, headerFields: allHeaders
        )!
    }

    private func capturedHTTPBody(of request: URLRequest) -> Data {
        guard let stream = request.httpBodyStream else { return request.httpBody ?? Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 4096)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let count = stream.read(buffer, maxLength: 4096)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }

    func testPasswordLoginMatchesAndroidRequestContract() async throws {
        PasswordAuthMockURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/auth/password-login")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            let body = self.capturedHTTPBody(of: request)
            XCTAssertFalse(body.isEmpty)
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(object, [
                "provider": "basic",
                "username": "admin",
                "password": "fixture-password",
                "next": "/",
            ])
            return (
                self.response(
                    request,
                    status: 200,
                    headers: ["Set-Cookie": "hermes_session=test-only; Path=/; HttpOnly; Secure"]
                ),
                Data(#"{"ok":true}"#.utf8)
            )
        }

        try await PasswordLoginClient(session: makeSession()).signIn(
            origin: "https://hermes.example.com",
            provider: "basic",
            username: " admin ",
            password: "fixture-password"
        )

        XCTAssertEqual(PasswordAuthMockURLProtocol.requests.count, 1)
    }

    func testPasswordLoginRejectsInvalidCredentialsWithoutLeakingThem() async {
        PasswordAuthMockURLProtocol.handler = { request in
            (self.response(request, status: 401), Data(#"{"detail":"wrong"}"#.utf8))
        }

        do {
            try await PasswordLoginClient(session: makeSession()).signIn(
                origin: "https://hermes.example.com",
                provider: "basic",
                username: "admin",
                password: "fixture-password"
            )
            XCTFail("Expected invalid credentials")
        } catch let error as PasswordLoginError {
            XCTAssertEqual(error, .invalidCredentials)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testPasswordLoginBoundsCredentialsBeforeNetwork() async {
        PasswordAuthMockURLProtocol.handler = { request in
            XCTFail("Invalid credentials must not reach the network")
            return (self.response(request, status: 500), Data())
        }
        let client = PasswordLoginClient(session: makeSession())

        for (username, password) in [
            ("", "password"),
            (String(repeating: "u", count: 257), "password"),
            ("admin", ""),
            ("admin", String(repeating: "p", count: 4097)),
        ] {
            do {
                try await client.signIn(
                    origin: "https://hermes.example.com",
                    provider: "basic",
                    username: username,
                    password: password
                )
                XCTFail("Expected local rejection")
            } catch let error as PasswordLoginError {
                XCTAssertEqual(error, .invalidCredentials)
            } catch {
                XCTFail("Unexpected error: \(error)")
            }
        }
        XCTAssertTrue(PasswordAuthMockURLProtocol.requests.isEmpty)
    }
}
