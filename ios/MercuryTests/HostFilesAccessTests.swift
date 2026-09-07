import Foundation
import XCTest
@testable import Mercury

private final class HostFilesAccessMockURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    nonisolated(unsafe) static var requests: [URLRequest] = []

    static func reset() {
        handler = nil
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.requests.append(request)
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

    static func body(of request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: 4_096)
        defer { buffer.deallocate() }
        while stream.hasBytesAvailable {
            let count = stream.read(buffer, maxLength: 4_096)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

private final class HostFilesAccessCredentialStore: CredentialStoring {
    var pair: TokenPair?

    init(pair: TokenPair? = nil) {
        self.pair = pair
    }

    func tokens(for origin: String) -> TokenPair? { pair }
    func setTokens(_ tokens: TokenPair, for origin: String) { pair = tokens }
    func clearTokens(for origin: String) { pair = nil }
}

final class HostFilesAccessTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HostFilesAccessMockURLProtocol.reset()
    }

    override func tearDown() {
        HostFilesAccessMockURLProtocol.reset()
        super.tearDown()
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HostFilesAccessMockURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func response(_ request: URLRequest, status: Int = 200) -> HTTPURLResponse {
        HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
    }

    private var emptyListing: Data {
        Data(#"{"path":"/srv","parent":"/","entries":[]}"#.utf8)
    }

    func testCapabilityDistinguishesDirectAccessFromRelayAndMissingOrigin() {
        XCTAssertEqual(
            HostFilesAccess.capability(origin: "https://hermes.example", relayActive: false),
            .direct
        )
        XCTAssertEqual(
            HostFilesAccess.capability(origin: nil, relayActive: true),
            .relayUnsupported
        )
        XCTAssertEqual(
            HostFilesAccess.capability(origin: nil, relayActive: false),
            .unavailable
        )
        XCTAssertEqual(
            HostFilesAccess.capability(origin: "relative/path", relayActive: false),
            .unavailable
        )

        XCTAssertThrowsError(
            try HostFilesAccess.makeClient(origin: nil, relayActive: true, urlSession: makeSession())
        ) { error in
            XCTAssertEqual(error as? HostFilesAccessError, .relayUnsupported)
        }
        XCTAssertThrowsError(
            try HostFilesAccess.makeClient(origin: nil, relayActive: false, urlSession: makeSession())
        ) { error in
            XCTAssertEqual(error as? HostFilesAccessError, .directOriginUnavailable)
        }
    }

    func testFolderPickerUsesServerDirectoryAndParentMetadataWithoutExtraConfinement() {
        let listing = HostFileListing(
            path: "/opt/data/project",
            entries: [
                HostFileEntry(name: "README.md", path: "/opt/data/project/README.md", isDirectory: false),
                HostFileEntry(name: "Sources", path: "/opt/data/project/Sources", isDirectory: true),
                HostFileEntry(name: "Linked folder", path: "/srv/shared", isDirectory: true),
            ],
            parentPath: "/opt/data",
            root: nil,
            lockedRoot: nil,
            canChangePath: true
        )

        XCTAssertTrue(HostFilesFolderPickerPolicy.canSelect(listing))
        XCTAssertEqual(
            HostFilesFolderPickerPolicy.directories(in: listing).map(\.path),
            ["/opt/data/project/Sources", "/srv/shared"]
        )
        XCTAssertEqual(
            HostFilesFolderPickerPolicy.parentPath(in: listing),
            "/opt/data"
        )

        let locked = HostFileListing(
            path: listing.path,
            entries: [],
            parentPath: listing.parentPath,
            root: "/opt/data",
            lockedRoot: "/opt/data",
            canChangePath: false
        )
        XCTAssertEqual(HostFilesFolderPickerPolicy.parentPath(in: locked), "/opt/data")
    }

    func testOperationResponsesAreRejectedAfterScopeSwitchForPreviewAndMkdir() {
        var previewState = HostFilesBrowserState()
        _ = previewState.beginLoad(scope: "direct|default", path: nil)
        let preview = previewState.beginPreview(path: "/srv/old.txt")
        let newerPreview = previewState.beginPreview(path: "/srv/new.txt")
        XCTAssertFalse(previewState.isCurrent(preview))
        XCTAssertTrue(previewState.isCurrent(newerPreview))
        _ = previewState.beginLoad(scope: "relay|default", path: nil)
        XCTAssertFalse(previewState.isCurrent(newerPreview))

        var createState = HostFilesBrowserState()
        _ = createState.beginLoad(scope: "direct|default", path: "/srv")
        let create = createState.beginCreate(parentPath: "/srv")
        let newerCreate = createState.beginCreate(parentPath: "/srv")
        XCTAssertFalse(createState.isCurrent(create))
        XCTAssertTrue(createState.isCurrent(newerCreate))
        _ = createState.beginLoad(scope: "direct|work", path: nil)
        XCTAssertFalse(createState.isCurrent(newerCreate))
    }

    func testClientKeepsFolder403SeparateFromAuthentication401() async throws {
        var status = 401
        HostFilesAccessMockURLProtocol.handler = { request in
            (self.response(request, status: status), Data(#"{"detail":"denied"}"#.utf8))
        }
        let client = try HostFilesClient(
            origin: "https://hermes.example",
            bearerToken: "access",
            session: makeSession()
        )

        do {
            _ = try await client.list()
            XCTFail("Expected HTTP 401")
        } catch let error as HermesAuthError {
            XCTAssertEqual(error, .authRejected)
        }

        status = 403
        do {
            _ = try await client.list()
            XCTFail("Expected denied-folder status")
        } catch let error as HostFilesClientError {
            XCTAssertEqual(error, .httpStatus(403))
        }
    }

    func testDirectClientRefreshesStoredBearerAndPersistsRotatedPair() async throws {
        let now = Int64(Date().timeIntervalSince1970)
        let store = HostFilesAccessCredentialStore(
            pair: TokenPair(
                accessToken: Data("stale-access".utf8),
                refreshToken: Data("refresh-secret".utf8),
                expiresAt: now + 1,
                provider: "nous"
            )
        )
        var fileAttempts = 0
        HostFilesAccessMockURLProtocol.handler = { request in
            switch request.url?.path {
            case "/api/files":
                fileAttempts += 1
                XCTAssertEqual(
                    request.value(forHTTPHeaderField: "Authorization"),
                    fileAttempts == 1 ? "Bearer stale-access" : "Bearer fresh-access"
                )
                if fileAttempts == 1 {
                    return (self.response(request, status: 401), Data())
                }
                return (self.response(request), self.emptyListing)
            case "/auth/native/refresh":
                XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
                let body = try XCTUnwrap(
                    JSONSerialization.jsonObject(with: HostFilesAccessMockURLProtocol.body(of: request)) as? [String: String]
                )
                XCTAssertEqual(body["refresh_token"], "refresh-secret")
                XCTAssertEqual(body["provider"], "nous")
                let payload = """
                {"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_at":\(now + 3600),"provider":"nous","user_id":"user-1"}
                """
                return (self.response(request), Data(payload.utf8))
            default:
                XCTFail("Unexpected request path")
                return (self.response(request, status: 404), Data())
            }
        }

        let client = try HostFilesAccess.makeClient(
            origin: "https://hermes.example",
            relayActive: false,
            urlSession: makeSession(),
            credentialStore: store
        )
        let listing = try await client.list(path: "/srv")

        XCTAssertEqual(listing.path, "/srv")
        XCTAssertEqual(fileAttempts, 2)
        XCTAssertEqual(String(data: store.pair?.accessToken ?? Data(), encoding: .utf8), "fresh-access")
        XCTAssertEqual(String(data: store.pair?.refreshToken ?? Data(), encoding: .utf8), "fresh-refresh")
    }
}
