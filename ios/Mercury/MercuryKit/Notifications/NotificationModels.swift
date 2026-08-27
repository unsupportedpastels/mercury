import Foundation

/// The user-attention category represented by a pending notification.
enum NotificationKind: Sendable, Equatable, Hashable {
    case completion(status: CompletionStatus)
    case approval
    case clarification
    case secureInput
}

enum CompletionStatus: Sendable, Equatable, Hashable {
    case finished
    case failed
    case cancelled
}

/// The app-level visibility state relevant to notification suppression.
/// iOS has no separate window-focus state in this policy layer.
struct SessionNotificationVisibility: Sendable, Equatable {
    var appForeground: Bool = false
    var visibleSessionID: String? = nil
}

struct PendingNotification: Sendable, Equatable {
    let sessionID: String
    let kind: NotificationKind
    let sessionTitle: String
    let heading: String
    let body: String
    let dedupeKey: String
}

/// Persistable per-session notification watermark owned by the platform layer.
struct SessionWatermark: Sendable, Equatable, Codable {
    var sessionID: String
    var lastCompletedTurnSignature: String? = nil
    var lastMessageCount: Int = 0
    /// Highest server-reported `message_count` this session has been reconciled
    /// at over the REST/background path. Used to detect "advanced since we last
    /// looked" without re-fetching a transcript every poll.
    var lastServerMessageCount: Int = 0
    var hasOpenApproval: Bool = false
    var hasOpenClarify: Bool = false
    var hasOpenSecure: Bool = false
}

struct CompletionOutcome: Sendable, Equatable {
    let text: String
    let status: CompletionStatus
    let turnSignature: String
}

struct ReconciliationDelta: Sendable, Equatable {
    let sessionID: String
    let sessionTitle: String
    /// The server's current `message_count` for this session. Always set so the
    /// engine can advance `lastServerMessageCount` even on an advance-only delta
    /// (mid-turn / non-assistant tail) that posts nothing.
    let serverMessageCount: Int
    let newCompletion: CompletionOutcome?
    let openedApproval: Bool
    let openedClarify: Bool
    let openedSecure: Bool
}
