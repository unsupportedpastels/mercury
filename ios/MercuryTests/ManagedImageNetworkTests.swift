import XCTest
@testable import Mercury

private final class ManagedImageNetworkURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((ManagedImageNetworkURLProtocol) -> Void)?
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var onStop: (() -> Void)?
    private let stopObserver = ManagedImageNetworkURLProtocol.onStop

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        Self.handler?(self)
    }

    override func stopLoading() {
        stopObserver?()
    }

    func respond(status: Int = 200, headers: [String: String] = ["Content-Type": "image/png"]) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: nil,
            headerFields: headers
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    }
}

final class ManagedImageNetworkTests: XCTestCase {
    override func setUp() {
        super.setUp()
        ManagedImageNetworkURLProtocol.handler = nil
        ManagedImageNetworkURLProtocol.requests = []
        ManagedImageNetworkURLProtocol.onStop = nil
    }

    override func tearDown() {
        ManagedImageNetworkURLProtocol.handler = nil
        ManagedImageNetworkURLProtocol.requests = []
        super.tearDown()
    }

    private func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ManagedImageNetworkURLProtocol.self]
        configuration.httpCookieStorage = HTTPCookieStorage.shared
        return URLSession(configuration: configuration)
    }

    func testUnknownLengthOverflowStopsTransferBeforeCompletion() async throws {
        let stopped = expectation(description: "underlying transfer stopped")
        ManagedImageNetworkURLProtocol.onStop = { stopped.fulfill() }
        let started = expectation(description: "request started")
        ManagedImageNetworkURLProtocol.handler = { _ in started.fulfill() }
        let downloader = ManagedImageDownload(maximumBytes: 4)
        let request = URLRequest(url: URL(string: "https://images.example/image.png")!)
        let configuration = session().configuration
        let work = Task { try await downloader.download(request: request, configuration: configuration) }
        await fulfillment(of: [started], timeout: 1)
        let callbackSession = URLSession(configuration: .ephemeral)
        defer { callbackSession.invalidateAndCancel() }
        let callbackTask = callbackSession.dataTask(with: request)
        let response = HTTPURLResponse(
            url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "image/png"]
        )!
        downloader.urlSession(callbackSession, dataTask: callbackTask, didReceive: response) {
            XCTAssertEqual($0, .allow)
        }
        downloader.urlSession(callbackSession, dataTask: callbackTask, didReceive: Data([1, 2, 3]))
        downloader.urlSession(callbackSession, dataTask: callbackTask, didReceive: Data([4, 5]))
        do {
            _ = try await work.value
            XCTFail("Oversized stream returned data")
        } catch is ResponseTooLargeError {}

        await fulfillment(of: [stopped], timeout: 2)
    }

    func testMissingAndDishonestContentLengthUseActualByteCap() async throws {
        var attempt = 0
        ManagedImageNetworkURLProtocol.handler = { protocolInstance in
            attempt += 1
            let headers = attempt == 1
                ? ["Content-Type": "image/png"]
                : ["Content-Type": "image/png", "Content-Length": "3"]
            protocolInstance.respond(headers: headers)
            let data = attempt == 1 ? Data([1, 2, 3, 4]) : Data([1, 2, 3, 4, 5])
            protocolInstance.client?.urlProtocol(protocolInstance, didLoad: data)
            protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
        }
        let client = HermesHTTPClient(origin: "https://images.example", session: session())

        let exactLimitData = try await client.downloadManagedImage(
            path: "/tmp/image.png",
            maximumResponseBytes: 4
        )
        XCTAssertEqual(exactLimitData, Data([1, 2, 3, 4]))
        do {
            _ = try await client.downloadManagedImage(path: "/tmp/image.png", maximumResponseBytes: 4)
            XCTFail("Dishonest Content-Length bypassed actual-byte cap")
        } catch is ResponseTooLargeError {}
    }

    func testDeclaredOversizeStopsBeforeBodyIsConsumed() async throws {
        let stopped = expectation(description: "underlying transfer stopped")
        ManagedImageNetworkURLProtocol.onStop = { stopped.fulfill() }
        let started = expectation(description: "request started")
        ManagedImageNetworkURLProtocol.handler = { _ in started.fulfill() }
        let downloader = ManagedImageDownload(maximumBytes: 4)
        let request = URLRequest(url: URL(string: "https://images.example/image.png")!)
        let configuration = session().configuration
        let work = Task { try await downloader.download(request: request, configuration: configuration) }
        await fulfillment(of: [started], timeout: 1)
        let callbackSession = URLSession(configuration: .ephemeral)
        defer { callbackSession.invalidateAndCancel() }
        let callbackTask = callbackSession.dataTask(with: request)
        let response = HTTPURLResponse(
            url: request.url!, statusCode: 200, httpVersion: nil,
            headerFields: ["Content-Type": "image/png", "Content-Length": "5"]
        )!
        downloader.urlSession(callbackSession, dataTask: callbackTask, didReceive: response) {
            XCTAssertEqual($0, .cancel)
        }
        do {
            _ = try await work.value
            XCTFail("Oversized declaration was accepted")
        } catch is ResponseTooLargeError {}
        await fulfillment(of: [stopped], timeout: 2)
    }

    func test401RefreshRetryKeepsCapAndOriginCredentials() async throws {
        let cookieStorage = HTTPCookieStorage.shared
        let cookie = HTTPCookie(properties: [
            .domain: "images.example", .path: "/", .name: "session", .value: "synthetic-cookie",
            .secure: "TRUE", .expires: Date(timeIntervalSinceNow: 60),
        ])!
        cookieStorage.setCookie(cookie)
        defer { cookieStorage.deleteCookie(cookie) }

        ManagedImageNetworkURLProtocol.handler = { protocolInstance in
            let attempt = ManagedImageNetworkURLProtocol.requests.count
            if attempt == 1 {
                protocolInstance.respond(status: 401, headers: ["Content-Type": "application/json"])
                protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
                return
            }
            protocolInstance.respond()
            protocolInstance.client?.urlProtocol(protocolInstance, didLoad: Data([1, 2, 3]))
            protocolInstance.client?.urlProtocol(protocolInstance, didLoad: Data([4, 5]))
            protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
        }
        let client = HermesHTTPClient(origin: "https://images.example", session: session())
        client.bearerToken = "expired"
        client.refreshTokenProvider = {
            NativeTokenSet(
                accessToken: "refreshed", refreshToken: "refresh-2", expiresAt: 9_000_000_000,
                provider: "nous", userID: "user"
            )
        }

        do {
            _ = try await client.downloadManagedImage(path: "/tmp/image.png", maximumResponseBytes: 4)
            XCTFail("Retry bypassed the stream cap")
        } catch is ResponseTooLargeError {}

        XCTAssertEqual(ManagedImageNetworkURLProtocol.requests.count, 2)
        XCTAssertEqual(ManagedImageNetworkURLProtocol.requests[0].value(forHTTPHeaderField: "Authorization"), "Bearer expired")
        XCTAssertEqual(ManagedImageNetworkURLProtocol.requests[1].value(forHTTPHeaderField: "Authorization"), "Bearer refreshed")
        XCTAssertEqual(ManagedImageNetworkURLProtocol.requests[0].value(forHTTPHeaderField: "Cookie"), "session=synthetic-cookie")
        XCTAssertEqual(ManagedImageNetworkURLProtocol.requests[1].value(forHTTPHeaderField: "Cookie"), "session=synthetic-cookie")
    }

    func testCallerCancellationStopsInFlightTransfer() async throws {
        let stopped = expectation(description: "underlying transfer stopped")
        ManagedImageNetworkURLProtocol.onStop = { stopped.fulfill() }
        let started = expectation(description: "stream started")
        ManagedImageNetworkURLProtocol.handler = { protocolInstance in
            protocolInstance.respond()
            protocolInstance.client?.urlProtocol(protocolInstance, didLoad: Data([1, 2]))
            started.fulfill()
        }
        let client = HermesHTTPClient(origin: "https://images.example", session: session())
        let work = Task {
            try await client.downloadManagedImage(path: "/tmp/image.png", maximumResponseBytes: 4)
        }
        await fulfillment(of: [started], timeout: 2)

        work.cancel()
        do {
            _ = try await work.value
            XCTFail("Cancelled transfer returned data")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
        await fulfillment(of: [stopped], timeout: 2)
    }

    func testRedirectIsRefusedWithoutForwardingCredentials() {
        let downloader = ManagedImageDownload(maximumBytes: 4)
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        var original = URLRequest(url: URL(string: "https://images.example/image.png")!)
        original.setValue("Bearer synthetic", forHTTPHeaderField: "Authorization")
        let redirected = URLRequest(url: URL(string: "https://other.example/image.png")!)
        let response = HTTPURLResponse(
            url: original.url!, statusCode: 302, httpVersion: nil,
            headerFields: ["Location": redirected.url!.absoluteString]
        )!

        downloader.urlSession(
            session,
            task: session.dataTask(with: original),
            willPerformHTTPRedirection: response,
            newRequest: redirected
        ) {
            XCTAssertNil($0)
        }
    }
}
