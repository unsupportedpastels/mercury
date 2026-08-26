import Foundation

// MARK: - WebSocket seams

/// The smallest socket surface needed by the later chat connection state
/// machine. Returning nil from `receiveText` means the peer has closed.
protocol ChatSocketing: Sendable {
    func sendText(_ text: String) async throws
    func receiveText() async throws -> String?
    func close() async
}

/// Factory seam that keeps URLSession details out of the connection state
/// machine and makes that state machine straightforward to fake in tests.
protocol ChatWebSocketFactorying: Sendable {
    func connect(url: String) async throws -> any ChatSocketing
}

// MARK: - URLSession transport

/// URLSession-backed WebSocket factory for Hermes chat connections.
final class URLSessionChatWebSocketFactory: ChatWebSocketFactorying, @unchecked Sendable {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func connect(url: String) async throws -> any ChatSocketing {
        guard let socketURL = URL(string: url),
              let scheme = socketURL.scheme?.lowercased(),
              scheme == "ws" || scheme == "wss",
              socketURL.host != nil
        else {
            throw ChatError.protocolError("Hermes WebSocket URL is invalid")
        }

        let task = session.webSocketTask(with: socketURL)
        task.resume()
        return URLSessionChatSocket(task: task)
    }
}

/// Thin adapter around URLSessionWebSocketTask.
///
/// URLSession's receive API exposes text and binary messages; control frames
/// (ping/pong) are handled by URLSession and close/error is reported by the
/// receive operation. The loop therefore naturally skips control activity and
/// keeps waiting until a text message, a UTF-8 binary message, or peer closure.
private final class URLSessionChatSocket: ChatSocketing, @unchecked Sendable {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask) {
        self.task = task
    }

    func sendText(_ text: String) async throws {
        do {
            try await task.send(.string(text))
        } catch let error as CancellationError {
            throw error
        } catch {
            // Never include the frame text in an error: chat frames can contain
            // prompts, tokens, or other user data.
            throw ChatError.transport("Could not send Hermes chat WebSocket frame")
        }
    }

    func receiveText() async throws -> String? {
        while true {
            do {
                let message = try await task.receive()
                switch message {
                case let .string(text):
                    return text
                case let .data(data):
                    guard let text = String(data: data, encoding: .utf8) else {
                        throw ChatError.protocolError(
                            "Hermes chat WebSocket binary frame was not valid UTF-8"
                        )
                    }
                    return text
                @unknown default:
                    // Future URLSession message kinds, like currently hidden
                    // control frames, should not terminate the text stream.
                    continue
                }
            } catch let error as ChatError {
                throw error
            } catch {
                // URLSession reports a peer close and task-level receive errors
                // through this path. Both have the socket contract's nil
                // meaning; send failures remain throwing above.
                return nil
            }
        }
    }

    func close() async {
        task.cancel(with: .normalClosure, reason: nil)
    }
}

// MARK: - Gateway composition

/// Composes ticket minting and WebSocket creation without owning the later
/// chat read loop, event routing, or request/response state machine.
struct ChatGateway: Sendable {
    private let origin: String
    private let accessToken: String?
    private let ticketClient: any WsTicketClienting
    private let socketFactory: any ChatWebSocketFactorying

    init(
        origin: String,
        accessToken: String?,
        ticketClient: any WsTicketClienting,
        socketFactory: any ChatWebSocketFactorying
    ) throws {
        if let accessToken,
           accessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ChatError.protocolError("Hermes access token must not be blank")
        }
        self.origin = origin
        self.accessToken = accessToken
        self.ticketClient = ticketClient
        self.socketFactory = socketFactory
    }

    /// Mints a fresh single-use ticket for each connection attempt, then
    /// connects to the ticketed `/api/ws` endpoint.
    func connect() async throws -> any ChatSocketing {
        let ticket = try await ticketClient.mintTicket(
            origin: origin,
            accessToken: accessToken
        )
        let url = try Self.webSocketURL(origin: origin, ticket: ticket.ticket)
        return try await socketFactory.connect(url: url)
    }

    /// Pure URL builder used by `connect()` and directly by hermetic tests.
    /// URLComponents assembles the origin and path; the ticket is encoded as an
    /// RFC3986-safe query value so characters such as `+`, `/`, `?`, and `&`
    /// cannot alter the endpoint or add parameters.
    static func webSocketURL(origin: String, ticket: String) throws -> String {
        guard !ticket.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let websocketOrigin = ServerOrigin.webSocketValue(origin),
              var components = URLComponents(string: websocketOrigin)
        else {
            throw ChatError.protocolError("Hermes WebSocket origin or ticket is invalid")
        }

        components.path = "/api/ws"
        components.fragment = nil

        // URLQueryItem follows URLComponents' broad RFC3986 query grammar,
        // which permits characters such as `+` and `/` in a query component.
        // Tickets are opaque values, so encode everything except RFC3986's
        // unreserved set to prevent any parser from treating ticket bytes as
        // query syntax or form encoding.
        let unreserved = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        )
        guard let encodedTicket = ticket.addingPercentEncoding(withAllowedCharacters: unreserved) else {
            throw ChatError.protocolError("Hermes WebSocket ticket could not be URL-encoded")
        }
        components.percentEncodedQuery = "ticket=\(encodedTicket)"

        guard let url = components.url else {
            throw ChatError.protocolError("Hermes WebSocket URL is invalid")
        }
        return url.absoluteString
    }
}
