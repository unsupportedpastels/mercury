import Foundation

enum HomeInboxPolicy {
    static let maximumProjectRows = 4
    static let maximumRecentSessionRows = 10

    /// Canonical ordering for every project surface: user-pinned projects
    /// first (in recency order among themselves), then everything else by
    /// most-recent activity. The Home bucket row is excluded — Home sessions
    /// live in the Recent Sessions list. There is deliberately no
    /// "active project" concept: session creation is context-driven (Home →
    /// server default cwd, project screen → that project's workspace).
    static func sortedProjects(
        _ projects: [ProjectSummary],
        pinnedIDs: Set<ProjectID>
    ) -> [ProjectSummary] {
        let visible = projects.filter { !$0.isNoProject }
        return visible.sorted { a, b in
            let aPinned = pinnedIDs.contains(a.id)
            let bPinned = pinnedIDs.contains(b.id)
            if aPinned != bPinned { return aPinned }
            let aTime = a.lastActive?.timeIntervalSince1970 ?? 0
            let bTime = b.lastActive?.timeIntervalSince1970 ?? 0
            if aTime != bTime { return aTime > bTime }
            return a.label.localizedCaseInsensitiveCompare(b.label) == .orderedAscending
        }
    }

    static func projectPreview(
        _ projects: [ProjectSummary],
        pinnedIDs: Set<ProjectID>
    ) -> [ProjectSummary] {
        Array(sortedProjects(projects, pinnedIDs: pinnedIDs).prefix(maximumProjectRows))
    }

    static func recentSessionPreview(_ sessions: [SessionRow]) -> [SessionRow] {
        Array(sessions.prefix(maximumRecentSessionRows))
    }
}

/// Client-side pinned-project preference, scoped by normalized server origin
/// and profile. The released Hermes server has no project pin field, so this
/// persists locally (UserDefaults), like the notification watermark store.
struct ProjectPinStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private func key(origin: String, profile: String) -> String {
        "mercury.projects.pinned.\(origin)\u{0}\(profile)"
    }

    func pinnedIDs(origin: String, profile: String) -> Set<ProjectID> {
        let raw = defaults.stringArray(forKey: key(origin: origin, profile: profile)) ?? []
        return Set(raw.map { ProjectID($0) })
    }

    func setPinned(_ pinned: Bool, id: ProjectID, origin: String, profile: String) {
        var ids = pinnedIDs(origin: origin, profile: profile)
        if pinned { ids.insert(id) } else { ids.remove(id) }
        defaults.set(
            ids.map(\.rawValue).sorted(),
            forKey: key(origin: origin, profile: profile)
        )
    }
}
