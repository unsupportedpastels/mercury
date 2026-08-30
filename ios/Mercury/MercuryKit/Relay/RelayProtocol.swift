import CryptoKit
import Foundation

// MARK: - Stable protocol constants (PROTOCOL §4, §7)

enum RelayProtocolPolicy {
    static let scheme = "mercury-relay"
    static let protocolMajor = 1
    static let installationIDBytes = 32
    static let hostPublicKeyBytes = 32
    static let capabilityBytes = 32
    static let deviceIDBytes = 16
    static let maxQRPayloadBytes = 1024
    static let maxOriginCharacters = 256
    static let maxEnvelopeBytes = 512
    static let maxProfileCharacters = 128
    /// Offers live at most 600 seconds (authorization MAX_TTL_SECONDS); an
    /// expiry further out than that plus generous skew is malformed, not just
    /// stale.
    static let maxExpirySkewSeconds: Int64 = 900
    /// SAS fingerprint: first 16 hex characters of SHA-256 over the Noise
    /// channel binding, matching the host management surface.
    static let fingerprintHexCharacters = 16
}

/// Distinct, user-explainable pairing/connection failure states (plan Task 8:
/// expiry, consumed offer, cancellation, offline, revoked, and
/// incompatible-version states are distinct).
enum RelayProtocolError: Error, Equatable {
    case malformedPayload
    case unsupportedScheme
    case unsupportedVersion
    case missingRelayOrigin
    case expiredOffer
    case invalidEnvelope
}

// MARK: - Binary text encodings

enum RelayBase64 {
    /// Standard base64 decode requiring an exact decoded size (QR fields).
    static func decodeExact(_ text: String, count: Int) -> Data? {
        guard text.count <= 4 * ((count + 2) / 3) + 4,
              let data = Data(base64Encoded: text),
              data.count == count
        else { return nil }
        return data
    }

    /// Unpadded URL-safe base64 (routes and device IDs).
    static func urlSafeEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func urlSafeDecodeExact(_ text: String, count: Int) -> Data? {
        guard text.allSatisfy({ $0.isASCII }),
              !text.contains("="),
              text.count == (4 * count + 2) / 3
        else { return nil }
        var padded = text
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while padded.count % 4 != 0 { padded.append("=") }
        guard let data = Data(base64Encoded: padded), data.count == count else { return nil }
        return data
    }
}

// MARK: - QR pairing payload (PROTOCOL §4)

/// The validated logical content of one scanned pairing QR. The capability is
/// the only secret; instances must never be persisted, logged, placed on the
/// clipboard, or embedded in errors.
struct RelayPairingPayload {
    let relayOrigin: String
    let installationID: Data
    let hostPublicKey: Data
    let capability: Data
    let expiresAtEpochSeconds: Int64

    /// Validates scheme, version, origin allow-list shape, field lengths, and
    /// expiry before any network use. `now` is injectable for tests.
    static func parse(
        _ text: String,
        now: Date = Date()
    ) throws -> RelayPairingPayload {
        guard text.utf8.count <= RelayProtocolPolicy.maxQRPayloadBytes,
              let data = text.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let fields = object as? [String: Any]
        else { throw RelayProtocolError.malformedPayload }

        // Scheme and version are rejected before anything else so an
        // incompatible QR fails with the distinct states the UI explains.
        guard let scheme = fields["s"] as? String else {
            throw RelayProtocolError.malformedPayload
        }
        guard scheme == RelayProtocolPolicy.scheme else {
            throw RelayProtocolError.unsupportedScheme
        }
        guard let version = fields["v"] as? Int else {
            throw RelayProtocolError.malformedPayload
        }
        guard version == RelayProtocolPolicy.protocolMajor else {
            throw RelayProtocolError.unsupportedVersion
        }

        // A host without a configured relay_origin renders `o: null`; the
        // device cannot dial anywhere, which is its own explainable state.
        let origin = fields["o"] as? String
        guard let origin, !origin.isEmpty else {
            throw RelayProtocolError.missingRelayOrigin
        }
        guard Self.normalizeOrigin(origin) != nil else {
            throw RelayProtocolError.malformedPayload
        }

        guard let installationText = fields["i"] as? String,
              let installationID = RelayBase64.decodeExact(
                  installationText, count: RelayProtocolPolicy.installationIDBytes
              ),
              let hostKeyText = fields["k"] as? String,
              let hostPublicKey = RelayBase64.decodeExact(
                  hostKeyText, count: RelayProtocolPolicy.hostPublicKeyBytes
              ),
              let capabilityText = fields["c"] as? String,
              let capability = RelayBase64.decodeExact(
                  capabilityText, count: RelayProtocolPolicy.capabilityBytes
              ),
              let expiresAny = fields["x"],
              let expires = (expiresAny as? NSNumber).map({ Int64(truncating: $0) }),
              expires > 0
        else { throw RelayProtocolError.malformedPayload }

        let nowEpoch = Int64(now.timeIntervalSince1970)
        guard expires <= nowEpoch + RelayProtocolPolicy.maxExpirySkewSeconds else {
            throw RelayProtocolError.malformedPayload
        }
        guard expires > nowEpoch else {
            throw RelayProtocolError.expiredOffer
        }

        return RelayPairingPayload(
            relayOrigin: origin,
            installationID: installationID,
            hostPublicKey: hostPublicKey,
            capability: capability,
            expiresAtEpochSeconds: expires
        )
    }

    /// Accepts an https/wss origin with no path, query, fragment, userinfo,
    /// or trailing slash and returns its canonical wss form.
    static func normalizeOrigin(_ origin: String) -> String? {
        guard origin.count <= RelayProtocolPolicy.maxOriginCharacters,
              !origin.hasSuffix("/"),
              origin.hasPrefix("https://") || origin.hasPrefix("wss://"),
              let components = URLComponents(string: origin),
              let scheme = components.scheme?.lowercased(),
              scheme == "https" || scheme == "wss",
              let host = components.host, !host.isEmpty,
              components.path.isEmpty,
              components.query == nil,
              components.fragment == nil,
              components.user == nil,
              components.password == nil
        else { return nil }
        var canonical = URLComponents()
        canonical.scheme = "wss"
        canonical.host = host
        canonical.port = components.port
        return canonical.string
    }

    /// The device-role hosted route: `wss://<relay>/v1/device/<b64url(i)>`.
    var deviceSocketURL: String? {
        Self.deviceSocketURL(relayOrigin: relayOrigin, installationID: installationID)
    }

    static func deviceSocketURL(relayOrigin: String, installationID: Data) -> String? {
        guard installationID.count == RelayProtocolPolicy.installationIDBytes,
              let origin = normalizeOrigin(relayOrigin)
        else { return nil }
        return "\(origin)/v1/device/\(RelayBase64.urlSafeEncode(installationID))"
    }
}

// MARK: - Pairing acknowledgement (PROTOCOL §4 step 7)

/// The one encrypted record the host sends on a pairing connection after it
/// consumes the capability: the only delivery of the host-assigned device_id.
enum RelayPairingAck {
    static func parse(_ plaintext: Data) throws -> String {
        guard plaintext.count <= RelayProtocolPolicy.maxEnvelopeBytes,
              let object = try? JSONSerialization.jsonObject(with: plaintext),
              let fields = object as? [String: Any],
              fields.count == 2,
              fields["type"] as? String == "pairing.pending",
              let deviceID = fields["device_id"] as? String,
              RelayBase64.urlSafeDecodeExact(
                  deviceID, count: RelayProtocolPolicy.deviceIDBytes
              ) != nil
        else { throw RelayProtocolError.invalidEnvelope }
        return deviceID
    }
}

// MARK: - Admission envelope (PROTOCOL §7)

/// Builds the bounded ASCII `controller.open` admission envelope. The host
/// parses strict JSON, so the payload is assembled byte-exactly with sorted
/// keys and only validated identifier characters (never JSONEncoder output
/// whose escaping/ordering could drift).
enum RelayAdmissionEnvelope {
    static func controllerOpen(
        deviceID: String,
        profile: String,
        resumeCursor: Int64? = nil
    ) throws -> Data {
        guard RelayBase64.urlSafeDecodeExact(
            deviceID, count: RelayProtocolPolicy.deviceIDBytes
        ) != nil else { throw RelayProtocolError.invalidEnvelope }
        guard !profile.isEmpty,
              profile.count <= RelayProtocolPolicy.maxProfileCharacters,
              profile.allSatisfy({ character in
                  character.isASCII
                      && (character.isLetter || character.isNumber
                          || character == "-" || character == "_" || character == ".")
              })
        else { throw RelayProtocolError.invalidEnvelope }
        if let resumeCursor { guard resumeCursor >= 0 else { throw RelayProtocolError.invalidEnvelope } }

        var envelope = "{\"device_id\":\"\(deviceID)\",\"profile\":\"\(profile)\""
        if let resumeCursor {
            envelope += ",\"resume_cursor\":\(resumeCursor)"
        }
        envelope += ",\"type\":\"controller.open\"}"
        let data = Data(envelope.utf8)
        guard data.count <= RelayProtocolPolicy.maxEnvelopeBytes else {
            throw RelayProtocolError.invalidEnvelope
        }
        return data
    }
}

// MARK: - Fingerprint

enum RelayFingerprint {
    /// `sha256(channel_binding).hex()[:16]`, identical to the host management
    /// surface so the operator can compare the two displays.
    static func shortAuthenticationString(channelBinding: Data) -> String {
        let digest = SHA256.hash(data: channelBinding)
        return digest
            .map { String(format: "%02x", $0) }
            .joined()
            .prefix(RelayProtocolPolicy.fingerprintHexCharacters)
            .lowercased()
    }
}
