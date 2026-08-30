import CryptoKit
import XCTest
@testable import Mercury

// MARK: - In-memory transport fakes

/// One direction of an in-memory socket pair. Single-consumer, like the
/// production URLSession socket.
final class FakeRelaySocket: RelayBinarySocketing, @unchecked Sendable {
    private var inbound: AsyncStream<Data>.AsyncIterator
    private let outbound: AsyncStream<Data>.Continuation
    private(set) var closed = false

    fileprivate init(
        inbound: AsyncStream<Data>.AsyncIterator,
        outbound: AsyncStream<Data>.Continuation
    ) {
        self.inbound = inbound
        self.outbound = outbound
    }

    func send(_ data: Data) async throws {
        outbound.yield(data)
    }

    func receive() async throws -> Data? {
        await inbound.next()
    }

    func close() async {
        closed = true
        outbound.finish()
    }

    func lastCloseCode() -> Int? { nil }
    func lastErrorDetail() -> String? { nil }
}

enum InMemoryRelayTransport {
    /// Builds a connected device/host socket pair.
    static func pair() -> (device: FakeRelaySocket, host: FakeRelaySocket) {
        var deviceContinuation: AsyncStream<Data>.Continuation!
        let toDevice = AsyncStream<Data> { deviceContinuation = $0 }
        var hostContinuation: AsyncStream<Data>.Continuation!
        let toHost = AsyncStream<Data> { hostContinuation = $0 }
        let device = FakeRelaySocket(
            inbound: toDevice.makeAsyncIterator(), outbound: hostContinuation
        )
        let host = FakeRelaySocket(
            inbound: toHost.makeAsyncIterator(), outbound: deviceContinuation
        )
        return (device, host)
    }
}

final class FakeRelaySocketFactory: RelayBinarySocketFactorying, @unchecked Sendable {
    private(set) var requestedURLs: [String] = []
    private var sockets: [FakeRelaySocket]

    init(sockets: [FakeRelaySocket]) {
        self.sockets = sockets
    }

    func connect(url: String) async throws -> any RelayBinarySocketing {
        requestedURLs.append(url)
        guard !sockets.isEmpty else { throw RelayConnectionError.offline }
        return sockets.removeFirst()
    }
}

final class InMemoryRelayTargetPersistence: RelayTargetPersisting, @unchecked Sendable {
    var stored: Data?
    var failWrites = false

    func readRelayTargetData() throws -> Data? { stored }

    func writeRelayTargetData(_ data: Data) throws {
        if failWrites { throw RelayTargetStoreError.persistenceFailed }
        stored = data
    }
}

// MARK: - Shared test host

enum TestRelayHost {
    static let installationID = Data((0..<32).map { UInt8($0 &+ 0x80) })
    static let hostStaticPrivateKey = Data((0..<32).map { UInt8($0 &+ 0x20) })
    static var hostStaticPublicKey: Data {
        try! RelaySecureChannel.publicKey(forPrivateKey: hostStaticPrivateKey)
    }

    static func responderChannel() throws -> RelaySecureChannel {
        try RelaySecureChannel(
            isInitiator: false,
            staticPrivateKey: hostStaticPrivateKey,
            installationID: installationID,
            remoteStaticPublicKey: nil,
            deterministicEphemeralPrivateKey: nil
        )
    }

    /// Serves one pairing connection: full XK, returns the delivered
    /// capability, sends the encrypted acknowledgement, closes.
    static func servePairing(
        socket: FakeRelaySocket,
        deviceID: String
    ) async throws -> (capability: Data, channelBinding: Data) {
        let channel = try responderChannel()
        let firstRaw = try await socket.receive()
        let first = try XCTUnwrap(firstRaw)
        _ = try channel.readHandshake(first)
        try await socket.send(channel.writeHandshake())
        let thirdRaw = try await socket.receive()
        let third = try XCTUnwrap(thirdRaw)
        let capability = try channel.readHandshake(third)
        let ack = Data(
            "{\"device_id\":\"\(deviceID)\",\"type\":\"pairing.pending\"}".utf8
        )
        try await socket.send(channel.encrypt(ack))
        let binding = try channel.channelBinding
        await socket.close()
        return (capability, binding)
    }

    static func qrPayloadText(
        capability: Data = Data((0..<32).map { UInt8($0 &+ 0xA0) }),
        origin: String = "https://relay.example.com",
        expires: Int64
    ) -> String {
        // Matches the plugin's compact sorted-key JSON encoding exactly.
        return "{\"c\":\"\(capability.base64EncodedString())\","
            + "\"i\":\"\(installationID.base64EncodedString())\","
            + "\"k\":\"\(hostStaticPublicKey.base64EncodedString())\","
            + "\"o\":\"\(origin)\","
            + "\"s\":\"mercury-relay\","
            + "\"v\":1,\"x\":\(expires)}"
    }
}

// MARK: - Tests

final class RelayPairingTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_756_400_000)

    private func validQRText(expiresIn seconds: Int64 = 300) -> String {
        TestRelayHost.qrPayloadText(expires: Int64(now.timeIntervalSince1970) + seconds)
    }

    // MARK: QR payload validation

    func testValidQRPayloadParses() throws {
        let payload = try RelayPairingPayload.parse(validQRText(), now: now)
        XCTAssertEqual(payload.installationID, TestRelayHost.installationID)
        XCTAssertEqual(payload.hostPublicKey, TestRelayHost.hostStaticPublicKey)
        XCTAssertEqual(payload.capability.count, 32)
        XCTAssertEqual(
            payload.deviceSocketURL,
            "wss://relay.example.com/v1/device/"
                + RelayBase64.urlSafeEncode(TestRelayHost.installationID)
        )
    }

    func testQRPayloadRejectionsAreDistinct() {
        func parseError(_ text: String) -> RelayProtocolError? {
            do {
                _ = try RelayPairingPayload.parse(text, now: now)
                return nil
            } catch {
                return error as? RelayProtocolError
            }
        }

        XCTAssertEqual(parseError("not json"), .malformedPayload)
        XCTAssertEqual(parseError("{}"), .malformedPayload)
        XCTAssertEqual(
            parseError(validQRText().replacingOccurrences(
                of: "mercury-relay", with: "other-scheme"
            )),
            .unsupportedScheme
        )
        XCTAssertEqual(
            parseError(validQRText().replacingOccurrences(of: "\"v\":1", with: "\"v\":2")),
            .unsupportedVersion
        )
        XCTAssertEqual(
            parseError(validQRText().replacingOccurrences(
                of: "\"o\":\"https://relay.example.com\"", with: "\"o\":null"
            )),
            .missingRelayOrigin
        )
        XCTAssertEqual(
            parseError(TestRelayHost.qrPayloadText(
                expires: Int64(now.timeIntervalSince1970) - 1
            )),
            .expiredOffer
        )
        // An expiry beyond any legal offer TTL is malformed, not just stale.
        XCTAssertEqual(
            parseError(TestRelayHost.qrPayloadText(
                expires: Int64(now.timeIntervalSince1970) + 100_000
            )),
            .malformedPayload
        )
        // Wrong field sizes fail before any network use.
        XCTAssertEqual(
            parseError(validQRText().replacingOccurrences(
                of: TestRelayHost.installationID.base64EncodedString(),
                with: Data((0..<16).map { UInt8($0) }).base64EncodedString()
            )),
            .malformedPayload
        )
        // Origins with paths, query, or trailing slash are rejected.
        XCTAssertEqual(
            parseError(validQRText().replacingOccurrences(
                of: "https://relay.example.com", with: "https://relay.example.com/path"
            )),
            .malformedPayload
        )
        XCTAssertEqual(
            parseError(validQRText().replacingOccurrences(
                of: "https://relay.example.com", with: "http://relay.example.com"
            )),
            .malformedPayload
        )
    }

    func testOriginNormalization() {
        XCTAssertEqual(
            RelayPairingPayload.normalizeOrigin("https://relay.example.com"),
            "wss://relay.example.com"
        )
        XCTAssertEqual(
            RelayPairingPayload.normalizeOrigin("wss://relay.example.com:8443"),
            "wss://relay.example.com:8443"
        )
        XCTAssertNil(RelayPairingPayload.normalizeOrigin("https://relay.example.com/"))
        XCTAssertNil(RelayPairingPayload.normalizeOrigin("https://user@relay.example.com"))
        XCTAssertNil(RelayPairingPayload.normalizeOrigin("https://relay.example.com?x=1"))
        XCTAssertNil(RelayPairingPayload.normalizeOrigin("ws://relay.example.com"))
    }

    // MARK: URL-safe base64

    func testURLSafeBase64RouteEncoding() {
        let route = RelayBase64.urlSafeEncode(TestRelayHost.installationID)
        XCTAssertEqual(route.count, 43) // matches the router's path regex
        XCTAssertFalse(route.contains("="))
        XCTAssertEqual(
            RelayBase64.urlSafeDecodeExact(route, count: 32),
            TestRelayHost.installationID
        )
        XCTAssertNil(RelayBase64.urlSafeDecodeExact(route + "=", count: 32))
        XCTAssertNil(RelayBase64.urlSafeDecodeExact(String(route.dropLast()), count: 32))
    }

    // MARK: Admission envelope and pairing ack

    func testAdmissionEnvelopeIsByteExactSortedASCII() throws {
        let deviceID = RelayBase64.urlSafeEncode(Data((0..<16).map { UInt8($0) }))
        let fresh = try RelayAdmissionEnvelope.controllerOpen(
            deviceID: deviceID, profile: "default"
        )
        XCTAssertEqual(
            String(data: fresh, encoding: .utf8),
            "{\"device_id\":\"\(deviceID)\",\"profile\":\"default\",\"type\":\"controller.open\"}"
        )
        let resuming = try RelayAdmissionEnvelope.controllerOpen(
            deviceID: deviceID, profile: "default", resumeCursor: 42
        )
        XCTAssertEqual(
            String(data: resuming, encoding: .utf8),
            "{\"device_id\":\"\(deviceID)\",\"profile\":\"default\","
                + "\"resume_cursor\":42,\"type\":\"controller.open\"}"
        )
        XCTAssertThrowsError(
            try RelayAdmissionEnvelope.controllerOpen(deviceID: "not!valid", profile: "default")
        )
        XCTAssertThrowsError(
            try RelayAdmissionEnvelope.controllerOpen(deviceID: deviceID, profile: "bad profile")
        )
        XCTAssertThrowsError(
            try RelayAdmissionEnvelope.controllerOpen(
                deviceID: deviceID, profile: "default", resumeCursor: -1
            )
        )
    }

    func testPairingAckParsing() throws {
        let deviceID = RelayBase64.urlSafeEncode(Data((0..<16).map { UInt8($0 &+ 7) }))
        let valid = Data("{\"device_id\":\"\(deviceID)\",\"type\":\"pairing.pending\"}".utf8)
        XCTAssertEqual(try RelayPairingAck.parse(valid), deviceID)

        XCTAssertThrowsError(try RelayPairingAck.parse(Data("{}".utf8)))
        XCTAssertThrowsError(try RelayPairingAck.parse(
            Data("{\"device_id\":\"\(deviceID)\",\"type\":\"other\"}".utf8)
        ))
        XCTAssertThrowsError(try RelayPairingAck.parse(
            Data("{\"device_id\":\"short\",\"type\":\"pairing.pending\"}".utf8)
        ))
        XCTAssertThrowsError(try RelayPairingAck.parse(
            Data("{\"device_id\":\"\(deviceID)\",\"extra\":1,\"type\":\"pairing.pending\"}".utf8)
        ))
    }

    func testFingerprintMatchesHostDerivation() {
        let binding = Data((0..<32).map { UInt8($0) })
        let expected = SHA256.hash(data: binding)
            .map { String(format: "%02x", $0) }
            .joined()
            .prefix(16)
        XCTAssertEqual(
            RelayFingerprint.shortAuthenticationString(channelBinding: binding),
            String(expected)
        )
    }

    // MARK: Target store

    private func makeTarget(id: UUID = UUID(), deviceIDByte: UInt8 = 1) -> RelayPairedTarget {
        RelayPairedTarget(
            id: id,
            label: "",
            relayOrigin: "https://relay.example.com",
            installationID: TestRelayHost.installationID,
            hostPublicKey: TestRelayHost.hostStaticPublicKey,
            deviceID: RelayBase64.urlSafeEncode(Data(repeating: deviceIDByte, count: 16)),
            deviceStaticPrivateKey: Data(repeating: 9, count: 32),
            fingerprint: String(repeating: "a", count: 16),
            status: .pending,
            createdAtEpochSeconds: 1,
            lastUsedEpochSeconds: nil
        )
    }

    func testTargetStoreRoundTripApprovalAndRemoval() async throws {
        let persistence = InMemoryRelayTargetPersistence()
        let store = RelayTargetStore(persistence: persistence, now: { self.now })
        let target = makeTarget()
        try await store.add(target)

        // A fresh store instance reads back the identical record.
        let reloaded = RelayTargetStore(persistence: persistence, now: { self.now })
        let loaded1 = try await reloaded.load()
        XCTAssertEqual(loaded1, [target])

        try await store.markApproved(id: target.id)
        let approved = try await store.load()
        XCTAssertEqual(approved.first?.status, .approved)

        try await store.remove(id: target.id)
        let loaded2 = try await store.load()
        XCTAssertEqual(loaded2, [])
        // Removal persisted, not just cached.
        let afterRemoval = RelayTargetStore(persistence: persistence, now: { self.now })
        let loaded3 = try await afterRemoval.load()
        XCTAssertEqual(loaded3, [])
    }

    func testTargetStoreBoundsAndFailClosed() async throws {
        let persistence = InMemoryRelayTargetPersistence()
        let store = RelayTargetStore(persistence: persistence, now: { self.now })
        for index in 0..<RelayTargetPolicy.maxTargets {
            try await store.add(makeTarget(deviceIDByte: UInt8(index + 1)))
        }
        do {
            try await store.add(makeTarget(deviceIDByte: 99))
            XCTFail("expected targetLimitReached")
        } catch {
            XCTAssertEqual(error as? RelayTargetStoreError, .targetLimitReached)
        }

        // Corrupt state fails closed instead of silently discarding keys.
        persistence.stored = Data("corrupt".utf8)
        let corrupt = RelayTargetStore(persistence: persistence, now: { self.now })
        do {
            _ = try await corrupt.load()
            XCTFail("expected corruptState")
        } catch {
            XCTAssertEqual(error as? RelayTargetStoreError, .corruptState)
        }
    }

    // MARK: Pairing coordinator end-to-end

    func testPairingScansValidatesHandshakesAndStoresPendingTarget() async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device])
        let persistence = InMemoryRelayTargetPersistence()
        let store = RelayTargetStore(persistence: persistence, now: { self.now })
        let deviceKey = Data((0..<32).map { UInt8($0 &+ 0x40) })
        let coordinator = RelayPairingCoordinator(
            socketFactory: factory,
            store: store,
            makeDeviceKey: { deviceKey },
            now: { self.now }
        )
        let assignedDeviceID = RelayBase64.urlSafeEncode(
            Data((0..<16).map { UInt8($0 &+ 3) })
        )

        async let hostSide = TestRelayHost.servePairing(
            socket: host, deviceID: assignedDeviceID
        )
        let qrCapability = Data((0..<32).map { UInt8($0 &+ 0xA0) })
        let target = try await coordinator.pair(scannedText: validQRText())
        let served = try await hostSide

        // The capability traveled only as the encrypted final payload.
        XCTAssertEqual(served.capability, qrCapability)
        XCTAssertEqual(target.deviceID, assignedDeviceID)
        XCTAssertEqual(target.status, .pending)
        XCTAssertEqual(
            target.fingerprint,
            RelayFingerprint.shortAuthenticationString(channelBinding: served.channelBinding)
        )
        XCTAssertEqual(target.deviceStaticPrivateKey, deviceKey)
        XCTAssertEqual(
            factory.requestedURLs,
            ["wss://relay.example.com/v1/device/"
                + RelayBase64.urlSafeEncode(TestRelayHost.installationID)]
        )
        // Persisted as scanned.
        let loaded4 = try await store.load()
        XCTAssertEqual(loaded4, [target])
    }

    func testPairingRejectionWithoutAckIsOfferRejected() async throws {
        let (device, host) = InMemoryRelayTransport.pair()
        let factory = FakeRelaySocketFactory(sockets: [device])
        let store = RelayTargetStore(
            persistence: InMemoryRelayTargetPersistence(), now: { self.now }
        )
        let coordinator = RelayPairingCoordinator(
            socketFactory: factory,
            store: store,
            makeDeviceKey: { Data(repeating: 5, count: 32) },
            now: { self.now }
        )

        // Host completes the handshake but refuses the offer: close, no ack.
        let hostTask = Task {
            let channel = try TestRelayHost.responderChannel()
            let firstRaw = try await host.receive()
            let first = try XCTUnwrap(firstRaw)
            _ = try channel.readHandshake(first)
            try await host.send(channel.writeHandshake())
            let thirdRaw = try await host.receive()
            _ = try channel.readHandshake(try XCTUnwrap(thirdRaw))
            await host.close()
        }
        do {
            _ = try await coordinator.pair(scannedText: validQRText())
            XCTFail("expected offerRejected")
        } catch {
            XCTAssertEqual(error as? RelayPairingError, .offerRejected)
        }
        try await hostTask.value
        let loaded5 = try await store.load()
        XCTAssertEqual(loaded5, [])
    }

    func testExpiredQRNeverTouchesTheNetwork() async throws {
        let factory = FakeRelaySocketFactory(sockets: [])
        let coordinator = RelayPairingCoordinator(
            socketFactory: factory,
            store: RelayTargetStore(
                persistence: InMemoryRelayTargetPersistence(), now: { self.now }
            ),
            makeDeviceKey: { Data(repeating: 5, count: 32) },
            now: { self.now }
        )
        do {
            _ = try await coordinator.pair(
                scannedText: TestRelayHost.qrPayloadText(
                    expires: Int64(now.timeIntervalSince1970) - 10
                )
            )
            XCTFail("expected expiredOffer")
        } catch {
            XCTAssertEqual(error as? RelayPairingError, .expiredOffer)
        }
        XCTAssertEqual(factory.requestedURLs, [])
    }
}
