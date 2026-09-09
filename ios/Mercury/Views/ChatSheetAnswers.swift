import SwiftUI

private extension ChatResponse.Status {
    var resolvesInput: Bool { self == .ok || self == .resolved || self == .expired }
}

extension ChatSessionState {
    func presentPendingInput() {
        if let request = outstandingSecure {
            pendingSecure = request
        } else {
            pendingRequest = transcript.pendingRequest.map {
                switch $0 {
                case .approval(let event): return .approval(event)
                case .clarify(let event): return .clarify(event)
                }
            }
        }
    }

    func finishInputRequests() {
        pendingRequest = nil
        pendingSecure = nil
        outstandingSecure = nil
        inputResponseID = nil
        secureResponseID = nil
        clarifyAnsweredIDs = []
    }
}

extension ChatView {
    // User-input RPCs have their own busy flag. An active assistant turn is
    // precisely when these controls must remain usable, not a reason to disable them.
    @MainActor
    func answerApproval(_ choice: String) async {
        guard state.inputResponseID == nil, let connection = state.connection,
              let runtime = state.runtimeSessionID,
              let expected = state.transcript.pendingRequestSnapshot else { return }
        let requestID = state.requestID
        let attempt = UUID()
        state.inputResponseID = attempt
        defer { if state.inputResponseID == attempt { state.inputResponseID = nil } }
        do {
            let response = try await connection.respondToApproval(
                runtimeSessionID: runtime, choice: choice, requestID: requestID)
            guard state.connection === connection, state.runtimeSessionID == runtime,
                  state.transcript.matchesPendingRequest(expected) else { return }
            guard response.status.resolvesInput else {
                state.composerError = "Could not answer approval."
                return
            }
            state.transcript.resolvePendingRequest(expected)
            state.pendingRequest = nil
            if let next = response.nextApproval {
                state.transcript.apply(next)
                state.pendingRequest = .approval(next)
            }
        } catch {
            guard state.connection === connection, state.transcript.matchesPendingRequest(expected) else { return }
            state.composerError = "Could not answer approval."
        }
    }

    /// Batch questions stay pending until the final qid is acknowledged. A
    /// stale reply must not dismiss a newer question or invent a Needs-you state.
    @MainActor
    func answerClarify(_ answer: String) async {
        guard state.inputResponseID == nil, let connection = state.connection,
              let requestID = state.requestID,
              let expected = state.transcript.pendingRequestSnapshot else { return }
        let runtime = state.runtimeSessionID
        let current = state.currentClarifyQuestion
        let attempt = UUID()
        state.inputResponseID = attempt
        defer { if state.inputResponseID == attempt { state.inputResponseID = nil } }
        do {
            let response = try await connection.respondToClarification(
                requestID: requestID, answer: answer, questionID: current?.qid)
            guard state.connection === connection, state.runtimeSessionID == runtime,
                  state.transcript.matchesPendingRequest(expected) else { return }
            guard response.status.resolvesInput else {
                state.composerError = "Could not send clarification."
                return
            }
            if response.status != .expired, let current {
                state.clarifyAnsweredIDs.insert(current.qid)
                if state.currentClarifyQuestion != nil { return }
            }
            state.transcript.resolvePendingRequest(expected)
            state.pendingRequest = nil
            state.clarifyAnsweredIDs = []
        } catch {
            guard state.connection === connection, state.transcript.matchesPendingRequest(expected) else { return }
            state.composerError = "Could not send clarification."
        }
    }

    /// Secure values stay transient. Dismissing the sheet is not a server-side
    /// answer: retain the outstanding request for a direct retry affordance.
    @MainActor
    func answerSecure(kind: UnsupportedBlockingKind, requestID: String, value: String) async {
        guard let connection = state.connection else { return }
        let runtime = state.runtimeSessionID
        let interactive = kind == .secret || kind == .sudo
        if interactive && state.secureResponseID != nil { return }
        let attempt = UUID()
        if interactive { state.secureResponseID = attempt }
        defer { if state.secureResponseID == attempt { state.secureResponseID = nil } }
        do {
            let response = try await connection.respondToBlockingPrompt(kind: kind, requestID: requestID, value: value)
            guard state.connection === connection, state.runtimeSessionID == runtime else { return }
            if response.status.resolvesInput {
                if state.outstandingSecure?.requestID == requestID && state.outstandingSecure?.kind == kind {
                    state.outstandingSecure = nil
                    if state.pendingSecure?.requestID == requestID { state.pendingSecure = nil }
                }
            } else if interactive {
                state.composerError = "Could not send secure input."
            }
        } catch {
            guard state.connection === connection, state.runtimeSessionID == runtime else { return }
            state.composerError = "Could not send secure input."
        }
    }
}
