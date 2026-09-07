import Foundation
import MercuryCore

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

    /// Routing is the shared decision (`MercuryCore.ComposerRoutingPolicy`);
    /// this maps its sealed result onto the Swift action.
    static func route(draft: String, turnActive: Bool, hasAttachments: Bool) -> Action {
        let action = MercuryCore.ComposerRoutingPolicy.shared.route(
            draft: draft, turnActive: turnActive, hasAttachments: hasAttachments
        )
        switch action {
        case let submit as MercuryCore.ComposerActionSubmit:
            return .submit(text: submit.text)
        case let steer as MercuryCore.ComposerActionSteer:
            return .steer(text: steer.text)
        case is MercuryCore.ComposerActionOpenModelPicker:
            return .openModelPicker
        case let reasoning as MercuryCore.ComposerActionSetReasoning:
            return .setReasoning(effort: reasoning.effort)
        case let reject as MercuryCore.ComposerActionReject:
            switch reject.reason.name {
            case "BlankSteer": return .reject(.blankSteer)
            case "NoActiveTurnToSteer": return .reject(.noActiveTurnToSteer)
            case "AttachmentsUnavailableWhileSteering": return .reject(.attachmentsUnavailableWhileSteering)
            default: return .reject(.blankPrompt)
            }
        default:
            return .reject(.blankPrompt)
        }
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
        MercuryCore.ComposerRoutingPolicy.shared.shouldShowStopButton(
            isSending: isSending, turnActive: turnActive, draft: draft
        )
    }

    /// Once the server has accepted `prompt.submit`, the local draft must stay
    /// cleared even if a later lifecycle/transport operation reports an error.
    /// Before acceptance, restoring the draft is still the safe data-preserving
    /// behavior for validation or transport failures.
    static func shouldRestoreDraftAfterSubmissionFailure(submissionAccepted: Bool) -> Bool {
        MercuryCore.ComposerRoutingPolicy.shared.shouldRestoreDraftAfterSubmissionFailure(
            submissionAccepted: submissionAccepted
        )
    }
}
