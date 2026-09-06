import Foundation
import MercuryCore

// Swift presentation DTOs only; all parsing, ownership and reduction live in KMP.
enum BackgroundTaskStatus: Sendable, Equatable {
    case active, finished, failed, stopped, unknown
    init(_ core: MercuryCore.BackgroundTaskStatus) {
        switch core {
        case .active: self = .active
        case .finished: self = .finished
        case .failed: self = .failed
        case .stopped: self = .stopped
        default: self = .unknown
        }
    }
    var core: MercuryCore.BackgroundTaskStatus {
        switch self {
        case .active: return .active
        case .finished: return .finished
        case .failed: return .failed
        case .stopped: return .stopped
        case .unknown: return .unknown
        }
    }
}
struct BackgroundTaskEvidence: Sendable, Equatable {
    enum Kind: Sendable { case start, tool, complete }
    var kind: Kind
    var childID: String?
    var goal: String?
    var action: String?
    var terminalStatus: BackgroundTaskStatus
    var eventID: String? = nil
    var historical = false

    static func decode(type: String, payload: [String: Any]) -> Self? {
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8),
              let event = MercuryCore.RelayRecoveryDecoder.shared.backgroundTaskEvent(
                type: type, runtime: "adapter", payloadJson: json
              ) else { return nil }
        return Self(kind: event.kind == .start ? .start : event.kind == .tool ? .tool : .complete,
                    childID: event.childId, goal: event.goal, action: event.action,
                    terminalStatus: BackgroundTaskStatus(event.terminalStatus))
    }
    func core(session: String) -> MercuryCore.BackgroundTaskEvent {
        MercuryCore.BackgroundTaskEvent(sessionId: session,
            kind: kind == .start ? .start : kind == .tool ? .tool : .complete,
            childId: childID, goal: goal, action: action, terminalStatus: terminalStatus.core,
            eventId: eventID, historical: historical)
    }
}
struct BackgroundTaskRow: Sendable, Equatable, Identifiable {
    var runtime: String
    var childID: String
    var goal: String
    var action: String?
    var status: BackgroundTaskStatus
    var observedAtMillis: Int64
    var available = true
    var identityKnown = true
    var id: String { runtime + "/" + childID }
    var core: MercuryCore.BackgroundTaskRow {
        MercuryCore.BackgroundTaskRow(runtimeId: runtime, id: childID, goal: goal,
            action: action, status: status.core, observedAtMillis: observedAtMillis,
            available: available, identityKnown: identityKnown)
    }
    var terminal: Bool { core.terminal }
    func recentlyActive(now: Int64) -> Bool { core.recentlyActive(now: now) }
    func label(now: Int64) -> String { core.label(now: now) }
}
struct BackgroundTasks: @unchecked Sendable, Equatable {
    private var core: MercuryCore.BackgroundTasks
    init(rows: [BackgroundTaskRow] = []) {
        core = MercuryCore.BackgroundTasks(rows: rows.map(\.core), processedEventIds: [])
    }
    var rows: [BackgroundTaskRow] {
        core.rows.map { row in
            BackgroundTaskRow(runtime: row.runtimeId, childID: row.id, goal: row.goal,
                action: row.action, status: BackgroundTaskStatus(row.status),
                observedAtMillis: row.observedAtMillis, available: row.available,
                identityKnown: row.identityKnown)
        }
    }
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.core == rhs.core }
    func activeCount(now: Int64) -> Int { Int(core.activeCount(now: now)) }
    mutating func markUnavailable() { core = core.unavailable() }
    mutating func reconcile(_ statuses: [String: String], runtime: String) {
        core = core.reconcile(active: statuses.map {
            MercuryCore.BackgroundTaskRegistryEntry(subagentId: $0.key, status: $0.value)
        }, runtime: runtime, previousRuntime: runtime)
    }
    mutating func recover(snapshot: MercuryCore.RelayLeaseSnapshot, durableID: String,
                          profile: String, runtime: String?) {
        core = core.recoverRelayTasks(snapshot: snapshot, durableId: durableID,
                                      profile: profile, runtime: runtime)
    }
    /// Return replay evidence only after this durable session's reducer owns it.
    @discardableResult
    mutating func apply(_ event: ChatEvent, runtime: String, now: Int64) -> String? {
        guard case .backgroundTask(let session, let evidence) = event,
              session == runtime else { return nil }
        core = core.reduce(event: evidence.core(session: session), expectedRuntime: runtime, now: now)
        return evidence.eventID
    }
}
