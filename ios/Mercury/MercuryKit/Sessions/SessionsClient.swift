import Foundation

// MARK: - Response models

/// One page of profile sessions returned by `GET /api/profiles/sessions`.
///
/// `total` mirrors the server's total-matching count when present; `hasMore`
/// is derived from `total` when available and falls back to a "page was
/// completely filled" heuristic when the server omits it.
struct SessionPage: Equatable {
    var rows: [SessionRow]
    var total: Int?
    var hasMore: Bool
}

/// A single message from a session transcript.
///
/// Both fields decode tolerantly: a missing or mistyped `role`/`content`
/// becomes an empty string rather than failing the whole transcript.
struct TranscriptMessage: Equatable, Decodable {
    var role: String
    var content: String
    var toolName: String?
    var reasoningText: String

    private enum CodingKeys: String, CodingKey {
        case role
        case content
        case text
        case toolName = "tool_name"
        case name
        case reasoning
        case reasoningContent = "reasoning_content"
        case reasoningDetails = "reasoning_details"
    }

    init(
        role: String = "",
        content: String = "",
        toolName: String? = nil,
        reasoningText: String = ""
    ) {
        self.role = role
        self.content = content
        self.toolName = toolName
        self.reasoningText = reasoningText
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        role = (try? c.decode(String.self, forKey: .role)) ?? ""
        content = (try? c.decode(String.self, forKey: .content))
            ?? (try? c.decode(String.self, forKey: .text))
            ?? ""
        toolName = (try? c.decode(String.self, forKey: .toolName))
            ?? (try? c.decode(String.self, forKey: .name))
        reasoningText = (try? c.decode(String.self, forKey: .reasoning))
            ?? (try? c.decode(String.self, forKey: .reasoningContent))
            ?? (try? c.decode(String.self, forKey: .reasoningDetails))
            ?? ""
    }
}

// MARK: - SessionsClient

/// Client for the official Hermes session-listing endpoints:
/// `GET /api/profiles/sessions` and `GET /api/sessions/{id}/messages`.
///
/// All decoding is tolerant — partial payloads never crash the client, in
/// line with the rest of MercuryKit.
struct SessionsClient {

    /// Transcript rows can carry tool/reasoning metadata and legitimately
    /// exceed the generic transport cap. Match the mature Android client:
    /// allow a bounded 1 MiB transcript response and reduce the latest-page
    /// size only when that transcript-specific bound is exceeded.
    private static let maxTranscriptResponseBytes = 1024 * 1024
    private static let transcriptPageLimits = [100, 50, 25, 10, 5, 1]

    private let client: HermesHTTPClient
    private let profile: String

    /// - Parameters:
    ///   - client: Shared HTTP transport scoped to the normalized server origin.
    ///   - profile: Hermes profile whose sessions are listed. Defaults to "default".
    init(client: HermesHTTPClient, profile: String = "default") {
        self.client = client
        self.profile = profile
    }

    /// Fetches one page of the profile's recent (non-archived) sessions.
    ///
    /// Builds the official query contract:
    /// `profile`, `limit`, `order=recent`, `archived=exclude`, `offset`.
    /// When the response carries a `total`, `hasMore` is
    /// `offset + rows.count < total`; otherwise it falls back to
    /// "the page was filled to `limit`, so more likely exist".
    func sessions(limit: Int = 20, offset: Int = 0) async throws -> SessionPage {
        let query: [URLQueryItem] = [
            URLQueryItem(name: "profile", value: profile),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "order", value: "recent"),
            URLQueryItem(name: "archived", value: "exclude"),
            URLQueryItem(name: "offset", value: String(offset)),
        ]
        let (data, response) = try await client.get(path: "/api/profiles/sessions", queryItems: query)
        if let authError = HermesAuthError.classify(response.statusCode) {
            throw authError
        }
        let decoded = try JSONDecoder().decode(SessionsResponse.self, from: data)

        let hasMore: Bool
        if let total = decoded.total {
            hasMore = offset + decoded.sessions.count < total
        } else {
            hasMore = decoded.sessions.count == limit
        }
        return SessionPage(rows: decoded.sessions, total: decoded.total, hasMore: hasMore)
    }

    /// Searches session IDs and full-text message content through Hermes' own
    /// FTS5-backed endpoint. The server prioritizes direct ID matches.
    func search(query: String, limit: Int = 20) async throws -> [SessionSearchResult] {
        let boundedQuery = String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(256))
        guard !boundedQuery.isEmpty else { return [] }
        let boundedLimit = max(1, min(limit, 20))
        let (data, response) = try await client.get(
            path: "/api/sessions/search",
            queryItems: [
                URLQueryItem(name: "q", value: boundedQuery),
                URLQueryItem(name: "limit", value: String(boundedLimit)),
                URLQueryItem(name: "profile", value: profile)
            ]
        )
        if let authError = HermesAuthError.classify(response.statusCode) {
            throw authError
        }
        guard (200..<300).contains(response.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let decoded = try JSONDecoder().decode(SessionSearchResponse.self, from: data)
        var seen = Set<String>()
        return decoded.results.compactMap { row in
            guard let rawID = (row.sessionID ?? row.id)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !rawID.isEmpty else { return nil }
            let sessionID = String(rawID.prefix(256))
            guard seen.insert(sessionID).inserted else { return nil }
            return SessionSearchResult(
                sessionID: sessionID,
                title: String((row.title?.isEmpty == false ? row.title! : "Untitled session").prefix(512)),
                snippet: String((row.snippet ?? "").prefix(1_000)),
                role: row.role.map { String($0.prefix(32)) }
            )
        }
    }

    /// Fetches the message transcript for one session, newest-last.
    ///
    /// The server has shipped the array under either `"data"` or
    /// `"messages"`; both are accepted, whichever decodes first.
    func transcript(sessionID: String, limit: Int = 100) async throws -> [TranscriptMessage] {
        let pageLimits = Self.transcriptPageLimits.filter { $0 <= limit }
        let attempts = pageLimits.isEmpty ? [max(1, limit)] : pageLimits
        for (index, pageLimit) in attempts.enumerated() {
            do {
                return try await transcriptPage(sessionID: sessionID, limit: pageLimit)
            } catch is ResponseTooLargeError {
                if index == attempts.indices.last { throw ResponseTooLargeError() }
            }
        }
        throw ResponseTooLargeError()
    }

    /// Fetches one older window of transcript history for "Load earlier".
    ///
    /// `offset` counts backward from the newest record on the released
    /// server, so the accumulated loaded count is passed directly. The same
    /// bounded-retry ladder as the primary transcript protects against
    /// oversized pages.
    func olderTranscript(sessionID: String, offset: Int, limit: Int = TranscriptHistoryPolicy.pageSize) async throws -> [TranscriptMessage] {
        let pageLimits = Self.transcriptPageLimits.filter { $0 <= limit }
        let attempts = pageLimits.isEmpty ? [max(1, limit)] : pageLimits
        for (index, pageLimit) in attempts.enumerated() {
            do {
                return try await transcriptPage(sessionID: sessionID, limit: pageLimit, offset: offset)
            } catch is ResponseTooLargeError {
                if index == attempts.indices.last { throw ResponseTooLargeError() }
            }
        }
        throw ResponseTooLargeError()
    }

    private func transcriptPage(sessionID: String, limit: Int, offset: Int = 0) async throws -> [TranscriptMessage] {
        let query: [URLQueryItem] = [
            URLQueryItem(name: "profile", value: profile),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "order", value: "latest"),
            URLQueryItem(name: "offset", value: String(offset)),
        ]
        // Session IDs are opaque; percent-encode so odd IDs stay a single path segment.
        let encodedID = sessionID.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? sessionID
        let (data, response) = try await client.get(
            path: "/api/sessions/\(encodedID)/messages",
            queryItems: query,
            maximumResponseBytes: Self.maxTranscriptResponseBytes
        )
        if let authError = HermesAuthError.classify(response.statusCode) {
            throw authError
        }
        return try JSONDecoder().decode(TranscriptEnvelope.self, from: data).messages
    }
}

private struct SessionSearchResponse: Decodable {
    let results: [SessionSearchRow]
}

private struct SessionSearchRow: Decodable {
    let sessionID: String?
    let id: String?
    let title: String?
    let snippet: String?
    let role: String?

    private enum CodingKeys: String, CodingKey {
        case sessionID = "session_id"
        case id, title, snippet, role
    }
}

// MARK: - Private envelope decoding

/// Tolerant envelope for the sessions page: `sessions` defaults to `[]`,
/// `total` is optional, unknown fields (e.g. `limit`, `offset` echoes) are ignored.
private struct SessionsResponse: Decodable {
    var sessions: [SessionRow]
    var total: Int?

    private enum CodingKeys: String, CodingKey {
        case sessions
        case total
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sessions = try c.decodeIfPresent([SessionRow].self, forKey: .sessions) ?? []
        total = try c.decodeIfPresent(Int.self, forKey: .total)
    }
}

/// Tolerant envelope for a transcript: accepts the message array under
/// either the `"data"` or `"messages"` key; absence of both yields `[]`.
private struct TranscriptEnvelope: Decodable {
    var messages: [TranscriptMessage]

    private enum CodingKeys: String, CodingKey {
        case data
        case messages
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let viaData = (try? c.decodeIfPresent([TranscriptMessage].self, forKey: .data)) ?? nil {
            messages = viaData
        } else if let viaMessages = (try? c.decodeIfPresent([TranscriptMessage].self, forKey: .messages)) ?? nil {
            messages = viaMessages
        } else {
            messages = []
        }
    }
}
