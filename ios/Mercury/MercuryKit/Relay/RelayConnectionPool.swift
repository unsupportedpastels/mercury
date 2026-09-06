import Foundation

/// A single authenticated admission, borrowed by chat and auxiliary readers.
/// No metadata view can displace or close a live retained controller.
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
    private var selectedScope: Scope?
    private var selectionRequired: Bool
    private var selecting = false
    private var scope: Scope?
    private var connection: ChatConnection?
    private var checkpoint = RelayRecoveryCheckpoint()
    private var taskOwner = RelayTaskOwner()
    private var opening: Task<ChatConnection, Error>?
    private var generation = UUID()
    private let socketFactory: any RelayBinarySocketFactorying

    init(socketFactory: any RelayBinarySocketFactorying = URLSessionRelaySocketFactory(),
         selectionRequired: Bool = false) {
        self.selectionRequired = selectionRequired
        self.socketFactory = socketFactory
    }

    /// Only explicit app transport/profile selection may replace the admission scope.
    func select(target: RelayPairedTarget?, profile: String) async {
        let requested = target.map { Scope(target: $0, profile: profile) }
        selectionRequired = true
        guard selectedScope != requested || scope != requested else { return }
        selectedScope = requested
        generation = UUID()
        let token = generation
        selecting = true
        let oldFlight = opening
        let old = connection
        opening = nil
        connection = nil
        scope = nil
        checkpoint = RelayRecoveryCheckpoint()
        taskOwner = RelayTaskOwner()
        oldFlight?.cancel()
        if let old { await old.close() }
        if let oldFlight, let stale = try? await oldFlight.value { await stale.close() }
        if generation == token { selecting = false }
    }

    func acquire(target: RelayPairedTarget, profile: String) async throws -> ChatConnection {
        let requested = Scope(target: target, profile: profile)
        guard !selecting, !selectionRequired || selectedScope == requested else {
            throw CancellationError()
        }
        if scope == requested {
            if let connection, !connection.isClosed { return connection }
            if let opening {
                let token = generation
                let candidate = try await opening.value
                guard generation == token, scope == requested else { throw CancellationError() }
                return candidate
            }
        }
        let previousOpening = opening
        let previousConnection = connection
        if scope != requested {
            checkpoint = RelayRecoveryCheckpoint()
            taskOwner = RelayTaskOwner()
        }
        let checkpoint = self.checkpoint
        let taskOwner = self.taskOwner
        previousOpening?.cancel()
        generation = UUID()
        let token = generation
        scope = requested
        connection = nil
        let factory = socketFactory
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
                socketFactory: factory
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
                let candidate = try ChatConnection(socket: socket)
                candidate.startReading()
                return candidate
            } catch {
                await socket.close()
                throw error
            }
        }
        opening = task
        do {
            let candidate = try await task.value
            guard generation == token, scope == requested else {
                await candidate.close()
                throw CancellationError()
            }
            connection = candidate
            opening = nil
            return candidate
        } catch {
            if generation == token { opening = nil }
            throw error
        }
    }

    static func release(_ connection: ChatConnection) async {
        if connection.relaySocket == nil { await connection.close() }
    }
}
