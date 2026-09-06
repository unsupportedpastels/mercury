import Foundation
import MercuryCore

/// Facade over the shared KMP core's ServerOriginPolicy
/// (shared/mercury-core, docs/plans/kmp-shared-core.md): canonicalization,
/// the bare-host "Use HTTPS" rule, default-port elision, punycode, and the
/// loopback/private classifier are decided once for both clients.
enum ServerOrigin {

    /// Normalizes a user-entered server address into a canonical origin.
    /// Bare hosts are accepted; `useTls` picks https vs http when the input
    /// carries no scheme (an explicit scheme always wins). Default ports are
    /// elided. Returns `nil` for invalid input.
    static func normalize(_ input: String, useTls: Bool = true) -> String? {
        let result = MercuryCore.ServerOriginPolicy.shared.canonicalize(input: input, useTls: useTls)
        return (result as? MercuryCore.OriginParseResultValid)?.origin
    }

    /// The shared policy's reason when `input` is not a valid origin, or nil
    /// when it is valid. Surfaces the same wording Android shows, including
    /// the public plain-HTTP rejection, instead of a generic message.
    static func validationFailure(_ input: String, useTls: Bool = true) -> String? {
        let result = MercuryCore.ServerOriginPolicy.shared.canonicalize(input: input, useTls: useTls)
        return (result as? MercuryCore.OriginParseResultInvalid)?.reason
    }

    /// Converts an explicit HTTP(S) origin to the matching WebSocket scheme.
    static func webSocketValue(_ origin: String) -> String? {
        MercuryCore.ServerOriginPolicy.shared.webSocketValue(origin: origin)
    }

    /// The host to show for an origin or dashboard URL in lists (shared
    /// display decision: no scheme, port, or path). Nil when there is no host.
    static func displayHost(_ originOrURL: String) -> String? {
        MercuryCore.ServerOriginPolicy.shared.displayHost(originOrUrl: originOrURL)
    }

    /// True when the host of a normalized origin is loopback or RFC1918-private.
    static func isLoopbackOrPrivate(_ origin: String) -> Bool {
        MercuryCore.ServerOriginPolicy.shared.isLoopbackOrPrivate(origin: origin)
    }

    /// Cleartext HTTP is acceptable ONLY for loopback/RFC1918 hosts.
    static func allowsCleartextHTTP(_ origin: String) -> Bool {
        MercuryCore.ServerOriginPolicy.shared.allowsCleartextHttp(origin: origin)
    }

    /// The pre-2026-08-30 normalization (no default-port elision, no
    /// punycode). Exists ONLY so Keychain entries written under the old
    /// canonical form can be found once and migrated; never use it for new
    /// scoping.
    static func legacyNormalize(_ input: String) -> String? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        var scheme = "https"
        var remainder = trimmed
        if let range = trimmed.range(of: "://") {
            let candidate = String(trimmed[..<range.lowerBound]).lowercased()
            guard candidate == "http" || candidate == "https" else { return nil }
            scheme = candidate
            remainder = String(trimmed[range.upperBound...])
        }

        guard !remainder.isEmpty else { return nil }
        if remainder.hasSuffix("/") {
            remainder.removeLast()
            guard !remainder.isEmpty else { return nil }
        }
        guard !remainder.contains("/"), !remainder.contains("?"), !remainder.contains("#"),
              !remainder.contains("\\"), !remainder.contains(" ") else {
            return nil
        }

        var host = remainder
        if let lastColon = remainder.lastIndex(of: ":") {
            let port = remainder[remainder.index(after: lastColon)...]
            guard !port.isEmpty, port.allSatisfy(\.isNumber),
                  let value = Int(port), (1...65535).contains(value)
            else { return nil }
            host = String(remainder[..<lastColon])
        }
        guard !host.isEmpty, !host.contains(":") else { return nil }

        return "\(scheme)://\(remainder.lowercased())"
    }

    /// Candidate Keychain account names used before default ports were
    /// elided. Callers pass the new canonical origin, so reconstruct the one
    /// legacy spelling that could otherwise no longer be derived.
    static func legacyCredentialAccountCandidates(for canonicalOrigin: String) -> [String] {
        let scheme: String
        let defaultPort: Int
        let authority: Substring
        if canonicalOrigin.hasPrefix("https://") {
            scheme = "https"
            defaultPort = 443
            authority = canonicalOrigin.dropFirst("https://".count)
        } else if canonicalOrigin.hasPrefix("http://") {
            scheme = "http"
            defaultPort = 80
            authority = canonicalOrigin.dropFirst("http://".count)
        } else {
            return []
        }
        guard !authority.isEmpty,
              !authority.contains("/"),
              !authority.contains("?"),
              !authority.contains("#")
        else { return [] }

        if authority.hasPrefix("[") {
            guard authority.hasSuffix("]") else { return [] }
        } else if authority.contains(":") {
            return []
        }
        return ["\(scheme)://\(authority):\(defaultPort)"]
    }
}
