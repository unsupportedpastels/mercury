import Foundation

/// Owns one explicit `prompt.submit(queued: true)` admission attempt.
/// It keeps the draft until the server acknowledges the queue so a transport
/// failure cannot silently discard app-owned dictation or typed input.
struct QueuedPromptLifecycle: Sendable, Equatable {
    struct Attempt: Sendable, Equatable {
        fileprivate let id: UInt64
        fileprivate let draft: String
    }

    enum Resolution: Sendable, Equatable {
        case accepted
        case restoreDraft(String)
        case stale
    }

    private var nextAttemptID: UInt64 = 0
    private var currentAttempt: Attempt?
    var hasPendingAttempt: Bool { currentAttempt != nil }

    /// Explicit user discard only. It does not cancel or replay server work.
    mutating func discard() { currentAttempt = nil }

    mutating func begin(draft: String) -> Attempt? {
        guard currentAttempt == nil else { return nil }
        nextAttemptID &+= 1
        let attempt = Attempt(id: nextAttemptID, draft: draft)
        currentAttempt = attempt
        return attempt
    }

    mutating func resolve(attempt: Attempt, accepted: Bool) -> Resolution {
        guard currentAttempt?.id == attempt.id else { return .stale }
        currentAttempt = nil
        return accepted ? .accepted : .restoreDraft(attempt.draft)
    }
}
