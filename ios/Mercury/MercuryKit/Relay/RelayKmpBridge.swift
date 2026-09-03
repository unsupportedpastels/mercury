import Foundation
import MercuryCore

// MARK: - Byte bridge

extension Data {
    var relayKotlinBytes: KotlinByteArray {
        let result = KotlinByteArray(size: Int32(count))
        for (index, byte) in enumerated() {
            result.set(index: Int32(index), value: Int8(bitPattern: byte))
        }
        return result
    }
}

extension KotlinByteArray {
    var relayData: Data {
        Data((0..<Int(size)).map { index in
            UInt8(bitPattern: get(index: Int32(index)))
        })
    }
}

// MARK: - Shared protocol facade

enum RelayProtocolPolicy {
    static let scheme = MercuryCore.RelayProtocolPolicy.shared.scheme
    static let protocolMajor = Int(MercuryCore.RelayProtocolPolicy.shared.protocolMajor)
    static let installationIDBytes = Int(MercuryCore.RelayProtocolPolicy.shared.installationIdBytes)
    static let hostPublicKeyBytes = Int(MercuryCore.RelayProtocolPolicy.shared.hostPublicKeyBytes)
    static let capabilityBytes = Int(MercuryCore.RelayProtocolPolicy.shared.capabilityBytes)
    static let deviceIDBytes = Int(MercuryCore.RelayProtocolPolicy.shared.deviceIdBytes)
    static let maxQRPayloadBytes = Int(MercuryCore.RelayProtocolPolicy.shared.maxQrPayloadBytes)
    static let maxOriginCharacters = Int(MercuryCore.RelayProtocolPolicy.shared.maxOriginCharacters)
    static let maxEnvelopeBytes = Int(MercuryCore.RelayProtocolPolicy.shared.maxEnvelopeBytes)
    static let maxProfileCharacters = Int(MercuryCore.RelayProtocolPolicy.shared.maxProfileCharacters)
    static let maxExpirySkewSeconds = MercuryCore.RelayProtocolPolicy.shared.maxExpirySkewSeconds
    static let fingerprintHexCharacters = Int(MercuryCore.RelayProtocolPolicy.shared.fingerprintHexCharacters)
}

enum RelayProtocolError: Error, Equatable {
    case malformedPayload
    case unsupportedScheme
    case unsupportedVersion
    case missingRelayOrigin
    case expiredOffer
    case invalidEnvelope

    fileprivate init(_ failure: MercuryCore.RelayProtocolFailure) {
        switch failure {
        case .unsupportedscheme: self = .unsupportedScheme
        case .unsupportedversion: self = .unsupportedVersion
        case .missingrelayorigin: self = .missingRelayOrigin
        case .expiredoffer: self = .expiredOffer
        case .invalidenvelope: self = .invalidEnvelope
        default: self = .malformedPayload
        }
    }
}

enum RelayBase64 {
    static func decodeExact(_ text: String, count: Int) -> Data? {
        MercuryCore.RelayBase64.shared.decodeExact(text: text, count: Int32(count))?.relayData
    }

    static func urlSafeEncode(_ data: Data) -> String {
        MercuryCore.RelayBase64.shared.urlSafeEncode(data: data.relayKotlinBytes)
    }

    static func urlSafeDecodeExact(_ text: String, count: Int) -> Data? {
        MercuryCore.RelayBase64.shared.urlSafeDecodeExact(text: text, count: Int32(count))?.relayData
    }
}

struct RelayPairingPayload {
    let relayOrigin: String
    let installationID: Data
    let hostPublicKey: Data
    let capability: Data
    let expiresAtEpochSeconds: Int64
    let pairingRoutingToken: String?

    static func parse(_ text: String, now: Date = Date()) throws -> RelayPairingPayload {
        let epoch = Int64(now.timeIntervalSince1970)
        if let failure = MercuryCore.RelayPairingPayload.companion.parseFailure(
            text: text,
            nowEpochSeconds: epoch
        ) {
            throw RelayProtocolError(failure)
        }
        let core = try MercuryCore.RelayPairingPayload.companion.parse(
            text: text,
            nowEpochSeconds: epoch
        )
        return RelayPairingPayload(
            relayOrigin: core.relayOrigin,
            installationID: core.installationId.relayData,
            hostPublicKey: core.hostPublicKey.relayData,
            capability: core.capability.relayData,
            expiresAtEpochSeconds: core.expiresAtEpochSeconds,
            pairingRoutingToken: core.pairingRoutingToken
        )
    }

    static func normalizeOrigin(_ origin: String) -> String? {
        MercuryCore.RelayPairingPayload.companion.normalizeOrigin(origin: origin)
    }

    var deviceSocketURL: String? {
        Self.deviceSocketURL(relayOrigin: relayOrigin, installationID: installationID)
    }

    static func deviceSocketURL(relayOrigin: String, installationID: Data) -> String? {
        MercuryCore.RelayPairingPayload.companion.deviceSocketUrl(
            relayOrigin: relayOrigin,
            installationId: installationID.relayKotlinBytes
        )
    }
}

struct RelayPairingAcknowledgement {
    let deviceID: String
    let relayRoutingToken: String?
}

enum RelayPairingAck {
    static func parse(_ plaintext: Data) throws -> String {
        try parseEnvelope(plaintext).deviceID
    }

    static func parseEnvelope(_ plaintext: Data) throws -> RelayPairingAcknowledgement {
        do {
            let core = try MercuryCore.RelayPairingAck.shared.parseEnvelope(
                plaintext: plaintext.relayKotlinBytes
            )
            return RelayPairingAcknowledgement(
                deviceID: core.deviceId,
                relayRoutingToken: core.relayRoutingToken
            )
        } catch {
            throw RelayProtocolError.invalidEnvelope
        }
    }
}

enum RelayApprovalProbeResult {
    case approved
    case rejected
    case ignore
}

enum RelayApprovalProbe {
    static func evaluateGatewayPing(_ text: String) -> RelayApprovalProbeResult {
        switch MercuryCore.RelayApprovalProbe.shared.evaluateGatewayPing(text: text) {
        case .approved: return .approved
        case .ignore: return .ignore
        default: return .rejected
        }
    }

    static func isSuccessfulGatewayPing(_ text: String) -> Bool {
        MercuryCore.RelayApprovalProbe.shared.isSuccessfulGatewayPing(text: text)
    }
}

enum RelayAdmissionEnvelope {
    static func controllerOpen(
        deviceID: String,
        profile: String,
        resumeCursor: Int64? = nil
    ) throws -> Data {
        do {
            return try MercuryCore.RelayAdmissionEnvelope.shared.controllerOpen(
                deviceId: deviceID,
                profile: profile,
                resumeCursor: resumeCursor.map(KotlinLong.init(value:))
            ).relayData
        } catch {
            throw RelayProtocolError.invalidEnvelope
        }
    }
}

enum RelayFingerprint {
    static func shortAuthenticationString(channelBinding: Data) -> String {
        MercuryCore.RelayFingerprint.shared.shortAuthenticationString(
            crypto: MercuryCore.RelayPlatformCrypto.shared,
            channelBinding: channelBinding.relayKotlinBytes
        )
    }
}

// MARK: - Shared framing facade

enum RelayFraming {
    static let magic: [UInt8] = [0x4D, 0x52]
    static let protocolVersion = Int(MercuryCore.RelayFraming.shared.protocolVersion)
    static let kindHermesBytes = Int(MercuryCore.RelayFraming.shared.kindHermesBytes)
    static let channelIDSize = Int(MercuryCore.RelayFraming.shared.channelIdSize)
    static let messageIDSize = Int(MercuryCore.RelayFraming.shared.messageIdSize)
    static let headerSize = Int(MercuryCore.RelayFraming.shared.headerSize)
    static let maxNoisePlaintextBytes = Int(MercuryCore.RelayFraming.shared.maxNoisePlaintextBytes)
    static let maxPayloadBytes = Int(MercuryCore.RelayFraming.shared.maxPayloadBytes)
    static let maxLogicalMessageBytes = Int(MercuryCore.RelayFraming.shared.maxLogicalMessageBytes)
    static let maxFragmentCount = Int(MercuryCore.RelayFraming.shared.maxFragmentCount)

    struct DecodeError: Error, Equatable {
        let reason: String
    }

    struct Frame: Equatable {
        let channelID: Data
        let messageID: Data
        let fragmentIndex: Int
        let fragmentCount: Int
        let logicalLength: Int
        let payload: Data
    }

    static func canonicalFragmentCount(logicalLength: Int) -> Int {
        Int(MercuryCore.RelayFraming.shared.canonicalFragmentCount(logicalLength: Int32(logicalLength)))
    }

    static func encodeMessage(channelID: Data, messageID: Data, payload: Data) throws -> [Data] {
        do {
            return try MercuryCore.RelayFraming.shared.encodeMessage(
                channelId: channelID.relayKotlinBytes,
                messageId: messageID.relayKotlinBytes,
                payload: payload.relayKotlinBytes
            ).map(\.relayData)
        } catch {
            throw DecodeError(reason: "frame encoding failed")
        }
    }

    static func decodeRecord(_ record: Data) throws -> Frame {
        if let reason = MercuryCore.RelayFraming.shared.decodeFailureReason(
            record: record.relayKotlinBytes
        ) {
            throw DecodeError(reason: reason)
        }
        do {
            let frame = try MercuryCore.RelayFraming.shared.decodeRecord(
                record: record.relayKotlinBytes
            )
            return Frame(
                channelID: frame.channelId.relayData,
                messageID: frame.messageId.relayData,
                fragmentIndex: Int(frame.fragmentIndex),
                fragmentCount: Int(frame.fragmentCount),
                logicalLength: Int(frame.logicalLength),
                payload: frame.payload.relayData
            )
        } catch {
            throw DecodeError(reason: "frame decoding failed")
        }
    }
}

final class RelayFrameReassembler {
    private let core: MercuryCore.RelayFrameReassembler

    init(channelID: Data? = nil) {
        core = MercuryCore.RelayFrameReassembler(
            expectedChannelId: channelID?.relayKotlinBytes
        )
    }

    var inProgress: Bool { core.inProgress }

    func reset() { core.reset() }

    func push(_ record: Data) throws -> Data? {
        do {
            return try core.push(record: record.relayKotlinBytes)?.relayData
        } catch {
            throw RelayFraming.DecodeError(reason: "frame reassembly failed")
        }
    }
}

// MARK: - Shared Noise facade

struct RelaySecureChannelError: Error, Equatable {
    let reason: String

    static let authenticationFailed = RelaySecureChannelError(reason: "authentication_failed")
    static let channelClosed = RelaySecureChannelError(reason: "channel_closed")
    static let handshakeNotFinished = RelaySecureChannelError(reason: "handshake_not_finished")
    static let plaintextLimit = RelaySecureChannelError(reason: "plaintext_limit")
    static let ciphertextLimit = RelaySecureChannelError(reason: "ciphertext_limit")
    static let transportFailed = RelaySecureChannelError(reason: "transport_failed")
}

enum RelaySecureChannelPolicy {
    static let keyBytes = Int(MercuryCore.RelaySecureChannelPolicy.shared.keyBytes)
    static let pairingCapabilityBytes = Int(MercuryCore.RelaySecureChannelPolicy.shared.pairingCapabilityBytes)
    static let maxCiphertextRecordBytes = Int(MercuryCore.RelaySecureChannelPolicy.shared.maxCiphertextRecordBytes)
    static let maxPlaintextRecordBytes = Int(MercuryCore.RelaySecureChannelPolicy.shared.maxPlaintextRecordBytes)
    static let protocolName = MercuryCore.RelaySecureChannelPolicy.shared.protocolName
    static let prologuePrefix: [UInt8] = Array("mercury-relay/v1".utf8) + [0]
}

final class RelaySecureChannel {
    private let core: MercuryCore.RelaySecureChannel

    var isInitiator: Bool { core.isInitiator }
    var closed: Bool { core.closed }
    var handshakeFinished: Bool { core.handshakeFinished }

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

    init(
        isInitiator: Bool,
        staticPrivateKey: Data,
        installationID: Data,
        remoteStaticPublicKey: Data?,
        deterministicEphemeralPrivateKey: Data?
    ) throws {
        do {
            core = try MercuryCore.RelaySecureChannel(
                crypto: MercuryCore.RelayPlatformCrypto.shared,
                isInitiator: isInitiator,
                staticPrivateKey: staticPrivateKey.relayKotlinBytes,
                installationId: installationID.relayKotlinBytes,
                remoteStaticPublicKey: remoteStaticPublicKey?.relayKotlinBytes,
                deterministicEphemeralPrivateKey: deterministicEphemeralPrivateKey?.relayKotlinBytes
            )
        } catch {
            throw RelaySecureChannelError.authenticationFailed
        }
    }

    var channelBinding: Data {
        get throws {
            guard core.handshakeFinished else { throw RelaySecureChannelError.handshakeNotFinished }
            return core.channelBinding.relayData
        }
    }

    var remoteStaticPublic: Data {
        get throws {
            guard core.handshakeFinished else { throw RelaySecureChannelError.handshakeNotFinished }
            return core.remoteStaticPublicKey.relayData
        }
    }

    func close() { core.close() }

    func writeHandshake(payload: Data = Data()) throws -> Data {
        guard !closed else { throw RelaySecureChannelError.channelClosed }
        do {
            return try core.writeHandshake(payload: payload.relayKotlinBytes).relayData
        } catch {
            throw RelaySecureChannelError.authenticationFailed
        }
    }

    func readHandshake(_ message: Data) throws -> Data {
        guard !closed else { throw RelaySecureChannelError.channelClosed }
        do {
            return try core.readHandshake(message: message.relayKotlinBytes).relayData
        } catch {
            throw RelaySecureChannelError.authenticationFailed
        }
    }

    func encrypt(_ plaintext: Data) throws -> Data {
        guard !closed else { throw RelaySecureChannelError.channelClosed }
        guard handshakeFinished else { throw RelaySecureChannelError.handshakeNotFinished }
        guard plaintext.count <= RelaySecureChannelPolicy.maxPlaintextRecordBytes else {
            throw RelaySecureChannelError.plaintextLimit
        }
        do {
            return try core.encrypt(plaintext: plaintext.relayKotlinBytes).relayData
        } catch {
            throw RelaySecureChannelError.transportFailed
        }
    }

    func decrypt(_ ciphertext: Data) throws -> Data {
        guard !closed else { throw RelaySecureChannelError.channelClosed }
        guard handshakeFinished else { throw RelaySecureChannelError.handshakeNotFinished }
        guard ciphertext.count >= 16,
              ciphertext.count <= RelaySecureChannelPolicy.maxCiphertextRecordBytes
        else { throw RelaySecureChannelError.ciphertextLimit }
        do {
            return try core.decrypt(ciphertext: ciphertext.relayKotlinBytes).relayData
        } catch {
            throw RelaySecureChannelError.transportFailed
        }
    }

    static func publicKey(forPrivateKey privateKey: Data) throws -> Data {
        guard privateKey.count == RelaySecureChannelPolicy.keyBytes else {
            throw RelaySecureChannelError.authenticationFailed
        }
        return MercuryCore.RelayPlatformCrypto.shared.x25519PublicKey(
            privateKey: privateKey.relayKotlinBytes
        ).relayData
    }
}
