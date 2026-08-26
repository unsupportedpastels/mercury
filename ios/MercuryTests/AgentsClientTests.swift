import Foundation
import XCTest
@testable import Mercury

// Hermetic tests for the Portal /api/agents discovery client (M5b).

final class AgentsMockURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    nonisolated(unsafe) static var lastRequest: URLRequest?

    static func reset() {
        handler = nil
        lastRequest = nil
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequest = request
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
}

final class AgentsClientTests: XCTestCase {

    private var session: URLSession!

    override func setUp() {
        super.setUp()
        AgentsMockURLProtocol.reset()
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AgentsMockURLProtocol.self]
        session = URLSession(configuration: config)
    }

    override func tearDown() {
        AgentsMockURLProtocol.reset()
        session = nil
        super.tearDown()
    }

    private func makeClient() -> AgentsClient {
        AgentsClient(session: session)
    }

    private func ok(_ body: String, url: URL? = nil) throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: url ?? URL(string: "https://portal.nousresearch.com/api/agents")!,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, Data(body.utf8))
    }

    private func status(_ code: Int, _ body: String) throws -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: URL(string: "https://portal.nousresearch.com/api/agents")!,
            statusCode: code,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, Data(body.utf8))
    }

    // MARK: - Happy path

    func testHappyPathDecodesAgentRows() async throws {
        AgentsMockURLProtocol.handler = { [self] request in
            try ok("""
            {"agents":[
                {"id":"a-1","name":"Atlas","status":"running","dashboardUrl":"https://d1","dashboardGatewayState":"connected"},
                {"id":"a-2"}
            ],"org":{"name":"Acme","slug":"acme"}}
            """, url: request.url!)
        }

        let result = try await makeClient().agents(
            origin: "https://portal.nousresearch.com",
            accessToken: "tok"
        )

        XCTAssertEqual(result.agents.count, 2)
        XCTAssertEqual(result.agents[0].id, "a-1")
        XCTAssertEqual(result.agents[0].name, "Atlas")
        XCTAssertEqual(result.agents[0].status, "running")
        XCTAssertEqual(result.agents[0].dashboardURL, "https://d1")
        XCTAssertEqual(result.agents[0].dashboardGatewayState, "connected")
        // Sparse row still decodes with only the required id.
        XCTAssertEqual(result.agents[1].id, "a-2")
        XCTAssertNil(result.agents[1].name)
        XCTAssertEqual(result.org?.slug, "acme")
    }

    // MARK: - Auth header + org query

    func testSendsBearerHeaderAndOrgQuery() async throws {
        AgentsMockURLProtocol.handler = { [self] request in
            try ok(#"{"agents":[]}"#, url: request.url!)
        }

        _ = try await makeClient().agents(
            origin: "https://portal.nousresearch.com",
            accessToken: "sekrit",
            org: "acme"
        )

        let request = try XCTUnwrap(AgentsMockURLProtocol.lastRequest)
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer sekrit")
        XCTAssertTrue(request.url!.absoluteString.contains("org=acme"), request.url!.absoluteString)
    }

    // MARK: - 409 org selection

    func test409SurfacesOrgSelectionOptions() async throws {
        AgentsMockURLProtocol.handler = { [self] _ in
            try status(409, #"{"error":"org_selection_required","orgs":[{"slug":"personal","name":"Personal"},{"slug":"work"}]}"#)
        }

        do {
            _ = try await makeClient().agents(origin: "https://portal.nousresearch.com", accessToken: "t")
            XCTFail("expected orgSelectionRequired")
        } catch let error as AgentsError {
            guard case .orgSelectionRequired(let options) = error else {
                return XCTFail("wrong error: \(error)")
            }
            XCTAssertEqual(options.map(\.slug), ["personal", "work"])
            XCTAssertEqual(options.first?.name, "Personal")
        }
    }

    func testUnrelated409DoesNotSurfaceOrgSelection() async throws {
        AgentsMockURLProtocol.handler = { [self] _ in
            try status(409, #"{"error":"agent_conflict","orgs":[{"slug":"work"}]}"#)
        }

        do {
            _ = try await makeClient().agents(origin: "https://portal.nousresearch.com", accessToken: "t")
            XCTFail("expected failed(409)")
        } catch let error as AgentsError {
            XCTAssertEqual(error, .failed("Hermes Cloud returned HTTP 409"))
        }
    }

    // MARK: - Status classification

    func test401ClassifiedAsInvalidToken() async throws {
        AgentsMockURLProtocol.handler = { [self] _ in
            try status(401, #"{}"#)
        }
        do {
            _ = try await makeClient().agents(origin: "https://portal.nousresearch.com", accessToken: "bad")
            XCTFail("expected invalidToken")
        } catch let error as AgentsError {
            XCTAssertEqual(error, .invalidToken)
        }
    }

    func testOtherNon2xxBecomesFailedWithStatus() async throws {
        AgentsMockURLProtocol.handler = { [self] _ in
            try status(503, #"{}"#)
        }
        do {
            _ = try await makeClient().agents(origin: "https://portal.nousresearch.com", accessToken: "t")
            XCTFail("expected failed(503)")
        } catch let error as AgentsError {
            XCTAssertEqual(error, .failed("Hermes Cloud returned HTTP 503"))
        }
    }

    func testMalformedJSONBecomesFailed() async throws {
        AgentsMockURLProtocol.handler = { [self] _ in
            try ok("not json at all")
        }
        do {
            _ = try await makeClient().agents(origin: "https://portal.nousresearch.com", accessToken: "t")
            XCTFail("expected failed")
        } catch let error as AgentsError {
            XCTAssertEqual(error, .failed("Hermes Cloud agents response was not valid JSON"))
        }
    }

    // MARK: - Local validation

    func testBlankTokenRejectedPreFlight() async {
        let mockHandlerExpectation = expectation(description: "no network call")
        mockHandlerExpectation.isInverted = true
        AgentsMockURLProtocol.handler = { [self] request in
            try ok(#"{"agents":[]}"#, url: request.url!)
        }

        do {
            _ = try await makeClient().agents(origin: "https://portal.nousresearch.com", accessToken: "   ")
            XCTFail("expected invalidToken pre-flight")
        } catch {
            guard let agentsError = error as? AgentsError else {
                XCTFail("unexpected error type: \(error)")
                return
            }
            XCTAssertEqual(agentsError, .invalidToken)
        }
        waitForExpectations(timeout: 0.2, handler: nil)
    }

    // MARK: - Pure parsing

    func testParseIgnoresRowsWithoutID() throws {
        let result = try AgentsClient.parse(Data(#"{"agents":[{"name":"no-id"},{"id":"ok"}]}"#.utf8))
        XCTAssertEqual(result.agents.map(\.id), ["ok"])
    }
}
