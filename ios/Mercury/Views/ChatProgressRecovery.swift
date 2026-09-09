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
