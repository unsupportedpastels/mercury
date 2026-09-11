import XCTest
@testable import Mercury

private final class ManagedImageLoadingURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    nonisolated(unsafe) static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        do {
            let (response, data) = try XCTUnwrap(Self.handler)(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }
    override func stopLoading() {}
}

final class ManagedImageLoadingTests: XCTestCase {
    override func setUp() {
        super.setUp()
        ManagedImageLoadingURLProtocol.requests = []
        ManagedImageLoadingURLProtocol.handler = nil
    }

    override func tearDown() {
        ManagedImageLoadingURLProtocol.requests = []
        ManagedImageLoadingURLProtocol.handler = nil
        super.tearDown()
    }

    private func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ManagedImageLoadingURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func response(_ request: URLRequest, status: Int = 200, mime: String = "image/png") -> HTTPURLResponse {
        HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": mime])!
    }

    func testDirectProductionLoaderAcceptsRealPreviewSizedBodyBeyondGenericJSONCap() async throws {
        let previewByteCount = 170_924
        var body = Data([137, 80, 78, 71, 13, 10, 26, 10])
        body.append(Data(repeating: 0xA5, count: previewByteCount - body.count))
        ManagedImageLoadingURLProtocol.handler = { request in
            (self.response(request), body)
        }
        let client = HermesHTTPClient(origin: "https://images.example", session: session())
        client.bearerToken = "synthetic-token"

        do {
            _ = try await client.get(
                path: "/api/files/download",
                queryItems: [URLQueryItem(name: "path", value: "/tmp/mercury-chat-preview.png")]
            )
            XCTFail("The generic JSON transport must retain its 64 KiB cap")
        } catch is ResponseTooLargeError {}

        let loaded = try await ManagedImageLoader.direct(client: client, path: "/tmp/mercury-chat-preview.png")

        XCTAssertEqual(loaded.count, previewByteCount)
        XCTAssertEqual(ManagedImageLoadingURLProtocol.requests.count, 2)
        let request = try XCTUnwrap(ManagedImageLoadingURLProtocol.requests.last)
        XCTAssertEqual(request.url?.path, "/api/files/download")
        XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems,
                       [URLQueryItem(name: "path", value: "/tmp/mercury-chat-preview.png")])
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "image/*")
    }

    func testDirectProductionLoaderKeepsPathMimeAndTenMiBBounds() async throws {
        let client = HermesHTTPClient(origin: "https://images.example", session: session())
        for invalid in ["tmp/bare.png", "https://example.com/x.png", "/tmp/../secret.png", "//host/x.png", "/tmp/image.bin"] {
            do {
                _ = try await ManagedImageLoader.direct(client: client, path: invalid)
                XCTFail("Invalid managed path must fail: \(invalid)")
            } catch {}
        }
        XCTAssertTrue(ManagedImageLoadingURLProtocol.requests.isEmpty)

        ManagedImageLoadingURLProtocol.handler = { request in
            (self.response(request, mime: "text/html"), Data([1]))
        }
        do {
            _ = try await ManagedImageLoader.direct(client: client, path: "/tmp/image.png")
            XCTFail("Non-image MIME must fail")
        } catch {}

        ManagedImageLoadingURLProtocol.handler = { request in
            (self.response(request), Data(repeating: 0, count: 10 * 1024 * 1024 + 1))
        }
        do {
            _ = try await ManagedImageLoader.direct(client: client, path: "/tmp/image.png")
            XCTFail("Oversized image must fail")
        } catch is ResponseTooLargeError {}
    }

    func testDirectProductionLoaderPreservesAppleNativeManagedImageFormats() async throws {
        ManagedImageLoadingURLProtocol.handler = { request in
            (self.response(request, mime: "image/heic"), Data([1, 2, 3]))
        }
        let client = HermesHTTPClient(origin: "https://images.example", session: session())

        for path in ["/tmp/photo.heic", "/tmp/scan.tif", "/tmp/page.tiff"] {
            let data = try await ManagedImageLoader.direct(client: client, path: path)
            XCTAssertEqual(data, Data([1, 2, 3]))
        }
        XCTAssertEqual(
            ManagedImageLoadingURLProtocol.requests.compactMap {
                URLComponents(url: $0.url!, resolvingAgainstBaseURL: false)?.queryItems?.first?.value
            },
            ["/tmp/photo.heic", "/tmp/scan.tif", "/tmp/page.tiff"]
        )
    }

    func testRelayProductionLoaderUsesCapabilityGatedReaderAndExactProfilePath() async throws {
        let reader = RelayImageReader()
        var methods: [String] = []
        let bytes = try await ManagedImageLoader.relay(reader: reader, profile: "work", path: "/tmp/relay.png") { method, params in
            methods.append(method)
            if method == "relay.status" {
                return ["capabilities": ["image_read": [
                    "method": "relay.image.read", "max_bytes": 2 * 1024 * 1024,
                    "mime_types": ["image/png", "image/jpeg"],
                ]]]
            }
            XCTAssertEqual(params["profile"] as? String, "work")
            XCTAssertEqual(params["path"] as? String, "/tmp/relay.png")
            return ["mime_type": "image/png", "size": 8, "base64": "iVBORw0KGgo="]
        }
        XCTAssertEqual(methods, ["relay.status", "relay.image.read"])
        XCTAssertEqual(bytes, Data(base64Encoded: "iVBORw0KGgo="))
    }

    func testProductionRenderPolicyPreservesMediaAndLocalMarkdownOnly() {
        let artifacts = ChatManagedImagePolicy.artifacts(in: """
        MEDIA:/tmp/media.png
        ![](/tmp/markdown-image.png)
        MEDIA:/tmp/photo.heic
        ![scan](/tmp/scan.tif)
        MEDIA:/tmp/page.tiff
        /tmp/bare.png
        [ordinary](/tmp/ordinary.png)
        ![remote](https://cdn.example/remote.png)
        ``![code](/tmp/code.png)``
        ~~~~markdown
        MEDIA:/tmp/fenced-media.png
        ![fenced](/tmp/fenced-markdown.png)
        ~~~
        MEDIA:/tmp/still-fenced.png
        ~~~~
        """)
        XCTAssertEqual(
            artifacts.map(\.source),
            ["/tmp/media.png", "/tmp/markdown-image.png", "/tmp/photo.heic", "/tmp/scan.tif", "/tmp/page.tiff"]
        )
        XCTAssertTrue(artifacts.allSatisfy { $0.origin == .managedPath && $0.type == .image })
    }
}
