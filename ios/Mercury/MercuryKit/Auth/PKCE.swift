import CryptoKit
import Foundation
import Security

/// PKCE (RFC 7636) helper: S256 code challenge + random state generation.
struct PKCE {
    let verifier: String
    let challenge: String

    /// Generates a fresh verifier (32 random bytes, base64url no padding → 43 chars)
    /// and its S256 challenge.
    static func generate() -> PKCE {
        let verifier = Self.encodeBase64URLNoPadding(randomBytes(32))
        let digest = SHA256.hash(data: Data(verifier.utf8))
        let challenge = Self.encodeBase64URLNoPadding(Data(digest))
        return PKCE(verifier: verifier, challenge: challenge)
    }

    /// 16 random bytes hex-encoded (32 hex chars).
    static func randomState() -> String {
        randomBytes(16).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Internals

    private static func randomBytes(_ count: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        if status != errSecSuccess {
            // SecRandomCopyBytes practically never fails; fall back so the
            // non-failing API contract holds.
            for i in 0..<count { bytes[i] = UInt8.random(in: 0...255) }
        }
        return bytes
    }

    static func encodeBase64URLNoPadding<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        Data(bytes)
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
