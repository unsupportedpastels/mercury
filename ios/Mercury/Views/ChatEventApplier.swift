import SwiftUI

extension ChatView {
    // MARK: Event handling

    @MainActor
    func handleEvent(_ event: ChatEvent) {
        guard event.belongsToPresentation(runtimeID: state.runtimeSessionID, durableID: state.durableID ?? sessionID) else { return }
        if case .backgroundTask = event {
            guard let runtimeSessionID = state.runtimeSessionID else { return }
            if let candidate = state.connection, let relay = candidate.relaySocket {
                let scope = backgroundTaskScope
                let durable = state.durableID ?? sessionID
                Task { @MainActor in
                    let tasks = await relay.retainedTasks(durable: durable, runtime: runtimeSessionID)
                    guard state.connection === candidate, backgroundTaskScope == scope,
                          state.runtimeSessionID == runtimeSessionID, let tasks else { return }
                    appModel.backgroundTasksBySession[scope] = tasks
                    startBackgroundRegistryPollingIfIdle()
                }
            } else {
                var tasks = backgroundTasks
                tasks.apply(event, runtime: runtimeSessionID, now: Int64(Date().timeIntervalSince1970 * 1000))
                appModel.backgroundTasksBySession[backgroundTaskScope] = tasks
                startBackgroundRegistryPollingIfIdle()
            }
            return
        }
        state.observeProgress(event, atMillis: Int64(Date().timeIntervalSince1970 * 1000))
        // Pure transcript mutation lives in the reducer.
        state.transcript.apply(event)

        // Best-effort live surfaces: notification delivery + Live Activity.
        // The notification coordinator dedupes and suppresses when this session
        // is the visible/foreground one; the run-activity coordinator drives
        // the Lock Screen / Dynamic Island. It is safe to feed every event.
        //
        // Re-key the event on the DURABLE session id so the notification
        // dedupe watermark, the background reconcile path, and the Live
        // Activity share state, and deep-link targets are sessions the app
        // can open.
        let notifyID = notificationSessionID
        if let notifyID {
            let notifyEvent = event.withSessionID(notifyID)
            let notifySessionTitle = state.titleText
            Task { await appModel.deliverLiveNotification(event: notifyEvent, sessionTitle: notifySessionTitle) }
        }

        // UI-only reactions preserved verbatim from the pre-extraction
        // handler: sending flag toggles, composer banner, sheet presentation,
        // connection-note clearing, and title adoption display.
        switch event {
        case .messageStart:
            state.isSending = true

        case .messageComplete:
            state.finishInputRequests()
            if case .releasedPending(let referenceIDs) = state.promptSubmission.observeTerminal() {
                clearStagedHostReferences(referenceIDs)
                // The terminal event is authoritative even if prompt.submit's
                // RPC acknowledgement is still in flight. Let a follow-up
                // draft start; the old task remains identity-fenced.
                state.isComposerActionPending = false
            }
            state.isSending = false
            state.isStopping = false
            Task { await cacheCurrentTranscript() }
            loadContext()

        case .error(_, let message):
            state.finishInputRequests()
            if case .releasedPending(let referenceIDs) = state.promptSubmission.observeTerminal() {
                clearStagedHostReferences(referenceIDs)
                state.isComposerActionPending = false
            }
            state.composerError = message
            state.isSending = false
            state.isStopping = false

        case .toolStart, .toolComplete:
            Task { await loadProcessRows() }

        case .approvalRequest, .clarifyRequest:
            state.clarifyAnsweredIDs = []
            state.pendingRequest = state.transcript.pendingRequest.map { request in
                switch request {
                case .approval(let approvalEvent): return .approval(approvalEvent)
                case .clarify(let clarifyEvent): return .clarify(clarifyEvent)
                }
            }

        case .approvalExpire, .clarifyExpire:
            // The reducer clears only an exact kind + request-id match; a
            // stale or unrelated expiry must not dismiss a newer prompt.
            if state.transcript.pendingRequest == nil, state.pendingRequest != nil { state.pendingRequest = nil }

        case .sessionTitle:
            if let adopted = state.transcript.adoptedTitle {
                state.titleText = adopted
            }

        case .sessionInfo(let infoRuntimeID, let storedID, let model, let provider, let reasoningEffort, let fastMode, let title, _):
            if let model, let provider {
                state.applyModelSelection(ModelSelection(provider: provider, model: model))
            }
            if let reasoningEffort { state.currentReasoningEffort = reasoningEffort }
            if let fastMode { state.currentFastMode = fastMode }
            if let title, !title.isEmpty { state.titleText = title }
            // A reclaimed/remapped runtime (e.g. a relay reconnect racing a
            // superseded socket) streams the turn under a fresh runtime id.
            // When the gateway ties that runtime to our durable session,
            // accept its events so the transcript renders instead of
            // silently filtering the whole turn.
            if let storedID, storedID == (state.durableID ?? sessionID), !infoRuntimeID.isEmpty {
                state.runtimeSessionID = infoRuntimeID
                state.transcript.ownSessionIDs = [storedID, infoRuntimeID]
            }

        case .statusUpdate:
            state.connectionNote = nil

        case .unsupportedBlockingRequest(_, let kind, let requestID, let prompt):
            switch kind {
            case .secret, .sudo:
                let request = SecureRequest(kind: kind, requestID: requestID, prompt: prompt)
                state.outstandingSecure = request
                state.pendingSecure = request
            case .terminalRead, .previewRead, .windowRead:
                // Mercury owns none of Desktop's terminal/preview/window
                // surfaces. The released bridge contract defines an empty
                // response as "surface unavailable" (Android parity).
                Task { await answerSecure(kind: kind, requestID: requestID, value: "") }
            }

        case .unsupportedBlockingExpire(_, _, let requestID):
            if state.pendingSecure?.requestID == requestID { state.pendingSecure = nil }
            if state.outstandingSecure?.requestID == requestID { state.outstandingSecure = nil }

        default:
            break
        }
    }
}
