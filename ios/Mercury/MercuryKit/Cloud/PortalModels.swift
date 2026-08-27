import Foundation

/// OAuth 2.0 Device Authorization Grant response from the portal
/// (`POST {portal}/api/oauth/device/code`).
public struct DeviceCode: Codable, Equatable {
    /// Opaque device identifier; sent back when polling for tokens.
    public let deviceCode: String
    /// Short human-readable code the user enters at the verification URI.
    public let userCode: String
    /// Base verification URL the user visits.
    public let verificationURI: String
    /// Optional pre-filled URL containing the user code.
    public let verificationURIComplete: String?
    /// Seconds until the device code expires.
    public let expiresIn: Int
    /// Seconds to wait between token polls.
    public let interval: Int

    private enum CodingKeys: String, CodingKey {
        case deviceCode = "device_code"
        case userCode = "user_code"
        case verificationURI = "verification_uri"
        case verificationURIComplete = "verification_uri_complete"
        case expiresIn = "expires_in"
        case interval
    }

    public init(
        deviceCode: String,
        userCode: String,
        verificationURI: String,
        verificationURIComplete: String? = nil,
        expiresIn: Int,
        interval: Int
    ) {
        self.deviceCode = deviceCode
        self.userCode = userCode
        self.verificationURI = verificationURI
        self.verificationURIComplete = verificationURIComplete
        self.expiresIn = expiresIn
        self.interval = interval
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        // Required fields.
        self.deviceCode = try container.decode(String.self, forKey: .deviceCode)
        self.userCode = try container.decode(String.self, forKey: .userCode)
        self.verificationURI = try container.decode(String.self, forKey: .verificationURI)
        // Tolerant optional fields with sensible defaults.
        self.verificationURIComplete = try container
            .decodeIfPresent(String.self, forKey: .verificationURIComplete)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        self.expiresIn = try container.decodeIfPresent(Int.self, forKey: .expiresIn) ?? 600
        self.interval = try container.decodeIfPresent(Int.self, forKey: .interval) ?? 5
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(deviceCode, forKey: .deviceCode)
        try container.encode(userCode, forKey: .userCode)
        try container.encode(verificationURI, forKey: .verificationURI)
        try container.encodeIfPresent(verificationURIComplete, forKey: .verificationURIComplete)
        try container.encode(expiresIn, forKey: .expiresIn)
        try container.encode(interval, forKey: .interval)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// A persisted pair of portal access and refresh tokens
/// (`access_token` is required, `refresh_token` may be absent).
public struct TokenSet: Codable, Equatable {
    public let accessToken: String
    public let refreshToken: String?

    private enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }

    public init(accessToken: String, refreshToken: String? = nil) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
    }
}

/// One agent row from portal agent discovery (`GET {portal}/api/agents`).
public struct CloudAgent: Codable, Equatable {
    public let id: String
    public let name: String
    public let status: String
    /// Optional dashboard deep link (absent on some agents).
    public let dashboardURL: String?
    /// Optional gateway state reported by the dashboard.
    public let gatewayState: String?

    private enum CodingKeys: String, CodingKey {
        case id, name, status
        case dashboardURL = "dashboardUrl"
        case gatewayState = "dashboardGatewayState"
    }

    public init(
        id: String,
        name: String,
        status: String,
        dashboardURL: String? = nil,
        gatewayState: String? = nil
    ) {
        self.id = id
        self.name = name
        self.status = status
        self.dashboardURL = dashboardURL
        self.gatewayState = gatewayState
    }
}

/// An organization visible to the authenticated portal user.
public struct PortalOrg: Codable, Equatable {
    public let id: String
    public let slug: String
    public let name: String
    /// True when this org is the user's personal workspace.
    public let isPersonal: Bool?
    /// The user's role within the organization.
    public let role: String?

    private enum CodingKeys: String, CodingKey {
        case id, slug, name
        case isPersonal = "isPersonal"
        case role
    }

    public init(
        id: String,
        slug: String,
        name: String,
        isPersonal: Bool? = nil,
        role: String? = nil
    ) {
        self.id = id
        self.slug = slug
        self.name = name
        self.isPersonal = isPersonal
        self.role = role
    }
}

/// Response body of portal agent discovery.
public struct AgentDiscovery: Codable, Equatable {
    public let agents: [CloudAgent]
    /// The org whose agents were listed, when the server reports one.
    public let org: PortalOrg?

    public init(agents: [CloudAgent], org: PortalOrg?) {
        self.agents = agents
        self.org = org
    }
}

/// Minimal org descriptor offered to the user when the portal requires
/// an explicit organization selection (HTTP 409).
public struct OrgChoice: Codable, Equatable {
    public let id: String
    public let slug: String
    public let name: String

    public init(id: String, slug: String, name: String) {
        self.id = id
        self.slug = slug
        self.name = name
    }
}

/// Thrown by `PortalClient.agents(bearer:org:)` when the portal responds
/// 409 because no default organization is resolvable.
public struct OrgSelectionRequiredError: Error {
    /// The organizations the user may pick from; retry with `?org=<slug>`.
    public let choices: [OrgChoice]
}
