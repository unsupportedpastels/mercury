import Foundation
import XCTest
@testable import Mercury

// MARK: - MockURLProtocol

/// URLProtocol stub that answers every request from a static handler and
/// records the most recent request for assertions.
final class SessionsMockURLProtocol: URLProtocol {

    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    static var lastRequest: URLRequest?
    static var requests: [URLRequest] = []

    static func reset() {
        requestHandler = nil
        lastRequest = nil
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = SessionsMockURLProtocol.requestHandler else {
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

// MARK: - SessionsTests

final class SessionsTests: XCTestCase {

    override func setUp() {
        super.setUp()
        SessionsMockURLProtocol.reset()
    }

    override func tearDown() {
        SessionsMockURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - Fixtures

    /// Two rows: one rich (every official field, including cwd/is_active/pinned
    /// which SessionRow intentionally ignores), one sparse (id only).
    private static let twoRowJSON = """
    {
      "sessions": [
        {
          "id": "rich-1",
          "title": "Refactor auth flow",
          "preview": "Splitting token refresh out…",
          "last_active": 1755858600,
          "message_count": 42,
          "model": "hermes-default",
          "billing_provider": "nous",
          "profile": "default",
          "cwd": "/workspace/mercury",
          "is_active": true,
          "pinned": true
        },
        {
          "id": "sparse-1"
        }
      ],
      "total": 45,
      "limit": 20,
      "offset": 0
    }
    """

    private static let transcriptDataKeyJSON = """
    {
      "session_id": "abc-123",
      "data": [
        {"role": "user", "content": "Hello"},
        {"role": "assistant", "content": "Hi there!"}
      ]
    }
    """

    private static let transcriptMessagesKeyJSON = """
    {
      "session_id": "abc-123",
      "messages": [
        {"role": "user", "content": "Ping"},
        {"role": "assistant", "content": "Pong"}
      ]
    }
    """

    private static let emptySessionsJSON = """
    {"sessions": [], "total": 0, "limit": 20, "offset": 0}
    """

    // MARK: - Helpers

    /// Builds a HermesHTTPClient over an ephemeral session routed through
    /// MockURLProtocol so no real network traffic occurs.
    private func makeClient() -> HermesHTTPClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SessionsMockURLProtocol.self]
        let session = URLSession(configuration: configuration)
        return HermesHTTPClient(
            origin: "https://hermes.example.com",
            session: session
        )
    }

    private func okResponse(_ request: URLRequest, body: String) throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: 200,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, Data(body.utf8))
    }

    // MARK: - Request contract

    func testSessionsFirstPageBuildsExactOfficialQuery() async throws {
        SessionsMockURLProtocol.requestHandler = { request in
            SessionsMockURLProtocol.lastRequest = request
            return try self.okResponse(request, body: Self.twoRowJSON)
        }

        _ = try await SessionsClient(client: makeClient()).sessions()

        let captured = try XCTUnwrap(SessionsMockURLProtocol.lastRequest?.url?.query)
        XCTAssertEqual(
            captured,
            "profile=default&limit=20&order=recent&archived=exclude&offset=0"
        )
    }

    func testSearchBuildsOfficialQueryBoundsFieldsAndDeduplicatesIDs() async throws {
        let longTitle = String(repeating: "T", count: 600)
        let longSnippet = String(repeating: "S", count: 1_200)
        let body = """
        {"results":[
          {"session_id":"search-1","title":"\(longTitle)","snippet":"\(longSnippet)","role":"\(String(repeating: "r", count: 40))"},
          {"session_id":"search-1","title":"duplicate","snippet":"duplicate"},
          {"id":"search-2","title":"Fallback ID","snippet":"message hit"}
        ]}
        """
        SessionsMockURLProtocol.requestHandler = { request in
            SessionsMockURLProtocol.lastRequest = request
            return try self.okResponse(request, body: body)
        }

        let results = try await SessionsClient(client: makeClient()).search(query: "  needle  ")

        XCTAssertEqual(SessionsMockURLProtocol.lastRequest?.url?.query, "q=needle&limit=20&profile=default")
        XCTAssertEqual(results.map(\.sessionID), ["search-1", "search-2"])
        XCTAssertEqual(results[0].title.count, 512)
        XCTAssertEqual(results[0].snippet.count, 1_000)
        XCTAssertEqual(results[0].role?.count, 32)
        XCTAssertEqual(results[1].title, "Fallback ID")
    }

    func testTranscriptBuildsOfficialQuery() async throws {
        SessionsMockURLProtocol.requestHandler = { request in
            SessionsMockURLProtocol.lastRequest = request
            return try self.okResponse(request, body: Self.transcriptDataKeyJSON)
        }

        _ = try await SessionsClient(client: makeClient())
            .transcript(sessionID: "abc-123")

        let capturedURL = try XCTUnwrap(SessionsMockURLProtocol.lastRequest?.url)
        XCTAssertTrue(capturedURL.path.hasSuffix("/api/sessions/abc-123/messages"))
        XCTAssertEqual(
            capturedURL.query,
            "profile=default&limit=100&order=latest&offset=0"
        )
    }

    func testOlderTranscriptBuildsOffsetQueryForLoadEarlier() async throws {
        SessionsMockURLProtocol.requestHandler = { request in
            SessionsMockURLProtocol.lastRequest = request
            return try self.okResponse(request, body: Self.transcriptDataKeyJSON)
        }

        _ = try await SessionsClient(client: makeClient())
            .olderTranscript(sessionID: "abc-123", offset: 150)

        let capturedURL = try XCTUnwrap(SessionsMockURLProtocol.lastRequest?.url)
        XCTAssertTrue(capturedURL.path.hasSuffix("/api/sessions/abc-123/messages"))
        XCTAssertEqual(
            capturedURL.query,
            "profile=default&limit=50&order=latest&offset=150"
        )
    }

    func testTranscriptAllowsPayloadAboveGenericResponseLimit() async throws {
        let content = String(repeating: "x", count: HermesHTTPClient.maxResponseBytes + 1)
        let body = """
        {"messages":[{"role":"assistant","content":"\(content)"}]}
        """
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: body)
        }

        let transcript = try await SessionsClient(client: makeClient())
            .transcript(sessionID: "large-session")

        XCTAssertEqual(transcript, [TranscriptMessage(role: "assistant", content: content)])
    }

    func testTranscriptRetriesWithSmallerLatestPageWhenResponseExceedsTranscriptLimit() async throws {
        let oversizedBody = Data(count: 1024 * 1024 + 1)
        SessionsMockURLProtocol.requestHandler = { request in
            let response = HTTPURLResponse(
                url: request.url!, statusCode: 200, httpVersion: nil,
                headerFields: ["Content-Type": "application/json"]
            )!
            let limit = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "limit" })?.value
            return limit == "100"
                ? (response, oversizedBody)
                : (response, Data(Self.transcriptDataKeyJSON.utf8))
        }

        let transcript = try await SessionsClient(client: makeClient())
            .transcript(sessionID: "oversized-session")

        let limits = SessionsMockURLProtocol.requests.compactMap { request in
            URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "limit" })?.value
        }
        XCTAssertEqual(limits, ["100", "50"])
        XCTAssertEqual(transcript.count, 2)
    }

    func testTranscriptPreservesToolIdentityAndAssistantReasoningForStructuredRendering() async throws {
        let body = #"""
        {
          "messages": [
            {
              "role": "tool",
              "content": "{\"success\":true,\"content\":\"raw result\"}",
              "tool_name": "read_file"
            },
            {
              "role": "assistant",
              "text": "Done.",
              "reasoning_content": "Checked the source first."
            }
          ]
        }
        """#
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: body)
        }

        let transcript = try await SessionsClient(client: makeClient())
            .transcript(sessionID: "structured-session")

        XCTAssertEqual(transcript, [
            TranscriptMessage(
                role: "tool",
                content: "{\"success\":true,\"content\":\"raw result\"}",
                toolName: "read_file"
            ),
            TranscriptMessage(
                role: "assistant",
                content: "Done.",
                reasoningText: "Checked the source first."
            ),
        ])
    }

    func testLatestTranscriptPageRemainsChronologicalForDisplay() {
        let page = [
            TranscriptMessage(role: "user", content: "First"),
            TranscriptMessage(role: "tool", content: "Result", toolName: "read_file"),
            TranscriptMessage(role: "assistant", content: "Final answer"),
        ]

        XCTAssertEqual(TranscriptPageOrdering.forDisplay(page), page)
        XCTAssertEqual(TranscriptPageOrdering.forDisplay(page).last?.content, "Final answer")
    }

    // MARK: - Sessions decoding + pagination

    func testSessionsDecodesRichAndSparseRows() async throws {
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: Self.twoRowJSON)
        }

        let page = try await SessionsClient(client: makeClient()).sessions()

        XCTAssertEqual(page.rows.count, 2)
        XCTAssertEqual(page.total, 45)

        // Rich row: every official field survives (cwd/is_active/pinned are
        // tolerated as unknown fields by SessionRow).
        let rich = page.rows[0]
        XCTAssertEqual(rich.id, "rich-1")
        XCTAssertEqual(rich.title, "Refactor auth flow")
        XCTAssertEqual(rich.preview, "Splitting token refresh out…")
        XCTAssertEqual(rich.messageCount, 42)
        XCTAssertEqual(rich.model, "hermes-default")
        XCTAssertEqual(rich.profile, "default")
        XCTAssertEqual(
            rich.lastActive?.timeIntervalSince1970 ?? -1,
            1755858600,
            accuracy: 1
        )

        // Sparse row: only `id` present, everything else falls back to defaults.
        let sparse = page.rows[1]
        XCTAssertEqual(sparse.id, "sparse-1")
        XCTAssertEqual(sparse.title, "")
        XCTAssertEqual(sparse.preview, "")
        XCTAssertNil(sparse.lastActive)
        XCTAssertEqual(sparse.messageCount, 0)
        XCTAssertNil(sparse.model)
        XCTAssertNil(sparse.profile)

        // total (45) > offset + limit (0 + 20) → more pages exist.
        XCTAssertTrue(page.hasMore)
    }

    func testSessionsHasMoreFalseWhenTotalWithinPage() async throws {
        let json = """
        {"sessions": [{"id": "a"}, {"id": "b"}], "total": 2, "limit": 20, "offset": 0}
        """
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: json)
        }

        let page = try await SessionsClient(client: makeClient()).sessions()

        XCTAssertEqual(page.rows.count, 2)
        XCTAssertEqual(page.total, 2)
        // total (2) <= offset + limit (0 + 20) → nothing more to fetch.
        XCTAssertFalse(page.hasMore)
    }

    func testSessionsHasMoreFallsBackToFilledPageWhenTotalAbsent() async throws {
        let filledJSON = #"{"sessions": [{"id": "a"}, {"id": "b"}]}"#
        let shortJSON = #"{"sessions": [{"id": "a"}, {"id": "b"}]}"#

        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: filledJSON)
        }
        let filled = try await SessionsClient(client: makeClient()).sessions(limit: 2)
        XCTAssertTrue(filled.hasMore, "rows.count == limit with no total → assume more exist")
        XCTAssertNil(filled.total)

        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: shortJSON)
        }
        let short = try await SessionsClient(client: makeClient()).sessions(limit: 5)
        XCTAssertFalse(short.hasMore, "rows.count < limit with no total → last page")
    }

    func testSessionsEmptyArrayHasMoreFalse() async throws {
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: Self.emptySessionsJSON)
        }

        let page = try await SessionsClient(client: makeClient()).sessions()

        XCTAssertTrue(page.rows.isEmpty)
        XCTAssertEqual(page.total, 0)
        XCTAssertFalse(page.hasMore)
    }

    // MARK: - Transcript decoding

    func testTranscriptDecodesDataKey() async throws {
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: Self.transcriptDataKeyJSON)
        }

        let messages = try await SessionsClient(client: makeClient())
            .transcript(sessionID: "abc-123")

        XCTAssertEqual(messages.count, 2)
        XCTAssertEqual(messages[0], TranscriptMessage(role: "user", content: "Hello"))
        XCTAssertEqual(messages[1], TranscriptMessage(role: "assistant", content: "Hi there!"))
    }

    func testTranscriptDecodesMessagesKey() async throws {
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: Self.transcriptMessagesKeyJSON)
        }

        let messages = try await SessionsClient(client: makeClient())
            .transcript(sessionID: "abc-123")

        XCTAssertEqual(messages.count, 2)
        XCTAssertEqual(messages[0], TranscriptMessage(role: "user", content: "Ping"))
        XCTAssertEqual(messages[1], TranscriptMessage(role: "assistant", content: "Pong"))
    }

    func testTranscriptTolerantRowFields() async throws {
        let json = #"{"data": [{"role": "user"}, {"content": "orphan"}]}"#
        SessionsMockURLProtocol.requestHandler = { request in
            try self.okResponse(request, body: json)
        }

        let messages = try await SessionsClient(client: makeClient())
            .transcript(sessionID: "abc-123")

        XCTAssertEqual(messages.count, 2)
        XCTAssertEqual(messages[0], TranscriptMessage(role: "user", content: ""))
        XCTAssertEqual(messages[1], TranscriptMessage(role: "", content: "orphan"))
    }
}
