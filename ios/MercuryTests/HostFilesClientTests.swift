import Foundation
import XCTest
@testable import Mercury

private final class HostFilesMockURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    nonisolated(unsafe) static var requests: [URLRequest] = []
    nonisolated(unsafe) static var hangsUntilCancelled = false
    nonisolated(unsafe) static var onStart: (() -> Void)?

    static func reset() {
        handler = nil
        requests = []
        hangsUntilCancelled = false
        onStart = nil
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
        Self.onStart?()
        if Self.hangsUntilCancelled { return }
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
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
}

final class HostFilesClientTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HostFilesMockURLProtocol.reset()
    }

    override func tearDown() {
        HostFilesMockURLProtocol.reset()
        super.tearDown()
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HostFilesMockURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func makeClient(
        session: URLSession? = nil,
        refreshProvider: HostFilesClient.BearerRefreshProvider? = nil
    ) throws -> HostFilesClient {
        try HostFilesClient(
            origin: " HTTPS://Hermes.Example/ ",
            bearerToken: "access",
            session: session ?? makeSession(),
            refreshProvider: refreshProvider
        )
    }

    private func response(
        _ request: URLRequest,
        status: Int = 200,
        contentType: String = "application/json",
        contentLength: Int? = nil
    ) -> HTTPURLResponse {
        var headers = ["Content-Type": contentType]
        if let contentLength { headers["Content-Length"] = String(contentLength) }
        return HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: headers)!
    }

    private func body(of request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let bufferSize = 1_024
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let count = stream.read(buffer, maxLength: bufferSize)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }

    func testListingUsesAuthenticatedOfficialQueryAndDecodesTolerantlyWithDedupeAndCap() async throws {
        var rows: [[String: Any]] = [
            ["name": "docs", "path": "/srv/docs", "is_directory": true],
            ["name": "notes.txt", "path": "/srv/notes.txt", "is_directory": false, "size": 5, "mime_type": "text/plain", "mtime": 1.5],
            ["name": "duplicate", "path": "/srv/notes.txt", "is_directory": false, "size": 5],
            ["name": "bad/row", "path": "relative", "is_directory": false],
            ["name": "traversal", "path": "/srv/../secret", "is_directory": false],
            ["name": "socket", "path": "/srv/socket", "is_directory": false, "type": "socket"],
        ]
        for index in 0..<600 {
            rows.append(["name": "file-\(index)", "path": "/srv/file-\(index)", "is_directory": false])
        }
        let payload: [String: Any] = [
            "path": "/srv", "parent": "/", "root": "/", "locked_root": "/srv",
            "can_change_path": false, "entries": rows,
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request), body)
        }

        let listing = try await makeClient().list(path: "/srv")

        let request = try XCTUnwrap(HostFilesMockURLProtocol.requests.first)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.scheme, "https")
        XCTAssertEqual(request.url?.host, "hermes.example")
        XCTAssertEqual(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems,
                       [URLQueryItem(name: "path", value: "/srv")])
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
        XCTAssertEqual(listing.path, "/srv")
        XCTAssertEqual(listing.parentPath, "/")
        XCTAssertEqual(listing.root, "/")
        XCTAssertEqual(listing.lockedRoot, "/srv")
        XCTAssertFalse(listing.canChangePath)
        XCTAssertEqual(listing.entries.count, maxHostFileEntries)
        XCTAssertEqual(Array(listing.entries.prefix(2).map(\.name)), ["docs", "notes.txt"])
        XCTAssertEqual(listing.entries[1].modifiedEpochSeconds, 1.5)
        XCTAssertEqual(Set(listing.entries.map(\.path)).count, listing.entries.count)
    }

    func testReadDecodesAndValidatesDataURL() async throws {
        let json = #"{"name":"notes.txt","path":"/srv/notes.txt","size":5,"mime_type":"text/plain","data_url":"data:text/plain;base64,aGVsbG8="}"#
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request), Data(json.utf8))
        }

        let content = try await makeClient().read(path: "/srv/notes.txt")

        XCTAssertEqual(HostFilesMockURLProtocol.requests.first?.url?.path, "/api/files/read")
        XCTAssertEqual(content.name, "notes.txt")
        XCTAssertEqual(content.path, "/srv/notes.txt")
        XCTAssertEqual(content.mimeType, "text/plain")
        XCTAssertEqual(content.bytes, Data("hello".utf8))
        XCTAssertEqual(content.size, 5)
    }

    func testReadRejectsDataURLMIMEAndSizeMismatches() async throws {
        let payloads = [
            #"{"name":"a.txt","path":"/a.txt","size":5,"mime_type":"text/plain","data_url":"data:application/json;base64,aGVsbG8="}"#,
            #"{"name":"a.txt","path":"/a.txt","size":4,"mime_type":"text/plain","data_url":"data:text/plain;base64,aGVsbG8="}"#,
        ]
        var index = 0
        HostFilesMockURLProtocol.handler = { request in
            defer { index += 1 }
            return (self.response(request), Data(payloads[index].utf8))
        }
        let client = try makeClient()

        for _ in payloads {
            await XCTAssertThrowsErrorAsync { try await client.read(path: "/a.txt") }
        }
    }

    func testDownloadValidatesMIMEDeclaredAndActualByteBounds() async throws {
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, contentType: "text/plain; charset=utf-8", contentLength: 5), Data("hello".utf8))
        }
        let downloaded = try await makeClient().download(path: #"C:\work\notes.txt"#)
        XCTAssertEqual(downloaded.name, "notes.txt")
        XCTAssertEqual(downloaded.mimeType, "text/plain")
        XCTAssertEqual(downloaded.bytes, Data("hello".utf8))
        XCTAssertEqual(HostFilesMockURLProtocol.requests.first?.url?.path, "/api/files/download")

        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, contentType: "text/plain", contentLength: maxHostFileBytes + 1), Data())
        }
        await XCTAssertThrowsErrorAsync { try await self.makeClient().download(path: "/large.bin") }

        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, contentType: "invalid", contentLength: 1), Data([0]))
        }
        await XCTAssertThrowsErrorAsync { try await self.makeClient().download(path: "/bad.bin") }

        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, contentType: "application/octet-stream"), Data(count: maxHostFileBytes + 1))
        }
        await XCTAssertThrowsErrorAsync { try await self.makeClient().download(path: "/actual-large.bin") }
    }

    func testCreateDirectoryPostsManagedMkdirThenReloadsReturnedCanonicalPath() async throws {
        HostFilesMockURLProtocol.handler = { request in
            switch request.url?.path {
            case "/api/files/mkdir":
                XCTAssertEqual(request.httpMethod, "POST")
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
                XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
                XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/json")
                let body = try XCTUnwrap(self.body(of: request))
                let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
                XCTAssertEqual(object, ["path": "/srv/projects/New Folder"])
                return (
                    self.response(request),
                    Data(#"{"ok":true,"path":"/srv/projects/New Folder","created":false}"#.utf8)
                )
            case "/api/files":
                let query = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems
                XCTAssertEqual(query, [URLQueryItem(name: "path", value: "/srv/projects/New Folder")])
                XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
                return (
                    self.response(request),
                    Data(#"{"path":"/srv/projects/New Folder","parent":"/srv/projects","entries":[]}"#.utf8)
                )
            default:
                XCTFail("Unexpected request")
                return (self.response(request, status: 404), Data())
            }
        }

        let listing = try await makeClient().createDirectory(parentPath: "/srv/projects", name: "New Folder")

        XCTAssertEqual(HostFilesMockURLProtocol.requests.map { $0.url?.path }, ["/api/files/mkdir", "/api/files"])
        XCTAssertEqual(listing.path, "/srv/projects/New Folder")
        XCTAssertEqual(listing.parentPath, "/srv/projects")
    }

    func testCreateDirectoryUsesParentWindowsPathStyleForCreateCall() async throws {
        HostFilesMockURLProtocol.handler = { request in
            if request.url?.path == "/api/files/mkdir" {
                let body = try XCTUnwrap(self.body(of: request))
                let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
                XCTAssertEqual(object["path"], #"C:\work\New Folder"#)
                return (self.response(request), Data(#"{"ok":true,"path":"C:\\work\\New Folder"}"#.utf8))
            }
            return (self.response(request), Data(#"{"path":"C:\\work\\New Folder","entries":[]}"#.utf8))
        }

        let listing = try await makeClient().createDirectory(parentPath: #"C:\work"#, name: "New Folder")

        XCTAssertEqual(listing.path, #"C:\work\New Folder"#)
    }

    func testCreateDirectoryRejectsUnsafeChildWithoutDispatch() async throws {
        HostFilesMockURLProtocol.handler = { request in
            XCTFail("Unsafe child must not dispatch")
            return (self.response(request), Data())
        }

        for name in ["../secret", "nested/child", #"nested\child"#, ".", "", "   "] {
            await XCTAssertThrowsErrorAsync {
                try await self.makeClient().createDirectory(parentPath: "/srv/projects", name: name)
            }
        }
        XCTAssertTrue(HostFilesMockURLProtocol.requests.isEmpty)
    }

    func test401RefreshesOnceAndRetriesExactRequestWithRotatedBearer() async throws {
        var attempt = 0
        var refreshCount = 0
        HostFilesMockURLProtocol.handler = { request in
            attempt += 1
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/files")
            XCTAssertEqual(
                request.value(forHTTPHeaderField: "Authorization"),
                attempt == 1 ? "Bearer access" : "Bearer rotated"
            )
            if attempt == 1 {
                return (self.response(request, status: 401), Data())
            }
            return (self.response(request), Data(#"{"path":"/srv","entries":[]}"#.utf8))
        }
        let client = try makeClient(refreshProvider: {
            refreshCount += 1
            return "rotated"
        })

        let listing = try await client.list(path: "/srv")

        XCTAssertEqual(listing.path, "/srv")
        XCTAssertEqual(attempt, 2)
        XCTAssertEqual(refreshCount, 1)
        XCTAssertEqual(HostFilesMockURLProtocol.requests[0].url, HostFilesMockURLProtocol.requests[1].url)
    }

    func testSecond401IsClassifiedWithoutAnotherRefresh() async throws {
        var refreshCount = 0
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, status: 401), Data("malformed".utf8))
        }
        let client = try makeClient(refreshProvider: {
            refreshCount += 1
            return "still-rejected"
        })

        do {
            _ = try await client.list()
            XCTFail("Expected auth rejection")
        } catch let error as HermesAuthError {
            XCTAssertEqual(error, .authRejected)
        }
        XCTAssertEqual(HostFilesMockURLProtocol.requests.count, 2)
        XCTAssertEqual(refreshCount, 1)
    }

    func testRefreshCancellationPropagatesWithoutRetry() async throws {
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, status: 401), Data())
        }
        let client = try makeClient(refreshProvider: { throw CancellationError() })

        do {
            _ = try await client.list()
            XCTFail("Expected CancellationError")
        } catch is CancellationError {
            // Expected.
        }
        XCTAssertEqual(HostFilesMockURLProtocol.requests.count, 1)
    }

    func testBlankRefreshedBearerIsRejectedWithoutRetry() async throws {
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, status: 401), Data())
        }
        let client = try makeClient(refreshProvider: { "  " })

        do {
            _ = try await client.list()
            XCTFail("Expected invalid refreshed bearer")
        } catch let error as HostFilesClientError {
            XCTAssertEqual(error, .invalidBearerToken)
        }
        XCTAssertEqual(HostFilesMockURLProtocol.requests.count, 1)
    }

    func testUnchangedRefreshedBearerIsRejectedWithoutRetry() async throws {
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, status: 401), Data())
        }
        let client = try makeClient(refreshProvider: { "access" })

        do {
            _ = try await client.list()
            XCTFail("Expected unchanged refreshed bearer rejection")
        } catch let error as HostFilesClientError {
            XCTAssertEqual(error, .invalidBearerToken)
        }
        XCTAssertEqual(HostFilesMockURLProtocol.requests.count, 1)
    }

    func testNon401DoesNotRefreshAndStatusWinsOverMkdirDecode() async throws {
        var refreshCount = 0
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, status: 503), Data("not json".utf8))
        }
        let client = try makeClient(refreshProvider: {
            refreshCount += 1
            return "unused"
        })

        do {
            _ = try await client.createDirectory(parentPath: "/srv", name: "new")
            XCTFail("Expected transient status")
        } catch let error as HermesAuthError {
            XCTAssertEqual(error, .transient("http_503"))
        }
        XCTAssertEqual(HostFilesMockURLProtocol.requests.count, 1)
        XCTAssertEqual(refreshCount, 0)
    }

    func testAuthAndHTTPStatusAreClassifiedBeforeMalformedBodyDecode() async throws {
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, status: 401, contentType: "text/plain"), Data("not json".utf8))
        }
        do {
            _ = try await makeClient().list()
            XCTFail("Expected auth rejection")
        } catch let error as HermesAuthError {
            XCTAssertEqual(error, .authRejected)
        }

        HostFilesMockURLProtocol.handler = { request in
            (self.response(request, status: 404, contentType: "text/plain"), Data("not json".utf8))
        }
        do {
            _ = try await makeClient().list()
            XCTFail("Expected HTTP status error")
        } catch let error as HostFilesClientError {
            XCTAssertEqual(error, .httpStatus(404))
        }
    }

    func testInvalidPathDoesNotDispatch() async throws {
        HostFilesMockURLProtocol.handler = { request in
            XCTFail("Invalid path must not dispatch")
            return (self.response(request), Data())
        }
        await XCTAssertThrowsErrorAsync { try await self.makeClient().download(path: "/srv/../secret") }
        XCTAssertTrue(HostFilesMockURLProtocol.requests.isEmpty)
    }

    func testTaskCancellationSurfacesAsCancellationError() async throws {
        let started = expectation(description: "request started")
        HostFilesMockURLProtocol.hangsUntilCancelled = true
        HostFilesMockURLProtocol.onStart = { started.fulfill() }
        let client = try makeClient()
        let task = Task { try await client.list() }

        await fulfillment(of: [started], timeout: 1)
        task.cancel()

        do {
            _ = try await task.value
            XCTFail("Expected CancellationError")
        } catch is CancellationError {
            // Expected: cancellation must not be wrapped as TransportError.
        }
    }

    func testInjectedSessionRemainsUsableAfterClientDeinitializes() async throws {
        let session = makeSession()
        HostFilesMockURLProtocol.handler = { request in
            (self.response(request), Data(#"{"path":"/","entries":[]}"#.utf8))
        }
        var client: HostFilesClient? = try makeClient(session: session)
        _ = try await client?.list()
        client = nil

        let request = URLRequest(url: URL(string: "https://hermes.example/api/files")!)
        let (data, _) = try await session.data(for: request)
        XCTAssertFalse(data.isEmpty)
    }
}

private func XCTAssertThrowsErrorAsync<T>(
    _ expression: @escaping () async throws -> T,
    file: StaticString = #filePath,
    line: UInt = #line
) async {
    do {
        _ = try await expression()
        XCTFail("Expected error", file: file, line: line)
    } catch {
        // Expected.
    }
}
