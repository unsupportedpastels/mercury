import Foundation
import Observation

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

/// One app-scoped queue receipt. Presentation state can disappear independently.
@MainActor @Observable
final class QueuedPromptState {
    var lifecycle = QueuedPromptLifecycle()
    var uncertain = false
    var error: String?
    var notice: String?
    var draftToRestore: String?
}

struct QueuedPromptScope: Hashable {
    let relayTargetID: UUID?
    let origin: String
    let profile: String
    let durableID: String
}

@MainActor
final class QueuedPromptStateStore {
    static let maxEntries = 64
    private var states: [QueuedPromptScope: QueuedPromptState] = [:]

    func state(for scope: QueuedPromptScope) -> QueuedPromptState? { states[scope] }

    @discardableResult
    func retain(_ state: QueuedPromptState, for scope: QueuedPromptScope) -> Bool {
        if states[scope] != nil { return states[scope] === state }
        if states.count >= Self.maxEntries {
            guard let settled = states.first(where: { !$0.value.lifecycle.hasPendingAttempt && $0.value.draftToRestore == nil })?.key else { return false }
            states.removeValue(forKey: settled)
        }
        states[scope] = state
        return true
    }

    func remove(origin: String, relayTargetID: UUID? = nil) {
        states = states.filter { $0.key.origin != origin || $0.key.relayTargetID != relayTargetID }
    }
}
