import Foundation

/// Native routing context, not a second visibility policy. The shared policy
/// still decides foreground/visible suppression after this scope is matched.
struct NotificationSourceScope: Codable, Equatable {
    let origin: String
    let relayTargetID: UUID?
    let profile: String

    init?(origin: String?, relayTargetID: UUID? = nil, profile: String) {
        guard let origin, let normalized = ServerOrigin.normalize(origin), !profile.isEmpty else { return nil }
        self.origin = normalized
        self.relayTargetID = relayTargetID
        self.profile = profile
    }
}

struct NotificationSessionIdentity: Codable, Equatable {
    static let payloadKey = "mercury.local.identity"
    let scope: NotificationSourceScope
    let sessionID: String

    var payload: String? {
        (try? JSONEncoder().encode(self)).flatMap { String(data: $0, encoding: .utf8) }
    }
    static func fromLocalPayload(_ value: Any?) -> Self? {
        guard let text = value as? String, text.utf8.count <= 4096,
              let data = text.data(using: .utf8), let identity = try? JSONDecoder().decode(Self.self, from: data),
              !identity.sessionID.isEmpty,
              let scope = NotificationSourceScope(origin: identity.scope.origin, relayTargetID: identity.scope.relayTargetID, profile: identity.scope.profile)
        else { return nil }
        return Self(scope: scope, sessionID: identity.sessionID)
    }
}
