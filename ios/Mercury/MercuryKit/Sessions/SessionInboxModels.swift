import Foundation

enum ActiveSessionStatus: String, Sendable, Equatable {
    case idle
    case starting
    case working
    case waiting

    var isRunning: Bool { self != .idle }
}

struct ActiveSessionRuntime: Sendable, Equatable {
    let runtimeSessionID: String
    let durableSessionID: String
    let title: String
    let status: ActiveSessionStatus
    let messageCount: Int?
    let model: String?
    let lastActive: Date?

    init(
        runtimeSessionID: String,
        durableSessionID: String,
        title: String,
        status: ActiveSessionStatus,
        messageCount: Int? = nil,
        model: String? = nil,
        lastActive: Date? = nil
    ) {
        self.runtimeSessionID = runtimeSessionID
        self.durableSessionID = durableSessionID
        self.title = title
        self.status = status
        self.messageCount = messageCount
        self.model = model
        self.lastActive = lastActive
    }
}

enum SessionInboxIndicator: Sendable, Equatable {
    case idle
    case running
    case completedUnread
}

struct SessionInboxActivityTracker: Sendable, Equatable {
    private(set) var runningSessionIDs = Set<String>()
    private(set) var unreadCompletedSessionIDs = Set<String>()
    private(set) var visibleSessionID: String?

    mutating func apply(_ runtimes: [ActiveSessionRuntime]) {
        let nextRunning = Set(
            runtimes.lazy
                .filter { $0.status.isRunning }
                .map(\.durableSessionID)
        )
        let newlyCompleted = runningSessionIDs.subtracting(nextRunning)
        for id in newlyCompleted where id != visibleSessionID {
            unreadCompletedSessionIDs.insert(id)
        }
        for id in nextRunning {
            unreadCompletedSessionIDs.remove(id)
        }
        runningSessionIDs = nextRunning
    }

    mutating func setVisibleSession(_ id: String?) {
        visibleSessionID = id
        if let id { unreadCompletedSessionIDs.remove(id) }
    }

    mutating func markRead(_ id: String) {
        unreadCompletedSessionIDs.remove(id)
    }

    func indicator(for durableSessionID: String) -> SessionInboxIndicator {
        if runningSessionIDs.contains(durableSessionID) { return .running }
        if unreadCompletedSessionIDs.contains(durableSessionID) { return .completedUnread }
        return .idle
    }
}

enum SessionInboxPolicy {
    static func projectLabel(for session: SessionRow, projects: [ProjectSummary]) -> String? {
        if let direct = projects.first(where: { project in
            project.previewSessions.contains(where: { $0.id == session.id })
        }) {
            return direct.label
        }
        guard let workspace = normalizedPath(session.workspacePath) else { return nil }
        return projects
            .compactMap { project -> (ProjectSummary, Int)? in
                guard let path = normalizedPath(project.path),
                      workspace == path || workspace.hasPrefix(path + "/") else { return nil }
                return (project, path.count)
            }
            .max(by: { $0.1 < $1.1 })?
            .0.label
    }

    static func metadata(model: String?, messageCount: Int) -> String {
        let count = max(0, messageCount)
        let messages = "\(count) \(count == 1 ? "message" : "messages")"
        guard let model = model?.trimmingCharacters(in: .whitespacesAndNewlines),
              !model.isEmpty else { return messages }
        return "\(model) · \(messages)"
    }

    private static func normalizedPath(_ path: String?) -> String? {
        guard var path = path?.trimmingCharacters(in: .whitespacesAndNewlines),
              path.hasPrefix("/") else { return nil }
        while path.count > 1 && path.hasSuffix("/") { path.removeLast() }
        return path
    }
}
