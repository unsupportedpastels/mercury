import SwiftUI

struct ProjectsView: View {
    @Environment(AppModel.self) private var appModel
    @Environment(\.dismiss) private var dismiss
    let controller: ProjectMetadataController
    @State private var showCreateProject = false
    @State private var pinnedProjectIDs = Set<ProjectID>()
    @State private var projectPendingDelete: ProjectSummary?

    private let projectPins = ProjectPinStore()

    private var sortedProjects: [ProjectSummary] {
        HomeInboxPolicy.sortedProjects(
            controller.tree?.projects ?? [],
            pinnedIDs: pinnedProjectIDs
        )
    }

    var body: some View {
        List {
            if controller.isUnsupported {
                Section {
                    ContentUnavailableView(
                        "Projects unavailable",
                        systemImage: "square.grid.2x2",
                        description: Text("This Hermes server does not support project metadata yet. Sessions and Files remain available.")
                    )
                }
                .listRowBackground(Color.clear)
            } else {
                if let error = controller.errorMessage {
                    Section {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(Color.statusAlert)
                        Button("Retry") { start() }
                    }
                }

                Section("Projects") {
                    if controller.isLoading && controller.tree == nil {
                        HStack { Spacer(); ProgressView(); Spacer() }
                            .listRowBackground(Color.clear)
                    } else if controller.tree?.projects.isEmpty == true {
                        Label("No projects yet", systemImage: "square.grid.2x2")
                            .foregroundStyle(Color.secondary)
                    }

                    ForEach(sortedProjects) { project in
                        NavigationLink {
                            ProjectSessionsView(project: project, controller: controller)
                        } label: {
                            projectRow(project)
                        }
                        .listRowBackground(Color.surfaceLow)
                        .swipeActions(edge: .leading, allowsFullSwipe: false) {
                            Button {
                                togglePin(project)
                            } label: {
                                Label(
                                    pinnedProjectIDs.contains(project.id) ? "Unpin" : "Pin",
                                    systemImage: "pin"
                                )
                            }
                            .tint(Color.accentPrimary)
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            // Only registered projects exist in projects.db;
                            // auto-grouped rows have nothing to delete.
                            if !project.isAuto && !project.isNoProject {
                                Button(role: .destructive) {
                                    projectPendingDelete = project
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Projects")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left")
                }
                .accessibilityLabel("Back")
            }
            if !controller.isUnsupported {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showCreateProject = true } label: {
                        Image(systemName: "plus")
                    }
                    .disabled(controller.isLoading || controller.isCreating)
                    .accessibilityLabel("Create project")
                    .foregroundStyle(Color.secondary)
                }
            }
        }
        .task {
            pinnedProjectIDs = projectPins.pinnedIDs(
                origin: appModel.serverOrigin ?? "",
                profile: appModel.activeProfile
            )
        }
        .refreshable {
            guard let credentials = currentCredentials() else { return }
            await controller.retry(origin: credentials.origin, accessToken: credentials.token, profile: appModel.activeProfile)
        }

        .sheet(isPresented: $showCreateProject) {
            NavigationStack {
                CreateProjectView(controller: controller)
            }
        }
        .confirmationDialog(
            "Delete this project?",
            isPresented: Binding(
                get: { projectPendingDelete != nil },
                set: { if !$0 { projectPendingDelete = nil } }
            ),
            titleVisibility: .visible,
            presenting: projectPendingDelete
        ) { project in
            Button("Delete project", role: .destructive) {
                Task {
                    if await controller.delete(project) {
                        projectPins.setPinned(
                            false,
                            id: project.id,
                            origin: appModel.serverOrigin ?? "",
                            profile: appModel.activeProfile
                        )
                        pinnedProjectIDs.remove(project.id)
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { project in
            Text("“\(project.label)” will be removed from Projects. Its sessions and files stay on the server.")
        }
        .interactiveDismissDisabled(true)
        .amoledScreen()
    }

    private func togglePin(_ project: ProjectSummary) {
        let pinned = !pinnedProjectIDs.contains(project.id)
        projectPins.setPinned(
            pinned,
            id: project.id,
            origin: appModel.serverOrigin ?? "",
            profile: appModel.activeProfile
        )
        if pinned {
            pinnedProjectIDs.insert(project.id)
        } else {
            pinnedProjectIDs.remove(project.id)
        }
    }

    @ViewBuilder
    private func projectRow(_ project: ProjectSummary) -> some View {
        HStack(spacing: 10) {
            Image(systemName: project.isNoProject ? "house.fill" : (project.isAuto ? "wand.and.stars" : "folder.fill"))
                .foregroundStyle(pinnedProjectIDs.contains(project.id) ? Color.accentPrimary : Color.secondary)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(project.label.isEmpty ? "Unnamed project" : project.label)
                        .font(.headline)
                    if pinnedProjectIDs.contains(project.id) {
                        Image(systemName: "pin.fill")
                            .font(.caption2)
                            .foregroundStyle(Color.secondary)
                    }
                    if project.isNoProject {
                        Text("HOME").font(.caption2).foregroundStyle(Color.secondary)
                    } else if project.isAuto {
                        Text("AUTO").font(.caption2).foregroundStyle(Color.secondary)
                    }
                }
                if let path = project.path {
                    Text(path)
                        .font(.caption.monospaced())
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                }
                Text("\(project.sessionCount) session\(project.sessionCount == 1 ? "" : "s")")
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
            }
        }
        .padding(.vertical, 3)
    }

    private func start() {
        guard let credentials = currentCredentials() else { return }
        Task {
            await controller.retry(origin: credentials.origin, accessToken: credentials.token, profile: appModel.activeProfile)
        }
    }

    private func currentCredentials() -> (origin: String, token: String?)? {
        guard let origin = appModel.serverOrigin else { return nil }
        let token = KeychainCredentialStore().tokens(for: origin)
            .flatMap { String(data: $0.accessToken, encoding: .utf8) }
            .flatMap { $0.isEmpty ? nil : $0 }
        return (origin, token)
    }
}

struct ProjectSessionsView: View {
    @Environment(AppModel.self) private var appModel
    let project: ProjectSummary
    let controller: ProjectMetadataController
    @State private var showNewSession = false

    var body: some View {
        List {
            if let error = controller.errorMessage {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(Color.statusAlert)
                    Button("Retry") { load() }
                }
            }

            Section {
                VStack(alignment: .leading, spacing: 3) {
                    Text(project.isNoProject ? "Home sessions" : project.label)
                        .font(.headline)
                    if let path = project.path {
                        Text(path).font(.caption.monospaced()).foregroundStyle(Color.secondary)
                    }
                }
            }

            Section("Sessions") {
                if controller.loadingProjectIDs.contains(project.id) {
                    HStack { Spacer(); ProgressView(); Spacer() }
                        .listRowBackground(Color.clear)
                } else if controller.sessionsByProject[project.id, default: []].isEmpty {
                    Label("No sessions in this project", systemImage: "tray")
                        .foregroundStyle(Color.secondary)
                }
                ForEach(controller.sessionsByProject[project.id, default: []]) { session in
                    NavigationLink {
                        ChatView(sessionID: session.id, title: session.row.title)
                            .onAppear { controller.setVisibleSession(session.id) }
                            .onDisappear { controller.setVisibleSession(nil) }
                    } label: {
                        SessionInboxRow(
                            session: session.row,
                            ownerLabel: session.row.profile ?? project.label,
                            workspacePath: session.workspacePath,
                            indicator: controller.inboxIndicator(for: session.id)
                        )
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle(project.label)
        .navigationBarTitleDisplayMode(.inline)
        .overlay(alignment: .bottomTrailing) {
            // The SAME amber FAB as Home (Android ProjectDetailScreen "New
            // task" parity) — creates the session rooted in this project.
            NewTaskFloatingButton { showNewSession = true }
        }
        .navigationDestination(isPresented: $showNewSession) {
            ChatView.newProjectSession(
                workspacePath: project.isNoProject ? nil : project.path
            )
        }
        .task(id: project.id) { load() }
        .onChange(of: showNewSession) { _, isShowing in
            // Returning from the "+" flow: the first prompt persisted a durable
            // row server-side, so re-pull this project's sessions to show it.
            if !isShowing { load() }
        }
        .refreshable {
            await controller.loadSessions(for: project, restSessions: appModel.sessions)
        }
        .amoledScreen()
    }

    private func load() {
        Task { await controller.loadSessions(for: project, restSessions: appModel.sessions) }
    }
}

private struct CreateProjectView: View {
    let controller: ProjectMetadataController
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var canonicalFolder: String?
    @State private var showFolderBrowser = false

    private var canCreate: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && canonicalFolder != nil
            && !controller.isCreating
    }

    var body: some View {
        Form {
            Section("Project") {
                TextField("Name", text: $name)
                    .textInputAutocapitalization(.words)
                Button {
                    showFolderBrowser = true
                } label: {
                    HStack {
                        Label(canonicalFolder == nil ? "Choose server folder" : "Change server folder", systemImage: "folder")
                        Spacer()
                        Image(systemName: "chevron.right")
                    }
                }
                if let canonicalFolder {
                    Text(canonicalFolder)
                        .font(.caption.monospaced())
                        .foregroundStyle(Color.secondary)
                        .textSelection(.enabled)
                }
            }
            if let error = controller.errorMessage {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(Color.statusAlert)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("New Project")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(controller.isCreating ? "Creating…" : "Create") { create() }
                    .disabled(!canCreate)
            }
        }
        .sheet(isPresented: $showFolderBrowser) {
            NavigationStack {
                HostFilesView(mode: .projectFolder, onSelectFolder: { path in
                    canonicalFolder = path
                })
            }
        }
        .amoledScreen()
    }

    private func create() {
        guard let canonicalFolder else { return }
        Task {
            if await controller.create(name: name, canonicalFolder: canonicalFolder) {
                dismiss()
            }
        }
    }
}
