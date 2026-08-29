import XCTest
@testable import Mercury

/// Exercises the admitted-transport path: fresh XK reconnect handshake, the
/// encrypted `controller.open` admission envelope, and exact framed Hermes
/// text carriage through `RelayChatSocket` (PROTOCOL §7, §9).
final class RelayTransportTests: XCTestCase {

    private func makeTarget(deviceKey: Data = Data(repeating: 0x51, count: 32)) -> RelayPairedTarget {
        RelayPairedTarget(
            id: UUID(),
            label: "study",
            relayOrigin: "https://relay.example.com",
            installationID: TestRelayHost.installationID,
            hostPublicKey: TestRelayHost.hostStaticPublicKey,
            deviceID: RelayBase64.urlSafeEncode(Data((0..<16).map { UInt8($0 &+ 3) })),
            deviceStaticPrivateKey: deviceKey,
            fingerprint: String(repeating: "b", count: 16),
            status: .approved,
            createdAtEpochSeconds: 1,
            lastUsedEpochSeconds: nil
        )
    }

    /// Host side of one admitted connection: handshake with empty final
    /// payload, then return the decrypted admission envelope plus a live
    /// channel and framing state for the test body.
    private func admit(
        socket: FakeRelaySocket
    ) async throws -> (channel: RelaySecureChannel, envelope: Data, reassembler: RelayFrameReassembler) {
        let channel = try TestRelayHost.responderChannel()
        let firstRaw = try await socket.receive()
        let first = try XCTUnwrap(firstRaw)
        _ = try channel.readHandshake(first)
        try await socket.send(channel.writeHandshake())
        let thirdRaw = try await socket.receive()
        let third = try XCTUnwrap(thirdRaw)
        XCTAssertEqual(try channel.readHandshake(third), Data())
        let envelopeRaw = try await socket.receive()
        let envelopeCiphertext = try XCTUnwrap(envelopeRaw)
        let envelope = try channel.decrypt(envelopeCiphertext)
        let channelID = try channel.channelBinding.prefix(RelayFraming.channelIDSize)
        return (channel, envelope, RelayFrameReassembler(channelID: Data(channelID)))
    }

    func testConnectSendsExactAdmissionEnvelope() async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device])
        let target = makeTarget()

        async let hostSide = admit(socket: host)
        let connected = try await RelayConnector.connect(
            target: target, profile: "default", resumeCursor: 7, socketFactory: factory
        )
        let admitted = try await hostSide
        XCTAssertEqual(
            String(data: admitted.envelope, encoding: .utf8),
            "{\"device_id\":\"\(target.deviceID)\",\"profile\":\"default\","
                + "\"resume_cursor\":7,\"type\":\"controller.open\"}"
        )
        // Both endpoints independently agree on the framing channel ID.
        XCTAssertEqual(
            connected.channelBinding,
            try admitted.channel.channelBinding
        )
        await RelayChatSocket(connected: connected).close()
    }

    func testChatSocketCarriesExactTextBothWaysIncludingWhitespace() async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device])
        let connectedTask = Task {
            try await RelayConnector.connect(
                target: makeTarget(), profile: "default", socketFactory: factory
            )
        }
        let admitted = try await admit(socket: host)
        let connected = try await connectedTask.value
        let chatSocket = RelayChatSocket(connected: connected)
        let hostChannelID = Data(try admitted.channel.channelBinding.prefix(16))

        // Device -> host, leading whitespace preserved exactly.
        let outbound = "  {\"jsonrpc\":\"2.0\",\"method\":\"prompt.submit\"}  "
        try await chatSocket.sendText(outbound)
        var received: Data?
        while received == nil {
            let ciphertextRaw = try await host.receive()
            let ciphertext = try XCTUnwrap(ciphertextRaw)
            received = try admitted.reassembler.push(try admitted.channel.decrypt(ciphertext))
        }
        XCTAssertEqual(String(data: try XCTUnwrap(received), encoding: .utf8), outbound)

        // Host -> device with a delta that starts with a space.
        let reply = "{\"method\":\"message.delta\",\"params\":{\"text\":\" leading\"}}"
        let hostChannel = admitted.channel
        for record in try RelayFraming.encodeMessage(
            channelID: hostChannelID,
            messageID: Data(repeating: 0x21, count: 16),
            payload: Data(reply.utf8)
        ) {
            try await host.send(hostChannel.encrypt(record))
        }
        let replyReceived = try await chatSocket.receiveText()
        XCTAssertEqual(replyReceived, reply)

        // Multi-fragment logical message round trip.
        let large = String(repeating: "y", count: RelayFraming.maxPayloadBytes + 5)
        for record in try RelayFraming.encodeMessage(
            channelID: hostChannelID,
            messageID: Data(repeating: 0x22, count: 16),
            payload: Data(large.utf8)
        ) {
            try await host.send(hostChannel.encrypt(record))
        }
        let largeReceived = try await chatSocket.receiveText()
        XCTAssertEqual(largeReceived, large)

        // Peer close surfaces the ChatSocketing nil contract.
        await host.close()
        let afterClose = try await chatSocket.receiveText()
        XCTAssertNil(afterClose)
        await chatSocket.close()
    }

    func testTamperedRecordFailsClosed() async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device])
        let connectedTask = Task {
            try await RelayConnector.connect(
                target: makeTarget(), profile: "default", socketFactory: factory
            )
        }
        let admitted = try await admit(socket: host)
        let connected = try await connectedTask.value
        let chatSocket = RelayChatSocket(connected: connected)

        let hostChannel = admitted.channel
        var tampered = try hostChannel.encrypt(
            try RelayFraming.encodeMessage(
                channelID: Data(try admitted.channel.channelBinding.prefix(16)),
                messageID: Data(repeating: 0x23, count: 16),
                payload: Data("x".utf8)
            )[0]
        )
        tampered[tampered.count - 1] ^= 1
        try await host.send(tampered)
        do {
            _ = try await chatSocket.receiveText()
            XCTFail("expected protocol failure")
        } catch {
            XCTAssertTrue(error is ChatError)
        }
    }

    func testUnadmittedDeviceSeesNotAuthorizedOnHostClose() async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device])
        // Host drops the connection after the first handshake message, the
        // observable shape of a pending/denied/revoked device.
        let hostTask = Task {
            _ = try? await host.receive()
            await host.close()
        }
        do {
            _ = try await RelayConnector.connect(
                target: makeTarget(), profile: "default", socketFactory: factory
            )
            XCTFail("expected notAuthorized")
        } catch {
            XCTAssertEqual(error as? RelayConnectionError, .notAuthorized)
        }
        await hostTask.value
    }
}
