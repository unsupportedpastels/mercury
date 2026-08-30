import SwiftUI

/// Live session list backed by `GET /api/profiles/sessions`. Loads on
/// appear, supports pull-to-refresh, and keeps the previous rows (with an
/// error banner) when a refresh fails.
///
/// M3 additions: profile switcher menu, new-session button, swipe/context
/// row actions (pin / archive / rename / delete), and infinite-scroll
/// pagination driven by the AppModel's paged-session API.
struct SessionListView: View {
    @Environment(AppModel.self) private var appModel: AppModel

    // Programmatic push of a brand-new chat session ("+" toolbar button).
    @State private var showNewSession = false
    // Row currently awaiting delete confirmation.
    @State private var sessionPendingDelete: SessionRow?
    // Row being renamed; holds the editable title while the alert is up.
    @State private var sessionPendingRename: SessionRow?
    @State private var renameText = ""
    // UI-level pin toggle until `pinned` is surfaced on SessionRow (see
    // swipe/context actions below).
    @State private var locallyPinnedIDs = Set<String>()
    @State private var showHostFiles = false
    @State private var showProjects = false
    @State private var showAllSessions = false
    @State private var showSettings = false
    @State private var showShareInbox = false
    @State private var incomingShareDraft: IncomingShareDraft?
    @State private var projectController = ProjectMetadataController()
    @State private var pinnedProjectIDs = Set<ProjectID>()
    @State private var searchQuery = ""
    @State private var serverSearchResults: [SessionSearchResult] = []
    @State private var searchLoading = false
    @State private var searchTask: Task<Void, Never>?

    private let projectPins = ProjectPinStore()

    private var normalizedSearchQuery: String {
        searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var mergedSearchResults: [SessionSearchResult] {
        guard !normalizedSearchQuery.isEmpty else { return [] }
        let needle = normalizedSearchQuery.localizedLowercase
        var results: [SessionSearchResult] = []
        var seen = Set<String>()
        for session in appModel.sessions where [
            session.id, session.title, session.preview, session.workspacePath ?? ""
        ].contains(where: { $0.localizedLowercase.contains(needle) }) {
            if seen.insert(session.id).inserted {
                results.append(SessionSearchResult(
                    sessionID: session.id,
                    title: session.title.isEmpty ? "Untitled session" : session.title,
                    snippet: session.preview,
                    role: nil
                ))
            }
        }
        for result in serverSearchResults where seen.insert(result.sessionID).inserted {
            results.append(result)
        }
        return results
    }

    /// True while a chat session is open on top of the list. `.task(id:)`
    /// re-runs when this flips, so it drives the relay projects-socket
    /// standdown.
    private var relayChatIsOpen: Bool {
        appModel.visibleSessionID != nil
    }

    private var projectScope: String {
        let host = appModel.serverOrigin
            ?? appModel.activeRelayTarget.map { "relay:\($0.id.uuidString)" }
            ?? ""
        return "\(host)\u{0}\(appModel.activeProfile)"
    }

    private var homeProjects: [ProjectSummary] {
        HomeInboxPolicy.projectPreview(
            projectController.tree?.projects ?? [],
            pinnedIDs: pinnedProjectIDs
        )
    }

    private var homeSessions: [SessionRow] {
        HomeInboxPolicy.recentSessionPreview(appModel.sessions)
    }

    var body: some View {
        NavigationStack {
            List {
                if !normalizedSearchQuery.isEmpty {
                    Section("Search results") {
                        if searchLoading && mergedSearchResults.isEmpty {
                            HStack { Spacer(); ProgressView(); Spacer() }
                        } else if mergedSearchResults.isEmpty {
                            Text("No sessions found")
                                .foregroundStyle(Color.secondary)
                        } else {
                            ForEach(mergedSearchResults) { result in
                                NavigationLink {
                                    ChatView(sessionID: result.sessionID, title: result.title)
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(result.title)
                                            .font(.headline)
                                            .lineLimit(1)
                                        if !result.snippet.isEmpty {
                                            Text(result.snippet)
                                                .font(.subheadline)
                                                .foregroundStyle(Color.secondary)
                                                .lineLimit(3)
                                        }
                                    }
                                    .padding(.vertical, 4)
                                }
                            }
                        }
                    }
                } else {
                if let error = appModel.sessionsError {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(Color.statusAlert)
                    }
                }

                if !projectController.isUnsupported {
                    Section {
                        if projectController.isLoading && projectController.tree == nil {
                            HStack { Spacer(); ProgressView(); Spacer() }
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        } else if homeProjects.isEmpty && projectController.errorMessage == nil {
                            Label("No projects yet", systemImage: "square.grid.2x2")
                                .font(.subheadline)
                                .foregroundStyle(Color.secondary)
                        }
                        ForEach(homeProjects) { project in
                            NavigationLink {
                                ProjectSessionsView(project: project, controller: projectController)
                            } label: {
                                homeProjectRow(project)
                            }
                            .listRowBackground(Color.surfaceLow)
                            .listRowSeparatorTint(Color.separatorSubtle)
                        }
                        if let error = projectController.errorMessage {
                            Label(error, systemImage: "exclamationmark.triangle.fill")
                                .font(.footnote)
                                .foregroundStyle(Color.statusAlert)
                        }
                    } header: {
                        sectionHeader("Projects", actionTitle: "View all") {
                            showProjects = true
                        }
                    }
                }

                Section {
                    if appModel.sessions.isEmpty && appModel.sessionsError == nil {
                        Label("No sessions yet", systemImage: "tray")
                            .font(.subheadline)
                            .foregroundStyle(Color.secondary)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }
                    ForEach(homeSessions) { session in
                        NavigationLink {
                            ChatView(sessionID: session.id, title: session.title)
                                .onAppear { projectController.setVisibleSession(session.id) }
                                .onDisappear { projectController.setVisibleSession(nil) }
                        } label: {
                            sessionRow(session)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.surfaceLow)
                        .listRowSeparatorTint(Color.separatorSubtle)
                        .swipeActions(edge: .leading, allowsFullSwipe: false) {
                            Button {
                                togglePin(session)
                            } label: {
                                Label(
                                    locallyPinnedIDs.contains(session.id) ? "Unpin" : "Pin",
                                    systemImage: "pin"
                                )
                            }
                            .tint(Color.accentPrimary)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button(role: .destructive) {
                                sessionPendingDelete = session
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                            Button {
                                Task {
                                    await appModel.updateSession(id: session.id, archived: true)
                                }
                            } label: {
                                Label("Archive", systemImage: "archivebox")
                            }
                            .tint(Color.surfaceMid)
                        }
                        .contextMenu {
                            Button {
                                togglePin(session)
                            } label: {
                                Label(
                                    locallyPinnedIDs.contains(session.id) ? "Unpin" : "Pin",
                                    systemImage: "pin"
                                )
                            }
                            Button {
                                renameText = session.title
                                sessionPendingRename = session
                            } label: {
                                Label("Rename", systemImage: "pencil")
                            }
                            Button {
                                Task {
                                    await appModel.updateSession(id: session.id, archived: true)
                                }
                            } label: {
                                Label("Archive", systemImage: "archivebox")
                            }
                            Divider()
                            Button(role: .destructive) {
                                sessionPendingDelete = session
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                } header: {
                    sectionHeader("Recent Sessions", actionTitle: "View all") {
                        showAllSessions = true
                    }
                }
                }
            }
            .scrollContentBackground(.hidden)
            .listStyle(.insetGrouped)
            .overlay(alignment: .bottomTrailing) {
                // Android SessionListScreen floatingActionButton parity: the
                // amber 48pt rounded-square "New task" FAB, bottom-trailing.
                NewTaskFloatingButton { showNewSession = true }
            }
            .navigationTitle("Mercury")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(
                text: $searchQuery,
                placement: .navigationBarDrawer(displayMode: .always),
                prompt: "Search sessions"
            )
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Picker("Profile", selection: profileSelection) {
                            ForEach(appModel.profiles, id: \.self) { profile in
                                Text(profile).tag(profile)
                            }
                        }
                    } label: {
                        Image(systemName: "person.crop.circle")
                            .foregroundStyle(Color.secondary)
                    }
                    .accessibilityLabel("Profile: \(appModel.activeProfile)")
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        showHostFiles = true
                    } label: {
                        Image(systemName: "folder")
                    }
                    .accessibilityLabel("Host files")
                    .foregroundStyle(Color.secondary)

                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
                    }
                    .foregroundStyle(Color.secondary)
                    .accessibilityLabel("Settings")
                }
            }
            .navigationDestination(isPresented: $showNewSession) {
                ChatView.newSession()
            }
            .navigationDestination(item: notificationOpenBinding) { request in
                ChatView(
                    sessionID: request.sessionID,
                    title: notificationOpenTitle(for: request.sessionID)
                )
                .onAppear { projectController.setVisibleSession(request.sessionID) }
                .onDisappear { projectController.setVisibleSession(nil) }
            }
            .navigationDestination(isPresented: $showAllSessions) {
                AllSessionsView(projectController: projectController)
            }
            .navigationDestination(item: $incomingShareDraft) { draft in
                ChatView.newSession(incomingShare: draft)
            }
            .sheet(isPresented: $showHostFiles) {
                NavigationStack { HostFilesView() }
            }
            .sheet(isPresented: $showProjects) {
                NavigationStack { ProjectsView(controller: projectController) }
            }
            .sheet(isPresented: $showSettings) {
                SettingsView()
            }
            .sheet(isPresented: $showShareInbox) {
                if let entry = appModel.pendingShareEntries.first {
                    ShareInboxSheet(
                        entry: entry,
                        onNewChat: {
                            incomingShareDraft = appModel.prepareIncomingShare(entryID: entry.id)
                            showShareInbox = false
                        },
                        onCancel: { showShareInbox = false }
                    )
                }
            }
            .refreshable {
                await appModel.loadSessions()
            }
            .task {
                await appModel.loadSessions()
                showShareInbox = !appModel.pendingShareEntries.isEmpty
            }
            .onChange(of: relayChatIsOpen) { _, isOpen in
                // The relay host keeps one live stream per device: a fresh
                // connection supersedes the previous lease. While a chat owns
                // the stream the projects metadata connection must be fully
                // closed — not merely not-started — or the two supersede each
                // other, dropping the chat mid-turn (the user sees activity
                // but no streamed reply). When the chat closes, reload
                // projects; without this the Home list stays empty after the
                // first session visit.
                guard let target = appModel.activeRelayTarget else { return }
                Task {
                    if isOpen {
                        await projectController.stop()
                    } else {
                        await projectController.start(
                            source: .relay(target),
                            profile: appModel.activeProfile
                        )
                    }
                }
            }
            .task(id: projectScope) {
                pinnedProjectIDs = projectPins.pinnedIDs(
                    origin: appModel.serverOrigin ?? "",
                    profile: appModel.activeProfile
                )
                if let target = appModel.activeRelayTarget {
                    // One device socket per installation: never open the
                    // projects metadata connection while a chat session is
                    // open or connecting — it would supersede the chat's
                    // socket and orphan its runtime.
                    guard appModel.visibleSessionID == nil else { return }
                    await projectController.start(
                        source: .relay(target),
                        profile: appModel.activeProfile
                    )
                    return
                }
                guard let credentials = currentCredentials() else {
                    await projectController.stop()
                    return
                }
                await projectController.start(
                    origin: credentials.origin,
                    accessToken: credentials.token,
                    profile: appModel.activeProfile
                )
            }
            .onChange(of: showProjects) { _, isShowing in
                // Re-read pins when the Projects sheet closes so swipes there
                // re-rank the Home preview immediately.
                if !isShowing {
                    pinnedProjectIDs = projectPins.pinnedIDs(
                        origin: appModel.serverOrigin ?? "",
                        profile: appModel.activeProfile
                    )
                }
            }
            .onChange(of: appModel.pendingShareEntries.count) {
                if !appModel.pendingShareEntries.isEmpty { showShareInbox = true }
            }
            .onChange(of: searchQuery) { _, value in
                scheduleSearch(value)
            }
            .onChange(of: appModel.activeProfile) {
                clearSearchResults()
            }
            .onChange(of: appModel.serverOrigin) {
                clearSearchResults()
            }
            .onDisappear {
                searchTask?.cancel()
            }
            .confirmationDialog(
                "Delete this session?",
                isPresented: Binding(
                    get: { sessionPendingDelete != nil },
                    set: { if !$0 { sessionPendingDelete = nil } }
                ),
                titleVisibility: .visible,
                presenting: sessionPendingDelete
            ) { session in
                Button("Delete", role: .destructive) {
                    Task {
                        await appModel.deleteSession(id: session.id)
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: { session in
                Text("“\(session.title.isEmpty ? "Untitled session" : session.title)” will be permanently removed.")
            }
            .alert(
                "Rename Session",
                isPresented: Binding(
                    get: { sessionPendingRename != nil },
                    set: { if !$0 { sessionPendingRename = nil } }
                ),
                presenting: sessionPendingRename
            ) { session in
                TextField(
                    "Title",
                    text: $renameText
                )
                Button("Save") {
                    let trimmed = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty, trimmed != session.title else { return }
                    Task {
                        await appModel.updateSession(id: session.id, title: trimmed)
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text("Enter a new title for this session.")
            }
        }
        .amoledScreen()
    }

    private func scheduleSearch(_ rawValue: String) {
        searchTask?.cancel()
        let query = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            serverSearchResults = []
            searchLoading = false
            return
        }
        searchLoading = true
        searchTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 200_000_000)
            guard !Task.isCancelled,
                  let origin = appModel.serverOrigin else { return }
            do {
                let client = HermesHTTPClient.makeAuthenticated(origin: origin)
                let results = try await SessionsClient(
                    client: client,
                    profile: appModel.activeProfile
                ).search(query: query)
                guard !Task.isCancelled, query == normalizedSearchQuery else { return }
                serverSearchResults = results
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, query == normalizedSearchQuery else { return }
                serverSearchResults = []
            }
            searchLoading = false
        }
    }

    private func clearSearchResults() {
        searchTask?.cancel()
        searchTask = nil
        serverSearchResults = []
        searchLoading = false
    }

    @ViewBuilder
    private func sectionHeader(
        _ title: String,
        actionTitle: String,
        action: @escaping () -> Void
    ) -> some View {
        HStack {
            Text(title)
            Spacer()
            Button(actionTitle, action: action)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.accentPrimary)
                .textCase(nil)
        }
    }

    @ViewBuilder
    private func homeProjectRow(_ project: ProjectSummary) -> some View {
        HStack(spacing: 11) {
            Image(systemName: project.isAuto ? "wand.and.stars" : "folder.fill")
                .foregroundStyle(
                    pinnedProjectIDs.contains(project.id)
                        ? Color.accentPrimary
                        : Color.secondary
                )
                .frame(width: 30, height: 30)
                .background(Color.surfaceMid, in: RoundedRectangle(cornerRadius: 9))
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 5) {
                    Text(project.label)
                        .font(.headline)
                        .lineLimit(1)
                    if pinnedProjectIDs.contains(project.id) {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundStyle(Color.secondary)
                    }
                }
                if let latest = project.previewSessions.first?.title, !latest.isEmpty {
                    Text("Latest · \(latest)")
                        .font(.caption)
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            Text("\(project.sessionCount)")
                .font(.caption.monospacedDigit())
                .foregroundStyle(Color.secondary)
        }
        .padding(.vertical, 3)
    }

    /// Bridges the AppModel open-session request into a navigation binding,
    /// clearing it when the pushed chat is dismissed.
    private var notificationOpenBinding: Binding<AppModel.NotificationOpenRequest?> {
        Binding(
            get: { appModel.notificationOpenRequest },
            set: { if $0 == nil { appModel.clearOpenSessionRequest() } }
        )
    }

    /// Resolves a friendly title for a deep-linked session id, falling back to
    /// a neutral label when the session isn't in the loaded list yet.
    private func notificationOpenTitle(for sessionID: String) -> String {
        if let row = appModel.sessions.first(where: { $0.id == sessionID }), !row.title.isEmpty {
            return row.title
        }
        return "Session"
    }

    private func currentCredentials() -> (origin: String, token: String?)? {
        guard let origin = appModel.serverOrigin else { return nil }
        let token = KeychainCredentialStore().tokens(for: origin)
            .flatMap { String(data: $0.accessToken, encoding: .utf8) }
            .flatMap { $0.isEmpty ? nil : $0 }
        return (origin, token)
    }

    /// Binding bridged into the profile `Picker` so picking an entry from the
    /// menu switches the active profile.
    private var profileSelection: Binding<String> {
        Binding(
            get: { appModel.activeProfile },
            set: { selected in
                guard selected != appModel.activeProfile else { return }
                Task { await appModel.switchProfile(selected) }
            }
        )
    }

    /// Optimistic pin toggle until `pinned` is surfaced on `SessionRow`.
    private func togglePin(_ session: SessionRow) {
        let shouldPin = !locallyPinnedIDs.contains(session.id)
        if shouldPin {
            locallyPinnedIDs.insert(session.id)
        } else {
            locallyPinnedIDs.remove(session.id)
        }
        Task {
            await appModel.updateSession(id: session.id, pinned: shouldPin)
        }
    }

    @ViewBuilder
    private func sessionRow(_ session: SessionRow) -> some View {
        let projects = projectController.tree?.projects ?? []
        let owner = SessionInboxPolicy.projectLabel(for: session, projects: projects)
            ?? session.profile
            ?? appModel.activeProfile
        SessionInboxRow(
            session: session,
            ownerLabel: owner,
            workspacePath: session.workspacePath,
            indicator: projectController.inboxIndicator(for: session.id)
        )
    }
}

private struct AllSessionsView: View {
    @Environment(AppModel.self) private var appModel
    let projectController: ProjectMetadataController
    @State private var pendingDelete: SessionRow?

    var body: some View {
        List {
            if let error = appModel.sessionsError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
            }
            ForEach(appModel.sessions) { session in
                NavigationLink {
                    ChatView(sessionID: session.id, title: session.title)
                        .onAppear { projectController.setVisibleSession(session.id) }
                        .onDisappear { projectController.setVisibleSession(nil) }
                } label: {
                    let projects = projectController.tree?.projects ?? []
                    let owner = SessionInboxPolicy.projectLabel(for: session, projects: projects)
                        ?? session.profile
                        ?? appModel.activeProfile
                    SessionInboxRow(
                        session: session,
                        ownerLabel: owner,
                        workspacePath: session.workspacePath,
                        indicator: projectController.inboxIndicator(for: session.id)
                    )
                }
                .listRowBackground(Color.surfaceLow)
                .listRowSeparatorTint(Color.separatorSubtle)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) { pendingDelete = session } label: {
                        Label("Delete", systemImage: "trash")
                    }
                    Button {
                        Task { await appModel.updateSession(id: session.id, archived: true) }
                    } label: {
                        Label("Archive", systemImage: "archivebox")
                    }
                    .tint(Color.surfaceMid)
                }
                .onAppear {
                    if session.id == appModel.sessions.last?.id,
                       appModel.canLoadMoreSessions,
                       !appModel.isLoadingMoreSessions {
                        Task { await appModel.loadNextSessionsPage() }
                    }
                }
            }
            if appModel.isLoadingMoreSessions {
                HStack { Spacer(); ProgressView(); Spacer() }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
        }
        .scrollContentBackground(.hidden)
        .listStyle(.insetGrouped)
        .navigationTitle("All Sessions")
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await appModel.loadSessions() }
        .confirmationDialog(
            "Delete this session?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingDelete
        ) { session in
            Button("Delete", role: .destructive) {
                Task { await appModel.deleteSession(id: session.id) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .amoledScreen()
    }
}

#Preview {
    SessionListView()
        .environment(AppModel())
        .preferredColorScheme(.dark)
}
