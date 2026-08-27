import Foundation

struct SessionOpenRoute: Equatable, Hashable, Sendable {
    let durableSessionID: String
    let serverID: UUID
    let profile: String
}

enum MercuryDeepLink {
    private static let scheme = "mercury"
    private static let host = "session"
    private static let maxSessionIDCharacters = 256
    private static let maxProfileCharacters = 64

    static func sessionURL(
        durableSessionID: String,
        serverID: UUID,
        profile: String
    ) -> URL? {
        guard isValidValue(durableSessionID, maximumCharacters: maxSessionIDCharacters),
              isValidValue(profile, maximumCharacters: maxProfileCharacters)
        else {
            return nil
        }

        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.queryItems = [
            URLQueryItem(name: "id", value: durableSessionID),
            URLQueryItem(name: "server", value: serverID.uuidString),
            URLQueryItem(name: "profile", value: profile)
        ]
        return components.url
    }

    static func parse(_ url: URL) -> SessionOpenRoute? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme?.caseInsensitiveCompare(scheme) == .orderedSame,
              components.host == host,
              components.path.isEmpty,
              components.user == nil,
              components.password == nil,
              components.port == nil,
              components.fragment == nil,
              let queryItems = components.queryItems
        else {
            return nil
        }

        // Validate every decoded query value, including values belonging to
        // unknown parameters. A control character must never cross this URL
        // boundary, even if it was percent-encoded in the source URL.
        guard !queryItems.contains(where: { item in
            guard let value = item.value else { return false }
            return value.unicodeScalars.contains { scalar in
                CharacterSet.controlCharacters.contains(scalar)
            }
        }) else {
            return nil
        }

        guard !queryItems.contains(where: { $0.name == "origin" }) else {
            return nil
        }

        guard let durableSessionID = uniqueValue(named: "id", in: queryItems),
              let serverValue = uniqueValue(named: "server", in: queryItems),
              let profile = uniqueValue(named: "profile", in: queryItems),
              isValidValue(durableSessionID, maximumCharacters: maxSessionIDCharacters),
              isValidValue(profile, maximumCharacters: maxProfileCharacters),
              let serverID = UUID(uuidString: serverValue)
        else {
            return nil
        }

        return SessionOpenRoute(
            durableSessionID: durableSessionID,
            serverID: serverID,
            profile: profile
        )
    }

    private static func uniqueValue(named name: String, in queryItems: [URLQueryItem]) -> String? {
        let matches = queryItems.filter { $0.name == name }
        guard matches.count == 1 else { return nil }
        return matches[0].value
    }

    private static func isValidValue(_ value: String, maximumCharacters: Int) -> Bool {
        guard !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              value.count <= maximumCharacters,
              !value.unicodeScalars.contains(where: { scalar in
                  CharacterSet.controlCharacters.contains(scalar)
              })
        else {
            return false
        }
        return true
    }
}
