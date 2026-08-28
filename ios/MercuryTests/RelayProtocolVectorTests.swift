import CryptoKit
import XCTest
@testable import Mercury

/// Proves the Swift secure channel and framing codec against the canonical
/// cross-language corpora vendored from the relay repository
/// (`protocol/vectors/{secure-channel,frames}`), mirroring the reference
/// suites `test_secure_channel_vectors.py` and `test_framing_vectors.py`.
final class RelayProtocolVectorTests: XCTestCase {

    // MARK: - Fixture loading

    private func fixtureJSON(named name: String) throws -> [String: Any] {
        let bundle = Bundle(for: Self.self)
        let url = bundle.url(
            forResource: name, withExtension: "json", subdirectory: "Fixtures/RelayProtocol"
        ) ?? bundle.url(forResource: name, withExtension: "json")
        let unwrapped = try XCTUnwrap(url, "missing fixture \(name).json")
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: unwrapped))
        return try XCTUnwrap(object as? [String: Any])
    }

    private func hexData(_ text: String) -> Data {
        precondition(text.count % 2 == 0)
        var data = Data(capacity: text.count / 2)
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            data.append(UInt8(text[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    private func hexString(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    private func secureChannelVector(_ identifier: String) throws -> [String: Any] {
        let corpus = try fixtureJSON(named: "secure-channel-corpus")
        let vectors = try XCTUnwrap(corpus["vectors"] as? [[String: Any]])
        return try XCTUnwrap(vectors.first { $0["id"] as? String == identifier })
    }

    private func vectorPair(
        _ vector: [String: Any]
    ) throws -> (mobile: RelaySecureChannel, host: RelaySecureChannel) {
        let keys = try XCTUnwrap(vector["keys"] as? [String: String])
        let installation = hexData(keys["installation_id"]!)
        let mobile = try RelaySecureChannel(
            isInitiator: true,
            staticPrivateKey: hexData(keys["initiator_static_private"]!),
            installationID: installation,
            remoteStaticPublicKey: hexData(keys["responder_static_public"]!),
            deterministicEphemeralPrivateKey: hexData(keys["initiator_ephemeral_private"]!)
        )
        let host = try RelaySecureChannel(
            isInitiator: false,
            staticPrivateKey: hexData(keys["responder_static_private"]!),
            installationID: installation,
            remoteStaticPublicKey: nil,
            deterministicEphemeralPrivateKey: hexData(keys["responder_ephemeral_private"]!)
        )
        return (mobile, host)
    }

    @discardableResult
    private func complete(
        mobile: RelaySecureChannel,
        host: RelaySecureChannel,
        finalPayload: Data
    ) throws -> [Data] {
        let first = try mobile.writeHandshake()
        XCTAssertEqual(try host.readHandshake(first), Data())
        let second = try host.writeHandshake()
        XCTAssertEqual(try mobile.readHandshake(second), Data())
        let third = try mobile.writeHandshake(payload: finalPayload)
        XCTAssertEqual(try host.readHandshake(third), finalPayload)
        return [first, second, third]
    }

    // MARK: - Secure channel positive vectors

    func testNoiseVectorsReproduceExactHandshakeAndTransportBytes() throws {
        for identifier in ["pairing-xk-capability", "reconnect-xk"] {
            let vector = try secureChannelVector(identifier)
            let (mobile, host) = try vectorPair(vector)
            let payloads = try XCTUnwrap(vector["handshake_payloads"] as? [String])
            let finalPayload = hexData(payloads[2])
            let messages = try complete(mobile: mobile, host: host, finalPayload: finalPayload)
            let expectedMessages = try XCTUnwrap(vector["handshake_messages"] as? [String])
            XCTAssertEqual(messages.map(hexString), expectedMessages, identifier)

            let binding = try XCTUnwrap(vector["channel_binding"] as? String)
            XCTAssertEqual(hexString(try mobile.channelBinding), binding, identifier)
            XCTAssertEqual(try host.channelBinding, try mobile.channelBinding)
            let keys = try XCTUnwrap(vector["keys"] as? [String: String])
            XCTAssertEqual(
                try host.remoteStaticPublic,
                hexData(keys["initiator_static_public"]!),
                identifier
            )

            let transport = try XCTUnwrap(vector["transport_messages"] as? [[String: Any]])
            let outbound = hexData(try XCTUnwrap(transport[0]["plaintext_hex"] as? String))
            let ciphertext = try mobile.encrypt(outbound)
            XCTAssertEqual(
                hexString(ciphertext),
                try XCTUnwrap(transport[0]["ciphertext_hex"] as? String),
                identifier
            )
            XCTAssertEqual(try host.decrypt(ciphertext), outbound)

            let reply = hexData(try XCTUnwrap(transport[1]["plaintext_hex"] as? String))
            let replyCiphertext = try host.encrypt(reply)
            XCTAssertEqual(
                hexString(replyCiphertext),
                try XCTUnwrap(transport[1]["ciphertext_hex"] as? String),
                identifier
            )
            XCTAssertEqual(try mobile.decrypt(replyCiphertext), reply)
        }
    }

    func testProductionChannelsUseFreshEphemerals() throws {
        let installation = Data((0..<32).map { UInt8($0) })
        let mobilePrivate = Data((32..<64).map { UInt8($0) })
        let hostPublic = try RelaySecureChannel.publicKey(
            forPrivateKey: Data((64..<96).map { UInt8($0) })
        )

        func firstMessage() throws -> Data {
            let channel = try RelaySecureChannel(
                initiatorStaticPrivateKey: mobilePrivate,
                installationID: installation,
                hostStaticPublicKey: hostPublic
            )
            return try channel.writeHandshake()
        }
        XCTAssertNotEqual(try firstMessage(), try firstMessage())
    }

    // MARK: - Secure channel negative cases

    func testTamperedHandshakeClosesTheChannel() throws {
        let (mobile, host) = try vectorPair(try secureChannelVector("reconnect-xk"))
        _ = try host.readHandshake(try mobile.writeHandshake())
        var second = try host.writeHandshake()
        second[second.count - 1] ^= 1
        XCTAssertThrowsError(try mobile.readHandshake(second)) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .authenticationFailed)
        }
        XCTAssertTrue(mobile.closed)
    }

    func testTruncatedHandshakeClosesTheChannel() throws {
        let (mobile, host) = try vectorPair(try secureChannelVector("reconnect-xk"))
        _ = try host.readHandshake(try mobile.writeHandshake())
        let second = try host.writeHandshake()
        XCTAssertThrowsError(try mobile.readHandshake(second.dropLast())) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .authenticationFailed)
        }
        XCTAssertTrue(mobile.closed)
    }

    func testWrongResponderIdentityFailsAuthentication() throws {
        let vector = try secureChannelVector("reconnect-xk")
        let keys = try XCTUnwrap(vector["keys"] as? [String: String])
        let wrongMobile = try RelaySecureChannel(
            initiatorStaticPrivateKey: hexData(keys["initiator_static_private"]!),
            installationID: hexData(keys["installation_id"]!),
            hostStaticPublicKey: RelaySecureChannel.publicKey(
                forPrivateKey: Data((1..<33).map { UInt8($0) })
            )
        )
        let realHost = try RelaySecureChannel(
            isInitiator: false,
            staticPrivateKey: hexData(keys["responder_static_private"]!),
            installationID: hexData(keys["installation_id"]!),
            remoteStaticPublicKey: nil,
            deterministicEphemeralPrivateKey: nil
        )
        XCTAssertThrowsError(try realHost.readHandshake(try wrongMobile.writeHandshake())) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .authenticationFailed)
        }
        XCTAssertTrue(realHost.closed)
    }

    func testWrongPrologueFailsAuthentication() throws {
        let vector = try secureChannelVector("reconnect-xk")
        let keys = try XCTUnwrap(vector["keys"] as? [String: String])
        var wrongInstallation = hexData(keys["installation_id"]!)
        wrongInstallation[wrongInstallation.count - 1] ^= 1
        let mobile = try RelaySecureChannel(
            initiatorStaticPrivateKey: hexData(keys["initiator_static_private"]!),
            installationID: wrongInstallation,
            hostStaticPublicKey: hexData(keys["responder_static_public"]!)
        )
        let host = try RelaySecureChannel(
            isInitiator: false,
            staticPrivateKey: hexData(keys["responder_static_private"]!),
            installationID: hexData(keys["installation_id"]!),
            remoteStaticPublicKey: nil,
            deterministicEphemeralPrivateKey: nil
        )
        XCTAssertThrowsError(try host.readHandshake(try mobile.writeHandshake())) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .authenticationFailed)
        }
        XCTAssertTrue(host.closed)
    }

    func testReplayTamperTruncationAndDirectionFailClosed() throws {
        // duplicate-replay
        var pair = try vectorPair(try secureChannelVector("reconnect-xk"))
        try complete(mobile: pair.mobile, host: pair.host, finalPayload: Data())
        let plaintext = Data("bounded record".utf8)
        let ciphertext = try pair.mobile.encrypt(plaintext)
        XCTAssertEqual(ciphertext.count, plaintext.count + 16)
        XCTAssertEqual(try pair.host.decrypt(ciphertext), plaintext)
        XCTAssertThrowsError(try pair.host.decrypt(ciphertext)) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .transportFailed)
        }
        XCTAssertTrue(pair.host.closed)

        // tampered-ciphertext
        pair = try vectorPair(try secureChannelVector("reconnect-xk"))
        try complete(mobile: pair.mobile, host: pair.host, finalPayload: Data())
        var tampered = try pair.mobile.encrypt(Data("negative case".utf8))
        tampered[tampered.count - 1] ^= 1
        XCTAssertThrowsError(try pair.host.decrypt(tampered)) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .transportFailed)
        }
        XCTAssertTrue(pair.host.closed)

        // truncated-ciphertext
        pair = try vectorPair(try secureChannelVector("reconnect-xk"))
        try complete(mobile: pair.mobile, host: pair.host, finalPayload: Data())
        let truncated = try pair.mobile.encrypt(Data("negative case".utf8)).dropLast()
        XCTAssertThrowsError(try pair.host.decrypt(truncated)) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .transportFailed)
        }
        XCTAssertTrue(pair.host.closed)

        // wrong-direction: the sender cannot decrypt its own record.
        pair = try vectorPair(try secureChannelVector("reconnect-xk"))
        try complete(mobile: pair.mobile, host: pair.host, finalPayload: Data())
        let outbound = try pair.mobile.encrypt(Data("negative case".utf8))
        XCTAssertThrowsError(try pair.mobile.decrypt(outbound)) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .transportFailed)
        }
        XCTAssertTrue(pair.mobile.closed)
    }

    func testTransportRecordBoundsAreEnforcedWithoutClosing() throws {
        let pair = try vectorPair(try secureChannelVector("reconnect-xk"))
        try complete(mobile: pair.mobile, host: pair.host, finalPayload: Data())
        XCTAssertThrowsError(
            try pair.mobile.encrypt(
                Data(repeating: 0x78, count: RelaySecureChannelPolicy.maxPlaintextRecordBytes + 1)
            )
        ) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .plaintextLimit)
        }
        XCTAssertFalse(pair.mobile.closed)
        XCTAssertThrowsError(
            try pair.host.decrypt(
                Data(repeating: 0x78, count: RelaySecureChannelPolicy.maxCiphertextRecordBytes + 1)
            )
        ) { error in
            XCTAssertEqual(error as? RelaySecureChannelError, .ciphertextLimit)
        }
        XCTAssertFalse(pair.host.closed)
    }

    // MARK: - Framing corpus

    private func framePayload(_ spec: [String: Any]) throws -> Data {
        switch try XCTUnwrap(spec["encoding"] as? String) {
        case "hex":
            return hexData(try XCTUnwrap(spec["hex"] as? String))
        case "utf8":
            return Data(try XCTUnwrap(spec["text"] as? String).utf8)
        case "repeat":
            let byte = hexData(try XCTUnwrap(spec["byte_hex"] as? String))
            let length = try XCTUnwrap(spec["length"] as? Int)
            return Data(repeating: byte[0], count: length)
        default:
            throw XCTSkip("unknown payload encoding")
        }
    }

    func testCanonicalFrameCorpusMatchesEncoderAndDecoder() throws {
        let corpus = try fixtureJSON(named: "frames-corpus")
        let vectors = try XCTUnwrap(corpus["vectors"] as? [[String: Any]])
        XCTAssertFalse(vectors.isEmpty)
        for vector in vectors {
            let identifier = try XCTUnwrap(vector["id"] as? String)
            let payload = try framePayload(try XCTUnwrap(vector["payload"] as? [String: Any]))
            let channelID = hexData(try XCTUnwrap(vector["channel_id_hex"] as? String))
            let messageID = hexData(try XCTUnwrap(vector["message_id_hex"] as? String))
            let records = try RelayFraming.encodeMessage(
                channelID: channelID, messageID: messageID, payload: payload
            )
            XCTAssertEqual(records.count, try XCTUnwrap(vector["fragment_count"] as? Int), identifier)
            XCTAssertEqual(payload.count, try XCTUnwrap(vector["logical_length"] as? Int), identifier)
            XCTAssertEqual(
                hexString(Data(SHA256.hash(data: payload))),
                try XCTUnwrap(vector["payload_sha256"] as? String),
                identifier
            )
            let expectedRecords = try XCTUnwrap(vector["records"] as? [[String: Any]])
            XCTAssertEqual(records.count, expectedRecords.count, identifier)
            for (record, expected) in zip(records, expectedRecords) {
                XCTAssertEqual(record.count, try XCTUnwrap(expected["length"] as? Int), identifier)
                if let recordHex = expected["record_hex"] as? String {
                    XCTAssertEqual(hexString(record), recordHex, identifier)
                } else {
                    XCTAssertEqual(
                        hexString(Data(SHA256.hash(data: record))),
                        try XCTUnwrap(expected["sha256"] as? String),
                        identifier
                    )
                }
                let frame = try RelayFraming.decodeRecord(record)
                XCTAssertEqual(frame.payload, record.suffix(from: RelayFraming.headerSize), identifier)
            }
            let reassembler = RelayFrameReassembler(channelID: channelID)
            var result: Data?
            for record in records {
                result = try reassembler.push(record)
            }
            XCTAssertEqual(result, payload, identifier)
        }
    }

    func testFramingBoundariesAndReplayFailClosed() throws {
        let channel = Data(repeating: 0x00, count: 16)
        let message = Data(repeating: 0x11, count: 16)
        let records = try RelayFraming.encodeMessage(
            channelID: channel,
            messageID: message,
            payload: Data(repeating: 0x78, count: RelayFraming.maxPayloadBytes + 1)
        )
        XCTAssertEqual(records.map(\.count), [RelayFraming.maxNoisePlaintextBytes, 51])

        let reassembler = RelayFrameReassembler(channelID: channel)
        XCTAssertNil(try reassembler.push(records[0]))
        XCTAssertThrowsError(try reassembler.push(records[0])) // duplicate fragment
        XCTAssertFalse(reassembler.inProgress)

        var malformed = records[0]
        malformed[2] ^= 1
        XCTAssertThrowsError(try RelayFraming.decodeRecord(malformed)) { error in
            XCTAssertEqual(
                (error as? RelayFraming.DecodeError)?.reason, "unknown version"
            )
        }

        XCTAssertThrowsError(
            try RelayFraming.encodeMessage(
                channelID: channel,
                messageID: message,
                payload: Data(repeating: 0x78, count: RelayFraming.maxLogicalMessageBytes + 1)
            )
        )
    }

    func testFrameDecodeNegativeMutations() throws {
        let channel = Data(repeating: 0xAA, count: 16)
        let message = Data(repeating: 0xBB, count: 16)
        let base = try RelayFraming.encodeMessage(
            channelID: channel, messageID: message, payload: Data("payload".utf8)
        )[0]

        func mutated(_ transform: (inout Data) -> Void) -> Data {
            var copy = base
            transform(&copy)
            return copy
        }

        // Mirrors the corpus negative_cases matrix by id.
        let cases: [(String, Data)] = [
            ("unknown-version", mutated { $0[2] = 2 }),
            ("unknown-kind", mutated { $0[3] = 2 }),
            ("unknown-flags", mutated { $0[4] = 1 }),
            ("reserved-byte", mutated { $0[5] = 1 }),
            ("zero-fragment-count", mutated { $0[8] = 0; $0[9] = 0 }),
            ("noncanonical-payload-length", mutated { $0[17] = $0[17] &+ 1 }),
            ("trailing-byte", base + Data([0x00])),
            ("truncated-header", base.prefix(RelayFraming.headerSize - 1)),
        ]
        for (identifier, record) in cases {
            XCTAssertThrowsError(try RelayFraming.decodeRecord(record), identifier)
        }

        // duplicate-fragment is a reassembly rejection, not a decode error.
        let two = try RelayFraming.encodeMessage(
            channelID: channel,
            messageID: message,
            payload: Data(repeating: 0x7A, count: RelayFraming.maxPayloadBytes + 1)
        )
        let reassembler = RelayFrameReassembler(channelID: channel)
        XCTAssertNil(try reassembler.push(two[0]))
        XCTAssertThrowsError(try reassembler.push(two[0]))
        XCTAssertFalse(reassembler.inProgress)
    }

    func testReassemblerRejectsChannelAndMetadataMismatches() throws {
        let channel = Data(repeating: 0x01, count: 16)
        let other = Data(repeating: 0x02, count: 16)
        let message = Data(repeating: 0x03, count: 16)
        let record = try RelayFraming.encodeMessage(
            channelID: other, messageID: message, payload: Data("x".utf8)
        )[0]
        let reassembler = RelayFrameReassembler(channelID: channel)
        XCTAssertThrowsError(try reassembler.push(record))

        // Metadata drift between fragments of one message.
        let parts = try RelayFraming.encodeMessage(
            channelID: channel,
            messageID: message,
            payload: Data(repeating: 0x7A, count: RelayFraming.maxPayloadBytes + 1)
        )
        let drifted = try RelayFraming.encodeMessage(
            channelID: channel,
            messageID: Data(repeating: 0x04, count: 16),
            payload: Data(repeating: 0x7A, count: RelayFraming.maxPayloadBytes + 1)
        )
        let strict = RelayFrameReassembler(channelID: channel)
        XCTAssertNil(try strict.push(parts[0]))
        XCTAssertThrowsError(try strict.push(drifted[1]))
        XCTAssertFalse(strict.inProgress)
    }
}
