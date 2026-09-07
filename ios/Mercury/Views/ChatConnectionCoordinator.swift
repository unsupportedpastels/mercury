import SwiftUI

extension ChatView {
    // MARK: Live connection

    /// Android: MAX_CHAT_RECOVERIES_PER_OPERATION = 2, backoff 500/1000/2000ms.
    private static let maxRecoveryAttempts = 3
    private static let recoveryBackoffMillis: [UInt64] = [500, 1_000, 2_000]

    /// One connect attempt: ticket → socket → event loop → resume/create.
    /// Returns true when the session reached `.live` (resume/create succeeded);
    /// false means the caller should try again or give up.
    @discardableResult
    @MainActor
    private func establishConnection(attempt: Int) async -> Bool {
        // Task re-entry safety: for the new-chat flow, once the runtime exists
        // (createSession already ran) never run this again.
        if state.connection != nil { return true }
        guard !state.establishing else { return false }
        state.establishing = true
        defer { state.establishing = false }
        let requestedScope = backgroundTaskScope
        let requestedProfile = appModel.activeProfile
        let requestedTargetID = appModel.activeRelayTarget?.id
        let requestedOrigin = appModel.serverOrigin
        let requestedSelection = appModel.relaySelectionGeneration
        guard appModel.activeRelayTarget != nil || appModel.serverOrigin != nil else {
            return false
        }

        var candidateConnection: ChatConnection?
        do {
            let candidate: ChatConnection
            if let relayTarget = appModel.activeRelayTarget {
                // Relay mode: same Hermes JSON-RPC contract through the
                // E2EE channel. Each chat owns its own lease channel on the
                // host, so several sessions can be open at once and list
                // refreshes keep using the default channel.
                candidate = try await RelayConnectionPool.shared.acquire(
                    target: relayTarget,
                    profile: appModel.activeProfile,
                    channel: RelayAdmissionEnvelope.channelForSession(state.durableID ?? sessionID)
                )
            } else {
                guard let origin = appModel.serverOrigin else { return false }
                let token = storedAccessToken(origin: origin)
                let ticketClient = WsTicketClient(session: .shared)
                let gateway = try ChatGateway(
                    origin: origin,
                    accessToken: token,
                    ticketClient: ticketClient,
                    socketFactory: URLSessionChatWebSocketFactory()
                )
                // Tickets are single-use: every attempt mints a fresh one.
                let socket = try await gateway.connect()
                candidate = try ChatConnection(socket: socket)
            }
            candidateConnection = candidate
            guard !Task.isCancelled, !state.closedByUs, backgroundTaskScope == requestedScope else {
                await RelayConnectionPool.release(candidate)
                return false
            }
            if attempt > 0, let relay = candidate.relaySocket,
               let snapshot = await relay.recoverySnapshot(),
               !snapshot.hasLiveBinding(durableId: state.durableID ?? sessionID, profile: requestedProfile) {
                var tasks = await relay.retainedTasks(durable: state.durableID ?? sessionID, runtime: nil) ?? backgroundTasks
                guard !Task.isCancelled, !state.closedByUs, backgroundTaskScope == requestedScope else {
                    throw CancellationError()
                }
                tasks.recover(snapshot: snapshot, durableID: state.durableID ?? sessionID,
                               profile: requestedProfile, runtime: nil)
                appModel.backgroundTasksBySession[backgroundTaskScope] = tasks
                _ = await loadTranscript(durableSessionID: state.durableID ?? sessionID, using: candidate)
                state.connectionNote = "Retained session unavailable. Retry to reconnect explicitly."
                throw ChatError.transport("Retained session unavailable")
            }

            let stream = candidate.start()
            // Keep the candidate private until create/resume proves it owns a
            // valid runtime. Publishing earlier lets a failed or stale attempt
            // clear a newer connection when its event stream finishes.
            if isNewSession && state.durableID == nil {
                let created = try await candidate.createSession(
                    profile: requestedProfile,
                    workspacePath: newSessionWorkspacePath
                )
                guard !Task.isCancelled, !state.closedByUs, backgroundTaskScope == requestedScope else {
                    throw CancellationError()
                }
                state.runtimeSessionID = created.runtimeSessionID
                state.transcript.ownSessionIDs.insert(created.runtimeSessionID)
                if let stored = created.durableSessionID {
                    state.durableID = stored
                    // A brand-new session's durable id only exists after
                    // create; register it now so background reconciliation is
                    // allowed to notify about it (Android engaged-scope parity).
                    appModel.markSessionEngaged(stored)
                }
            } else {
                let resumed = try await candidate.resume(durableSessionID: state.durableID ?? sessionID, profile: requestedProfile,
                                                         automaticRecovery: attempt > 0)
                guard !Task.isCancelled, !state.closedByUs, backgroundTaskScope == requestedScope else {
                    throw CancellationError()
                }
                state.runtimeSessionID = resumed.runtimeSessionID
                state.transcript.ownSessionIDs.insert(resumed.runtimeSessionID)
                if let provider = resumed.provider, let model = resumed.model {
                    state.currentModelSelection = ModelSelection(provider: provider, model: model)
                }
                state.currentReasoningEffort = resumed.reasoningEffort
                state.currentFastMode = resumed.fastMode
                // `session.resume` carries an authoritative message snapshot.
                // REST may have raced the turn completion or failed while the
                // app was away, so do not rely on the earlier REST load alone.
                // Android applies this snapshot before reconciling in-flight
                // state; Mercury must do the same to show replies after a
                // terminate/relaunch cycle.
                let resumedRows = transcriptRows(from: resumed.messages)
                if !resumedRows.isEmpty {
                    state.transcript.loadTranscript(resumedRows)
                }
                if resumed.running || resumed.inflight != nil {
                    // A turn was already executing when we attached; reopen the
                    // REST-loaded assistant row (rather than creating a second
                    // bubble) so subsequent deltas append to the same reply.
                    let inflightText = resumed.inflight?.assistant ?? ""
                    state.transcript.ensureInflightAssistantRow(
                        text: inflightText,
                        completed: false
                    )
                } else {
                    // The turn completed while we were away. Android performs
                    // a second REST transcript load here because the first
                    // load may have raced the tool phase and omitted the final
                    // assistant response. Keep the resume snapshot visible if
                    // that follow-up request is temporarily unavailable. The
                    // candidate is not published yet, so pass it explicitly:
                    // in relay mode a standalone read here would supersede
                    // this very connection and loop the reconnect.
                    _ = await loadTranscript(durableSessionID: sessionID, using: candidate)
                    state.transcript.finishStreamingAssistant()
                }
            }

            // Task cancellation is cooperative: a socket/create/resume await
            // may return normally after dismissal or a manual retry cancelled
            // this attempt. Never let that stale candidate become active.
            guard let ownershipToken = state.connectionOwnership.publish(
                when: !Task.isCancelled && !state.closedByUs
                    && appModel.activeProfile == requestedProfile
                    && appModel.activeRelayTarget?.id == requestedTargetID
                    && appModel.serverOrigin == requestedOrigin
                    && appModel.relaySelectionGeneration == requestedSelection
            ) else {
                await RelayConnectionPool.release(candidate)
                return false
            }
            state.connection = candidate
            let childScope = backgroundTaskScope
            if let relay = candidate.relaySocket,
               let tasks = await relay.retainedTasks(durable: state.durableID ?? sessionID, runtime: state.runtimeSessionID) {
                guard state.connectionOwnership.isCurrent(ownershipToken), state.connection === candidate,
                      backgroundTaskScope == childScope else { return false }
                appModel.backgroundTasksBySession[childScope] = tasks
            }
            state.eventTask?.cancel()
            if !backgroundTasks.rows.isEmpty, let childRuntime = state.runtimeSessionID {
                Task {
                    if let statuses = try? await candidate.backgroundTaskStatuses(),
                       state.connectionOwnership.isCurrent(ownershipToken), state.connection === candidate,
                       backgroundTaskScope == childScope {
                        if let relay = candidate.relaySocket {
                            let tasks = await relay.reconcileRetainedTasks(durable: state.durableID ?? sessionID,
                                                                          runtime: childRuntime, statuses: statuses)
                            guard state.connectionOwnership.isCurrent(ownershipToken), state.connection === candidate,
                                  backgroundTaskScope == childScope, state.runtimeSessionID == childRuntime,
                                  let tasks else { return }
                            appModel.backgroundTasksBySession[childScope] = tasks
                        } else {
                            var tasks = backgroundTasks
                            tasks.reconcile(statuses, runtime: childRuntime)
                            appModel.backgroundTasksBySession[childScope] = tasks
                        }
                    }
                }
            }
            state.eventTask = Task { [weak candidate] in
                guard let candidate else { return }
                for await event in stream {
                    // Guard AND apply inside one MainActor hop: handleEvent
                    // mutates screen state (the transcript reducer among it),
                    // and a nonisolated Task runs off the main actor in Swift 5
                    // mode. Off-main state writes tear against render — the
                    // transcript list kept showing a stale mid-turn snapshot
                    // ("Working · 1 step") while the streamed answer was
                    // already reduced into rows, on both transports.
                    let stillOwnsConnection = await MainActor.run {
                        guard state.connectionOwnership.isCurrent(ownershipToken)
                            && state.connection === candidate && backgroundTaskScope == childScope else { return false }
                        handleEvent(event)
                        return true
                    }
                    guard stillOwnsConnection else { break }
                }
                await MainActor.run {
                    guard state.connectionOwnership.isCurrent(ownershipToken),
                          state.connection === candidate, backgroundTaskScope == childScope else { return }
                    _ = state.connectionOwnership.release(ifCurrent: ownershipToken)
                    markBackgroundTasksUnavailable()
                    state.connection = nil
                    clearSlashCompletion()
                    // Unexpected stream end (peer drop / transport death) —
                    // deliberate close() never reaches here with closedByUs false.
                    guard !state.closedByUs else { return }
                    scheduleReconnect()
                }
            }

            if let durableID = state.durableID {
                state.transcript.ownSessionIDs.insert(durableID)
            }
            // Live notifications are keyed on the durable id; keep the visible
            // session in sync so suppression matches while on screen.
            appModel.setVisibleSession(notificationSessionID)
            state.connectionNote = nil
            state.connectionState = .live
            scheduleSlashCompletion(for: state.draft)
            loadModelOptions()
            loadContext()
            await loadProcessRows()
            return true
        } catch {
            if let candidateConnection {
                await RelayConnectionPool.release(candidateConnection)
            }
            return false
        }
    }

    @MainActor
    func connectAndResume() async {
        state.connectionState = .connecting
        state.closedByUs = false
        let ok = await establishConnection(attempt: 0)
        if !ok {
            scheduleReconnect()
        }
    }

    /// Android recoverChat parity: bounded attempts, fixed backoff ladder
    /// (500ms/1s/2s), cancel-safe between sleeps, offline banner at the end
    /// with a manual-retry tap target.
    @MainActor
    private func scheduleReconnect() {
        guard state.reconnectTask == nil, !state.closedByUs else { return }
        state.reconnectID = UUID()
        let token = state.reconnectID
        let scope = backgroundTaskScope
        state.reconnectTask = Task {
            // The Task inherits MainActor; the nested function must say so
            // explicitly to read the actor-isolated state synchronously.
            @MainActor func current() -> Bool {
                !Task.isCancelled && !state.closedByUs && state.reconnectID == token && backgroundTaskScope == scope
            }
            for attempt in 1...Self.maxRecoveryAttempts {
                guard current() else { break }
                // iOS suspension is not a failed recovery attempt.
                while scenePhase != .active && current() {
                    try? await Task.sleep(for: .milliseconds(250))
                }
                guard current() else { break }
                state.connectionState = .reconnecting(attempt: attempt)
                let millis = Self.recoveryBackoffMillis[min(attempt - 1, Self.recoveryBackoffMillis.count - 1)]
                try? await Task.sleep(nanoseconds: millis * 1_000_000)
                while scenePhase != .active && current() {
                    try? await Task.sleep(for: .milliseconds(250))
                }
                guard current() else { break }
                if await establishConnection(attempt: attempt) {
                    if state.reconnectID == token { state.reconnectTask = nil }
                    return
                }
            }
            if current() { state.connectionState = .offline }
            if state.reconnectID == token { state.reconnectTask = nil }
        }
    }

    @MainActor
    func retryConnectionNow(automatic: Bool = false) {
        state.reconnectTask?.cancel()
        state.reconnectTask = nil
        state.reconnectID = UUID()
        let token = state.reconnectID
        let scope = backgroundTaskScope
        guard state.connection == nil, !state.closedByUs else { return }
        state.reconnectTask = Task {
            state.connectionState = .reconnecting(attempt: 1)
            // Foreground recovery is not consent to resume a historical binding.
            let connected = await establishConnection(attempt: automatic ? 1 : 0)
            guard state.reconnectID == token, !Task.isCancelled, backgroundTaskScope == scope else { return }
            state.reconnectTask = nil
            if !connected { scheduleReconnect() }
        }
    }
}
