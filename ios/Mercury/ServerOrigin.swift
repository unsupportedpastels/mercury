import Foundation

/// Pure origin-normalization helpers shared conceptually with the Android
/// client (`ServerOrigin` there). No UIKit/AppKit dependencies — Foundation only.

enum ServerOrigin {

    /// Normalizes a user-entered server address into a canonical origin string:
    ///
    /// - Trims surrounding whitespace.
    /// - Lowercases scheme and host (path-less origins only).
    /// - Defaults to `https://` when no scheme is present.
    /// - Rejects paths, queries, and fragments; a single trailing slash is stripped.
    /// - Returns `nil` for empty or invalid input.
    ///
    /// Example results:
    ///   "  Hermes.Example.com/ " -> "https://hermes.example.com"
    ///   "http://10.1.2.3:8080"   -> "http://10.1.2.3:8080"
    ///   "https://h/x?q=1"        -> nil
    static func normalize(_ input: String) -> String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // Split off an explicit scheme if present.
        var scheme = "https"
        var remainder = trimmed
        if let range = trimmed.range(of: "://") {
            let candidate = String(trimmed[..<range.lowerBound]).lowercased()
            guard candidate == "http" || candidate == "https" else { return nil }
            scheme = candidate
            remainder = String(trimmed[range.upperBound...])
        } else if trimmed.lowercased().hasPrefix(":") {
            // Scheme without "//" is malformed.
            return nil
        }

        // Everything after the scheme must be host[:port] and nothing more.
        // A single trailing slash is stripped; any path/query/fragment is rejected.
        guard !remainder.isEmpty else { return nil }
        if remainder.hasSuffix("/") {
            remainder.removeLast()
            guard !remainder.isEmpty else { return nil }
        }
        guard !remainder.contains("/"), !remainder.contains("?"), !remainder.contains("#"),
              !remainder.contains("\\"), !remainder.contains(" ") else {
            return nil
        }
        guard isValidHostAndPort(remainder) else { return nil }

        return "\(scheme)://\(remainder.lowercased())"
    }

    /// Converts an already explicit HTTP(S) origin to the matching WebSocket
    /// scheme. Validation still goes through `normalize` so paths, queries,
    /// fragments, and malformed authorities cannot be smuggled into `/api/ws`.
    /// A scheme is required here; callers that accept bare host input should
    /// normalize it first and then call this helper.
    static func webSocketValue(_ origin: String) -> String? {
        let trimmed = origin.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let schemeSeparator = trimmed.range(of: "://") else { return nil }
        let scheme = String(trimmed[..<schemeSeparator.lowerBound]).lowercased()
        guard scheme == "http" || scheme == "https",
              let normalized = normalize(trimmed),
              let normalizedSeparator = normalized.range(of: "://")
        else { return nil }

        let websocketScheme = scheme == "https" ? "wss" : "ws"
        return "\(websocketScheme)://\(normalized[normalizedSeparator.upperBound...])"
    }

    private static func isValidHostAndPort(_ authority: String) -> Bool {
        // Optional port on the last colon segment (no IPv6 literal support yet;
        // Android parity will follow when bracketed literals land upstream).
        var host = authority
        if let lastColon = authority.lastIndex(of: ":") {
            let port = authority[authority.index(after: lastColon)...]
            guard !port.isEmpty, port.allSatisfy(\.isNumber),
                  let value = Int(port), (1...65535).contains(value)
            else { return false }
            host = String(authority[..<lastColon])
        }
        guard !host.isEmpty, !host.contains(":") else { return false }
        return host.allSatisfy { !$0.isWhitespace && $0 != "?" && $0 != "#" }
    }

    // MARK: - Public predicates

    /// True when the host of a normalized origin is loopback or RFC1918-private,
    /// i.e. it never leaves the user's network. Used to decide whether cleartext
    /// HTTP may be tolerated for self-hosted backends.
    static func isLoopbackOrPrivate(_ origin: String) -> Bool {
        guard let host = host(of: origin) else { return false }
        return hostIsLoopbackOrPrivate(host)
    }

    /// Cleartext HTTP is acceptable ONLY for loopback/RFC1918 hosts. Anything
    /// public must ride over TLS.
    static func allowsCleartextHTTP(_ origin: String) -> Bool {
        guard origin.hasPrefix("http://") else { return false }
        return isLoopbackOrPrivate(origin)
    }

    // MARK: - Internals

    private static func host(of origin: String) -> String? {
        guard let schemeEnd = origin.range(of: "://") else { return nil }
        var rest = origin[schemeEnd.upperBound...]
        if let slash = rest.firstIndex(of: "/") {
            rest = rest[..<slash]
        }
        var host = String(rest)
        if let colon = host.firstIndex(of: ":") {
            host = String(host[..<colon])
        }
        return host.isEmpty ? nil : host.lowercased()
    }

    private static func hostIsLoopbackOrPrivate(_ rawHost: String) -> Bool {
        let host = rawHost.lowercased()
            .trimmingCharacters(in: CharacterSet(charactersIn: "[]"))

        if host == "localhost" || host == "::1" || host.hasSuffix(".localhost") || host.hasSuffix(".local") {
            return true
        }

        // IPv4 dotted-quad checks: 127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16.
        let parts = host.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4 else { return false }
        let octets = parts.compactMap(Int.init)
        guard octets.count == 4, octets.allSatisfy({ (0...255).contains($0) }) else { return false }

        let (first, second) = (octets[0], octets[1])
        switch first {
        case 127, 10:
            return true
        case 172:
            return (16...31).contains(second)
        case 192:
            return second == 168
        default:
            return false
        }
    }
}
