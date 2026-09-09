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
                    terminalStatus: BackgroundTaskStatus(event.terminalStatus),
                    eventID: event.eventId,
                    historical: event.historical)
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
    /// Last time the gateway's subagent registry reported this child running.
    var registryConfirmedAtMillis: Int64 = 0
    var id: String { runtime + "/" + childID }
    var core: MercuryCore.BackgroundTaskRow {
        MercuryCore.BackgroundTaskRow(runtimeId: runtime, id: childID, goal: goal,
            action: action, status: status.core, observedAtMillis: observedAtMillis,
            available: available, identityKnown: identityKnown,
            registryConfirmedAtMillis: registryConfirmedAtMillis)
    }
    var terminal: Bool { core.terminal }
    func recentlyActive(now: Int64) -> Bool { core.recentlyActive(now: now) }
    func label(now: Int64) -> String { core.label(now: now) }
    func isDismissible(now: Int64) -> Bool { core.isDismissible(now: now) }
    var dismissalKey: String { core.dismissalKey() }
    func timeLabel(now: Int64) -> String { core.timeLabel(now: now) }
}

struct BackgroundTaskPresentation: Sendable, Equatable {
    let activeCount: Int
    let unresolvedCount: Int
    let terminalCount: Int
    let unavailableCount: Int
    let unknownCount: Int
    let terminalOnly: Bool
    let statusUnavailable: Bool
    let headline: String
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
                identityKnown: row.identityKnown,
                registryConfirmedAtMillis: row.registryConfirmedAtMillis)
        }
    }
    static func == (lhs: Self, rhs: Self) -> Bool { lhs.core == rhs.core }
    func activeCount(now: Int64) -> Int { Int(core.activeCount(now: now)) }
    func presentation(now: Int64) -> BackgroundTaskPresentation {
        presentation(rows: rows, now: now)
    }
    func presentation(rows: [BackgroundTaskRow], now: Int64) -> BackgroundTaskPresentation {
        let decision = MercuryCore.BackgroundTaskPresentationPolicy.shared.summarize(
            rows: rows.map(\.core), now: now
        )
        return BackgroundTaskPresentation(
            activeCount: Int(decision.activeCount),
            unresolvedCount: Int(decision.unresolvedCount),
            terminalCount: Int(decision.terminalCount),
            unavailableCount: Int(decision.unavailableCount),
            unknownCount: Int(decision.unknownCount),
            terminalOnly: decision.terminalOnly,
            statusUnavailable: decision.statusUnavailable,
            headline: decision.headline
        )
    }
    /// Rows a host-wide "running" surface may show; see the shared policy.
    static func runningRows(_ rows: [BackgroundTaskRow], now: Int64) -> [BackgroundTaskRow] {
        let core = MercuryCore.BackgroundTaskPresentationPolicy.shared.runningRows(rows: rows.map(\.core), now: now)
        return core.map { row in
            BackgroundTaskRow(runtime: row.runtimeId, childID: row.id, goal: row.goal,
                action: row.action, status: BackgroundTaskStatus(row.status),
                observedAtMillis: row.observedAtMillis, available: row.available,
                identityKnown: row.identityKnown)
        }
    }
    func secondaryLabel(rows: [BackgroundTaskRow], now: Int64) -> String {
        MercuryCore.BackgroundTaskPresentationPolicy.shared.secondaryLabel(
            rows: rows.map(\.core), now: now
        )
    }
    mutating func markUnavailable() { core = core.unavailable() }
    /// `now` stamps a "running" registry answer as confirmed liveness for one
    /// activity window; it never touches the worker-observed timestamp.
    /// `previousRuntime` is the caller's proof that rows still bound to an
    /// earlier runtime of this same durable session may move to `runtime`
    /// when the registry reports them running (Android parity).
    mutating func reconcile(_ statuses: [String: String], runtime: String,
                            previousRuntime: String? = nil, now: Int64 = 0) {
        core = core.reconcile(active: statuses.map {
            MercuryCore.BackgroundTaskRegistryEntry(subagentId: $0.key, status: $0.value)
        }, runtime: runtime, previousRuntime: previousRuntime ?? runtime, now: now)
    }
    /// Unresolved children with a known identity: the rows worth asking the host about.
    var hasUnresolvedIdentifiedChildren: Bool {
        rows.contains { !$0.terminal && $0.identityKnown }
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
