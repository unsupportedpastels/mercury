import Foundation

enum OperationsBounds {
    static let maxCronRows = 128
    static let maxProcessRows = 50
    static let maxActivityRows = 50
    static let maxFieldCharacters = 512
    static let maxIDCharacters = 256
    static let maxProfileCharacters = 64
    static let maxCommandCharacters = 4_096
    static let maxOutputCharacters = 4_000
    static let maxStatusCharacters = 64
    static let maxOperationalComponents = 32
}

enum OperationsProtocolError: Error, Equatable, LocalizedError {
    case invalidInput(String)
    case malformedResponse(String)

    var errorDescription: String? {
        switch self {
        case .invalidInput(let message), .malformedResponse(let message): message
        }
    }
}

struct CronJob: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let schedule: String
    let enabled: Bool?
    let state: String?
    let nextRunAt: String?
    let lastRunAt: String?
    let lastStatus: String?
    let lastDeliveryError: String?

    var displayStatus: String {
        if enabled == true { return "Enabled" }
        if enabled == false { return "Paused" }
        return state ?? lastStatus ?? "Unknown"
    }

    var requiresAttention: Bool {
        if let lastDeliveryError, !lastDeliveryError.isEmpty { return true }
        guard let status = lastStatus?.lowercased() else { return false }
        let successful = Set(["completed", "success", "succeeded", "ok"])
        let terminal = successful.union(["failed", "failure", "error", "timeout", "timed_out", "cancelled", "canceled", "skipped", "partial"])
        return terminal.contains(status) && !successful.contains(status)
    }
}

struct CronTriggerResult: Equatable, Sendable {
    let refreshedJob: CronJob?
    let accepted: Bool
    let background: Bool

    var message: String {
        if refreshedJob != nil { return "Run requested; status refreshed from the server." }
        guard accepted else { return "Run was not accepted." }
        return background ? "Run accepted in the background." : "Run accepted."
    }
}

enum OperationalHealth: Equatable, Sendable {
    case ok
    case degraded
    case unknown
}

enum OperationalPressure: Equatable, Sendable {
    case ok
    case warning
    case critical
    case unknown
}

struct OperationalComponentStatus: Identifiable, Equatable, Sendable {
    let name: String
    let health: OperationalHealth
    let state: String?
    var id: String { name }
}

struct OperationalStatus: Equatable, Sendable {
    let profile: String
    let version: String?
    let overall: OperationalHealth
    let components: [OperationalComponentStatus]
    let memoryPressure: OperationalPressure
    let diskPressure: OperationalPressure
}

enum OperationalStatusParser {
    static func parse(_ result: [String: Any], profile: String) -> OperationalStatus {
        let profile = String(profile.trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(OperationsBounds.maxProfileCharacters))
        return OperationalStatus(
            profile: profile.isEmpty ? "default" : profile,
            version: operationalText(result["version"]),
            overall: operationalHealth(result["overall"]),
            components: components(result["components"]),
            memoryPressure: operationalPressure(result["memory"] ?? result["memory_pressure"]),
            diskPressure: operationalPressure(result["disk"] ?? result["disk_pressure"])
        )
    }

    private static func components(_ value: Any?) -> [OperationalComponentStatus] {
        if let object = value as? [String: Any] {
            return object.sorted { $0.key < $1.key }
                .prefix(OperationsBounds.maxOperationalComponents)
                .compactMap { name, value in
                let name = String(name.trimmingCharacters(in: .whitespacesAndNewlines)
                    .prefix(OperationsBounds.maxFieldCharacters))
                guard !name.isEmpty else { return nil }
                let row = value as? [String: Any]
                return OperationalComponentStatus(
                    name: name,
                    health: operationalHealth(row?["status"] ?? value),
                    state: operationalText(row?["state"])
                )
            }
        }
        if let array = value as? [Any] {
            return array.prefix(OperationsBounds.maxOperationalComponents).compactMap { value in
                guard let row = value as? [String: Any], let name = operationalText(row["name"]) else { return nil }
                return OperationalComponentStatus(
                    name: name,
                    health: operationalHealth(row["status"]),
                    state: operationalText(row["state"])
                )
            }
        }
        return []
    }

    private static func operationalHealth(_ value: Any?) -> OperationalHealth {
        return switch operationalText(value)?.lowercased() {
        case "ok", "healthy", "ready", "running": .ok
        case "degraded", "warning", "critical", "error", "failed", "unhealthy": .degraded
        default: .unknown
        }
    }

    private static func operationalPressure(_ value: Any?) -> OperationalPressure {
        let scalar: Any?
        if let object = value as? [String: Any] {
            scalar = object["pressure"] ?? object["status"]
        } else {
            scalar = value
        }
        return switch operationalText(scalar)?.lowercased() {
        case "ok", "healthy": .ok
        case "warning", "elevated", "degraded": .warning
        case "critical", "full": .critical
        default: .unknown
        }
    }

    private static func operationalText(_ value: Any?) -> String? {
        guard let string = value as? String, !string.isEmpty else { return nil }
        return String(string.prefix(OperationsBounds.maxFieldCharacters))
    }
}

enum CronPendingAction: String, Equatable, Hashable, Sendable {
    case enable
    case disable
    case runNow
}

/// Pure per-job mutation state. A job can have at most one action in flight,
/// while unrelated jobs remain independently actionable.
struct CronActionState: Equatable, Sendable {
    private(set) var pending: [String: CronPendingAction] = [:]
    private(set) var messages: [String: String] = [:]

    mutating func begin(jobID: String, action: CronPendingAction) -> Bool {
        guard pending[jobID] == nil else { return false }
        pending[jobID] = action
        messages.removeValue(forKey: jobID)
        return true
    }

    mutating func finish(jobID: String, message: String? = nil) {
        pending.removeValue(forKey: jobID)
        if let message { messages[jobID] = String(message.prefix(OperationsBounds.maxFieldCharacters)) }
    }

    func pendingAction(for jobID: String) -> CronPendingAction? { pending[jobID] }
    func message(for jobID: String) -> String? { messages[jobID] }
}

struct ActivityProcess: Identifiable, Equatable, Sendable {
    let id: String
    let command: String
    let status: String
    let outputTail: String?
    let exitCode: Int?
    let uptimeSeconds: Int64?

    init(
        id: String,
        command: String,
        status: String,
        outputTail: String? = nil,
        exitCode: Int? = nil,
        uptimeSeconds: Int64? = nil
    ) {
        self.id = id
        self.command = command
        self.status = status
        self.outputTail = outputTail
        self.exitCode = exitCode
        self.uptimeSeconds = uptimeSeconds
    }
}

enum ActivityTodoStatus: String, Equatable, Sendable {
    case pending
    case inProgress
    case completed
    case cancelled
}

struct ActivityTodo: Identifiable, Equatable, Sendable {
    let id: String
    let content: String
    let status: ActivityTodoStatus
}

enum ActivityLoopStatus: String, Equatable, Sendable {
    case pending
    case running
    case completed
    case failed
    case paused
}

struct ActivityLoop: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let status: ActivityLoopStatus
}

enum ActivityFamily: Equatable, Sendable {
    case todos
    case loops
    case processes
}

/// Presentation-ready activity includes only explicitly audited families.
/// Tools, subagents, approvals, secrets, and inferred transcript activity are
/// intentionally absent rather than represented as misleading empty rows.
struct ActivityStackState: Equatable, Sendable {
    let todos: [ActivityTodo]
    let loops: [ActivityLoop]
    let processes: [ActivityProcess]

    init(
        todos: [ActivityTodo] = [],
        loops: [ActivityLoop] = [],
        processes: [ActivityProcess] = []
    ) {
        self.todos = Array(todos.prefix(OperationsBounds.maxActivityRows))
        self.loops = Array(loops.prefix(OperationsBounds.maxActivityRows))
        self.processes = Array(processes.prefix(OperationsBounds.maxProcessRows))
    }

    var visibleFamilies: [ActivityFamily] {
        var result: [ActivityFamily] = []
        if !todos.isEmpty { result.append(.todos) }
        if !loops.isEmpty { result.append(.loops) }
        if !processes.isEmpty { result.append(.processes) }
        return result
    }

    var isEmpty: Bool { visibleFamilies.isEmpty }

    var isRunning: Bool {
        todos.contains { $0.status == .pending || $0.status == .inProgress } ||
        loops.contains { $0.status == .pending || $0.status == .running } ||
        processes.contains { $0.status.lowercased() == "running" }
    }

    var summary: String {
        let countedTodos = todos.filter { $0.status != .cancelled }
        let completedTodos = countedTodos.filter { $0.status == .completed }.count
        var parts = ["Activity", "\(completedTodos)/\(countedTodos.count) tasks"]
        if !loops.isEmpty { parts.append("\(loops.count) \(loops.count == 1 ? "loop" : "loops")") }
        if !processes.isEmpty {
            parts.append("\(processes.count) process-local \(processes.count == 1 ? "process" : "processes")")
        }
        return parts.joined(separator: " · ")
    }
}
