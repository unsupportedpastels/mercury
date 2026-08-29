import Foundation
import Observation

/// Owns a metadata-only gateway socket for the Projects UI. It starts the
/// ChatConnection read loop so JSON-RPC replies are correlated, but never calls
/// session.resume/session.create and therefore never takes over a chat runtime.
@MainActor
@Observable
final class ProjectMetadataController {
    private(set) var tree: ProjectTree?
    private(set) var sessionsByProject: [ProjectID: [ReconciledProjectSession]] = [:]
    private(set) var loadingProjectIDs = Set<ProjectID>()
    private(set) var isLoading = false
    private(set) var isCreating = false
    private(set) var isUnsupported = false
    private(set) var errorMessage: String?
    private(set) var activeRuntimeSessions: [ActiveSessionRuntime] = []
    private(set) var activityTracker = SessionInboxActivityTracker()

    @ObservationIgnored
    private var connection: ChatConnection?
    @ObservationIgnored
    private var readTask: Task<Void, Never>?
    @ObservationIgnored
    private var activityTask: Task<Void, Never>?
    private var generation: UInt64 = 0
    private var profile = "default"
    private var activeListSupported = true

    /// Which transport carries the metadata connection.
    enum Source {
        case direct(origin: String, accessToken: String?)
        case relay(RelayPairedTarget)
    }

    func start(origin: String, accessToken: String?, profile: String) async {
        await start(source: .direct(origin: origin, accessToken: accessToken), profile: profile)
    }

    func start(source: Source, profile: String) async {
        generation &+= 1
        let loadGeneration = generation
        await closeOwnedConnection()

        self.profile = profile
        tree = nil
        sessionsByProject = [:]
        loadingProjectIDs = []
        isCreating = false
        isUnsupported = false
        errorMessage = nil
        activeRuntimeSessions = []
        activityTracker = SessionInboxActivityTracker()
        activeListSupported = true
        isLoading = true

        do {
            let socket: any ChatSocketing
            switch source {
            case let .direct(origin, accessToken):
                let gateway = try ChatGateway(
                    origin: origin,
                    accessToken: accessToken,
                    ticketClient: WsTicketClient(session: .shared),
                    socketFactory: URLSessionChatWebSocketFactory()
                )
                socket = try await gateway.connect()
            case let .relay(target):
                let connected = try await RelayConnector.connect(
                    target: target, profile: profile
                )
                socket = RelayChatSocket(connected: connected)
            }
            guard loadGeneration == generation else {
                await socket.close()
                return
            }
            let owned = try ChatConnection(socket: socket)
            connection = owned
            let stream = owned.start()
            readTask = Task { [weak owned] in
                for await _ in stream {
                    guard owned != nil, !Task.isCancelled else { break }
                }
            }
            let loaded = try await owned.loadProjectTree(profile: profile)
            guard loadGeneration == generation, connection === owned else { return }
            tree = loaded
            isLoading = false
            let shouldPoll = await refreshActiveSessions(connection: owned, generation: loadGeneration)
            if shouldPoll { beginActivityPolling(connection: owned, generation: loadGeneration) }
        } catch is ChatMethodNotFoundError {
            guard loadGeneration == generation else { return }
            isUnsupported = true
            isLoading = false
            errorMessage = nil
            await closeOwnedConnection()
        } catch is CancellationError {
            return
        } catch {
            guard loadGeneration == generation else { return }
            isLoading = false
            errorMessage = "Could not load projects from this server."
        }
    }

    func retry(origin: String, accessToken: String?, profile: String) async {
        await start(origin: origin, accessToken: accessToken, profile: profile)
    }

    func loadSessions(for project: ProjectSummary, restSessions: [SessionRow]) async {
        guard !isUnsupported, let owned = connection else { return }
        loadingProjectIDs.insert(project.id)
        errorMessage = nil
        let requestGeneration = generation
        do {
            let result = try await owned.loadProjectSessions(projectID: project.id, profile: profile)
            guard requestGeneration == generation, connection === owned else { return }
            sessionsByProject[project.id] = ProjectSessionReconciler.reconcile(
                projectSessions: result.sessions,
                restSessions: restSessions,
                project: result.project
            )
            loadingProjectIDs.remove(project.id)
        } catch is ChatMethodNotFoundError {
            guard requestGeneration == generation else { return }
            isUnsupported = true
            loadingProjectIDs.remove(project.id)
            await closeOwnedConnection()
        } catch is CancellationError {
            return
        } catch {
            guard requestGeneration == generation else { return }
            loadingProjectIDs.remove(project.id)
            errorMessage = "Could not load this project's sessions. You can retry."
        }
    }

    func create(name: String, canonicalFolder: String) async -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let owned = connection else {
            errorMessage = "Enter a project name and choose a server folder."
            return false
        }
        isCreating = true
        errorMessage = nil
        let requestGeneration = generation
        do {
            _ = try await owned.createProject(
                name: trimmed,
                folders: [canonicalFolder],
                primaryPath: canonicalFolder,
                use: true,
                profile: profile
            )
            let loaded = try await owned.loadProjectTree(profile: profile)
            guard requestGeneration == generation, connection === owned else { return false }
            tree = loaded
            isCreating = false
            return true
        } catch is ChatMethodNotFoundError {
            guard requestGeneration == generation else { return false }
            isUnsupported = true
            isCreating = false
            await closeOwnedConnection()
            return false
        } catch {
            guard requestGeneration == generation else { return false }
            isCreating = false
            errorMessage = "Could not create that project. Check the name and folder, then retry."
            return false
        }
    }

    func stop() async {
        generation &+= 1
        await closeOwnedConnection()
    }

    /// Deletes the project registration server-side, then reloads the tree so
    /// its sessions reappear under their auto/Home grouping. Returns false
    /// (with errorMessage set) on failure so the caller can keep the row.
    func delete(_ project: ProjectSummary) async -> Bool {
        guard !isUnsupported, let owned = connection else { return false }
        errorMessage = nil
        let requestGeneration = generation
        do {
            try await owned.deleteProject(id: project.id, profile: profile)
            let loaded = try await owned.loadProjectTree(profile: profile)
            guard requestGeneration == generation, connection === owned else { return false }
            tree = loaded
            sessionsByProject[project.id] = nil
            return true
        } catch is ChatMethodNotFoundError {
            guard requestGeneration == generation else { return false }
            errorMessage = "This Hermes server does not support deleting projects."
            return false
        } catch is CancellationError {
            return false
        } catch {
            guard requestGeneration == generation else { return false }
            errorMessage = "Could not delete that project. Try again."
            return false
        }
    }

    func inboxIndicator(for durableSessionID: String) -> SessionInboxIndicator {
        activityTracker.indicator(for: durableSessionID)
    }

    func setVisibleSession(_ durableSessionID: String?) {
        activityTracker.setVisibleSession(durableSessionID)
    }

    func markSessionRead(_ durableSessionID: String) {
        activityTracker.markRead(durableSessionID)
    }

    private func refreshActiveSessions(
        connection owned: ChatConnection,
        generation expected: UInt64
    ) async -> Bool {
        guard activeListSupported else { return false }
        do {
            let runtimes = try await owned.loadActiveSessions()
            guard expected == generation, connection === owned else { return false }
            activeRuntimeSessions = runtimes
            activityTracker.apply(runtimes)
            return true
        } catch is ChatMethodNotFoundError {
            guard expected == generation else { return false }
            activeListSupported = false
            activeRuntimeSessions = []
            activityTracker.apply([])
            return false
        } catch is CancellationError {
            return false
        } catch {
            // Runtime presence is ephemeral metadata. Preserve the last good
            // state on transient failure and retry on the next bounded poll.
            return expected == generation && connection === owned
        }
    }

    private func beginActivityPolling(
        connection owned: ChatConnection,
        generation expected: UInt64
    ) {
        activityTask?.cancel()
        activityTask = Task { [weak self, weak owned] in
            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(3))
                } catch {
                    break
                }
                guard let self, let owned,
                      expected == self.generation,
                      self.connection === owned else { break }
                let shouldContinue = await self.refreshActiveSessions(
                    connection: owned,
                    generation: expected
                )
                if !shouldContinue { break }
            }
        }
    }

    private func closeOwnedConnection() async {
        activityTask?.cancel()
        activityTask = nil
        readTask?.cancel()
        readTask = nil
        let owned = connection
        connection = nil
        await owned?.close()
    }
}
