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
            target: target, profile: "default", resumeCursor: 7, deviceName: nil, socketFactory: factory
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

    private func sendFrame(_ value: [String: Any], socket: FakeRelaySocket,
                           channel: RelaySecureChannel) async throws {
        for record in try RelayFraming.encodeMessage(
            channelID: Data(try channel.channelBinding.prefix(16)),
            messageID: Data((0..<16).map { _ in UInt8.random(in: 0...255) }),
            payload: JSONSerialization.data(withJSONObject: value)
        ) { try await socket.send(channel.encrypt(record)) }
    }

    private func readFrame(socket: FakeRelaySocket, channel: RelaySecureChannel,
                           reassembler: RelayFrameReassembler) async throws -> [String: Any] {
        while let raw = try await socket.receive() {
            if let frame = try reassembler.push(channel.decrypt(raw)) {
                return try XCTUnwrap(JSONSerialization.jsonObject(with: frame) as? [String: Any])
            }
        }
        throw RelayConnectionError.offline
    }

    func testRetainedAdmissionIsSingleFlightAndNamespacesLateReplies() async throws {
        let (device1, host1) = InMemoryRelayTransport.pair()
        let (device2, host2) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device1, device2])
        let pool = RelayConnectionPool(socketFactory: factory)
        let target = makeTarget()
        let host = Task { () throws -> [String] in
            var ids: [String] = []
            for (round, socket) in [host1, host2].enumerated() {
                let admitted = try await admit(socket: socket)
                let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: admitted.envelope) as? [String: Any])
                XCTAssertEqual(envelope["recovery_version"] as? Int, 1)
                XCTAssertEqual(envelope["resume_cursor"] as? Int, round == 0 ? 0 : 1)
                let cursor = round == 0 ? 0 : 1
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                    "recovery_version": 1, "lease_id": "lease", "last_seq": cursor,
                    "resume_cursor": cursor, "replay_gap": false, "recovery_reset": false,
                    "bindings": [], "task_snapshot": []
                ]], socket: socket, channel: admitted.channel)
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                     "params": ["lease_id": "lease", "last_seq": cursor]],
                                    socket: socket, channel: admitted.channel)
                let request = try await readFrame(socket: socket, channel: admitted.channel,
                                                  reassembler: admitted.reassembler)
                let id = try XCTUnwrap(request["id"] as? String)
                ids.append(id)
                if round == 1 {
                    let old = String(decoding: try JSONSerialization.data(withJSONObject: [
                        "jsonrpc": "2.0", "id": ids[0], "result": ["marker": "stale"]
                    ]), as: UTF8.self)
                    try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.frame", "params": [
                        "lease_id": "lease", "seq": 2, "replay": false, "frame": old
                    ]], socket: socket, channel: admitted.channel)
                }
                let reply = String(decoding: try JSONSerialization.data(withJSONObject: [
                    "jsonrpc": "2.0", "id": id, "result": ["marker": "current"]
                ]), as: UTF8.self)
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.frame", "params": [
                    "lease_id": "lease", "seq": round == 0 ? 1 : 3, "replay": false, "frame": reply
                ]], socket: socket, channel: admitted.channel)
            }
            return ids
        }
        async let first = pool.acquire(target: target, profile: "default")
        async let simultaneous = pool.acquire(target: target, profile: "default")
        let a = try await first
        let b = try await simultaneous
        XCTAssertTrue(a === b)
        XCTAssertEqual(factory.requestedURLs.count, 1)
        let initial = try await a.relayRequest("relay.sessions.list", params: [:])
        XCTAssertEqual(initial["marker"] as? String, "current")
        await RelayConnectionPool.release(b)
        XCTAssertFalse(a.isClosed, "An auxiliary read must not close chat custody")
        let ended = Task { for await _ in a.start(replayBuffered: false) {} }
        await host1.close()
        await ended.value
        XCTAssertTrue(a.isClosed, "Peer death must invalidate the pooled controller")
        let replacement = try await pool.acquire(target: target, profile: "default")
        XCTAssertFalse(a === replacement)
        let next = try await replacement.relayRequest("relay.sessions.list", params: [:])
        XCTAssertEqual(next["marker"] as? String, "current", "Late live old replies cannot settle new RPCs")
        let ids = try await host.value
        XCTAssertNotEqual(ids[0], ids[1])
        XCTAssertEqual(factory.requestedURLs.count, 2)
        await replacement.close()
        await host2.close()
    }

    func testDiscardedFailedCandidateIsNotBorrowedOnRetry() async throws {
        let (device1, hostSocket1) = InMemoryRelayTransport.pair()
        let (device2, hostSocket2) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device1, device2])
        let pool = RelayConnectionPool(socketFactory: factory)
        let target = makeTarget()

        let host1 = Task {
            let admitted = try await admit(socket: hostSocket1)
            for frame in [
                ["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                    "recovery_version": 1, "lease_id": "failed", "last_seq": 0,
                    "resume_cursor": 0, "replay_gap": false, "recovery_reset": false,
                    "bindings": [], "task_snapshot": []
                ]] as [String: Any],
                ["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                 "params": ["lease_id": "failed", "last_seq": 0]] as [String: Any]
            ] {
                try await sendFrame(frame, socket: hostSocket1, channel: admitted.channel)
            }
            let request = try await readFrame(
                socket: hostSocket1, channel: admitted.channel, reassembler: admitted.reassembler
            )
            try await sendFrame([
                "jsonrpc": "2.0",
                "id": try XCTUnwrap(request["id"]),
                "error": ["code": -32000, "message": "resume failed"]
            ], socket: hostSocket1, channel: admitted.channel)
        }

        let failed = try await pool.acquire(target: target, profile: "default")
        do {
            _ = try await failed.resume(durableSessionID: "durable", profile: "default")
            XCTFail("expected the first resume to fail")
        } catch { }
        try await host1.value
        await pool.discard(failed)

        let host2 = Task {
            let admitted = try await admit(socket: hostSocket2)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                "recovery_version": 1, "lease_id": "replacement", "last_seq": 0,
                "resume_cursor": 0, "replay_gap": false, "recovery_reset": false,
                "bindings": [], "task_snapshot": []
            ]], socket: hostSocket2, channel: admitted.channel)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                 "params": ["lease_id": "replacement", "last_seq": 0]],
                                socket: hostSocket2, channel: admitted.channel)
        }
        let replacement = try await pool.acquire(target: target, profile: "default")
        try await host2.value
        XCTAssertFalse(failed === replacement)
        XCTAssertEqual(factory.requestedURLs.count, 2)
        await replacement.close()
        await hostSocket1.close()
        await hostSocket2.close()
    }

    func testPoolConsumesOffscreenChildBeforeFollowingRPC() async throws {
        let (device, hostSocket) = InMemoryRelayTransport.pair()
        let pool = RelayConnectionPool(socketFactory: FakeRelaySocketFactory(sockets: [device]))
        let host = Task {
            let admitted = try await admit(socket: hostSocket)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                "recovery_version": 1, "lease_id": "offscreen", "last_seq": 0,
                "resume_cursor": 0, "replay_gap": false, "recovery_reset": false,
                "bindings": [["runtime_session_id": "A", "durable_session_id": "durable-A",
                              "profile": "default", "live": true]], "task_snapshot": []
            ]], socket: hostSocket, channel: admitted.channel)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                 "params": ["lease_id": "offscreen", "last_seq": 0]],
                                socket: hostSocket, channel: admitted.channel)
            let request = try await readFrame(socket: hostSocket, channel: admitted.channel,
                                              reassembler: admitted.reassembler)
            let child: [String: Any] = ["jsonrpc": "2.0", "method": "event", "params": [
                "session_id": "A", "type": "subagent.start", "payload": ["subagent_id": "child", "goal": "offscreen"]]]
            let reply: [String: Any] = ["jsonrpc": "2.0", "id": try XCTUnwrap(request["id"]), "result": ["ok": true]]
            for (index, frame) in [child, reply].enumerated() {
                let text = String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.frame", "params": [
                    "lease_id": "offscreen", "seq": index + 1, "replay": false, "frame": text
                ]], socket: hostSocket, channel: admitted.channel)
            }
            let resume = try await readFrame(socket: hostSocket, channel: admitted.channel,
                                             reassembler: admitted.reassembler)
            XCTAssertEqual(resume["method"] as? String, "session.resume")
            try await sendFrame(["jsonrpc": "2.0", "id": try XCTUnwrap(resume["id"]),
                                 "result": ["session_id": "new-A", "session_key": "durable-A"]],
                                socket: hostSocket, channel: admitted.channel)
        }
        let connection = try await pool.acquire(target: makeTarget(), profile: "default")
        _ = try await connection.relayRequest("relay.sessions.list", params: [:])
        let relay = try XCTUnwrap(connection.relaySocket)
        let cursor = await relay.acknowledgedRecoveryCursor()
        XCTAssertEqual(cursor, 2, "Offscreen A must be applied or explicitly rejected before B/RPC progress")
        let retained = await relay.retainedTasks(durable: "durable-A", runtime: "A")
        XCTAssertEqual(retained?.rows.map(\.childID), ["child"])
        XCTAssertEqual(retained?.rows.first?.status, .active)
        let wrongDurable = await relay.retainedTasks(durable: "durable-B", runtime: "A")
        XCTAssertNil(wrongDurable, "Returning to B cannot import A's retained tasks")
        _ = try await connection.resume(durableSessionID: "durable-A", profile: "default")
        let rebound = await relay.retainedTasks(durable: "durable-A", runtime: "new-A")
        XCTAssertEqual(rebound?.rows.map(\.childID), ["child"])
        XCTAssertEqual(rebound?.rows.first?.available, false,
                       "A replacement binding must not make old-runtime workers look active")
        let rejectedOld = await relay.retainedTasks(durable: "durable-A", runtime: "A")
        XCTAssertNil(rejectedOld)
        try await host.value
        await connection.close()
        await hostSocket.close()
    }

    func testOtherRuntimeObserverDoesNotBlockCursorAndRejectsUnboundChildren() async throws {
        let (device, hostSocket) = InMemoryRelayTransport.pair()
        let pool = RelayConnectionPool(socketFactory: FakeRelaySocketFactory(sockets: [device]))
        let host = Task {
            let admitted = try await admit(socket: hostSocket)
            func binding(_ runtime: String, _ durable: String, _ profile: String, _ live: Bool) -> [String: Any] {
                ["runtime_session_id": runtime, "durable_session_id": durable, "profile": profile, "live": live]
            }
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                "recovery_version": 1, "lease_id": "multiplex", "last_seq": 0,
                "resume_cursor": 0, "replay_gap": false, "recovery_reset": true,
                "bindings": [binding("A", "durable-A", "default", true),
                             binding("B", "durable-B", "default", true),
                             binding("old-A", "durable-A", "default", false),
                             binding("foreign", "durable-A", "other", true)], "task_snapshot": []
            ]], socket: hostSocket, channel: admitted.channel)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                 "params": ["lease_id": "multiplex", "last_seq": 0]],
                                socket: hostSocket, channel: admitted.channel)
            let request = try await readFrame(socket: hostSocket, channel: admitted.channel,
                                              reassembler: admitted.reassembler)
            func send(_ seq: Int, _ frame: [String: Any]) async throws {
                let text = String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.frame", "params": [
                    "lease_id": "multiplex", "seq": seq, "replay": false, "frame": text
                ]], socket: hostSocket, channel: admitted.channel)
            }
            for (index, runtime) in ["A", "old-A", "unbound", "foreign", "B"].enumerated() {
                let child: [String: Any] = ["jsonrpc": "2.0", "method": "event", "params": [
                    "session_id": runtime, "type": "subagent.start",
                    "payload": ["subagent_id": "child-" + runtime, "goal": runtime]]]
                try await send(index + 1, child)
                if index == 0 { try await send(1, child) } // Same evidence delivered twice, applied once.
            }
            for seq in 6...4105 {
                try await send(seq, ["jsonrpc": "2.0", "method": "event", "params": [
                    "session_id": "B", "type": "future.additive", "payload": [:]]])
            }
            try await send(4106, ["jsonrpc": "2.0", "id": try XCTUnwrap(request["id"]), "result": ["ok": true]])
        }
        let connection = try await pool.acquire(target: makeTarget(), profile: "default")
        let stream = connection.start(replayBuffered: false)
        let observer = Task { () -> (all: [String], visible: [String]) in
            var children: [String] = []
            var visible: [String] = []
            for await event in stream {
                guard case .backgroundTask(let runtime, _) = event else { continue }
                children.append(runtime)
                // Same exact-runtime guard as a chat displaying B. No ACK by this observer.
                guard runtime == "B" else { continue }
                visible.append(runtime)
            }
            return (children, visible)
        }
        _ = try await connection.relayRequest("relay.sessions.list", params: [:])
        let relay = try XCTUnwrap(connection.relaySocket)
        let cursor = await relay.acknowledgedRecoveryCursor()
        XCTAssertEqual(cursor, 4106)
        XCTAssertFalse(connection.isClosed)
        let a = await relay.retainedTasks(durable: "durable-A", runtime: "A")
        let b = await relay.retainedTasks(durable: "durable-B", runtime: "B")
        let old = await relay.retainedTasks(durable: "durable-A", runtime: "old-A")
        let wrong = await relay.retainedTasks(durable: "durable-B", runtime: "A")
        XCTAssertEqual(a?.rows.map(\.childID), ["child-A"])
        XCTAssertEqual(b?.rows.map(\.childID), ["child-B"])
        XCTAssertNil(old)
        XCTAssertNil(wrong)
        let reconciled = await relay.reconcileRetainedTasks(durable: "durable-A", runtime: "A",
                                                           statuses: ["child-A": "completed"])
        XCTAssertEqual(reconciled?.rows.first?.status, .active, "Registry silence/non-running is not terminal evidence")
        XCTAssertEqual(reconciled?.rows.first?.available, false)
        let later = await relay.retainedTasks(durable: "durable-A", runtime: "A")
        XCTAssertEqual(later, reconciled, "Registry reconciliation must update the retained shared state, not just a view copy")
        let rejectedRegistry = await relay.reconcileRetainedTasks(durable: "durable-B", runtime: "A",
                                                                 statuses: ["child-A": "completed"])
        XCTAssertNil(rejectedRegistry)
        try await host.value
        await connection.close()
        await hostSocket.close()
        let observed = await observer.value
        XCTAssertEqual(observed.all, ["A", "B"], "Rejected children never reach a view; exact runtime UI filtering remains intact")
        XCTAssertEqual(observed.visible, ["B"])
    }

    func testColdReopenRecoveredChildBecomesActiveViaRegistryAndLiveEvents() async throws {
        // Parent turn already finished on the host (resume running=false); the relay
        // snapshot carries the child's start, the registry says it still runs, and a
        // live subagent.tool frame arrives after the reattach.
        let (device, hostSocket) = InMemoryRelayTransport.pair()
        let pool = RelayConnectionPool(socketFactory: FakeRelaySocketFactory(sockets: [device]))
        let host = Task {
            let admitted = try await admit(socket: hostSocket)
            let binding: [String: Any] = ["runtime_session_id": "A", "durable_session_id": "durable-A",
                                          "profile": "default", "live": true]
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                "recovery_version": 1, "lease_id": "reopen", "last_seq": 0,
                "resume_cursor": 0, "replay_gap": false, "recovery_reset": false,
                "bindings": [binding],
                "task_snapshot": [["jsonrpc": "2.0", "method": "event", "params": [
                    "session_id": "A", "type": "subagent.start", "recovery_revision": 1,
                    "payload": ["subagent_id": "child-A", "goal": "Write a poem", "status": "running"],
                    "recovery_binding": binding]]]
            ]], socket: hostSocket, channel: admitted.channel)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                 "params": ["lease_id": "reopen", "last_seq": 0]],
                                socket: hostSocket, channel: admitted.channel)
            let request = try await readFrame(socket: hostSocket, channel: admitted.channel,
                                              reassembler: admitted.reassembler)
            func send(_ seq: Int, _ frame: [String: Any]) async throws {
                let text = String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.frame", "params": [
                    "lease_id": "reopen", "seq": seq, "replay": false, "frame": text
                ]], socket: hostSocket, channel: admitted.channel)
            }
            try await send(1, ["jsonrpc": "2.0", "id": try XCTUnwrap(request["id"]), "result": ["ok": true]])
            let second = try await readFrame(socket: hostSocket, channel: admitted.channel,
                                             reassembler: admitted.reassembler)
            try await send(2, ["jsonrpc": "2.0", "method": "event", "params": [
                "session_id": "A", "type": "subagent.tool",
                "payload": ["subagent_id": "child-A", "tool_name": "execute_code", "tool_preview": "python poem.py"]]])
            try await send(3, ["jsonrpc": "2.0", "id": try XCTUnwrap(second["id"]), "result": ["ok": true]])
        }
        let connection = try await pool.acquire(target: makeTarget(), profile: "default")
        _ = connection.start(replayBuffered: false)
        _ = try await connection.relayRequest("relay.sessions.list", params: [:])
        let relay = try XCTUnwrap(connection.relaySocket)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let recoveredTasks = await relay.retainedTasks(durable: "durable-A", runtime: "A")
        let recovered = try XCTUnwrap(recoveredTasks)
        XCTAssertEqual(recovered.rows.map(\.childID), ["child-A"])
        XCTAssertEqual(recovered.rows.first?.status, .active)
        XCTAssertEqual(recovered.activeCount(now: now), 0, "a recovered snapshot alone is not live evidence")
        XCTAssertTrue(recovered.hasUnresolvedIdentifiedChildren)
        let confirmedTasks = await relay.reconcileRetainedTasks(durable: "durable-A", runtime: "A",
                                                                statuses: ["child-A": "running"])
        let confirmed = try XCTUnwrap(confirmedTasks)
        XCTAssertEqual(confirmed.activeCount(now: now + 1), 1, "the host registry confirming the child counts as live")
        XCTAssertEqual(confirmed.rows.first?.observedAtMillis, 0, "registry polling is not worker activity")
        // The live tool frame rides the second request's turn on the host side.
        _ = try await connection.relayRequest("relay.sessions.list", params: [:])
        let observedTasks = await relay.retainedTasks(durable: "durable-A", runtime: "A")
        let observed = try XCTUnwrap(observedTasks)
        XCTAssertEqual(observed.rows.first?.action, "python poem.py")
        XCTAssertGreaterThanOrEqual(try XCTUnwrap(observed.rows.first?.observedAtMillis), now)
        XCTAssertEqual(observed.rows.first?.registryConfirmedAtMillis, confirmed.rows.first?.registryConfirmedAtMillis)
        XCTAssertEqual(observed.activeCount(now: now + 1), 1)
        try await host.value
        await connection.close()
        await hostSocket.close()
    }

    func testAutomaticRecoveryAfterResetDoesNotResumeUntilExplicitRetry() async throws {
        let (device, hostSocket) = InMemoryRelayTransport.pair()
        let pool = RelayConnectionPool(socketFactory: FakeRelaySocketFactory(sockets: [device]))
        let host = Task {
            let admitted = try await admit(socket: hostSocket)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                "recovery_version": 1, "lease_id": "reset", "last_seq": 0,
                "resume_cursor": 0, "replay_gap": false, "recovery_reset": true,
                "bindings": [["runtime_session_id": "old", "durable_session_id": "durable",
                              "profile": "default", "live": false]], "task_snapshot": []
            ]], socket: hostSocket, channel: admitted.channel)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                 "params": ["lease_id": "reset", "last_seq": 0]],
                                socket: hostSocket, channel: admitted.channel)
            var methods: [String] = []
            for _ in 0..<3 {
                let request = try await readFrame(socket: hostSocket, channel: admitted.channel,
                                                  reassembler: admitted.reassembler)
                let method = try XCTUnwrap(request["method"] as? String)
                methods.append(method)
                try await sendFrame(["jsonrpc": "2.0", "id": try XCTUnwrap(request["id"]),
                                     "result": method == "session.resume"
                                     ? ["session_id": "new", "session_key": "durable"] : ["ok": true]],
                                    socket: hostSocket, channel: admitted.channel)
            }
            return methods
        }
        let connection = try await pool.acquire(target: makeTarget(), profile: "default")
        do {
            _ = try await connection.resume(durableSessionID: "durable", profile: "default", automaticRecovery: true)
            XCTFail("Foreground recovery must not resume a historical binding")
            await connection.close()
            await hostSocket.close()
            host.cancel()
            return
        } catch { }
        _ = try await connection.relayRequest("relay.sessions.list", params: [:])
        let resumed = try await connection.resume(durableSessionID: "durable", profile: "default")
        XCTAssertEqual(resumed.runtimeSessionID, "new")
        // Correlated explicit resume installed the binding before any subsequent frames.
        _ = try await connection.relayRequest("relay.sessions.list", params: [:])
        let methods = try await host.value
        XCTAssertEqual(methods, ["relay.sessions.list", "session.resume", "relay.sessions.list"])
        let relay = try XCTUnwrap(connection.relaySocket)
        let retained = await relay.retainedTasks(durable: "durable", runtime: "new")
        XCTAssertNotNil(retained)
        await connection.close()
        await hostSocket.close()
    }

    /// One phone, several sessions: each named channel gets its own admitted
    /// socket and lease, and closing one leaves the others untouched.
    func testPoolHoldsOneConnectionPerLeaseChannel() async throws {
        let (deviceA, hostA) = InMemoryRelayTransport.pair()
        let (deviceB, hostB) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [deviceA, deviceB])
        let pool = RelayConnectionPool(socketFactory: factory)
        let target = makeTarget()

        func serve(_ socket: FakeRelaySocket, lease: String) -> Task<String?, Error> {
            Task {
                let admitted = try await admit(socket: socket)
                let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: admitted.envelope) as? [String: Any])
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                    "recovery_version": 1, "lease_id": lease, "last_seq": 0,
                    "resume_cursor": 0, "replay_gap": false, "recovery_reset": false,
                    "bindings": [], "task_snapshot": []
                ]], socket: socket, channel: admitted.channel)
                try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                     "params": ["lease_id": lease, "last_seq": 0]],
                                    socket: socket, channel: admitted.channel)
                return envelope["channel"] as? String
            }
        }
        let hostASide = serve(hostA, lease: "lease-a")
        let hostBSide = serve(hostB, lease: "lease-b")
        let a = try await pool.acquire(target: target, profile: "default", channel: "s-session-a")
        let b = try await pool.acquire(target: target, profile: "default", channel: "s-session-b")
        XCTAssertFalse(a === b)
        XCTAssertEqual(factory.requestedURLs.count, 2)
        let channelA = try await hostASide.value
        let channelB = try await hostBSide.value
        XCTAssertEqual(channelA, "s-session-a")
        XCTAssertEqual(channelB, "s-session-b")

        // Re-acquiring a channel returns its live connection; no new socket.
        let again = try await pool.acquire(target: target, profile: "default", channel: "s-session-a")
        XCTAssertTrue(again === a)
        XCTAssertEqual(factory.requestedURLs.count, 2)

        // Losing one channel's peer does not touch the other channel.
        let ended = Task { for await _ in a.start(replayBuffered: false) {} }
        await hostA.close()
        await ended.value
        XCTAssertTrue(a.isClosed)
        XCTAssertFalse(b.isClosed)
        await b.close()
        await hostB.close()
    }

    func testStaleAuxiliaryScopeCannotOpenOrSupersedeSelectedProfile() async throws {
        let factory = FakeRelaySocketFactory(sockets: [])
        let pool = RelayConnectionPool(socketFactory: factory, selectionRequired: true)
        let target = makeTarget()
        await pool.select(target: target, profile: "selected")
        do {
            _ = try await pool.acquire(target: target, profile: "stale")
            XCTFail("A stale metadata read must not change admission scope")
        } catch is CancellationError {
            XCTAssertTrue(factory.requestedURLs.isEmpty)
        }
        await pool.select(target: nil, profile: "selected")
        do {
            _ = try await pool.acquire(target: target, profile: "selected")
            XCTFail("A late reader must not reopen a disconnected account")
        } catch is CancellationError {
            XCTAssertTrue(factory.requestedURLs.isEmpty)
        }
    }

    /// The router's 4004 close means no host is attached, not a refused device.
    func testRouterNoHostCloseIsDistinctFromHostRefusal() async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        device.closeCodeOverride = relayCloseNoHost
        let factory = FakeRelaySocketFactory(sockets: [device])
        let hostTask = Task {
            _ = try? await host.receive()
            await host.close()
        }
        do {
            _ = try await RelayConnector.connect(
                target: makeTarget(), profile: "default", socketFactory: factory
            )
            XCTFail("expected noHost")
        } catch {
            XCTAssertEqual(error as? RelayConnectionError, .noHost)
        }
        await hostTask.value
    }

    /// A router token renewed in the attach preamble reaches the sink once.
    func testRenewedRoutingTokenFromAttachPreambleReachesTheSink() async throws {
        let (device, hostSocket) = InMemoryRelayTransport.pair()
        let pool = RelayConnectionPool(socketFactory: FakeRelaySocketFactory(sockets: [device]))
        let renewed = RenewedTokens()
        await pool.setRoutingTokenSink { target, token in await renewed.record(target.id, token) }
        let target = makeTarget()
        let host = Task {
            let admitted = try await admit(socket: hostSocket)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.attached", "params": [
                "recovery_version": 1, "lease_id": "lease", "last_seq": 0, "resume_cursor": 0,
                "replay_gap": false, "recovery_reset": false, "bindings": [], "task_snapshot": [],
                "relay_token": "renewed-token-1"
            ]], socket: hostSocket, channel: admitted.channel)
            try await sendFrame(["jsonrpc": "2.0", "method": "relay.lease.replay_complete",
                                 "params": ["lease_id": "lease", "last_seq": 0]],
                                socket: hostSocket, channel: admitted.channel)
        }
        let connection = try await pool.acquire(target: target, profile: "default")
        try await host.value
        let seen = await renewed.entries
        XCTAssertEqual(seen.map(\.0), [target.id])
        XCTAssertEqual(seen.map(\.1), ["renewed-token-1"])
        await connection.close()
        await hostSocket.close()
    }

    private actor RenewedTokens {
        var entries: [(UUID, String)] = []
        func record(_ id: UUID, _ token: String) { entries.append((id, token)) }
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
