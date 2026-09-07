import SwiftUI

extension ChatView {
    // MARK: Sheet answers

    func answerApproval(_ choice: String) async {
        guard let connection = state.connection, let runtimeSessionID = state.runtimeSessionID else { return }
        defer { state.pendingRequest = nil }
        do {
            _ = try await connection.respondToApproval(
                runtimeSessionID: runtimeSessionID,
                choice: choice
            )
        } catch {
            state.composerError = "Could not answer approval."
        }
    }

    func answerClarify(_ answer: String) async {
        guard let connection = state.connection, let requestID = state.requestID else { return }
        defer { state.pendingRequest = nil }
        do {
            _ = try await connection.respondToClarification(requestID: requestID, answer: answer)
        } catch {
            state.composerError = "Could not send clarification."
        }
    }

    /// Responds to a secure blocking prompt (secret/sudo) or auto-answers an
    /// unavailable read surface with the official empty value. The value is
    /// never logged or stored.
    func answerSecure(kind: UnsupportedBlockingKind, requestID: String, value: String) async {
        guard let connection = state.connection else { return }
        do {
            _ = try await connection.respondToBlockingPrompt(
                kind: kind,
                requestID: requestID,
                value: value
            )
        } catch {
            // Never include the value or kind detail in surfaced errors.
            state.composerError = "Could not send secure input."
        }
    }
}
