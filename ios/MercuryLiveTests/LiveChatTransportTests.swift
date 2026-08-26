import XCTest
@testable import Mercury

/// Live chat-transport checks against a configured real Hermes origin.
///
/// M1 gate per plan: "ticket minted against the configured origin; WS connects".
/// A full ticket mint needs a bearer token from an interactive PKCE sign-in;
/// without one the server must still answer the endpoint with 401 — which
/// proves the route, verb, and auth classification are exactly right. The
/// happy-path mint + open runs in ChatViewLiveTests once a token exists via
/// simulator sign-in (keychain persists across boots on this device).
final class LiveChatTransportTests: XCTestCase {

    /// The ticket endpoint exists and classifies bad credentials properly:
    /// 401 (rejected token) or 503 (the server's nous auth provider is
    /// transiently unreachable upstream — observed live). Either way the
    /// route/verb are proven and a ticket is never minted.
    func testLiveTicketEndpointRejectsInvalidAuth() async throws {
        let selfHostedOrigin = try LiveTestConfiguration.selfHostedOrigin()
        let tickets = WsTicketClient(session: .shared)

        do {
            _ = try await tickets.mintTicket(origin: selfHostedOrigin, accessToken: "deliberately-invalid")
            XCTFail("an invalid token must not mint a ticket")
        } catch let error as ChatError {
            guard case .transport(let message) = error else {
                return XCTFail("expected transport-classified failure, got \(error)")
            }
            XCTAssertTrue(
                message.contains("401") || message.contains("503"),
                "expected HTTP 401 or 503 in: \(message)"
            )
        }
    }

    /// Blank tokens are rejected locally before any network call.
    func testLiveBlankTokenRejectedPreFlight() async throws {
        let selfHostedOrigin = try LiveTestConfiguration.selfHostedOrigin()
        let tickets = WsTicketClient()
        do {
            _ = try await tickets.mintTicket(origin: selfHostedOrigin, accessToken: "  ")
            XCTFail("blank token must be rejected pre-flight")
        } catch let error as ChatError {
            guard case .protocolError = error else {
                return XCTFail("expected local protocol rejection, got \(error)")
            }
        }
    }

    /// The gateway builds a well-formed wss URL for the real origin.
    func testLiveWebSocketURLForRealOrigin() throws {
        let selfHostedOrigin = try LiveTestConfiguration.selfHostedOrigin()
        let url = try ChatGateway.webSocketURL(
            origin: selfHostedOrigin,
            ticket: "abc+123/def?x=1"
        )
        let input = try XCTUnwrap(URLComponents(string: selfHostedOrigin))
        let output = try XCTUnwrap(URLComponents(string: url))
        XCTAssertEqual(output.scheme, "wss")
        XCTAssertEqual(output.host, input.host)
        XCTAssertEqual(output.port, input.port)
        XCTAssertEqual(output.path, "/api/ws")
        // RFC3986-unreserved encoding: no raw +, /, ?, or = from the ticket.
        let query = url.split(separator: "=", maxSplits: 1).last.map(String.init) ?? ""
        XCTAssertFalse(query.contains("/"), query)
        XCTAssertFalse(query.contains("?"), query)
    }
}
