import Foundation

/// Deterministic composer routing for M7. The view performs RPCs, while this
/// policy guarantees that local commands never leak into `prompt.submit` and
/// active-turn text always uses `session.steer`.
struct M7ComposerPolicy {
    enum Rejection: Equatable, Sendable {
        case blankPrompt
        case blankSteer
        case noActiveTurnToSteer
        case attachmentsUnavailableWhileSteering
    }

    enum Action: Equatable, Sendable {
        case submit(text: String)
        case steer(text: String)
        case openModelPicker
        case setReasoning(effort: String)
        case reject(Rejection)
    }

    static func route(draft: String, turnActive: Bool, hasAttachments: Bool) -> Action {
        if isModelPickerCommand(draft) {
            return .openModelPicker
        }
        if let effort = reasoningEffortCommand(draft) {
            return .setReasoning(effort: effort)
        }

        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if isSteerCommand(draft) {
            let payload = steerPayload(from: draft)
            guard !payload.isEmpty else { return .reject(.blankSteer) }
            guard turnActive else { return .reject(.noActiveTurnToSteer) }
            guard !hasAttachments else { return .reject(.attachmentsUnavailableWhileSteering) }
            return .steer(text: payload)
        }

        if turnActive {
            guard !hasAttachments else { return .reject(.attachmentsUnavailableWhileSteering) }
            guard !trimmed.isEmpty else { return .reject(.blankSteer) }
            return .steer(text: trimmed)
        }

        guard !trimmed.isEmpty || hasAttachments else { return .reject(.blankPrompt) }
        return .submit(text: trimmed)
    }

    static func shouldRequestSlashCompletion(text: String, connectionIsLive: Bool) -> Bool {
        connectionIsLive && isSlashCommandContext(text)
    }

    static func mayPublishSlashCompletion(responseGeneration: Int, currentGeneration: Int) -> Bool {
        responseGeneration == currentGeneration
    }

    /// Android replaces the composer's send affordance with Stop while a turn
    /// is active and the composer is empty. Typing guidance restores the
    /// active-turn send/steer affordance without adding a second stop control.
    static func shouldShowStopButton(isSending: Bool, turnActive: Bool, draft: String) -> Bool {
        (isSending || turnActive)
            && draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Once the server has accepted `prompt.submit`, the local draft must stay
    /// cleared even if a later lifecycle/transport operation reports an error.
    /// Before acceptance, restoring the draft is still the safe data-preserving
    /// behavior for validation or transport failures.
    static func shouldRestoreDraftAfterSubmissionFailure(submissionAccepted: Bool) -> Bool {
        !submissionAccepted
    }

    private static func steerPayload(from text: String) -> String {
        let command = String(text.drop(while: { $0.isWhitespace }))
        guard command == "/steer" || command.hasPrefix("/steer ") else { return "" }
        return String(command.dropFirst("/steer".count))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
