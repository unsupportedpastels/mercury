import Foundation

// MARK: - Errors

/// Errors surfaced by `SessionLifecycleClient`. Mirrors the Android client's
/// failure taxonomy (`HermesConnectionException` /
/// `HermesSessionBulkDeleteUnsupportedException` / `IllegalArgumentException`).
enum SessionLifecycleError: Error, Equatable {
    /// Update called with no title/archived/pinned field (Android: "Session update is empty").
    case emptyUpdate
    /// Bulk delete called with no IDs (Android: "Bulk session deletion requires at least one ID").
    case bulkDeleteEmpty
    /// Bulk delete called with more than 500 IDs (Android bound).
    case bulkDeleteLimitExceeded
    /// A session ID is blank or longer than 256 characters.
    case invalidSessionID
    /// A profile argument is blank or longer than 64 characters.
    case invalidProfile
    /// The bulk-delete endpoint is missing on this server (HTTP 404/405).
    case bulkDeleteUnsupported(statusCode: Int)
    /// The server answered with a non-2xx status for the named operation.
    case requestFailed(statusCode: Int, operation: String)
    /// Bulk delete answered `ok: false`.
    case bulkDeleteNotAccepted
    /// Bulk delete omitted a usable `deleted` count (or it is out of range).
    case incompleteBulkDeleteResponse
}

// MARK: - Result models

/// Result of `PATCH /api/sessions/{id}` — the server echoes back the applied
/// fields. Decoded tolerantly: missing fields become `nil`/`false`.
struct SessionUpdateResult: Equatable {
    var ok: Bool
    var title: String?
    var archived: Bool?
    var pinned: Bool?
}

/// Result of `POST /api/sessions/bulk-delete`: how many rows were deleted.
struct BulkDeleteResult: Equatable {
    var deleted: Int
}

// MARK: - Request bodies

/// Wire body for the update call. Kotlin's serializer config keeps
/// `explicitNulls = true`, so unset fields are sent as explicit JSON nulls;
/// the custom `encode(to:)` below reproduces that exactly (Swift's default
/// optional encoding would silently omit them).
private struct SessionUpdateRequest: Encodable {
    var title: String?
    var archived: Bool?
    var pinned: Bool?
    var profile: String?

    private enum CodingKeys: String, CodingKey {
        case title, archived, pinned, profile
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        // Plain `encode(_:forKey:)` on optionals emits `null` when nil,
        // matching the Android wire body.
        try c.encode(title, forKey: .title)
        try c.encode(archived, forKey: .archived)
        try c.encode(pinned, forKey: .pinned)
        try c.encode(profile, forKey: .profile)
    }
}

/// Wire body for bulk deletion. Same explicit-null rule as above.
private struct BulkDeleteSessionsRequest: Encodable {
    var ids: [String]
    var profile: String?

    private enum CodingKeys: String, CodingKey {
        case ids, profile
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(ids, forKey: .ids)
        try c.encode(profile, forKey: .profile)
    }
}

// MARK: - Response envelopes

/// Tolerant decode of the update response; unknown fields ignored, `ok`
/// defaults to `false`.
private struct SessionUpdateResponse: Decodable {
    var ok: Bool
    var title: String?
    var archived: Bool?
    var pinned: Bool?

    private enum CodingKeys: String, CodingKey {
        case ok, title, archived, pinned
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        title = try c.decodeIfPresent(String.self, forKey: .title)
        archived = try c.decodeIfPresent(Bool.self, forKey: .archived)
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned)
    }
}

/// Tolerant decode of the bulk-delete response (`ok` defaults false,
/// `deleted` optional and range-checked by the caller, unknown fields ignored).
private struct BulkDeleteSessionsResponse: Decodable {
    var ok: Bool
    var deleted: Int?

    private enum CodingKeys: String, CodingKey {
        case ok, deleted
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        deleted = try c.decodeIfPresent(Int.self, forKey: .deleted)
    }
}

// MARK: - SessionLifecycleClient

/// Client for the Hermes session-lifecycle REST endpoints ported from the
/// Android `HermesConnectionClient`:
///
/// | Operation            | Verb + path                          |
/// |----------------------|--------------------------------------|
/// | archive/unarchive    | `PATCH /api/sessions/{id}`           |
/// | pin/unpin            | `PATCH /api/sessions/{id}`           |
/// | rename               | `PATCH /api/sessions/{id}`           |
/// | delete               | `DELETE /api/sessions/{id}`          |
/// | bulk delete          | `POST /api/sessions/bulk-delete`     |
///
/// Session *creation* is intentionally absent: on Android it is performed over
/// the WebSocket gateway (`session.create` JSON-RPC in `HermesChatGateway`),
/// not REST, so there is no REST contract to port.
struct SessionLifecycleClient {

    /// Matches the Android client's bound on session IDs and titles.
    static let maxSessionIDLength = 256
    static let maxTitleLength = 512
    static let maxProfileLength = 64

    private let client: HermesHTTPClient

    init(client: HermesHTTPClient) {
        self.client = client
    }

    // MARK: PATCH /api/sessions/{id}

    /// Updates a durable session's title/archived/pinned flags.
    ///
    /// Wire contract (from Android `updateSession`):
    /// - `PATCH /api/sessions/{encodedID}` with bearer auth.
    /// - Body `{"title": …|null, "archived": …|null, "pinned": …|null,
    ///   "profile": …|null}` with explicit nulls; `profile` is sent as `null`
    ///   when nil or `"default"`; `title` is truncated to 512 characters.
    func update(
        sessionID: String,
        title: String? = nil,
        archived: Bool? = nil,
        pinned: Bool? = nil,
        profile: String? = nil
    ) async throws -> SessionUpdateResult {
        guard title != nil || archived != nil || pinned != nil else {
            throw SessionLifecycleError.emptyUpdate
        }
        guard isValidSessionID(sessionID) else {
            throw SessionLifecycleError.invalidSessionID
        }
        let boundedTitle = title.map { String($0.prefix(Self.maxTitleLength)) }
        let body = SessionUpdateRequest(
            title: boundedTitle,
            archived: archived,
            pinned: pinned,
            profile: normalizedProfile(profile)
        )
        let (data, response) = try await client.patch(
            path: "/api/sessions/\(encodedPathSegment(sessionID))",
            jsonBody: body
        )
        if let authError = HermesAuthError.classify(response.statusCode) {
            throw authError
        }
        guard (200...299).contains(response.statusCode) else {
            throw SessionLifecycleError.requestFailed(
                statusCode: response.statusCode,
                operation: "session update"
            )
        }
        return try JSONDecoder().decode(SessionUpdateResponse.self, from: data).asResult
    }

    /// Archives a session (`archived: true`).
    func archive(sessionID: String, profile: String? = nil) async throws -> SessionUpdateResult {
        try await update(sessionID: sessionID, archived: true, profile: profile)
    }

    /// Unarchives a session (`archived: false`).
    func unarchive(sessionID: String, profile: String? = nil) async throws -> SessionUpdateResult {
        try await update(sessionID: sessionID, archived: false, profile: profile)
    }

    /// Pins a session (`pinned: true`).
    func pin(sessionID: String, profile: String? = nil) async throws -> SessionUpdateResult {
        try await update(sessionID: sessionID, pinned: true, profile: profile)
    }

    /// Unpins a session (`pinned: false`).
    func unpin(sessionID: String, profile: String? = nil) async throws -> SessionUpdateResult {
        try await update(sessionID: sessionID, pinned: false, profile: profile)
    }

    /// Renames a session (`title: newTitle`, truncated to 512 characters).
    func rename(sessionID: String, to newTitle: String, profile: String? = nil) async throws -> SessionUpdateResult {
        try await update(sessionID: sessionID, title: newTitle, profile: profile)
    }

    // MARK: DELETE /api/sessions/{id}

    /// Permanently deletes one durable session.
    ///
    /// Wire contract (from Android `deleteSession`): no request body; the
    /// profile is passed as the `profile` query parameter only when set and
    /// not `"default"`.
    func delete(sessionID: String, profile: String? = nil) async throws {
        guard isValidSessionID(sessionID) else {
            throw SessionLifecycleError.invalidSessionID
        }
        var query: [URLQueryItem] = []
        if let profileParam = normalizedProfile(profile) {
            query.append(URLQueryItem(name: "profile", value: profileParam))
        }
        let (_, response) = try await client.delete(
            path: "/api/sessions/\(encodedPathSegment(sessionID))",
            queryItems: query
        )
        if let authError = HermesAuthError.classify(response.statusCode) {
            throw authError
        }
        guard (200...299).contains(response.statusCode) else {
            throw SessionLifecycleError.requestFailed(
                statusCode: response.statusCode,
                operation: "session deletion"
            )
        }
    }

    // MARK: POST /api/sessions/bulk-delete

    /// Permanently deletes up to 500 sessions in one call.
    ///
    /// Wire contract (from Android `bulkDeleteSessions`):
    /// - Body `{"ids": […], "profile": …|null}` (explicit null profile when
    ///   absent/default); IDs are validated non-blank, ≤256 characters, then
    ///   deduplicated; 1–500 IDs required.
    /// - HTTP 404/405 maps to `.bulkDeleteUnsupported` (older servers lack
    ///   the endpoint); a valid response must carry `ok == true` and a
    ///   `deleted` count within `0...ids.count`.
    func bulkDelete(sessionIDs: [String], profile: String? = nil) async throws -> BulkDeleteResult {
        var seen = Set<String>()
        var ids: [String] = []
        for id in sessionIDs {
            guard isValidSessionID(id) else {
                throw SessionLifecycleError.invalidSessionID
            }
            if seen.insert(id).inserted {
                ids.append(id)
            }
        }
        guard !ids.isEmpty else {
            throw SessionLifecycleError.bulkDeleteEmpty
        }
        guard ids.count <= 500 else {
            throw SessionLifecycleError.bulkDeleteLimitExceeded
        }

        if let profile {
            let trimmed = profile.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, trimmed.count <= Self.maxProfileLength else {
                throw SessionLifecycleError.invalidProfile
            }
        }

        let body = BulkDeleteSessionsRequest(ids: ids, profile: normalizedProfile(profile))
        let (data, response) = try await client.post(path: "/api/sessions/bulk-delete", jsonBody: body)

        if response.statusCode == 404 || response.statusCode == 405 {
            throw SessionLifecycleError.bulkDeleteUnsupported(statusCode: response.statusCode)
        }
        if let authError = HermesAuthError.classify(response.statusCode) {
            throw authError
        }
        guard (200...299).contains(response.statusCode) else {
            throw SessionLifecycleError.requestFailed(
                statusCode: response.statusCode,
                operation: "bulk session deletion"
            )
        }

        // Mirror Android's parseBulkDeleteResponse: `deleted` must be present
        // and within 0...requestedCount, and `ok` must be true.
        let decoded = try JSONDecoder().decode(BulkDeleteSessionsResponse.self, from: data)
        guard let deleted = decoded.deleted, deleted >= 0, deleted <= ids.count else {
            throw SessionLifecycleError.incompleteBulkDeleteResponse
        }
        guard decoded.ok else {
            throw SessionLifecycleError.bulkDeleteNotAccepted
        }
        return BulkDeleteResult(deleted: deleted)
    }

    // MARK: - Validation helpers

    /// Blank-or-too-long check matching the Android client's bounds.
    private func isValidSessionID(_ id: String) -> Bool {
        !id.isEmpty && id.count <= Self.maxSessionIDLength
    }

    /// `"default"` profiles are elided on the wire (the server treats them as
    /// implicit); everything else passes through unchanged.
    private func normalizedProfile(_ profile: String?) -> String? {
        guard let profile, profile != "default" else { return nil }
        return profile
    }

    /// Session IDs are opaque; percent-encode so odd IDs stay a single path
    /// segment (same treatment as `SessionsClient.transcript`).
    private func encodedPathSegment(_ id: String) -> String {
        id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
    }
}

// MARK: - Envelope → result bridging

private extension SessionUpdateResponse {
    var asResult: SessionUpdateResult {
        SessionUpdateResult(ok: ok, title: title, archived: archived, pinned: pinned)
    }
}
