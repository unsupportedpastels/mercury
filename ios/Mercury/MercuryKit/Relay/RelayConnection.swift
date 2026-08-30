import Foundation

// MARK: - Binary socket seams

/// Minimal binary message transport toward the hosted router. Returning nil
/// from `receive` means the peer (router or host) closed the socket.
protocol RelayBinarySocketing: Sendable {
    func send(_ data: Data) async throws
    func receive() async throws -> Data?
    func close() async
    /// WebSocket close code observed after the peer/router closed the socket,
    /// for diagnostics (e.g. 4001 superseded, 4009 host gone). nil if unknown.
    func lastCloseCode() -> Int?
    /// Underlying transport error (domain#code) if receive/send failed, for
    /// diagnostics. nil if the socket closed cleanly.
    func lastErrorDetail() -> String?
}

protocol RelayBinarySocketFactorying: Sendable {
    func connect(url: String) async throws -> any RelayBinarySocketing
}

/// URLSession-backed binary WebSocket toward `wss://<relay>/v1/device/…`.
final class URLSessionRelaySocketFactory: RelayBinarySocketFactorying, @unchecked Sendable {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func connect(url: String) async throws -> any RelayBinarySocketing {
        guard let socketURL = URL(string: url),
              socketURL.scheme?.lowercased() == "wss",
              socketURL.host != nil
        else { throw RelayConnectionError.offline }
        let task = session.webSocketTask(with: socketURL)
        // The URLSession default (1 MiB) comfortably covers the 65,535-byte
        // Noise record cap; do not shrink it below that.
        task.maximumMessageSize = 1 << 20
        task.resume()
        return URLSessionRelaySocket(task: task)
    }
}

private final class URLSessionRelaySocket: RelayBinarySocketing, @unchecked Sendable {
    private let task: URLSessionWebSocketTask
    private let errorLock = NSLock()
    private var errorDetail: String?

    init(task: URLSessionWebSocketTask) {
        self.task = task
    }

    private func record(_ error: Error) {
        let ns = error as NSError
        errorLock.lock()
        errorDetail = "\(ns.domain)#\(ns.code)"
        errorLock.unlock()
    }

    func send(_ data: Data) async throws {
        do {
            try await task.send(.data(data))
        } catch let error as CancellationError {
            throw error
        } catch {
            record(error)
            // Ciphertext must never appear in errors.
            throw RelayConnectionError.offline
        }
    }

    func receive() async throws -> Data? {
        do {
            switch try await task.receive() {
            case let .data(data):
                return data
            case .string:
                // The router only ever forwards binary ciphertext to the
                // device role; text is a protocol violation.
                throw RelayConnectionError.protocolViolation
            @unknown default:
                throw RelayConnectionError.protocolViolation
            }
        } catch let error as RelayConnectionError {
            throw error
        } catch {
            record(error)
            // Peer close and receive errors share nil, like ChatSocketing.
            return nil
        }
    }

    func close() async {
        task.cancel(with: .normalClosure, reason: nil)
    }

    func lastCloseCode() -> Int? {
        let code = task.closeCode.rawValue
        return code == 0 ? nil : code
    }

    func lastErrorDetail() -> String? {
        errorLock.lock()
        defer { errorLock.unlock() }
        return errorDetail
    }
}

// MARK: - Connection errors

/// Distinct, user-explainable relay connection outcomes (PROTOCOL §11:
/// offline is distinct from revoked/unapproved and from protocol failure).
enum RelayConnectionError: Error, Equatable {
    /// The relay could not be reached, refused the socket (for example no
    /// host is attached), or dropped mid-exchange.
    case offline
    /// The host closed the channel where the protocol required a message:
    /// admission was refused (device pending, denied, or revoked).
    case notAuthorized
    /// Cryptographic or framing failure; never retried silently.
    case protocolViolation
    /// The pairing offer was rejected (consumed, expired host-side, or
    /// capability mismatch).
    case pairingRejected
}

// MARK: - Established connection

/// One admitted device channel: the Noise transport plus its framing state.
/// Created only through `RelayConnector.connect`, which completes the fresh
/// XK handshake and sends the admission envelope (PROTOCOL §7).
final class RelayConnectedChannel: @unchecked Sendable {
    let socket: any RelayBinarySocketing
    let channel: RelaySecureChannel
    let channelBinding: Data

    fileprivate init(
        socket: any RelayBinarySocketing,
        channel: RelaySecureChannel,
        channelBinding: Data
    ) {
        self.socket = socket
        self.channel = channel
        self.channelBinding = channelBinding
    }
}

enum RelayConnector {
    /// Dials the device route, runs a complete fresh XK handshake with an
    /// empty final payload, and sends the encrypted `controller.open`
    /// admission envelope. Authorization is proven by the host accepting
    /// subsequent traffic; a pending/revoked device sees the socket close.
    static func connect(
        target: RelayPairedTarget,
        profile: String,
        resumeCursor: Int64? = nil,
        socketFactory: any RelayBinarySocketFactorying = URLSessionRelaySocketFactory()
    ) async throws -> RelayConnectedChannel {
        guard let url = RelayPairingPayload.deviceSocketURL(
            relayOrigin: target.relayOrigin,
            installationID: target.installationID
        ) else { throw RelayConnectionError.protocolViolation }
        let socket = try await socketFactory.connect(url: url)
        do {
            let channel = try RelaySecureChannel(
                initiatorStaticPrivateKey: target.deviceStaticPrivateKey,
                installationID: target.installationID,
                hostStaticPublicKey: target.hostPublicKey
            )
            try await socket.send(channel.writeHandshake())
            guard let second = try await socket.receive() else {
                throw RelayConnectionError.notAuthorized
            }
            _ = try channel.readHandshake(second)
            try await socket.send(channel.writeHandshake())

            let envelope = try RelayAdmissionEnvelope.controllerOpen(
                deviceID: target.deviceID,
                profile: profile,
                resumeCursor: resumeCursor
            )
            try await socket.send(channel.encrypt(envelope))
            return RelayConnectedChannel(
                socket: socket,
                channel: channel,
                channelBinding: try channel.channelBinding
            )
        } catch let error as RelayConnectionError {
            await socket.close()
            throw error
        } catch is RelaySecureChannelError {
            await socket.close()
            throw RelayConnectionError.protocolViolation
        } catch let error as RelayProtocolError {
            await socket.close()
            throw error
        } catch {
            await socket.close()
            throw RelayConnectionError.offline
        }
    }
}

// MARK: - Chat socket adapter

/// Carries exact Hermes JSON-RPC text frames through the secure channel as
/// framed kind-1 records (PROTOCOL §7), conforming to the same `ChatSocketing`
/// seam the direct WebSocket uses so `ChatConnection` is transport-agnostic.
actor RelayChatSocket: ChatSocketing {
    private let socket: any RelayBinarySocketing
    private let channel: RelaySecureChannel
    private let channelID: Data
    private let reassembler: RelayFrameReassembler
    private let randomMessageID: @Sendable () -> Data
    private var closed = false

    /// Router/host close code seen on the underlying socket, for diagnostics.
    nonisolated func lastCloseCode() -> Int? { socket.lastCloseCode() }
    nonisolated func lastErrorDetail() -> String? { socket.lastErrorDetail() }

    init(
        connected: RelayConnectedChannel,
        randomMessageID: @escaping @Sendable () -> Data = {
            Data((0..<RelayFraming.messageIDSize).map { _ in UInt8.random(in: 0...255) })
        }
    ) {
        socket = connected.socket
        channel = connected.channel
        // PROTOCOL §7: the record channel ID is the first 16 bytes of the
        // Noise channel binding, computed independently by both endpoints.
        channelID = connected.channelBinding.prefix(RelayFraming.channelIDSize)
        reassembler = RelayFrameReassembler(channelID: channelID)
        self.randomMessageID = randomMessageID
    }

    func sendText(_ text: String) async throws {
        guard !closed else { throw ChatError.transport("Mercury Relay channel is closed") }
        do {
            let records = try RelayFraming.encodeMessage(
                channelID: channelID,
                messageID: randomMessageID(),
                payload: Data(text.utf8)
            )
            for record in records {
                try await socket.send(try channel.encrypt(record))
            }
        } catch let error as CancellationError {
            throw error
        } catch {
            // Frame text can contain prompts or tool output; never echo it.
            throw ChatError.transport("Could not send a Mercury Relay chat frame")
        }
    }

    func receiveText() async throws -> String? {
        while true {
            guard !closed else { return nil }
            let ciphertext: Data?
            do {
                ciphertext = try await socket.receive()
            } catch {
                throw ChatError.transport("Mercury Relay connection failed")
            }
            guard let ciphertext else { return nil }
            do {
                let record = try channel.decrypt(ciphertext)
                guard let message = try reassembler.push(record) else { continue }
                guard let text = String(data: message, encoding: .utf8) else {
                    throw ChatError.protocolError("Mercury Relay frame was not valid UTF-8")
                }
                return text
            } catch let error as ChatError {
                throw error
            } catch {
                // Decrypt/framing failures are terminal for the channel
                // (PROTOCOL §10): fail closed rather than resynchronize.
                await close()
                throw ChatError.protocolError("Mercury Relay channel failed")
            }
        }
    }

    func close() async {
        guard !closed else { return }
        closed = true
        channel.close()
        await socket.close()
    }
}
