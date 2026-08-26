import Foundation

/// Client-side limits for project metadata returned by the Hermes project RPCs.
enum ProjectModelBounds {
    static let maxProjects = 100
    static let maxPreviewSessions = 3
    static let maxScopedSessionIDs = 2_000
    static let maxLoadedSessions = 100
    /// Android DEFAULT/MAX_PROJECT_SESSION_LIMIT parity: the `session_limit`
    /// sent on `projects.project_sessions` is the SERVER'S global session scan
    /// budget (how many recent rows it groups into projects), NOT the per-
    /// project row cap. Sending the 100-row parse cap here made the server
    /// scan only the 100 most recent sessions across ALL projects, so any
    /// project whose sessions were older showed 0 sessions.
    static let sessionScanBudget = 500
    static let maxLabelCharacters = 160
    static let maxTitleCharacters = 160
    static let maxPathCharacters = 1_024
    static let maxIDCharacters = 256
}

struct ProjectID: RawRepresentable, Hashable, Codable, Sendable {
    let rawValue: String

    init(rawValue: String) { self.rawValue = rawValue }
    init(_ rawValue: String) { self.rawValue = rawValue }
}

let noProjectBucketID = ProjectID("__no_project__")

struct ProjectSession: Identifiable, Equatable, Sendable {
    var id: String
    var title: String
    var preview: String
    var lastActive: Date?
    var messageCount: Int
    var model: String?
    var provider: String?
    var profile: String?
    var pinned: Bool
    var archived: Bool
    var workspacePath: String?
    var projectID: ProjectID

    init(
        id: String,
        title: String = "Untitled session",
        preview: String = "",
        lastActive: Date? = nil,
        messageCount: Int = 0,
        model: String? = nil,
        provider: String? = nil,
        profile: String? = nil,
        pinned: Bool = false,
        archived: Bool = false,
        workspacePath: String? = nil,
        projectID: ProjectID
    ) {
        self.id = id
        self.title = String(title.prefix(ProjectModelBounds.maxTitleCharacters))
        self.preview = String(preview.prefix(ProjectModelBounds.maxTitleCharacters))
        self.lastActive = lastActive
        self.messageCount = max(0, messageCount)
        self.model = model
        self.provider = provider
        self.profile = profile
        self.pinned = pinned
        self.archived = archived
        self.workspacePath = workspacePath
        self.projectID = projectID
    }
}

struct ProjectGroup: Identifiable, Equatable, Sendable {
    var id: String
    var label: String
    var path: String?
    var isMain: Bool
    var isKanban: Bool
    var sessions: [ProjectSession]
}

struct ProjectRepository: Identifiable, Equatable, Sendable {
    var id: String
    var label: String
    var path: String?
    var sessionCount: Int
    var groups: [ProjectGroup]
}

struct ProjectSummary: Identifiable, Equatable, Sendable {
    var id: ProjectID
    var label: String
    var path: String?
    var isAuto: Bool
    var isNoProject: Bool
    var sessionCount: Int
    var lastActive: Date?
    var previewSessions: [ProjectSession]
    var repos: [ProjectRepository]

    /// Descriptive alias for callers that do not mirror the wire key.
    var repositories: [ProjectRepository] { repos }

    init(
        id: ProjectID,
        label: String,
        path: String?,
        isAuto: Bool = false,
        isNoProject: Bool = false,
        sessionCount: Int = 0,
        lastActive: Date? = nil,
        previewSessions: [ProjectSession] = [],
        repositories: [ProjectRepository] = []
    ) {
        self.id = id
        let boundedLabel = String(label.prefix(ProjectModelBounds.maxLabelCharacters))
        self.label = boundedLabel.isEmpty ? id.rawValue : boundedLabel
        self.path = id == noProjectBucketID ? nil : path
        self.isAuto = isAuto
        self.isNoProject = isNoProject || id == noProjectBucketID
        self.sessionCount = max(0, sessionCount)
        self.lastActive = lastActive
        self.previewSessions = Array(previewSessions.prefix(ProjectModelBounds.maxPreviewSessions))
        self.repos = Array(repositories.prefix(ProjectModelBounds.maxProjects))
    }
}

struct ProjectTree: Equatable, Sendable {
    var projects: [ProjectSummary]
    var activeProjectID: ProjectID?
    var scopedSessionIDs: Set<String>
}

/// Android-compatible name for the parsed `projects.tree` result.
typealias ProjectTreeResult = ProjectTree

struct ProjectSessionsResult: Equatable, Sendable {
    var project: ProjectSummary
    var sessions: [ProjectSession]
}

/// Strict result of `projects.create`. The server-returned project ID is the
/// only authoritative identity; clients must never derive it from a folder.
struct ProjectCreateResult: Equatable, Sendable {
    var project: ProjectSummary
}

enum ProjectModelParsingError: Error, Equatable {
    case malformedCreateResult
    case malformedActiveProjectResult
}

/// A REST session row enriched with project membership supplied by project RPC metadata.
struct ReconciledProjectSession: Identifiable, Equatable {
    var row: SessionRow
    var projectID: ProjectID
    var workspacePath: String?
    var provider: String?
    var pinned: Bool
    var archived: Bool

    var id: String { row.id }
}

/// Pure durable-ID reconciliation. Runtime/session-key identifiers are never consulted.
enum ProjectSessionReconciler {
    static func reconcile(
        projectSessions: [ProjectSession],
        restSessions: [SessionRow],
        project: ProjectSummary
    ) -> [ReconciledProjectSession] {
        var restByDurableID: [String: SessionRow] = [:]
        for row in restSessions where restByDurableID[row.id] == nil {
            restByDurableID[row.id] = row
        }

        var seen = Set<String>()
        return projectSessions.compactMap { rpc in
            guard seen.insert(rpc.id).inserted else { return nil }
            let row = restByDurableID[rpc.id] ?? SessionRow(
                id: rpc.id,
                title: rpc.title,
                preview: rpc.preview,
                lastActive: rpc.lastActive,
                messageCount: rpc.messageCount,
                model: rpc.model,
                provider: rpc.provider,
                profile: rpc.profile,
                workspacePath: rpc.workspacePath ?? project.path
            )
            return ReconciledProjectSession(
                row: row,
                projectID: project.id,
                workspacePath: rpc.workspacePath ?? project.path,
                provider: rpc.provider,
                pinned: rpc.pinned,
                archived: rpc.archived
            )
        }
    }
}

/// Tolerant, bounded parsing for `projects.tree` and `projects.project_sessions` results.
/// Unknown fields are ignored and malformed child rows are skipped independently.
enum ProjectModelsParser {
    static func parseTree(_ data: Data) throws -> ProjectTree {
        let root = try rootObject(data)
        let rawProjects = root["projects"] as? [Any] ?? []
        var seenProjects = Set<ProjectID>()
        var projects: [ProjectSummary] = []
        for raw in rawProjects {
            guard projects.count < ProjectModelBounds.maxProjects,
                  let object = raw as? [String: Any],
                  let project = parseProject(object),
                  seenProjects.insert(project.id).inserted else { continue }
            projects.append(project)
        }

        let activeID = validID(root["active_id"]).map { ProjectID($0) }
        let rawScoped = root["scoped_session_ids"] as? [Any] ?? []
        var scopedIDs = Set<String>()
        for raw in rawScoped {
            guard scopedIDs.count < ProjectModelBounds.maxScopedSessionIDs else { break }
            let candidate: Any?
            if let object = raw as? [String: Any] {
                candidate = object["id"] ?? object["durable_id"]
            } else {
                candidate = raw
            }
            if let id = validID(candidate) { scopedIDs.insert(id) }
        }
        return ProjectTree(projects: projects, activeProjectID: activeID, scopedSessionIDs: scopedIDs)
    }

    static func parseProjectSessions(
        _ data: Data,
        requestedProjectID: ProjectID
    ) throws -> ProjectSessionsResult {
        let root = try rootObject(data)
        let projectObject = root["project"] as? [String: Any]
        let parsedProject = projectObject.flatMap { parseProject($0, fallbackID: requestedProjectID) }
        let project = parsedProject ?? ProjectSummary(
            id: requestedProjectID,
            label: requestedProjectID.rawValue,
            path: nil
        )

        var candidates: [ProjectSession] = []
        if let projectObject {
            appendNestedSessions(from: projectObject, projectID: project.id, to: &candidates)
            appendSessions(projectObject["sessions"], projectID: project.id, to: &candidates)
        }
        appendSessions(root["sessions"], projectID: project.id, to: &candidates)

        var seen = Set<String>()
        let sessions = candidates.compactMap { session -> ProjectSession? in
            guard seen.count < ProjectModelBounds.maxLoadedSessions,
                  seen.insert(session.id).inserted else { return nil }
            return session
        }
        return ProjectSessionsResult(project: project, sessions: sessions)
    }

    /// Unlike tree/session child parsing, create is a mutation response and
    /// its required top-level project and authoritative ID must be intact.
    static func parseCreateResult(_ data: Data) throws -> ProjectCreateResult {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let object = root["project"] as? [String: Any],
              validID(object["id"]) != nil,
              let project = parseProject(object) else {
            throw ProjectModelParsingError.malformedCreateResult
        }
        return ProjectCreateResult(project: project)
    }

    /// Parses the strict acknowledgement from `projects.set_active` while
    /// allowing JSON null for a future explicit clear operation.
    static func parseActiveProjectID(_ data: Data) throws -> ProjectID? {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let value = root["active_id"] else {
            throw ProjectModelParsingError.malformedActiveProjectResult
        }
        if value is NSNull { return nil }
        guard let id = validID(value) else {
            throw ProjectModelParsingError.malformedActiveProjectResult
        }
        return ProjectID(id)
    }

    private static func rootObject(_ data: Data) throws -> [String: Any] {
        (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private static func parseProject(
        _ object: [String: Any],
        fallbackID: ProjectID? = nil
    ) -> ProjectSummary? {
        let id = validID(object["id"] ?? object["project_id"]).map { ProjectID($0) } ?? fallbackID
        guard let id else { return nil }
        let label = boundedString(object["label"] ?? object["name"], max: ProjectModelBounds.maxLabelCharacters)
            ?? id.rawValue
        let path = validPath(object["path"] ?? object["primary_path"])
        let previewsValue = object["previewSessions"] ?? object["preview_sessions"]
        var previews: [ProjectSession] = []
        appendSessions(previewsValue, projectID: id, limit: ProjectModelBounds.maxPreviewSessions, to: &previews)
        let repositories = parseRepositories(object["repos"], projectID: id)
        return ProjectSummary(
            id: id,
            label: label,
            path: path,
            isAuto: bool(object["isAuto"] ?? object["is_auto"]) ?? false,
            isNoProject: bool(object["isNoProject"] ?? object["is_no_project"]) ?? false,
            sessionCount: integer(object["sessionCount"] ?? object["session_count"] ?? object["count"]) ?? 0,
            lastActive: number(object["lastActive"] ?? object["last_active"]).map(epochDate),
            previewSessions: previews,
            repositories: repositories
        )
    }

    private static func parseRepositories(_ value: Any?, projectID: ProjectID) -> [ProjectRepository] {
        let rows = value as? [Any] ?? []
        var result: [ProjectRepository] = []
        var sessionBudget = ProjectModelBounds.maxLoadedSessions
        for raw in rows.prefix(ProjectModelBounds.maxProjects) {
            guard let object = raw as? [String: Any] else { continue }
            let path = validPath(object["path"])
            guard let id = validID(object["id"]) ?? path else { continue }
            let label = boundedString(object["label"] ?? object["name"], max: ProjectModelBounds.maxLabelCharacters) ?? id
            result.append(ProjectRepository(
                id: id,
                label: label,
                path: path,
                sessionCount: max(0, integer(object["sessionCount"] ?? object["session_count"]) ?? 0),
                groups: parseGroups(object["groups"], projectID: projectID, sessionBudget: &sessionBudget)
            ))
        }
        return result
    }

    private static func parseGroups(
        _ value: Any?,
        projectID: ProjectID,
        sessionBudget: inout Int
    ) -> [ProjectGroup] {
        let rows = value as? [Any] ?? []
        var result: [ProjectGroup] = []
        for raw in rows.prefix(ProjectModelBounds.maxProjects) {
            guard let object = raw as? [String: Any] else { continue }
            let path = validPath(object["path"])
            guard let id = validID(object["id"]) ?? path else { continue }
            var sessions: [ProjectSession] = []
            appendSessions(object["sessions"], projectID: projectID, limit: sessionBudget, to: &sessions)
            sessionBudget -= sessions.count
            result.append(ProjectGroup(
                id: id,
                label: boundedString(object["label"] ?? object["name"], max: ProjectModelBounds.maxLabelCharacters) ?? id,
                path: path,
                isMain: bool(object["isMain"] ?? object["is_main"]) ?? false,
                isKanban: bool(object["isKanban"] ?? object["is_kanban"]) ?? false,
                sessions: sessions
            ))
        }
        return result
    }

    private static func appendNestedSessions(
        from project: [String: Any],
        projectID: ProjectID,
        to result: inout [ProjectSession]
    ) {
        let repos = project["repos"] as? [Any] ?? []
        for rawRepo in repos.prefix(ProjectModelBounds.maxProjects) {
            guard result.count < ProjectModelBounds.maxLoadedSessions,
                  let repo = rawRepo as? [String: Any] else { continue }
            let groups = repo["groups"] as? [Any] ?? []
            for rawGroup in groups.prefix(ProjectModelBounds.maxProjects) {
                guard result.count < ProjectModelBounds.maxLoadedSessions,
                      let group = rawGroup as? [String: Any] else { continue }
                appendSessions(group["sessions"], projectID: projectID, to: &result)
            }
        }
    }

    private static func appendSessions(
        _ value: Any?,
        projectID: ProjectID,
        limit: Int = ProjectModelBounds.maxLoadedSessions,
        to result: inout [ProjectSession]
    ) {
        let rows = value as? [Any] ?? []
        for raw in rows {
            guard result.count < limit else { break }
            guard let object = raw as? [String: Any],
                  let session = parseSession(object, projectID: projectID) else { continue }
            result.append(session)
        }
    }

    private static func parseSession(_ object: [String: Any], projectID: ProjectID) -> ProjectSession? {
        // `session_key` can be a transient runtime id. Only durable fields are identity candidates.
        guard let id = validID(object["id"] ?? object["durable_id"]) else { return nil }
        let parsedTitle = boundedString(object["title"] ?? object["name"], max: ProjectModelBounds.maxTitleCharacters)
        let title = parsedTitle.flatMap { $0.isEmpty ? nil : $0 } ?? "Untitled session"
        let timestamp = number(object["last_active"] ?? object["lastActive"])
        return ProjectSession(
            id: id,
            title: title,
            preview: boundedString(object["preview"], max: ProjectModelBounds.maxTitleCharacters) ?? "",
            lastActive: timestamp.map(epochDate),
            messageCount: integer(object["message_count"] ?? object["messageCount"]) ?? 0,
            model: boundedString(object["model"], max: ProjectModelBounds.maxIDCharacters),
            provider: boundedString(object["provider"] ?? object["billing_provider"], max: ProjectModelBounds.maxIDCharacters),
            profile: boundedString(object["profile"], max: ProjectModelBounds.maxIDCharacters),
            pinned: bool(object["pinned"]) ?? false,
            archived: bool(object["archived"]) ?? false,
            workspacePath: validPath(object["workspace_path"] ?? object["workspace"] ?? object["cwd"]),
            projectID: projectID
        )
    }

    private static func validID(_ value: Any?) -> String? {
        guard let string = value as? String,
              !string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              string.count <= ProjectModelBounds.maxIDCharacters,
              !string.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { return nil }
        return string
    }

    private static func validPath(_ value: Any?) -> String? {
        guard let string = value as? String else { return nil }
        return validCanonicalHostFilePath(string)
    }

    private static func boundedString(_ value: Any?, max: Int) -> String? {
        guard let string = value as? String else { return nil }
        return String(string.prefix(max))
    }

    private static func bool(_ value: Any?) -> Bool? { value as? Bool }

    private static func integer(_ value: Any?) -> Int? {
        guard !(value is Bool) else { return nil }
        if let integer = value as? Int { return integer }
        if let number = value as? NSNumber { return number.intValue }
        return nil
    }

    private static func number(_ value: Any?) -> Double? {
        guard !(value is Bool) else { return nil }
        if let double = value as? Double { return double }
        if let number = value as? NSNumber { return number.doubleValue }
        return nil
    }

    private static func epochDate(_ value: Double) -> Date {
        Date(timeIntervalSince1970: value > 1e11 ? value / 1_000 : value)
    }
}
