import Foundation

/// Distinct pairing outcomes for the pairing UI (plan Task 8: expiry,
/// consumed offer, cancellation, offline, and incompatible-version states
/// must be distinguishable and never embed QR bytes).
enum RelayPairingError: Error, Equatable {
    case malformedQR
    case unsupportedVersion
    case missingRelayOrigin
    case expiredOffer
    case offerRejected
    case offline
    case protocolViolation
    case storageFailed
    case targetLimitReached
}

/// Orchestrates one QR pairing: validate the payload, generate a fresh device
/// identity, run the XK handshake carrying the one-time capability as the
/// encrypted final payload, read the host's pairing acknowledgement for the
/// assigned device_id, and persist the pending target.
///
/// The scanned payload text and the capability live only for the duration of
/// `pair`; nothing secret is returned, logged, or persisted beyond the target
/// record's own key material.
actor RelayPairingCoordinator {
    private let socketFactory: any RelayBinarySocketFactorying
    private let store: RelayTargetStore
    private let makeDeviceKey: @Sendable () -> Data
    private let now: @Sendable () -> Date

    init(
        socketFactory: any RelayBinarySocketFactorying = URLSessionRelaySocketFactory(),
        store: RelayTargetStore = RelayTargetStore(),
        makeDeviceKey: @escaping @Sendable () -> Data = {
            Data((0..<RelaySecureChannelPolicy.keyBytes).map { _ in UInt8.random(in: 0...255) })
        },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.socketFactory = socketFactory
        self.store = store
        self.makeDeviceKey = makeDeviceKey
        self.now = now
    }

    /// Pairs from one scanned QR payload. Returns the persisted **pending**
    /// target; the caller displays `target.fingerprint` and waits for the
    /// host operator's explicit approval before the target becomes usable.
    func pair(scannedText: String) async throws -> RelayPairedTarget {
        let payload: RelayPairingPayload
        do {
            payload = try RelayPairingPayload.parse(scannedText, now: now())
        } catch let error as RelayProtocolError {
            switch error {
            case .unsupportedScheme, .malformedPayload, .invalidEnvelope:
                throw RelayPairingError.malformedQR
            case .unsupportedVersion:
                throw RelayPairingError.unsupportedVersion
            case .missingRelayOrigin:
                throw RelayPairingError.missingRelayOrigin
            case .expiredOffer:
                throw RelayPairingError.expiredOffer
            }
        }

        // Refuse to start a pairing that could never be stored.
        let existing: [RelayPairedTarget]
        do {
            existing = try await store.load()
        } catch {
            throw RelayPairingError.storageFailed
        }
        guard existing.count < RelayTargetPolicy.maxTargets else {
            throw RelayPairingError.targetLimitReached
        }

        guard let url = payload.deviceSocketURL else {
            throw RelayPairingError.malformedQR
        }
        let deviceKey = makeDeviceKey()

        let socket: any RelayBinarySocketing
        do {
            socket = try await socketFactory.connect(url: url)
        } catch {
            throw RelayPairingError.offline
        }

        let fingerprint: String
        let deviceID: String
        do {
            let channel = try RelaySecureChannel(
                initiatorStaticPrivateKey: deviceKey,
                installationID: payload.installationID,
                hostStaticPublicKey: payload.hostPublicKey
            )
            try await socket.send(channel.writeHandshake())
            guard let second = try await socket.receive() else {
                throw RelayPairingError.offline
            }
            _ = try channel.readHandshake(second)
            // The capability travels only as the encrypted final handshake
            // payload (PROTOCOL §4/§5); it never appears on the wire in clear.
            try await socket.send(channel.writeHandshake(payload: payload.capability))

            // PROTOCOL §4 step 7: one encrypted acknowledgement carries the
            // host-assigned device_id. A close instead means the host refused
            // the offer (consumed, expired host-side, or mismatched).
            guard let ackCiphertext = try await socket.receive() else {
                throw RelayPairingError.offerRejected
            }
            deviceID = try RelayPairingAck.parse(try channel.decrypt(ackCiphertext))
            fingerprint = RelayFingerprint.shortAuthenticationString(
                channelBinding: try channel.channelBinding
            )
            channel.close()
            await socket.close()
        } catch let error as RelayPairingError {
            await socket.close()
            throw error
        } catch is RelaySecureChannelError {
            await socket.close()
            throw RelayPairingError.protocolViolation
        } catch is RelayProtocolError {
            await socket.close()
            throw RelayPairingError.protocolViolation
        } catch {
            await socket.close()
            throw RelayPairingError.offline
        }

        let target = RelayPairedTarget(
            id: UUID(),
            label: "",
            relayOrigin: payload.relayOrigin,
            installationID: payload.installationID,
            hostPublicKey: payload.hostPublicKey,
            deviceID: deviceID,
            deviceStaticPrivateKey: deviceKey,
            fingerprint: fingerprint,
            status: .pending,
            createdAtEpochSeconds: max(0, Int64(now().timeIntervalSince1970)),
            lastUsedEpochSeconds: nil
        )
        do {
            try await store.add(target)
        } catch RelayTargetStoreError.targetLimitReached {
            throw RelayPairingError.targetLimitReached
        } catch {
            throw RelayPairingError.storageFailed
        }
        return target
    }

    /// One approval probe: a fresh handshake plus admission envelope, proven
    /// by a `gateway.ping` round trip. Pending or revoked devices see the
    /// host close the socket instead.
    func probeApproval(target: RelayPairedTarget, profile: String) async -> Bool {
        do {
            let connected = try await RelayConnector.connect(
                target: target,
                profile: profile,
                socketFactory: socketFactory
            )
            let chatSocket = RelayChatSocket(connected: connected)
            defer { Task { await chatSocket.close() } }
            try await chatSocket.sendText(
                #"{"jsonrpc":"2.0","id":"pairing-probe","method":"gateway.ping","params":{}}"#
            )
            guard let reply = try await chatSocket.receiveText() else { return false }
            _ = reply
            try? await store.markApproved(id: target.id)
            return true
        } catch {
            return false
        }
    }
}
