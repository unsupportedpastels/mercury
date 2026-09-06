import XCTest
@testable import Mercury

/// The router allows one device socket per installation, so an approval
/// probe must have finished closing its socket before `probeApproval`
/// returns, on every exit path. A caller that opens the chat right after a
/// probe must not race a socket that is still being torn down.
final class RelayProbeTeardownTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_756_400_000)

    private func makeTarget() -> RelayPairedTarget {
        RelayPairedTarget(
            id: UUID(),
            label: "study",
            relayOrigin: "https://relay.example.com",
            installationID: TestRelayHost.installationID,
            hostPublicKey: TestRelayHost.hostStaticPublicKey,
            deviceID: RelayBase64.urlSafeEncode(Data((0..<16).map { UInt8($0 &+ 3) })),
            deviceStaticPrivateKey: Data(repeating: 0x51, count: 32),
            fingerprint: String(repeating: "b", count: 16),
            status: .pending,
            createdAtEpochSeconds: 1,
            lastUsedEpochSeconds: nil
        )
    }

    private struct AdmittedHost {
        let channel: RelaySecureChannel
        let reassembler: RelayFrameReassembler
        let channelID: Data
    }

    /// Host side of one admitted connection: full XK plus the admission
    /// envelope, leaving a live channel for the probe exchange.
    private func admit(socket: FakeRelaySocket) async throws -> AdmittedHost {
        let channel = try TestRelayHost.responderChannel()
        let firstRaw = try await socket.receive()
        let first = try XCTUnwrap(firstRaw)
        _ = try channel.readHandshake(first)
        try await socket.send(channel.writeHandshake())
        let thirdRaw = try await socket.receive()
        let third = try XCTUnwrap(thirdRaw)
        XCTAssertEqual(try channel.readHandshake(third), Data())
        let envelopeRaw = try await socket.receive()
        let envelope = try XCTUnwrap(envelopeRaw)
        _ = try channel.decrypt(envelope)
        let channelID = Data(try channel.channelBinding.prefix(RelayFraming.channelIDSize))
        return AdmittedHost(
            channel: channel,
            reassembler: RelayFrameReassembler(channelID: channelID),
            channelID: channelID
        )
    }

    private func receiveText(_ host: AdmittedHost, socket: FakeRelaySocket) async throws -> String {
        var received: Data?
        while received == nil {
            let ciphertextRaw = try await socket.receive()
            let ciphertext = try XCTUnwrap(ciphertextRaw)
            received = try host.reassembler.push(try host.channel.decrypt(ciphertext))
        }
        return String(decoding: try XCTUnwrap(received), as: UTF8.self)
    }

    private func sendText(_ text: String, _ host: AdmittedHost, socket: FakeRelaySocket) async throws {
        for record in try RelayFraming.encodeMessage(
            channelID: host.channelID,
            messageID: Data(repeating: 0x21, count: 16),
            payload: Data(text.utf8)
        ) {
            try await socket.send(host.channel.encrypt(record))
        }
    }

    /// Runs one probe against a scripted host and asserts the device socket
    /// is closed by the time `probeApproval` returns. Then proves the next
    /// connection through the same factory succeeds.
    private func runProbe(
        expectApproved: Bool,
        cancelWhileWaiting: Bool = false,
        host script: @escaping (AdmittedHost, FakeRelaySocket) async throws -> Void
    ) async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        let (nextDevice, nextHost) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device, nextDevice])
        let store = RelayTargetStore(persistence: InMemoryRelayTargetPersistence(), now: { self.now })
        let target = makeTarget()
        try await store.add(target)
        let coordinator = RelayPairingCoordinator(socketFactory: factory, store: store, now: { self.now })

        let hostTask = Task {
            let admitted = try await self.admit(socket: host)
            try await script(admitted, host)
        }
        let probe = Task { await coordinator.probeApproval(target: target, profile: "default") }
        if cancelWhileWaiting {
            // Let the handshake and ping land before cancelling the waiter.
            try await hostTask.value
            probe.cancel()
        }
        let approved = await probe.value
        XCTAssertEqual(approved, expectApproved)
        // The contract under test: close has completed before the return.
        XCTAssertTrue(device.closed, "probe returned before its socket was closed")
        if !cancelWhileWaiting { try await hostTask.value }

        let loaded = try await store.load()
        XCTAssertEqual(loaded.first?.status, expectApproved ? .approved : .pending)

        // The next connection is not superseded by a late close.
        async let nextAdmitted = admit(socket: nextHost)
        let connected = try await RelayConnector.connect(
            target: target, profile: "default", socketFactory: factory
        )
        _ = try await nextAdmitted
        XCTAssertEqual(factory.requestedURLs.count, 2)
        await RelayChatSocket(connected: connected).close()
    }

    func testApprovedProbeClosesSocketBeforeReturning() async throws {
        try await runProbe(expectApproved: true) { admitted, socket in
            let ping = try await self.receiveText(admitted, socket: socket)
            XCTAssertTrue(ping.contains("\"gateway.ping\""))
            try await self.sendText(
                #"{"jsonrpc":"2.0","id":"pairing-probe","result":{"ok":true}}"#, admitted, socket: socket
            )
            // Host observes the device's close as end of stream.
            let afterClose = try await socket.receive()
            XCTAssertNil(afterClose)
        }
    }

    func testRejectedProbeClosesSocketBeforeReturning() async throws {
        try await runProbe(expectApproved: false) { admitted, socket in
            _ = try await self.receiveText(admitted, socket: socket)
            try await self.sendText(
                #"{"jsonrpc":"2.0","id":"pairing-probe","error":{"code":-32000,"message":"denied"}}"#,
                admitted, socket: socket
            )
            let afterClose = try await socket.receive()
            XCTAssertNil(afterClose)
        }
    }

    func testEarlyEOFClosesSocketBeforeReturning() async throws {
        try await runProbe(expectApproved: false) { admitted, socket in
            _ = try await self.receiveText(admitted, socket: socket)
            await socket.close()
        }
    }

    func testProtocolErrorClosesSocketBeforeReturning() async throws {
        try await runProbe(expectApproved: false) { admitted, socket in
            _ = try await self.receiveText(admitted, socket: socket)
            // Garbage ciphertext: the secure channel fails closed.
            try await socket.send(Data(repeating: 0xEE, count: 64))
            let afterClose = try await socket.receive()
            XCTAssertNil(afterClose)
        }
    }

    func testCancellationClosesSocketBeforeReturning() async throws {
        try await runProbe(expectApproved: false, cancelWhileWaiting: true) { admitted, socket in
            // Read the ping, then never reply; the test cancels the waiter.
            _ = try await self.receiveText(admitted, socket: socket)
        }
    }
}
