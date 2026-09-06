import XCTest
import MercuryCore
@testable import Mercury

final class ManagedVideoTests: XCTestCase {
    private var root: URL!
    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws {
        VideoProtocol.handler = nil
        try? FileManager.default.removeItem(at: root)
    }
    private func configuration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [VideoProtocol.self]
        config.httpCookieStorage = nil
        return config
    }
    private func request() -> URLRequest {
        URLRequest(url: URL(string: "https://video.example/api/files/download?path=%2Ftmp%2Fclip.mp4")!)
    }
    private func files() throws -> [URL] { try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) }

    func testSharedVideoAdapterOnlyExplicitMediaPromotesManagedVideo() {
        let artifacts = MediaDirectiveExtractor.extract("MEDIA:/tmp/clip.mp4\nMEDIA:https://example.com/clip.webm")
        XCTAssertEqual(artifacts.map(\.type), [.video, .video])
        XCTAssertEqual(artifacts.map(\.origin), [.managedPath, .remoteURL])
        XCTAssertFalse(MediaDirectiveExtractor.extract("`/tmp/clip.mp4`").contains { $0.type == .video })
        XCTAssertTrue(ManagedVideoPolicy.shared.isVideoMimeType(contentType: "video/mp4; codecs=avc1"))
        XCTAssertFalse(ManagedVideoPolicy.shared.isVideoMimeType(contentType: "application/octet-stream"))
    }

    func testStreamsToAtomicLocalFileWithNoPartLeft() async throws {
        VideoProtocol.handler = { p in
            p.respond(headers: ["Content-Type": "video/mp4"])
            p.client?.urlProtocol(p, didLoad: Data([1, 2]))
            p.client?.urlProtocol(p, didLoad: Data([3, 4]))
            p.client?.urlProtocolDidFinishLoading(p)
        }
        let file = try await ManagedVideoDownload(maximumBytes: 4).download(request: request(), directory: root, configuration: configuration())
        XCTAssertTrue(file.url.isFileURL)
        XCTAssertNil(file.url.query)
        XCTAssertEqual(try Data(contentsOf: file.url), Data([1, 2, 3, 4]))
        XCTAssertEqual(try files().count, 1)
        XCTAssertFalse(try files().contains { $0.pathExtension == "part" })
    }

    func testRejectsOversizedHeaderBeforeAnyBodyOrPublication() async throws {
        VideoProtocol.handler = { $0.respond(headers: ["Content-Type": "video/mp4", "Content-Length": "5"]) }
        do {
            _ = try await ManagedVideoDownload(maximumBytes: 4).download(request: request(), directory: root, configuration: configuration())
            XCTFail("Oversize header accepted")
        } catch { }
        XCTAssertTrue(try files().isEmpty)
    }

    func testRejectsMissingAndNonVideoMimeWithoutBody() async throws {
        for headers in [[:], ["Content-Type": "text/html"], ["Content-Type": "application/octet-stream"]] {
            VideoProtocol.handler = { $0.respond(headers: headers) }
            do {
                _ = try await ManagedVideoDownload().download(request: request(), directory: root, configuration: configuration())
                XCTFail("Unsafe MIME accepted")
            } catch { }
            XCTAssertTrue(try files().isEmpty)
        }
    }

    func testUnknownLengthStreamCapCancelsAndRemovesPart() async throws {
        VideoProtocol.handler = { p in
            p.respond(headers: ["Content-Type": "video/mp4"])
            p.client?.urlProtocol(p, didLoad: Data(repeating: 1, count: 3))
            p.client?.urlProtocol(p, didLoad: Data(repeating: 1, count: 2))
        }
        do {
            _ = try await ManagedVideoDownload(maximumBytes: 4).download(request: request(), directory: root, configuration: configuration())
            XCTFail("Chunked overflow accepted")
        } catch { XCTAssertTrue(error is ResponseTooLargeError) }
        XCTAssertTrue(try files().isEmpty)
    }

    func testCancellationWhileWaitingForHeadersResumesAndCleansPart() async throws {
        let started = expectation(description: "request started")
        VideoProtocol.handler = { _ in started.fulfill() }
        let downloader = ManagedVideoDownload()
        let request = request(), directory = root!, config = configuration()
        let work = Task { try await downloader.download(request: request, directory: directory, configuration: config) }
        await fulfillment(of: [started], timeout: 2)
        work.cancel()
        do { _ = try await work.value; XCTFail("Cancelled download returned a file") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertTrue(try files().isEmpty)
    }

    func testCancellationDuringBodyRemovesPartial() async throws {
        let started = expectation(description: "body sent")
        VideoProtocol.handler = { p in
            p.respond(headers: ["Content-Type": "video/mp4"])
            p.client?.urlProtocol(p, didLoad: Data([1, 2]))
            started.fulfill()
        }
        let downloader = ManagedVideoDownload()
        let request = request(), directory = root!, config = configuration()
        let work = Task { try await downloader.download(request: request, directory: directory, configuration: config) }
        await fulfillment(of: [started], timeout: 2)
        work.cancel()
        do { _ = try await work.value; XCTFail("Cancelled stream returned a file") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertTrue(try files().isEmpty)
    }

    func testRedirectDuringDownloadResumesFailureAndRemovesPartial() async throws {
        let started = expectation(description: "request started")
        VideoProtocol.handler = { _ in started.fulfill() }
        let downloader = ManagedVideoDownload()
        let request = request(), directory = root!, config = configuration()
        let work = Task { try await downloader.download(request: request, directory: directory, configuration: config) }
        await fulfillment(of: [started], timeout: 2)
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let response = HTTPURLResponse(url: request.url!, statusCode: 302, httpVersion: nil,
                                       headerFields: ["Location": "https://other.example/clip.mp4"])!
        downloader.urlSession(session, task: session.dataTask(with: request), willPerformHTTPRedirection: response, newRequest: request) {
            XCTAssertNil($0)
        }
        do { _ = try await work.value; XCTFail("Redirect returned a file") }
        catch { XCTAssertEqual((error as? URLError)?.code, .redirectToNonExistentLocation) }
        XCTAssertTrue(try files().isEmpty)
    }

    func testCancellationBeforeStartDoesNotMakePart() async throws {
        let downloader = ManagedVideoDownload()
        downloader.cancel()
        do { _ = try await downloader.download(request: request(), directory: root, configuration: configuration()); XCTFail() }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertTrue(try files().isEmpty)
    }

    func testRedirectDelegateRefusesEvenSameOriginAndNeverReturnsCredentials() {
        let downloader = ManagedVideoDownload()
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        var request = request()
        request.setValue("Bearer synthetic", forHTTPHeaderField: "Authorization")
        let response = HTTPURLResponse(url: request.url!, statusCode: 302, httpVersion: nil, headerFields: ["Location": "https://other.example/clip.mp4"])!
        downloader.urlSession(session, task: session.dataTask(with: request), willPerformHTTPRedirection: response, newRequest: request) {
            XCTAssertNil($0)
        }
    }

    func testCacheScopesOriginsProfilesAndProtectsLeasedFiles() throws {
        let a = try ManagedVideoCache.directory(origin: "https://one.example", profile: "default", root: root)
        let b = try ManagedVideoCache.directory(origin: "https://two.example", profile: "default", root: root)
        let c = try ManagedVideoCache.directory(origin: "https://one.example", profile: "other", root: root)
        XCTAssertNotEqual(a, b); XCTAssertNotEqual(a, c)
        let active = a.appendingPathComponent("active.mp4"), stale = a.appendingPathComponent("stale.mp4")
        try Data([1]).write(to: active); try Data([2]).write(to: stale)
        var lease: ManagedVideoFile? = ManagedVideoFile(url: active)
        ManagedVideoCache.prune(root: root, limit: 0)
        XCTAssertTrue(FileManager.default.fileExists(atPath: lease!.url.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stale.path))
        lease = nil
        ManagedVideoCache.prune(root: root, limit: 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: active.path))
    }

    func testOfficialHTTPAdapterUsesBearerPathQueryAndLocalPlayerURL() async throws {
        VideoProtocol.handler = { p in
            XCTAssertEqual(p.request.url?.path, "/api/files/download")
            let query = URLComponents(url: p.request.url!, resolvingAgainstBaseURL: false)?.queryItems
            XCTAssertEqual(query, [URLQueryItem(name: "path", value: "/tmp/clip.mp4")])
            XCTAssertEqual(p.request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic")
            p.respond(headers: ["Content-Type": "video/mp4"])
            p.client?.urlProtocol(p, didLoad: Data([1]))
            p.client?.urlProtocolDidFinishLoading(p)
        }
        let session = URLSession(configuration: configuration())
        defer { session.invalidateAndCancel() }
        let client = HermesHTTPClient(origin: "https://video.example", session: session)
        client.bearerToken = "synthetic"
        let file = try await client.downloadManagedVideo(path: "/tmp/clip.mp4", profile: "video-test")
        defer { try? FileManager.default.removeItem(at: file.url) }
        XCTAssertTrue(file.url.isFileURL)
        XCTAssertFalse(file.url.absoluteString.contains("synthetic"))
    }

    func testExpiredVideoBearerRefreshesOnceBeforeRetry() async throws {
        var calls = 0
        VideoProtocol.handler = { p in
            calls += 1
            if calls == 1 {
                p.respond(headers: [:], status: 401)
                p.client?.urlProtocolDidFinishLoading(p)
                return
            }
            XCTAssertEqual(p.request.value(forHTTPHeaderField: "Authorization"), "Bearer refreshed")
            p.respond(headers: ["Content-Type": "video/mp4"])
            p.client?.urlProtocol(p, didLoad: Data([1]))
            p.client?.urlProtocolDidFinishLoading(p)
        }
        let session = URLSession(configuration: configuration())
        defer { session.invalidateAndCancel() }
        let client = HermesHTTPClient(origin: "https://video.example", session: session)
        client.bearerToken = "expired"
        var refreshes = 0
        client.refreshTokenProvider = {
            refreshes += 1
            return NativeTokenSet(accessToken: "refreshed", refreshToken: "r2", expiresAt: 9_000_000_000, provider: "nous", userID: "u")
        }
        let file = try await client.downloadManagedVideo(path: "/tmp/clip.mp4", profile: "video-test")
        defer { try? FileManager.default.removeItem(at: file.url) }
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(refreshes, 1)
    }

    func testVideoAuthRetryStopsAfterSecond401() async throws {
        var calls = 0
        VideoProtocol.handler = { p in
            calls += 1
            p.respond(headers: [:], status: 401)
            p.client?.urlProtocolDidFinishLoading(p)
        }
        let session = URLSession(configuration: configuration())
        defer { session.invalidateAndCancel() }
        let client = HermesHTTPClient(origin: "https://video.example", session: session)
        var refreshes = 0
        client.refreshTokenProvider = {
            refreshes += 1
            return NativeTokenSet(accessToken: "refreshed", refreshToken: "r2", expiresAt: 9_000_000_000, provider: "nous", userID: "u")
        }
        do {
            _ = try await client.downloadManagedVideo(path: "/tmp/clip.mp4", profile: "video-test")
            XCTFail("Repeated auth rejection must fail")
        } catch { }
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(refreshes, 1)
    }
}

private final class VideoProtocol: URLProtocol {
    static var handler: ((VideoProtocol) -> Void)?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() { Self.handler?(self) }
    override func stopLoading() { }
    func respond(headers: [String: String], status: Int = 200) {
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!, cacheStoragePolicy: .notAllowed)
    }
}
