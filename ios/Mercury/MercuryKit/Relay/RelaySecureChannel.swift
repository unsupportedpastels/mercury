import CryptoKit
import Foundation

// MARK: - Errors

/// One stable channel failure reason without raw cryptographic details.
/// Mirrors the plugin's `SecureChannelError` reasons so both endpoints
/// collapse failures identically (PROTOCOL §13).
struct RelaySecureChannelError: Error, Equatable {
    let reason: String

    static let authenticationFailed = RelaySecureChannelError(reason: "authentication_failed")
    static let channelClosed = RelaySecureChannelError(reason: "channel_closed")
    static let handshakeNotFinished = RelaySecureChannelError(reason: "handshake_not_finished")
    static let plaintextLimit = RelaySecureChannelError(reason: "plaintext_limit")
    static let ciphertextLimit = RelaySecureChannelError(reason: "ciphertext_limit")
    static let transportFailed = RelaySecureChannelError(reason: "transport_failed")
}

// MARK: - Constants

enum RelaySecureChannelPolicy {
    static let keyBytes = 32
    static let pairingCapabilityBytes = 32
    static let maxCiphertextRecordBytes = 65_535
    static let maxPlaintextRecordBytes = 65_519
    static let protocolName = "Noise_XK_25519_ChaChaPoly_SHA256"
    static let prologuePrefix: [UInt8] = Array("mercury-relay/v1".utf8) + [0]
}

// MARK: - Noise primitives

/// Noise CipherState: ChaCha20-Poly1305 with the Noise nonce layout
/// (4 zero bytes then the 64-bit little-endian counter).
private struct NoiseCipherState {
    var key: SymmetricKey?
    var nonce: UInt64 = 0

    private mutating func nextNonce() throws -> ChaChaPoly.Nonce {
        // Bounded lifetime: refuse the final counter value instead of wrapping.
        guard nonce != UInt64.max else { throw RelaySecureChannelError.transportFailed }
        var bytes = [UInt8](repeating: 0, count: 12)
        var value = nonce.littleEndian
        withUnsafeBytes(of: &value) { raw in
            for i in 0..<8 { bytes[4 + i] = raw[i] }
        }
        nonce += 1
        return try ChaChaPoly.Nonce(data: bytes)
    }

    mutating func encrypt(ad: Data, plaintext: Data) throws -> Data {
        guard let key else { return plaintext }
        let sealed = try ChaChaPoly.seal(plaintext, using: key, nonce: nextNonce(), authenticating: ad)
        return sealed.ciphertext + sealed.tag
    }

    mutating func decrypt(ad: Data, ciphertext: Data) throws -> Data {
        guard let key else { return ciphertext }
        guard ciphertext.count >= 16 else { throw RelaySecureChannelError.authenticationFailed }
        let box = try ChaChaPoly.SealedBox(
            nonce: try nextNonce(),
            ciphertext: ciphertext.dropLast(16),
            tag: ciphertext.suffix(16)
        )
        return try ChaChaPoly.open(box, using: key, authenticating: ad)
    }
}

/// Noise HKDF (spec §4.3) with HMAC-SHA256.
private func noiseHKDF(chainingKey: Data, input: Data, outputs: Int) -> [Data] {
    let tempKey = SymmetricKey(data: HMAC<SHA256>.authenticationCode(
        for: input, using: SymmetricKey(data: chainingKey)
    ))
    var results: [Data] = []
    var previous = Data()
    for index in 1...outputs {
        var material = previous
        material.append(UInt8(index))
        previous = Data(HMAC<SHA256>.authenticationCode(for: material, using: tempKey))
        results.append(previous)
    }
    return results
}

private struct NoiseSymmetricState {
    var cipher = NoiseCipherState()
    var chainingKey: Data
    var handshakeHash: Data

    init(protocolName: String) {
        var name = Data(protocolName.utf8)
        if name.count <= 32 {
            name.append(Data(repeating: 0, count: 32 - name.count))
            handshakeHash = name
        } else {
            handshakeHash = Data(SHA256.hash(data: name))
        }
        chainingKey = handshakeHash
    }

    mutating func mixHash(_ data: Data) {
        var input = handshakeHash
        input.append(data)
        handshakeHash = Data(SHA256.hash(data: input))
    }

    mutating func mixKey(_ input: Data) {
        let derived = noiseHKDF(chainingKey: chainingKey, input: input, outputs: 2)
        chainingKey = derived[0]
        cipher = NoiseCipherState(key: SymmetricKey(data: derived[1]), nonce: 0)
    }

    mutating func encryptAndHash(_ plaintext: Data) throws -> Data {
        let ciphertext = try cipher.encrypt(ad: handshakeHash, plaintext: plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    mutating func decryptAndHash(_ ciphertext: Data) throws -> Data {
        let plaintext = try cipher.decrypt(ad: handshakeHash, ciphertext: ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    func split() -> (NoiseCipherState, NoiseCipherState) {
        let derived = noiseHKDF(chainingKey: chainingKey, input: Data(), outputs: 2)
        return (
            NoiseCipherState(key: SymmetricKey(data: derived[0]), nonce: 0),
            NoiseCipherState(key: SymmetricKey(data: derived[1]), nonce: 0)
        )
    }
}

/// Noise_XK handshake state for either role. The pre-message is the
/// responder's static key, which the initiator learns from the QR/pinned
/// installation record and the responder owns locally.
private struct NoiseXKHandshake {
    let isInitiator: Bool
    var symmetric: NoiseSymmetricState
    let localStatic: Curve25519.KeyAgreement.PrivateKey
    var localEphemeral: Curve25519.KeyAgreement.PrivateKey?
    var remoteStatic: Curve25519.KeyAgreement.PublicKey?
    var remoteEphemeral: Curve25519.KeyAgreement.PublicKey?
    /// Injected only by vector tests; production always generates fresh keys.
    let deterministicEphemeral: Curve25519.KeyAgreement.PrivateKey?

    init(
        isInitiator: Bool,
        localStatic: Curve25519.KeyAgreement.PrivateKey,
        remoteStatic: Curve25519.KeyAgreement.PublicKey?,
        prologue: Data,
        deterministicEphemeral: Curve25519.KeyAgreement.PrivateKey?
    ) {
        self.isInitiator = isInitiator
        self.localStatic = localStatic
        self.remoteStatic = remoteStatic
        self.deterministicEphemeral = deterministicEphemeral
        symmetric = NoiseSymmetricState(protocolName: RelaySecureChannelPolicy.protocolName)
        symmetric.mixHash(prologue)
        // XK pre-message: "<- s".
        if isInitiator {
            symmetric.mixHash(Data(remoteStatic!.rawRepresentation))
        } else {
            symmetric.mixHash(Data(localStatic.publicKey.rawRepresentation))
        }
    }

    private mutating func generateEphemeral() -> Curve25519.KeyAgreement.PrivateKey {
        let key = deterministicEphemeral ?? Curve25519.KeyAgreement.PrivateKey()
        localEphemeral = key
        return key
    }

    private func agree(
        _ privateKey: Curve25519.KeyAgreement.PrivateKey,
        _ publicKey: Curve25519.KeyAgreement.PublicKey
    ) throws -> Data {
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
        return shared.withUnsafeBytes { Data($0) }
    }

    // -> e, es
    mutating func writeMessage1(payload: Data) throws -> Data {
        let ephemeral = generateEphemeral()
        let ephemeralPublic = Data(ephemeral.publicKey.rawRepresentation)
        symmetric.mixHash(ephemeralPublic)
        try symmetric.mixKey(agree(ephemeral, remoteStatic!))
        return ephemeralPublic + (try symmetric.encryptAndHash(payload))
    }

    mutating func readMessage1(_ message: Data) throws -> Data {
        guard message.count >= 32 else { throw RelaySecureChannelError.authenticationFailed }
        let ephemeralPublic = message.prefix(32)
        remoteEphemeral = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: ephemeralPublic)
        symmetric.mixHash(Data(ephemeralPublic))
        try symmetric.mixKey(agree(localStatic, remoteEphemeral!))
        return try symmetric.decryptAndHash(Data(message.dropFirst(32)))
    }

    // <- e, ee
    mutating func writeMessage2(payload: Data) throws -> Data {
        let ephemeral = generateEphemeral()
        let ephemeralPublic = Data(ephemeral.publicKey.rawRepresentation)
        symmetric.mixHash(ephemeralPublic)
        try symmetric.mixKey(agree(ephemeral, remoteEphemeral!))
        return ephemeralPublic + (try symmetric.encryptAndHash(payload))
    }

    mutating func readMessage2(_ message: Data) throws -> Data {
        guard message.count >= 32 else { throw RelaySecureChannelError.authenticationFailed }
        let ephemeralPublic = message.prefix(32)
        remoteEphemeral = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: ephemeralPublic)
        symmetric.mixHash(Data(ephemeralPublic))
        try symmetric.mixKey(agree(localEphemeral!, remoteEphemeral!))
        return try symmetric.decryptAndHash(Data(message.dropFirst(32)))
    }

    // -> s, se
    mutating func writeMessage3(payload: Data) throws -> Data {
        let staticCiphertext = try symmetric.encryptAndHash(
            Data(localStatic.publicKey.rawRepresentation)
        )
        try symmetric.mixKey(agree(localStatic, remoteEphemeral!))
        return staticCiphertext + (try symmetric.encryptAndHash(payload))
    }

    mutating func readMessage3(_ message: Data) throws -> Data {
        guard message.count >= 48 else { throw RelaySecureChannelError.authenticationFailed }
        let staticPlain = try symmetric.decryptAndHash(Data(message.prefix(48)))
        remoteStatic = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: staticPlain)
        try symmetric.mixKey(agree(localEphemeral!, remoteStatic!))
        return try symmetric.decryptAndHash(Data(message.dropFirst(48)))
    }
}

// MARK: - Channel

/// One fresh mutually authenticated Mercury Noise connection.
///
/// Behavioral mirror of the plugin's `NoiseChannel`: three-message XK with
/// bounded wire sizes, handshake payload rules (only the final initiator
/// message may carry the 32-byte pairing capability), a transcript-derived
/// channel binding, and stable non-oracular errors that close the channel.
final class RelaySecureChannel {
    private enum NextAction: Equatable {
        case write1, read1, write2, read2, write3, read3, complete
    }

    let isInitiator: Bool
    private(set) var closed = false
    private var handshake: NoiseXKHandshake?
    private var nextAction: NextAction
    private var sendCipher = NoiseCipherState()
    private var receiveCipher = NoiseCipherState()
    private var binding: Data?
    private var remoteStaticBytes: Data?

    /// Device side: initiator toward the host static key from the QR/pinned
    /// installation record.
    convenience init(
        initiatorStaticPrivateKey: Data,
        installationID: Data,
        hostStaticPublicKey: Data
    ) throws {
        try self.init(
            isInitiator: true,
            staticPrivateKey: initiatorStaticPrivateKey,
            installationID: installationID,
            remoteStaticPublicKey: hostStaticPublicKey,
            deterministicEphemeralPrivateKey: nil
        )
    }

    /// Full initializer; the deterministic ephemeral is for vector tests only.
    init(
        isInitiator: Bool,
        staticPrivateKey: Data,
        installationID: Data,
        remoteStaticPublicKey: Data?,
        deterministicEphemeralPrivateKey: Data?
    ) throws {
        guard staticPrivateKey.count == RelaySecureChannelPolicy.keyBytes,
              installationID.count == RelaySecureChannelPolicy.keyBytes
        else { throw RelaySecureChannelError.authenticationFailed }
        if isInitiator {
            guard let remote = remoteStaticPublicKey,
                  remote.count == RelaySecureChannelPolicy.keyBytes
            else { throw RelaySecureChannelError.authenticationFailed }
            _ = remote
        } else if remoteStaticPublicKey != nil {
            // The responder recovers the mobile identity from message 3.
            throw RelaySecureChannelError.authenticationFailed
        }

        var prologue = Data(RelaySecureChannelPolicy.prologuePrefix)
        prologue.append(installationID)
        do {
            let localStatic = try Curve25519.KeyAgreement.PrivateKey(
                rawRepresentation: staticPrivateKey
            )
            let remoteStatic = try remoteStaticPublicKey.map {
                try Curve25519.KeyAgreement.PublicKey(rawRepresentation: $0)
            }
            let ephemeral = try deterministicEphemeralPrivateKey.map {
                try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: $0)
            }
            handshake = NoiseXKHandshake(
                isInitiator: isInitiator,
                localStatic: localStatic,
                remoteStatic: remoteStatic,
                prologue: prologue,
                deterministicEphemeral: ephemeral
            )
        } catch {
            throw RelaySecureChannelError.authenticationFailed
        }
        self.isInitiator = isInitiator
        nextAction = isInitiator ? .write1 : .read1
    }

    var handshakeFinished: Bool { nextAction == .complete && !closed }

    /// 32-byte Noise handshake hash; the transcript-bound input to the
    /// pairing fingerprint and the source of the 16-byte framing channel ID.
    var channelBinding: Data {
        get throws {
            guard handshakeFinished, let binding else {
                throw RelaySecureChannelError.handshakeNotFinished
            }
            return binding
        }
    }

    var remoteStaticPublic: Data {
        get throws {
            guard handshakeFinished, let remoteStaticBytes else {
                throw RelaySecureChannelError.handshakeNotFinished
            }
            return remoteStaticBytes
        }
    }

    func close() {
        closed = true
        handshake = nil
        sendCipher = NoiseCipherState()
        receiveCipher = NoiseCipherState()
    }

    private func fail(_ error: RelaySecureChannelError) -> RelaySecureChannelError {
        close()
        return error
    }

    private func requireOpen() throws {
        if closed { throw RelaySecureChannelError.channelClosed }
    }

    private func finishHandshake() throws {
        guard let state = handshake else { throw fail(.authenticationFailed) }
        let hash = state.symmetric.handshakeHash
        guard hash.count == RelaySecureChannelPolicy.keyBytes,
              let peer = state.remoteStatic
        else { throw fail(.authenticationFailed) }
        let (initiatorToResponder, responderToInitiator) = state.symmetric.split()
        if isInitiator {
            sendCipher = initiatorToResponder
            receiveCipher = responderToInitiator
        } else {
            sendCipher = responderToInitiator
            receiveCipher = initiatorToResponder
        }
        binding = hash
        remoteStaticBytes = Data(peer.rawRepresentation)
        handshake = nil
        nextAction = .complete
    }

    func writeHandshake(payload: Data = Data()) throws -> Data {
        try requireOpen()
        guard payload.count <= RelaySecureChannelPolicy.pairingCapabilityBytes else {
            throw fail(.authenticationFailed)
        }
        guard var state = handshake else { throw fail(.authenticationFailed) }
        do {
            let wire: Data
            let following: NextAction
            var finishes = false
            switch nextAction {
            case .write1 where isInitiator:
                guard payload.isEmpty else { throw RelaySecureChannelError.authenticationFailed }
                wire = try state.writeMessage1(payload: payload)
                following = .read2
            case .write2 where !isInitiator:
                guard payload.isEmpty else { throw RelaySecureChannelError.authenticationFailed }
                wire = try state.writeMessage2(payload: payload)
                following = .read3
            case .write3 where isInitiator:
                guard payload.isEmpty
                    || payload.count == RelaySecureChannelPolicy.pairingCapabilityBytes
                else { throw RelaySecureChannelError.authenticationFailed }
                wire = try state.writeMessage3(payload: payload)
                following = .complete
                finishes = true
            default:
                throw RelaySecureChannelError.authenticationFailed
            }
            guard wire.count >= 32,
                  wire.count <= RelaySecureChannelPolicy.maxCiphertextRecordBytes
            else { throw RelaySecureChannelError.authenticationFailed }
            handshake = state
            nextAction = following
            if finishes { try finishHandshake() }
            return wire
        } catch {
            throw fail(.authenticationFailed)
        }
    }

    func readHandshake(_ message: Data) throws -> Data {
        try requireOpen()
        guard message.count >= 32,
              message.count <= RelaySecureChannelPolicy.maxCiphertextRecordBytes
        else { throw fail(.authenticationFailed) }
        guard var state = handshake else { throw fail(.authenticationFailed) }
        do {
            let payload: Data
            let following: NextAction
            var finishes = false
            switch nextAction {
            case .read1 where !isInitiator:
                payload = try state.readMessage1(message)
                following = .write2
            case .read2 where isInitiator:
                payload = try state.readMessage2(message)
                following = .write3
            case .read3 where !isInitiator:
                payload = try state.readMessage3(message)
                following = .complete
                finishes = true
            default:
                throw RelaySecureChannelError.authenticationFailed
            }
            if finishes {
                guard payload.isEmpty
                    || payload.count == RelaySecureChannelPolicy.pairingCapabilityBytes
                else { throw RelaySecureChannelError.authenticationFailed }
            } else {
                guard payload.isEmpty else { throw RelaySecureChannelError.authenticationFailed }
            }
            handshake = state
            nextAction = following
            if finishes { try finishHandshake() }
            return payload
        } catch {
            throw fail(.authenticationFailed)
        }
    }

    func encrypt(_ plaintext: Data) throws -> Data {
        try requireOpen()
        guard handshakeFinished else { throw RelaySecureChannelError.handshakeNotFinished }
        guard plaintext.count <= RelaySecureChannelPolicy.maxPlaintextRecordBytes else {
            throw RelaySecureChannelError.plaintextLimit
        }
        do {
            let ciphertext = try sendCipher.encrypt(ad: Data(), plaintext: plaintext)
            guard ciphertext.count <= RelaySecureChannelPolicy.maxCiphertextRecordBytes else {
                throw RelaySecureChannelError.transportFailed
            }
            return ciphertext
        } catch {
            throw fail(.transportFailed)
        }
    }

    func decrypt(_ ciphertext: Data) throws -> Data {
        try requireOpen()
        guard handshakeFinished else { throw RelaySecureChannelError.handshakeNotFinished }
        guard ciphertext.count <= RelaySecureChannelPolicy.maxCiphertextRecordBytes else {
            throw RelaySecureChannelError.ciphertextLimit
        }
        guard ciphertext.count >= 16 else { throw RelaySecureChannelError.ciphertextLimit }
        do {
            let plaintext = try receiveCipher.decrypt(ad: Data(), ciphertext: ciphertext)
            guard plaintext.count <= RelaySecureChannelPolicy.maxPlaintextRecordBytes else {
                throw RelaySecureChannelError.transportFailed
            }
            return plaintext
        } catch {
            throw fail(.transportFailed)
        }
    }

    /// Derives the device static public key without exposing key handling to
    /// callers.
    static func publicKey(forPrivateKey privateKey: Data) throws -> Data {
        guard privateKey.count == RelaySecureChannelPolicy.keyBytes,
              let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        else { throw RelaySecureChannelError.authenticationFailed }
        return Data(key.publicKey.rawRepresentation)
    }
}
