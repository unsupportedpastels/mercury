import Foundation

// MARK: - Defensive protocol limits

/// Default upper bound for one chat frame. The bound keeps a malformed or
/// unexpectedly large attachment from consuming unbounded native memory while
/// still matching the Android client's 36 MiB contract.
let maxFrameBytes = 36 * 1024 * 1024

/// The ticket endpoint is intentionally much smaller than the general HTTP
/// response cap because it returns only a short JSON object.
let wsTicketMaxResponseBytes = 16 * 1024

/// Maximum number of buffered events retained by the later connection layer.
let maxEventBuffer = 128

let maxEventIDChars = 256
let maxEventNameChars = 256
let maxEventTextChars = 4096
let maxMessageTextChars = 1024 * 1024
let maxEventContextChars = 4096
let maxEventChoiceChars = 256
let maxEventChoiceCount = 32

/// Validates a caller-supplied frame limit without allowing it to exceed the
/// protocol's hard ceiling. This is kept separate from the later connection
/// state machine so every entry point can apply the same safety rule.
func validatedMaxFrameBytes(_ configured: Int) throws -> Int {
    guard (1...maxFrameBytes).contains(configured) else {
        throw ChatError.protocolError(
            "Configured chat frame limit must be between 1 and \(maxFrameBytes) bytes"
        )
    }
    return configured
}

// MARK: - Errors

/// Errors surfaced by the chat transport slice. Transport failures describe
/// connectivity or HTTP/WebSocket failures; protocol errors describe a peer
/// payload or local configuration that violates the Hermes contract.
enum ChatError: Error, Sendable, Equatable {
    case transport(String)
    case protocolError(String)
}

extension ChatError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case let .transport(message), let .protocolError(message):
            return message
        }
    }
}

/// JSON-RPC's standard "method not found" error, defined now for the later
/// request/response layer even though this slice does not implement RPC calls.
struct ChatMethodNotFoundError: Error, Sendable, Equatable, LocalizedError {
    let method: String

    var errorDescription: String? {
        "Hermes method is not supported: \(method)"
    }
}

// MARK: - WebSocket ticket

/// A fresh, short-lived ticket returned by `/api/auth/ws-ticket`.
///
/// The initializer validates here rather than relying on callers to remember
/// the checks, because accepting an empty ticket or non-positive TTL would
/// create a connection that can never be authenticated safely.
struct WsTicket: Codable, Sendable, Equatable {
    let ticket: String
    let ttlSeconds: Int64

    init(ticket: String, ttlSeconds: Int64) throws {
        guard !ticket.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ChatError.protocolError("Hermes WebSocket ticket must not be blank")
        }
        guard ttlSeconds > 0 else {
            throw ChatError.protocolError("Hermes WebSocket ticket TTL must be positive")
        }
        self.ticket = ticket
        self.ttlSeconds = ttlSeconds
    }

    private enum CodingKeys: String, CodingKey {
        case ticket
        case ttlSeconds = "ttl_seconds"
    }

    init(from decoder: Decoder) throws {
        let container: KeyedDecodingContainer<CodingKeys>
        do {
            container = try decoder.container(keyedBy: CodingKeys.self)
        } catch {
            throw ChatError.protocolError("Hermes ticket response must be a JSON object")
        }

        // Decode only the required fields and ignore future server fields.
        // Mistyped required values are protocol failures, not raw decoding
        // errors, so callers can classify all malformed ticket responses alike.
        guard let ticket = try? container.decode(String.self, forKey: .ticket) else {
            throw ChatError.protocolError("Hermes ticket response was incomplete")
        }
        guard let ttlSeconds = try? container.decode(Int64.self, forKey: .ttlSeconds) else {
            throw ChatError.protocolError("Hermes ticket response was incomplete")
        }
        try self.init(ticket: ticket, ttlSeconds: ttlSeconds)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(ticket, forKey: .ticket)
        try container.encode(ttlSeconds, forKey: .ttlSeconds)
    }
}
