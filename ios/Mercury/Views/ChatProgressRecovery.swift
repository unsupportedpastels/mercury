import SwiftUI
import MercuryCore

extension ChatView {
    /// Observer-only refresh. Never sends a prompt, resumes/activates a runtime,
    /// replaces a controller, or mutates the visible transcript.
    @MainActor
    func refreshSessionProgress() async {
        guard !state.progressRefreshing else { return }
        let durable = state.durableID ?? sessionID
        guard !durable.isEmpty else { return }
        let scope = backgroundTaskScope
        let selection = appModel.relaySelectionGeneration
        let profile = appModel.activeProfile
        let origin = appModel.serverOrigin
        let target = appModel.activeRelayTarget
        let connection = state.connection
        let runtime = state.runtimeSessionID
        let version = state.progress.observationVersion
        let request = UUID()
        state.progressRefreshID = request
        state.progressRefreshing = true
        state.progressRefreshError = nil
        defer {
            if state.progressRefreshID == request { state.progressRefreshing = false }
        }
        do {
            let recovered: MercuryCore.DurableProgress
            if target != nil {
                // Reuse only this session's connection. Do not acquire another
                // controller behind an innocent read-only refresh affordance.
                guard let connection else {
                    state.progressRefreshError = "Reconnect to read saved progress."
                    return
                }
                recovered = try await relayTranscriptMessages(
                    transcriptID: durable, limit: 100, offset: 0, using: connection).progress
            } else {
                guard let origin else { return }
                recovered = try await SessionsClient(client: makeHTTPClient(origin: origin), profile: profile)
                    .transcriptWithProgress(sessionID: durable).progress
            }
            guard !Task.isCancelled, !state.closedByUs, backgroundTaskScope == scope,
                  appModel.relaySelectionGeneration == selection,
                  state.connection === connection, state.runtimeSessionID == runtime,
                  state.progressRefreshID == request else { return }
            state.progress = state.progress.recover(history: recovered, expectedVersion: version)
        } catch {
            guard !Task.isCancelled, backgroundTaskScope == scope,
                  appModel.relaySelectionGeneration == selection,
                  state.progressRefreshID == request,
                  state.connection === connection, state.runtimeSessionID == runtime,
                  state.progress.observationVersion == version else { return }
            state.progressRefreshError = "Could not get progress update. Showing last observed history."
        }
    }

    /// How often a chat re-asks the gateway registry about unresolved children.
    static let backgroundRegistryPollInterval: Duration = .seconds(30)

    /// A registry poll stops for a definitively unsupported method or a dead
    /// connection (its reconnect restarts polling); every other failure is
    /// retried on the next interval, matching the Android loop.
    static func registryPollContinues(after error: Error, connectionClosed: Bool) -> Bool {
        if connectionClosed || error is CancellationError || error is ChatMethodNotFoundError { return false }
        return true
    }

    /// A reopened app's only child evidence is a recovered snapshot with no
    /// fresh event to observe, and a child inside a long tool call emits
    /// nothing. The gateway's `delegation.status` registry is the authoritative
    /// liveness signal, so keep asking it while unresolved children remain.
    /// Each "running" answer counts as activity for one window without being
    /// mistaken for a worker event. Registry silence never invents rows.
    @MainActor
    func startBackgroundRegistryPolling() {
        state.backgroundRegistryTask?.cancel()
        state.backgroundRegistryPolling = false
        guard backgroundTasks.hasUnresolvedIdentifiedChildren, let childRuntime = state.runtimeSessionID,
              let candidate = state.connection, let ownershipToken = state.connectionOwnershipToken,
              state.connectionOwnership.isCurrent(ownershipToken) else { return }
        let scope = backgroundTaskScope
        state.backgroundRegistryGeneration &+= 1
        let generation = state.backgroundRegistryGeneration
        state.backgroundRegistryPolling = true
        state.backgroundRegistryTask = Task { @MainActor [weak candidate] in
            defer { if state.backgroundRegistryGeneration == generation { state.backgroundRegistryPolling = false } }
            while !Task.isCancelled {
                guard let candidate, state.connectionOwnership.isCurrent(ownershipToken), state.connection === candidate,
                      backgroundTaskScope == scope, state.runtimeSessionID == childRuntime else { return }
                let statuses: [String: String]
                do {
                    statuses = try await candidate.backgroundTaskStatuses()
                } catch {
                    // Retain last known rows; an unanswered registry is not
                    // success or failure. Only a host without the method ends
                    // the poll: a transient RPC failure must not hide a
                    // still-running silent child once its window expires.
                    guard Self.registryPollContinues(after: error, connectionClosed: candidate.isClosed) else { return }
                    try? await Task.sleep(for: Self.backgroundRegistryPollInterval)
                    continue
                }
                guard !Task.isCancelled, state.connectionOwnership.isCurrent(ownershipToken), state.connection === candidate,
                      backgroundTaskScope == scope, state.runtimeSessionID == childRuntime else { return }
                if let relay = candidate.relaySocket {
                    let tasks = await relay.reconcileRetainedTasks(durable: state.durableID ?? sessionID,
                                                                  runtime: childRuntime, statuses: statuses)
                    guard !Task.isCancelled, state.connectionOwnership.isCurrent(ownershipToken),
                          state.connection === candidate, backgroundTaskScope == scope,
                          state.runtimeSessionID == childRuntime, let tasks else { return }
                    appModel.backgroundTasksBySession[scope] = tasks
                } else {
                    var tasks = backgroundTasks
                    tasks.reconcile(statuses, runtime: childRuntime, previousRuntime: state.previousRuntimeSessionID,
                                    now: Int64(Date().timeIntervalSince1970 * 1000))
                    appModel.backgroundTasksBySession[scope] = tasks
                }
                guard backgroundTasks.hasUnresolvedIdentifiedChildren else { return }
                try? await Task.sleep(for: Self.backgroundRegistryPollInterval)
            }
        }
    }

    /// Fresh child evidence on a chat that is not already asking the registry.
    @MainActor
    func startBackgroundRegistryPollingIfIdle() {
        guard !state.backgroundRegistryPolling else { return }
        startBackgroundRegistryPolling()
    }

    func visibleBackgroundTasks(now: Int64) -> BackgroundTasks {
        BackgroundTasks(rows: backgroundTasks.rows.filter {
            !($0.isDismissible(now: now) && state.dismissedBackgroundEvidence.contains($0.dismissalKey))
        })
    }

    var activitySheet: some View {
        SessionActivitySheet(
            state: state,
            backgroundTasks: backgroundTasks,
            onRefresh: { Task { await refreshSessionProgress() } },
            onReconnect: { retryConnectionNow() },
            media: { AnyView(managedImages(in: $0)) }
        )
    }
}
