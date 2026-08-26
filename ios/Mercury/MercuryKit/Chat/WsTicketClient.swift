import Foundation

/// Mints the short-lived ticket used to authenticate a chat WebSocket.
protocol WsTicketClienting: Sendable {
    func mintTicket(origin: String, accessToken: String?) async throws -> WsTicket
}

/// URLSession-backed implementation of the Hermes ticket endpoint.
///
/// This client uses a direct URLSession request rather than the JSON-body POST
/// helper because `/api/auth/ws-ticket` intentionally has no request body.
/// The response is still checked against the ticket-specific cap after the
/// fetch, keeping malformed or oversized responses out of the decoder.
final class WsTicketClient: WsTicketClienting, @unchecked Sendable {
    private let session: URLSession
    private let defaultOrigin: String?

    /// Creates a ticket client with an injectable session so unit tests can
    /// route requests through a per-file URLProtocol without real networking.
    init(session: URLSession = .shared) {
        self.session = session
        self.defaultOrigin = nil
    }

    /// Convenience initializer for callers that already own MercuryKit's
    /// bearer-auth HTTP client. The origin is retained for the convenience
    /// overload below; the gateway still supplies its origin explicitly so it
    /// cannot accidentally mint against a different configured host.
    convenience init(client: HermesHTTPClient, session: URLSession = .shared) {
        self.init(session: session, defaultOrigin: client.origin)
    }

    private init(session: URLSession, defaultOrigin: String?) {
        self.session = session
        self.defaultOrigin = defaultOrigin
    }

    /// Mints against the origin supplied by `init(client:)`. The gateway uses
    /// the explicit-origin overload, which is preferable when several hosts
    /// can be configured in one process.
    func mintTicket(accessToken: String?) async throws -> WsTicket {
        guard let defaultOrigin else {
            throw ChatError.protocolError("Hermes ticket client has no server origin")
        }
        return try await mintTicket(origin: defaultOrigin, accessToken: accessToken)
    }

    func mintTicket(origin: String, accessToken: String?) async throws -> WsTicket {
        // Nil deliberately selects cookie authentication. A present-but-blank
        // bearer remains invalid and can never reach the wire.
        if let accessToken,
           accessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw ChatError.protocolError("Hermes access token must not be blank")
        }

        var request: URLRequest
        do {
            request = try Self.makeRequest(origin: origin, accessToken: accessToken)
        } catch let error as ChatError {
            throw error
        } catch {
            throw ChatError.transport("Could not build Hermes ticket request")
        }
        if accessToken == nil,
           let url = request.url,
           let storage = session.configuration.httpCookieStorage {
            let cookies = storage.cookies(for: url) ?? []
            if !cookies.isEmpty {
                request.setValue(
                    HTTPCookie.requestHeaderFields(with: cookies)["Cookie"],
                    forHTTPHeaderField: "Cookie"
                )
            }
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as CancellationError {
            throw error
        } catch {
            // Do not include the request URL or Authorization value in this
            // message; URLSession errors are not a safe logging boundary.
            throw ChatError.transport("Could not mint Hermes chat ticket")
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw ChatError.transport("Hermes ticket request returned an invalid response")
        }

        // Check the narrow ticket cap before status handling and decoding, just
        // as the Android client bounds the body before parsing its JSON object.
        guard data.count <= wsTicketMaxResponseBytes else {
            throw ChatError.transport(
                "Hermes ticket response too large (maximum \(wsTicketMaxResponseBytes) bytes)"
            )
        }

        guard (200...299).contains(httpResponse.statusCode) else {
            throw ChatError.transport(
                "Hermes ticket request returned HTTP \(httpResponse.statusCode)"
            )
        }

        do {
            return try JSONDecoder().decode(WsTicket.self, from: data)
        } catch let error as ChatError {
            throw error
        } catch {
            throw ChatError.protocolError("Hermes ticket response was not valid JSON")
        }
    }

    /// Builds the bodyless ticket request in one place so the security-sensitive
    /// header and endpoint contract are easy to audit.
    private static func makeRequest(origin: String, accessToken: String?) throws -> URLRequest {
        guard let normalizedOrigin = ServerOrigin.normalize(origin),
              var components = URLComponents(string: normalizedOrigin)
        else {
            throw ChatError.protocolError("Hermes server origin is invalid")
        }
        components.path = "/api/auth/ws-ticket"
        components.query = nil
        components.fragment = nil
        guard let url = components.url else {
            throw ChatError.protocolError("Hermes server origin is invalid")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        if let accessToken {
            request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = nil
        return request
    }
}
