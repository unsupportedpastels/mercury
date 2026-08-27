import Foundation
import XCTest
@testable import Mercury

// MARK: - MockURLProtocol (namespaced to this file)

/// URLProtocol stub that answers every request from a static handler and
/// records the most recent request for assertions.
final class LifecycleMockURLProtocol: URLProtocol {

    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    static var lastRequest: URLRequest?
    static var lastBody: Data?

    static func reset() {
        requestHandler = nil
        lastRequest = nil
        lastBody = nil
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = LifecycleMockURLProtocol.requestHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        // URLSession consumes the body stream; capture it before handling.
        if let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            let bufferSize = 4096
            let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
            defer { buffer.deallocate() }
            while stream.hasBytesAvailable {
                let read = stream.read(buffer, maxLength: bufferSize)
                guard read > 0 else { break }
                data.append(buffer, count: read)
            }
            stream.close()
            LifecycleMockURLProtocol.lastBody = data
        } else {
            LifecycleMockURLProtocol.lastBody = request.httpBody
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

// MARK: - SessionLifecycleTests

final class SessionLifecycleTests: XCTestCase {

    override func setUp() {
        super.setUp()
        LifecycleMockURLProtocol.reset()
    }

    override func tearDown() {
        LifecycleMockURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - Fixtures

    private static let updateResultJSON = """
    {"ok": true, "title": "Renamed", "archived": true, "pinned": false}
    """

    private static func bulkDeleteJSON(ok: Bool = true, deleted: Int?) -> String {
        let deletedField = deleted.map { "\($0)" } ?? "null"
        return "{\"ok\": \(ok), \"deleted\": \(deletedField)}"
    }

    // MARK: - Helpers

    /// HermesHTTPClient over an ephemeral session routed through the mock
    /// URLProtocol so no real network traffic occurs.
    private func makeClient(bearer: String? = nil) -> HermesHTTPClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [LifecycleMockURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let client = HermesHTTPClient(origin: "https://hermes.example.com", session: session)
        client.bearerToken = bearer
        return client
    }

    private func makeLifecycleClient(bearer: String? = "tok") -> SessionLifecycleClient {
        SessionLifecycleClient(client: makeClient(bearer: bearer))
    }

    private func okResponse(_ request: URLRequest, body: String, statusCode: Int = 200) throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, Data(body.utf8))
    }

    /// Captures the outgoing request and answers `200` with `body`.
    private func captureAndOK(body: String) {
        LifecycleMockURLProtocol.requestHandler = { request in
            LifecycleMockURLProtocol.lastRequest = request
            return try self.okResponse(request, body: body)
        }
    }

    private func capturedJSONBody() throws -> [String: Any] {
        let data = try XCTUnwrap(LifecycleMockURLProtocol.lastBody)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        return object
    }

    private func capturedQueryItems() throws -> [String: String] {
        let query = try XCTUnwrap(LifecycleMockURLProtocol.lastRequest?.url?.query)
        var items: [String: String] = [:]
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            guard let key = kv.first.map(String.init) else { continue }
            items[key.removingPercentEncoding ?? key] =
                kv.count > 1 ? (kv[1].removingPercentEncoding ?? String(kv[1])) : ""
        }
        return items
    }

    // MARK: - Update (archive/unarchive/pin/unpin/rename)

    func testArchiveSendsPatchWithExplicitNullsAndBearer() async throws {
        captureAndOK(body: Self.updateResultJSON)

        let result = try await makeLifecycleClient().archive(sessionID: "abc-123")

        XCTAssertEqual(result, SessionUpdateResult(ok: true, title: "Renamed", archived: true, pinned: false))
        let request = try XCTUnwrap(LifecycleMockURLProtocol.lastRequest)
        XCTAssertEqual(request.httpMethod, "PATCH")
        XCTAssertTrue(request.url!.path.hasSuffix("/api/sessions/abc-123"))
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer tok")

        let json = try capturedJSONBody()
        XCTAssertEqual(json["archived"] as? Bool, true)
        XCTAssertEqual(json["title"] as? NSNull, NSNull())
        XCTAssertEqual(json["pinned"] as? NSNull, NSNull())
        XCTAssertEqual(json["profile"] as? NSNull, NSNull())
    }

    func testUnarchivePinUnpinSendExpectedFlags() async throws {
        captureAndOK(body: Self.updateResultJSON)

        _ = try await makeLifecycleClient().unarchive(sessionID: "s")
        XCTAssertEqual(try capturedJSONBody()["archived"] as? Bool, false)

        _ = try await makeLifecycleClient().pin(sessionID: "s")
        XCTAssertEqual(try capturedJSONBody()["pinned"] as? Bool, true)

        _ = try await makeLifecycleClient().unpin(sessionID: "s")
        XCTAssertEqual(try capturedJSONBody()["pinned"] as? Bool, false)
    }

    func testRenameTruncatesTitleTo512Characters() async throws {
        captureAndOK(body: Self.updateResultJSON)

        let longTitle = String(repeating: "x", count: 600)
        _ = try await makeLifecycleClient().rename(sessionID: "s", to: longTitle)

        let title = try XCTUnwrap(capturedJSONBody()["title"] as? String)
        XCTAssertEqual(title.count, 512)
    }

    func testUpdateOmitsProfileWhenDefaultButSendsCustom() async throws {
        captureAndOK(body: Self.updateResultJSON)

        _ = try await makeLifecycleClient().update(sessionID: "s", archived: true, profile: "default")
        XCTAssertEqual(try capturedJSONBody()["profile"] as? NSNull, NSNull())

        _ = try await makeLifecycleClient().update(sessionID: "s", archived: true, profile: "work")
        XCTAssertEqual(try capturedJSONBody()["profile"] as? String, "work")
    }

    func testUpdateDecodesTolerantlyWhenEchoFieldsMissing() async throws {
        captureAndOK(body: #"{"ok": true}"#)

        let result = try await makeLifecycleClient().pin(sessionID: "s")

        XCTAssertEqual(result.ok, true)
        XCTAssertNil(result.title)
        XCTAssertNil(result.archived)
        XCTAssertNil(result.pinned)
    }

    func testUpdateRejectsEmptyChangeWithoutSendingRequest() async throws {
        do {
            _ = try await makeLifecycleClient().update(sessionID: "s")
            XCTFail("expected emptyUpdate")
        } catch let error as SessionLifecycleError {
            XCTAssertEqual(error, .emptyUpdate)
        }
        XCTAssertNil(LifecycleMockURLProtocol.lastRequest, "no network call for an empty update")
    }

    func testUpdateClassifiesAuthBeforeDecode() async throws {
        LifecycleMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: "", statusCode: 401)
        }
        do {
            _ = try await makeLifecycleClient().archive(sessionID: "s")
            XCTFail("expected authRejected")
        } catch let error as HermesAuthError {
            XCTAssertEqual(error, .authRejected)
        }
    }

    func testUpdateMaps500ToTransient() async throws {
        LifecycleMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: "", statusCode: 503)
        }
        do {
            _ = try await makeLifecycleClient().archive(sessionID: "s")
            XCTFail("expected transient")
        } catch let error as HermesAuthError {
            guard case .transient = error else {
                return XCTFail("expected transient, got \(error)")
            }
        }
    }

    func testUpdateMapsOtherNon2xxToRequestFailed() async throws {
        LifecycleMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: "", statusCode: 404)
        }
        do {
            _ = try await makeLifecycleClient().archive(sessionID: "s")
            XCTFail("expected requestFailed")
        } catch let error as SessionLifecycleError {
            XCTAssertEqual(error, .requestFailed(statusCode: 404, operation: "session update"))
        }
    }

    func testUpdateRejectsInvalidSessionID() async throws {
        for bad in ["", String(repeating: "y", count: 257)] {
            do {
                _ = try await makeLifecycleClient().rename(sessionID: bad, to: "t")
                XCTFail("expected invalidSessionID for \(bad.prefix(8))…")
            } catch let error as SessionLifecycleError {
                XCTAssertEqual(error, .invalidSessionID)
            }
        }
        XCTAssertNil(LifecycleMockURLProtocol.lastRequest)
    }

    func testUpdatePercentEncodesOddSessionIDs() async throws {
        captureAndOK(body: Self.updateResultJSON)

        _ = try await makeLifecycleClient().archive(sessionID: "a b/c")

        let url = LifecycleMockURLProtocol.lastRequest!.url!
        // Space is percent-encoded on the wire; "/" is allowed by
        // urlPathAllowed and remains a separator (URL.path decodes, so assert
        // on the absolute string).
        XCTAssertTrue(url.absoluteString.hasSuffix("/api/sessions/a%20b/c"), "got \(url.absoluteString)")
    }

    // MARK: - Delete

    func testDeleteSendsDeleteVerbWithoutBody() async throws {
        captureAndOK(body: "{}")

        try await makeLifecycleClient().delete(sessionID: "abc-123")

        let request = try XCTUnwrap(LifecycleMockURLProtocol.lastRequest)
        XCTAssertEqual(request.httpMethod, "DELETE")
        XCTAssertTrue(request.url!.path.hasSuffix("/api/sessions/abc-123"))
        XCTAssertNil(request.value(forHTTPHeaderField: "Content-Type"))
        // Default profile is elided: the URL carries no query at all.
        XCTAssertNil(request.url!.query, "default profile is elided from the query")
    }

    func testDeletePassesNonDefaultProfileAsQueryParameter() async throws {
        captureAndOK(body: "{}")

        try await makeLifecycleClient().delete(sessionID: "abc-123", profile: "work")

        XCTAssertEqual(try capturedQueryItems(), ["profile": "work"])
    }

    func testDeleteMapsNon2xxToRequestFailed() async throws {
        LifecycleMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: "", statusCode: 410)
        }
        do {
            try await makeLifecycleClient().delete(sessionID: "s")
            XCTFail("expected requestFailed")
        } catch let error as SessionLifecycleError {
            XCTAssertEqual(error, .requestFailed(statusCode: 410, operation: "session deletion"))
        }
    }

    func testDeleteRejectsInvalidSessionID() async throws {
        do {
            try await makeLifecycleClient().delete(sessionID: "")
            XCTFail("expected invalidSessionID")
        } catch let error as SessionLifecycleError {
            XCTAssertEqual(error, .invalidSessionID)
        }
        XCTAssertNil(LifecycleMockURLProtocol.lastRequest)
    }

    // MARK: - Bulk delete

    func testBulkDeleteSendsPostWithDeduplicatedIDsAndNullProfile() async throws {
        captureAndOK(body: Self.bulkDeleteJSON(deleted: 2))

        let result = try await makeLifecycleClient()
            .bulkDelete(sessionIDs: ["a", "b", "a"], profile: "default")

        XCTAssertEqual(result.deleted, 2)
        let request = try XCTUnwrap(LifecycleMockURLProtocol.lastRequest)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertTrue(request.url!.path.hasSuffix("/api/sessions/bulk-delete"))

        let json = try capturedJSONBody()
        XCTAssertEqual(json["ids"] as? [String], ["a", "b"], "duplicates removed, order kept")
        XCTAssertEqual(json["profile"] as? NSNull, NSNull())
    }

    func testBulkDeleteSendsCustomProfile() async throws {
        captureAndOK(body: Self.bulkDeleteJSON(deleted: 1))

        _ = try await makeLifecycleClient().bulkDelete(sessionIDs: ["a"], profile: "work")

        XCTAssertEqual(try capturedJSONBody()["profile"] as? String, "work")
    }

    func testBulkDeleteValidatesArgumentsWithoutSendingRequest() throws {
        let lifecycle = makeLifecycleClient()

        XCTAssertThrowsError(try runSync { try await lifecycle.bulkDelete(sessionIDs: []) }) { error in
            XCTAssertEqual(error as? SessionLifecycleError, .bulkDeleteEmpty)
        }
        XCTAssertThrowsError(
            try runSync {
                try await lifecycle.bulkDelete(sessionIDs: (0..<501).map(String.init))
            }
        ) { error in
            XCTAssertEqual(error as? SessionLifecycleError, .bulkDeleteLimitExceeded)
        }
        XCTAssertThrowsError(try runSync { try await lifecycle.bulkDelete(sessionIDs: ["ok", ""]) }) { error in
            XCTAssertEqual(error as? SessionLifecycleError, .invalidSessionID)
        }
        XCTAssertThrowsError(
            try runSync { try await lifecycle.bulkDelete(sessionIDs: ["ok"], profile: String(repeating: "p", count: 65)) }
        ) { error in
            XCTAssertEqual(error as? SessionLifecycleError, .invalidProfile)
        }
        XCTAssertNil(LifecycleMockURLProtocol.lastRequest, "validation failures must not touch the network")
    }

    func testBulkDeleteMapsUnsupportedStatuses() async throws {
        for status in [404, 405] {
            LifecycleMockURLProtocol.reset()
            LifecycleMockURLProtocol.requestHandler = { request in
                try self.okResponse(request, body: "", statusCode: status)
            }
            do {
                _ = try await makeLifecycleClient().bulkDelete(sessionIDs: ["a"])
                XCTFail("expected bulkDeleteUnsupported for \(status)")
            } catch let error as SessionLifecycleError {
                XCTAssertEqual(error, .bulkDeleteUnsupported(statusCode: status))
            }
        }
    }

    func testBulkDeleteRequiresOkTrueAndInRangeDeletedCount() async throws {
        // Missing / out-of-range deleted → incomplete.
        let incompleteCases: [Int?] = [nil, -1, 3]
        for deleted in incompleteCases {
            LifecycleMockURLProtocol.reset()
            captureAndOK(body: Self.bulkDeleteJSON(ok: true, deleted: deleted))
            do {
                _ = try await makeLifecycleClient().bulkDelete(sessionIDs: ["a", "b"])
                XCTFail("expected incompleteBulkDeleteResponse for deleted=\(String(describing: deleted))")
            } catch let error as SessionLifecycleError {
                XCTAssertEqual(error, .incompleteBulkDeleteResponse)
            }
        }

        // ok=false with a valid count → not accepted.
        LifecycleMockURLProtocol.reset()
        captureAndOK(body: Self.bulkDeleteJSON(ok: false, deleted: 1))
        do {
            _ = try await makeLifecycleClient().bulkDelete(sessionIDs: ["a"])
            XCTFail("expected bulkDeleteNotAccepted")
        } catch let error as SessionLifecycleError {
            XCTAssertEqual(error, .bulkDeleteNotAccepted)
        }
    }

    func testBulkDeleteBoundaryDeletedCountsAreAccepted() async throws {
        for deleted in [0, 2] {
            LifecycleMockURLProtocol.reset()
            captureAndOK(body: Self.bulkDeleteJSON(ok: true, deleted: deleted))
            let result = try await makeLifecycleClient().bulkDelete(sessionIDs: ["a", "b"])
            XCTAssertEqual(result.deleted, deleted)
        }
    }

    // MARK: - Sync bridge

    /// Runs an async throwing closure synchronously inside XCTest's sync
    /// assertion helpers (`XCTAssertThrowsError` has no async overload).
    private func runSync(_ work: @escaping () async throws -> Void) throws -> Void {
        let expectation = expectation(description: "async work")
        var caught: Error?
        Task {
            do { try await work() } catch { caught = error }
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 5)
        if let caught { throw caught }
    }
}
