import Foundation
import XCTest
@testable import Mercury

// MARK: - ChatMockURLProtocol

/// Per-file URLProtocol stub so chat transport tests remain offline and do not
/// collide with the similarly named mocks in the other Mercury test files.
final class ChatMockURLProtocol: URLProtocol {
    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?
    static var requests: [URLRequest] = []

    static func reset() {
        requestHandler = nil
        requests = []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.requestHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }

        Self.requests.append(request)
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

final class ChatTransportTests: XCTestCase {
    override func setUp() {
        super.setUp()
        ChatMockURLProtocol.reset()
    }

    override func tearDown() {
        ChatMockURLProtocol.reset()
        super.tearDown()
    }

    // MARK: - Helpers

    private func makeTicketClient() -> WsTicketClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ChatMockURLProtocol.self]
        return WsTicketClient(session: URLSession(configuration: configuration))
    }

    private func response(for request: URLRequest, statusCode: Int = 200) -> HTTPURLResponse {
        HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
    }

    private func assertChatError(
        _ operation: () async throws -> Void,
        matches predicate: (ChatError) -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            try await operation()
            XCTFail("Expected ChatError", file: file, line: line)
        } catch let error as ChatError {
            XCTAssertTrue(predicate(error), "Unexpected error: \(error)", file: file, line: line)
        } catch {
            XCTFail("Expected ChatError, got \(error)", file: file, line: line)
        }
    }

    // MARK: - WsTicket decoding and validation

    func testWsTicketDecodesHappyPath() throws {
        let data = Data(#"{"ticket":"ticket-abc","ttl_seconds":45,"future":"ignored"}"#.utf8)
        let ticket = try JSONDecoder().decode(WsTicket.self, from: data)

        XCTAssertEqual(ticket.ticket, "ticket-abc")
        XCTAssertEqual(ticket.ttlSeconds, 45)
    }

    func testBlankTicketIsRejectedAsProtocolError() throws {
        let data = Data(#"{"ticket":"   ","ttl_seconds":45}"#.utf8)

        XCTAssertThrowsError(try JSONDecoder().decode(WsTicket.self, from: data)) { error in
            guard case .protocolError = error as? ChatError else {
                return XCTFail("Expected ChatError.protocolError, got \(error)")
            }
        }
    }

    func testZeroAndNegativeTicketTTLAreRejected() throws {
        for ttl in [0, -1] {
            let data = Data(#"{"ticket":"ticket-abc","ttl_seconds":\#(ttl)}"#.utf8)
            XCTAssertThrowsError(try JSONDecoder().decode(WsTicket.self, from: data)) { error in
                guard case .protocolError = error as? ChatError else {
                    return XCTFail("Expected ChatError.protocolError, got \(error)")
                }
            }
        }
    }

    func testMissingTicketTTLIsRejectedAsProtocolError() throws {
        let data = Data(#"{"ticket":"ticket-abc"}"#.utf8)

        XCTAssertThrowsError(try JSONDecoder().decode(WsTicket.self, from: data)) { error in
            guard case .protocolError = error as? ChatError else {
                return XCTFail("Expected ChatError.protocolError, got \(error)")
            }
        }
    }

    // MARK: - HTTP ticket endpoint

    func testHTTP500BecomesTransportErrorMentioningStatus() async {
        ChatMockURLProtocol.requestHandler = { request in
            (self.response(for: request, statusCode: 500), Data(#"{"error":"unavailable"}"#.utf8))
        }

        await assertChatError({
            _ = try await makeTicketClient().mintTicket(
                origin: "https://hermes.example.com",
                accessToken: "access-token"
            )
        }, matches: { error in
            guard case let .transport(message) = error else { return false }
            return message.contains("500")
        })
    }

    func testTicketResponseOver16KiBBecomesTooLargeTransportError() async {
        ChatMockURLProtocol.requestHandler = { request in
            (
                self.response(for: request),
                Data(repeating: 0x78, count: wsTicketMaxResponseBytes + 1)
            )
        }

        await assertChatError({
            _ = try await makeTicketClient().mintTicket(
                origin: "https://hermes.example.com",
                accessToken: "access-token"
            )
        }, matches: { error in
            guard case let .transport(message) = error else { return false }
            return message.localizedCaseInsensitiveContains("too large")
        })
    }

    func testBlankAccessTokenFailsBeforeNetwork() async {
        ChatMockURLProtocol.requestHandler = { request in
            XCTFail("Blank access token must not make a request")
            return (self.response(for: request), Data(#"{}"#.utf8))
        }

        await assertChatError({
            _ = try await makeTicketClient().mintTicket(
                origin: "https://hermes.example.com",
                accessToken: " \n\t"
            )
        }, matches: { error in
            guard case .protocolError = error else { return false }
            return true
        })
        XCTAssertTrue(ChatMockURLProtocol.requests.isEmpty)
    }

    func testTicketRequestUsesBearerHeaderAndNoBody() async throws {
        ChatMockURLProtocol.requestHandler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-token")
            XCTAssertNil(request.httpBody)
            XCTAssertEqual(request.url?.path, "/api/auth/ws-ticket")
            return (self.response(for: request), Data(#"{"ticket":"ticket-abc","ttl_seconds":45}"#.utf8))
        }

        let ticket = try await makeTicketClient().mintTicket(
            origin: "https://hermes.example.com",
            accessToken: "access-token"
        )
        XCTAssertEqual(ticket.ticket, "ticket-abc")
    }

    func testTicketRequestAllowsCookieAuthenticationWithoutBearerHeader() async throws {
        ChatMockURLProtocol.requestHandler = { request in
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertNil(request.httpBody)
            XCTAssertEqual(request.url?.path, "/api/auth/ws-ticket")
            return (self.response(for: request), Data(#"{"ticket":"cookie-ticket","ttl_seconds":45}"#.utf8))
        }

        let ticket = try await makeTicketClient().mintTicket(
            origin: "https://hermes.example.com",
            accessToken: nil
        )
        XCTAssertEqual(ticket.ticket, "cookie-ticket")
    }

    func testChatGatewayAllowsCookieAuthenticationWithoutAccessToken() throws {
        XCTAssertNoThrow(
            try ChatGateway(
                origin: "https://hermes.example.com",
                accessToken: nil,
                ticketClient: WsTicketClient(),
                socketFactory: URLSessionChatWebSocketFactory()
            )
        )
    }

    func testChatGatewayRejectsBlankAccessTokenAtInit() {
        XCTAssertThrowsError(
            try ChatGateway(
                origin: "https://hermes.example.com",
                accessToken: " \n",
                ticketClient: WsTicketClient(),
                socketFactory: URLSessionChatWebSocketFactory()
            )
        ) { error in
            guard case .protocolError = error as? ChatError else {
                return XCTFail("Expected ChatError.protocolError, got \(error)")
            }
        }
    }

    // MARK: - WebSocket URL and origin conversion

    func testWebSocketURLBuilderPercentEncodesTicketQueryValue() throws {
        let url = try ChatGateway.webSocketURL(
            origin: "https://hermes.example.com:9443",
            ticket: "ticket+with spaces/?=&"
        )

        XCTAssertEqual(
            url,
            "wss://hermes.example.com:9443/api/ws?ticket=ticket%2Bwith%20spaces%2F%3F%3D%26"
        )
    }

    func testHTTPOriginBuildsWSWebSocketURL() throws {
        let url = try ChatGateway.webSocketURL(
            origin: "http://192.168.1.20:8080",
            ticket: "abc"
        )

        XCTAssertEqual(url, "ws://192.168.1.20:8080/api/ws?ticket=abc")
    }

    func testServerOriginWebSocketValueConversions() {
        XCTAssertEqual(
            ServerOrigin.webSocketValue("https://hermes.example.com:9443"),
            "wss://hermes.example.com:9443"
        )
        XCTAssertEqual(
            ServerOrigin.webSocketValue("http://192.168.1.20:8080"),
            "ws://192.168.1.20:8080"
        )
        XCTAssertNil(ServerOrigin.webSocketValue("ftp://hermes.example.com"))
        XCTAssertNil(ServerOrigin.webSocketValue("hermes.example.com/path"))
    }
}
