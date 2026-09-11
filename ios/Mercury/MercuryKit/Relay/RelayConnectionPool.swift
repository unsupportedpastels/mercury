import Foundation

/// Authenticated relay admissions, one per lease channel, borrowed by chat and
/// auxiliary readers. No metadata view can displace or close a live retained
/// controller, and no chat can displace another chat's channel.
actor RelayConnectionPool {
    static let shared = RelayConnectionPool(selectionRequired: true)
    private struct Scope: Equatable {
        let target: RelayPairedTarget
        let profile: String
        static func == (lhs: Scope, rhs: Scope) -> Bool {
            lhs.profile == rhs.profile && lhs.target.id == rhs.target.id
                && lhs.target.relayOrigin == rhs.target.relayOrigin
                && lhs.target.installationID == rhs.target.installationID
                && lhs.target.deviceID == rhs.target.deviceID
                && lhs.target.deviceStaticPrivateKey == rhs.target.deviceStaticPrivateKey
                && lhs.target.hostPublicKey == rhs.target.hostPublicKey
                && lhs.target.relayRoutingToken == rhs.target.relayRoutingToken
        }
    }
    /// One admitted controller per lease channel. The default channel (nil)
    /// serves auxiliary readers and metadata; each open chat owns its own
    /// channel, so several sessions stay open on one phone at once. The host
    /// keeps one lease per (device, channel); the router multiplexes sockets.
    private final class Slot {
        var scope: Scope?
        var connection: ChatConnection?
        var checkpoint = RelayRecoveryCheckpoint()
        var taskOwner = RelayTaskOwner()
        var opening: Task<ChatConnection, Error>?
        var generation = UUID()
    }
    private var pushConnectionSink: (@Sendable (RelayPairedTarget, ChatConnection) async -> Void)?

    func setPushConnectionSink(_ sink: @escaping @Sendable (RelayPairedTarget, ChatConnection) async -> Void) {
        pushConnectionSink = sink
    }

    private var selectedScope: Scope?
    private var selectionRequired: Bool
    private var selecting = false
    private var slots: [String: Slot] = [:]
    private var selectionGeneration = UUID()
    private let socketFactory: any RelayBinarySocketFactorying
    /// Receives a router token the host renewed inside the attach preamble.
    private var routingTokenSink: (@Sendable (RelayPairedTarget, String) async -> Void)?

    func setRoutingTokenSink(_ sink: (@Sendable (RelayPairedTarget, String) async -> Void)?) {
        routingTokenSink = sink
    }

    /// Receives every durable background-task state a pooled reader rewrites:
    /// (target, profile, durable session id, state). Delivered off the actor.
    typealias TaskSink = @Sendable (RelayPairedTarget, String, String, BackgroundTasks) -> Void
    private var taskSink: TaskSink?

    func setTaskSink(_ sink: TaskSink?) {
        taskSink = sink
        for slot in slots.values {
            guard let scope = slot.scope else { continue }
            slot.taskOwner.setSink(Self.ownerSink(sink, target: scope.target, profile: scope.profile))
        }
    }

    private static func ownerSink(_ sink: TaskSink?, target: RelayPairedTarget, profile: String)
        -> (@Sendable (String, BackgroundTasks) -> Void)? {
        guard let sink else { return nil }
        return { durable, state in sink(target, profile, durable, state) }
    }

    init(socketFactory: any RelayBinarySocketFactorying = URLSessionRelaySocketFactory(),
         selectionRequired: Bool = false) {
        self.selectionRequired = selectionRequired
        self.socketFactory = socketFactory
    }

    private func slot(for channel: String?) -> Slot {
        let key = channel ?? ""
        if let existing = slots[key] { return existing }
        let created = Slot()
        slots[key] = created
        return created
    }

    /// Only explicit app transport/profile selection may replace the admission scope.
    /// Selection drops every channel's controller.
    func select(target: RelayPairedTarget?, profile: String) async {
        let requested = target.map { Scope(target: $0, profile: profile) }
        selectionRequired = true
        guard selectedScope != requested || slots.values.contains(where: { $0.scope != requested }) else { return }
        selectedScope = requested
        selectionGeneration = UUID()
        let token = selectionGeneration
        selecting = true
        let old = slots
        slots = [:]
        for slot in old.values {
            slot.generation = UUID()
            slot.opening?.cancel()
        }
        for slot in old.values {
            if let connection = slot.connection { await connection.close() }
            if let flight = slot.opening, let stale = try? await flight.value { await stale.close() }
        }
        if selectionGeneration == token { selecting = false }
    }

    func acquire(
        target: RelayPairedTarget, profile: String, channel: String? = nil
    ) async throws -> ChatConnection {
        let requested = Scope(target: target, profile: profile)
        guard !selecting, !selectionRequired || selectedScope == requested else {
            throw CancellationError()
        }
        let slot = slot(for: channel)
        if slot.scope == requested {
            if let connection = slot.connection, !connection.isClosed { return connection }
            if let opening = slot.opening {
                let token = slot.generation
                let candidate = try await opening.value
                guard slot.generation == token, slot.scope == requested else { throw CancellationError() }
                return candidate
            }
        }
        let previousOpening = slot.opening
        let previousConnection = slot.connection
        if slot.scope != requested {
            slot.checkpoint = RelayRecoveryCheckpoint()
            slot.taskOwner = RelayTaskOwner()
        }
        let checkpoint = slot.checkpoint
        let taskOwner = slot.taskOwner
        taskOwner.setSink(Self.ownerSink(taskSink, target: target, profile: profile))
        previousOpening?.cancel()
        slot.generation = UUID()
        let token = slot.generation
        slot.scope = requested
        slot.connection = nil
        let factory = socketFactory
        let tokenSink = routingTokenSink
        // Publish the flight BEFORE any await, including cursor acquisition and
        // old-channel draining. Actor reentrancy must not create two candidates.
        let task = Task<ChatConnection, Error> {
            if let previousConnection { await previousConnection.close() }
            if let previousOpening, let stale = try? await previousOpening.value { await stale.close() }
            try Task.checkCancellation()
            let checkpointOwner = checkpoint.begin()
            let cursor = checkpointOwner.cursor
            let connected = try await RelayConnector.connect(
                target: target, profile: profile, resumeCursor: cursor, recoveryVersion: 1,
                channel: channel, socketFactory: factory
            )
            let socket = RelayChatSocket(connected: connected, recoveryProfile: profile,
                checkpoint: checkpoint, checkpointGeneration: checkpointOwner.generation,
                taskOwner: taskOwner)
            do {
                try await withThrowingTaskGroup(of: Void.self) { group in
                    group.addTask { try await socket.prepareRecovery() }
                    group.addTask {
                        try await Task.sleep(for: .seconds(15))
                        await socket.close()
                        throw RelayConnectionError.offline
                    }
                    defer { group.cancelAll() }
                    _ = try await group.next()
                }
                try Task.checkCancellation()
                // The host renews the router token on every attach; persist
                // it so the pairing never ages out and needs a re-pair.
                if let tokenSink, let renewed = await socket.recoverySnapshot()?.routingToken,
                   renewed != target.relayRoutingToken {
                    await tokenSink(target, renewed)
                }
                let candidate = try ChatConnection(socket: socket)
                candidate.startReading()
                return candidate
            } catch {
                await socket.close()
                throw error
            }
        }
        slot.opening = task
        do {
            let candidate = try await task.value
            guard slot.generation == token, slot.scope == requested else {
                await candidate.close()
                throw CancellationError()
            }
            slot.connection = candidate
            slot.opening = nil
            if channel == nil, let pushConnectionSink {
                Task { await pushConnectionSink(target, candidate) }
            }
            return candidate
        } catch {
            if slot.generation == token { slot.opening = nil }
            throw error
        }
    }

    static func release(_ connection: ChatConnection) async {
        if connection.relaySocket == nil { await connection.close() }
    }

    /// Discards a candidate that never became the published chat owner. A
    /// failed resume/create must not leave a live-looking connection in the
    /// channel slot: the next retry would otherwise borrow the same dead
    /// reader and require a process restart to recover.
    func discard(_ connection: ChatConnection) async {
        for slot in slots.values where slot.connection === connection {
            slot.generation = UUID()
            slot.opening?.cancel()
            slot.opening = nil
            slot.connection = nil
            await connection.close()
            return
        }
        await connection.close()
    }
}
