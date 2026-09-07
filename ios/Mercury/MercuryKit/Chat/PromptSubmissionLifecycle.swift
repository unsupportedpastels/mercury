import Foundation
import MercuryCore

/// Native owner for one prompt.submit admission attempt.
///
/// The WebSocket event stream and the correlated prompt.submit response are
/// independent asynchronous results. A terminal event can therefore arrive
/// while the RPC acknowledgement is still pending. This helper keeps the
/// attempt identity and terminal-before-ack fact together so a late callback
/// cannot settle a newer draft.
struct PromptSubmissionLifecycle: Sendable, Equatable {
    struct Attempt: Sendable, Equatable {
        fileprivate let id: UInt64
        fileprivate let draft: String
        fileprivate let hostReferenceIDs: [String]
    }

    enum Phase: Equatable, Sendable {
        case idle
        case awaitingAcceptance
        case terminalBeforeAcceptance
    }

    enum TerminalEffect: Equatable, Sendable {
        case releasedPending(hostReferenceIDs: [String])
        case ignored
    }

    enum Resolution: Equatable, Sendable {
        case accepted(hostReferenceIDs: [String])
        case restoreDraft(String)
        case authoritativeTerminal
        case stale
    }

    private(set) var phase: Phase = .idle
    private var nextAttemptID: UInt64 = 0
    private var currentAttempt: Attempt?

    /// Starts a new admission attempt. A second attempt is rejected until the
    /// current one is authoritatively terminal or has been acknowledged.
    mutating func begin(draft: String, hostReferenceIDs: [String] = []) -> Attempt? {
        guard phase != .awaitingAcceptance else { return nil }
        nextAttemptID &+= 1
        let attempt = Attempt(
            id: nextAttemptID,
            draft: draft,
            hostReferenceIDs: hostReferenceIDs
        )
        currentAttempt = attempt
        phase = .awaitingAcceptance
        return attempt
    }

    /// Records a terminal message/error for the currently awaiting attempt.
    /// The terminal event releases the UI gate before prompt.submit's ACK, but
    /// does not discard the attempt: a late ACK still needs an identity check.
    /// Reference ids are returned so the caller can remove only the accepted
    /// attempt's host references before a follow-up send reuses the composer.
    mutating func observeTerminal() -> TerminalEffect {
        guard let currentAttempt, phase == .awaitingAcceptance else {
            return .ignored
        }
        let decision = MercuryCore.PromptSubmissionPolicy.shared.onTerminalEvent(
            awaitingAcceptance: true
        )
        guard decision == .releasePending else { return .ignored }
        phase = .terminalBeforeAcceptance
        return .releasedPending(hostReferenceIDs: currentAttempt.hostReferenceIDs)
    }

    /// Settles one asynchronous acknowledgement. Stale attempts are a strict
    /// no-op: they cannot clear the current gate, surface an old error, restore
    /// old text, or remove references staged for a newer submission.
    mutating func resolve(attempt: Attempt, accepted: Bool) -> Resolution {
        guard let currentAttempt, currentAttempt.id == attempt.id else {
            return .stale
        }

        let decision = MercuryCore.PromptSubmissionPolicy.shared.onAcknowledgement(
            authoritativeTerminal: phase == .terminalBeforeAcceptance,
            accepted: accepted
        )
        self.currentAttempt = nil
        phase = .idle

        switch decision {
        case .accepted:
            return .accepted(hostReferenceIDs: currentAttempt.hostReferenceIDs)
        case .restoreDraft:
            return .restoreDraft(currentAttempt.draft)
        case .ignore:
            return .authoritativeTerminal
        case .releasePending:
            // This is not a valid acknowledgement decision, but fail closed
            // if the shared policy grows a new outcome without this adapter
            // being updated at the same time.
            return .authoritativeTerminal
        default:
            // Keep the adapter bounded if the KMP enum gains a new outcome:
            // never restore text or touch a newer action implicitly.
            return .authoritativeTerminal
        }
    }
}
