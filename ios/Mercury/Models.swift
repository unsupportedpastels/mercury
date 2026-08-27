import Foundation

/// Tolerant Codable models for the official Hermes HTTP API.
/// Unknown JSON fields are ignored by JSONDecoder by default; known fields
/// decode defensively so partial payloads never crash the client.

struct SessionRow: Identifiable, Equatable, Codable {
    var id: String
    var title: String
    var preview: String
    var lastActive: Date?
    var messageCount: Int
    var model: String?
    var provider: String?
    var profile: String?
    var workspacePath: String?

    private enum CodingKeys: String, CodingKey {
        case id
        case title
        case preview
        case lastActive = "last_active"
        case messageCount = "message_count"
        case model
        case provider
        case profile
        case workspacePath = "cwd"
        case workspacePathAlt = "workspace_path"
    }

    init(
        id: String,
        title: String = "",
        preview: String = "",
        lastActive: Date? = nil,
        messageCount: Int = 0,
        model: String? = nil,
        provider: String? = nil,
        profile: String? = nil,
        workspacePath: String? = nil
    ) {
        self.id = id
        self.title = title
        self.preview = preview
        self.lastActive = lastActive
        self.messageCount = messageCount
        self.model = model
        self.provider = provider
        self.profile = profile
        self.workspacePath = workspacePath
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // `id` is the one required field: a session row without an identity
        // cannot be keyed, so decoding fails cleanly instead of guessing.
        guard let decodedID = try c.decodeIfPresent(String.self, forKey: .id) else {
            throw DecodingError.keyNotFound(
                CodingKeys.id,
                .init(codingPath: decoder.codingPath, debugDescription: "SessionRow requires 'id'")
            )
        }
        id = decodedID
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        preview = try c.decodeIfPresent(String.self, forKey: .preview) ?? ""

        // Accept ISO-8601 strings, epoch seconds/milliseconds, or absence.
        // A wrong-type attempt throws typeMismatch (not nil), so coerce with
        // try? and let the next branch try the numeric shape.
        let iso = (try? c.decodeIfPresent(String.self, forKey: .lastActive)) ?? nil
        if let iso {
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            lastActive = formatter.date(from: iso)
                ?? ISO8601DateFormatter().date(from: iso)
        } else if let seconds = (try? c.decodeIfPresent(Double.self, forKey: .lastActive)) ?? nil {
            // Heuristic: millisecond epochs are > ~year 2500 in seconds.
            lastActive = Date(timeIntervalSince1970: seconds > 1e11 ? seconds / 1000.0 : seconds)
        } else {
            lastActive = nil
        }

        messageCount = try c.decodeIfPresent(Int.self, forKey: .messageCount) ?? 0
        model = try c.decodeIfPresent(String.self, forKey: .model)
        provider = try c.decodeIfPresent(String.self, forKey: .provider)
        profile = try c.decodeIfPresent(String.self, forKey: .profile)
        if let cwd = try c.decodeIfPresent(String.self, forKey: .workspacePath) {
            workspacePath = cwd
        } else {
            workspacePath = try c.decodeIfPresent(String.self, forKey: .workspacePathAlt)
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(title, forKey: .title)
        try c.encode(preview, forKey: .preview)
        try c.encodeIfPresent(lastActive, forKey: .lastActive)
        try c.encode(messageCount, forKey: .messageCount)
        try c.encodeIfPresent(model, forKey: .model)
        try c.encodeIfPresent(provider, forKey: .provider)
        try c.encodeIfPresent(profile, forKey: .profile)
        try c.encodeIfPresent(workspacePath, forKey: .workspacePath)
    }
}

/// A bounded result from Hermes' session-ID/full-text search endpoint.
struct SessionSearchResult: Identifiable, Equatable, Codable {
    let sessionID: String
    let title: String
    let snippet: String
    let role: String?

    var id: String { sessionID }
}

struct HermesStatus: Equatable, Codable {
    var version: String
    var authRequired: Bool
    var activeSessions: Int?

    private enum CodingKeys: String, CodingKey {
        case version
        case authRequired = "auth_required"
        case activeSessions = "active_sessions"
    }

    init(version: String = "", authRequired: Bool = false, activeSessions: Int? = nil) {
        self.version = version
        self.authRequired = authRequired
        self.activeSessions = activeSessions
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(String.self, forKey: .version) ?? ""
        authRequired = try c.decodeIfPresent(Bool.self, forKey: .authRequired) ?? false
        activeSessions = try c.decodeIfPresent(Int.self, forKey: .activeSessions)
    }
}

struct AuthProvider: Identifiable, Equatable, Codable {
    var name: String
    var displayName: String
    var supportsPassword: Bool

    var id: String { name }

    private enum CodingKeys: String, CodingKey {
        case name
        case displayName = "display_name"
        case supportsPassword = "supports_password"
    }

    init(name: String, displayName: String? = nil, supportsPassword: Bool = false) {
        self.name = name
        self.displayName = displayName ?? name
        self.supportsPassword = supportsPassword
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // A provider without a machine-readable name is unusable; fail cleanly.
        guard let decodedName = try c.decodeIfPresent(String.self, forKey: .name) else {
            throw DecodingError.keyNotFound(
                CodingKeys.name,
                .init(codingPath: decoder.codingPath, debugDescription: "AuthProvider requires 'name'")
            )
        }
        name = decodedName
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? name
        supportsPassword = try c.decodeIfPresent(Bool.self, forKey: .supportsPassword) ?? false
    }
}

struct AuthProvidersResponse: Equatable, Codable {
    var providers: [AuthProvider]

    private enum CodingKeys: String, CodingKey {
        case providers
    }

    init(providers: [AuthProvider] = []) {
        self.providers = providers
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        providers = try c.decodeIfPresent([AuthProvider].self, forKey: .providers) ?? []
    }
}
