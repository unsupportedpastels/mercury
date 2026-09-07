import SwiftUI

/// Purpose controls selection affordances without changing navigation: every
/// directory is still opened using its server-returned canonical path.
enum HostFilesSelectionMode {
    case browse
    case chatReference
    case projectFolder
}

struct HostFilesView: View {
    var mode: HostFilesSelectionMode = .browse
    var onSelectReference: ((HostFileEntry) -> Void)? = nil
    var onSelectFolder: ((String) -> Void)? = nil

    @Environment(AppModel.self) private var appModel
    @Environment(\.dismiss) private var dismiss

    @State private var browser = HostFilesBrowserState()
    @State private var client: HostFilesClient?
    @State private var preview: FilePreview?
    @State private var previewLoading = false
    @State private var showCreateFolder = false
    @State private var folderName = ""
    @State private var mutationPending = false

    private struct FilePreview: Identifiable {
        let path: String
        let title: String
        let text: String?
        let mimeType: String
        var id: String { path }
    }

    private var relayTarget: RelayPairedTarget? {
        appModel.activeRelayTarget ?? appModel.selectedRelayTarget
    }

    private var scope: String {
        if let target = relayTarget {
            return "relay:\(target.relayOrigin)\u{0}\(target.id.uuidString)\u{0}\(appModel.activeProfile)"
        }
        return "direct:\(appModel.serverOrigin ?? "")\u{0}\(appModel.activeProfile)"
    }

    private var displayedEntries: [HostFileEntry] {
        guard mode == .projectFolder, let listing = browser.listing else {
            return browser.visibleEntries
        }
        let permittedPaths = Set(
            HostFilesFolderPickerPolicy.directories(in: listing).map(\.path)
        )
        return browser.visibleEntries.filter { permittedPaths.contains($0.path) }
    }

    private var displayedIsEmpty: Bool {
        if mode == .projectFolder, browser.listing != nil {
            return !browser.isLoading && browser.errorMessage == nil && displayedEntries.isEmpty
        }
        return browser.isEmpty
    }

    private var parentPath: String? {
        guard let listing = browser.listing else { return nil }
        return HostFilesFolderPickerPolicy.parentPath(in: listing)
    }

    var body: some View {
        List {
            if let listing = browser.listing {
                Section {
                    Text(listing.path)
                        .font(.caption.monospaced())
                        .foregroundStyle(Color.secondary)
                        .textSelection(.enabled)

                    if mode == .projectFolder {
                        Button {
                            onSelectFolder?(listing.path)
                            dismiss()
                        } label: {
                            Label("Choose this folder", systemImage: "checkmark.circle.fill")
                        }
                        .disabled(
                            browser.isLoading || mutationPending || !HostFilesFolderPickerPolicy.canSelect(listing)
                        )
                    }
                }
            }

            if let error = browser.errorMessage {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(Color.statusAlert)
                    Button("Retry") { Task { await reloadCurrent() } }
                }
            }

            Section("Server files") {
                if browser.isLoading && browser.listing == nil {
                    HStack { Spacer(); ProgressView(); Spacer() }
                        .listRowBackground(Color.clear)
                } else if displayedIsEmpty {
                    Label(
                        mode == .projectFolder
                            ? (browser.filter.isEmpty ? "No subfolders here" : "No matching folders")
                            : (browser.filter.isEmpty ? "This folder is empty" : "No matching files"),
                        systemImage: "tray"
                    )
                        .foregroundStyle(Color.secondary)
                }

                ForEach(displayedEntries, id: \.path) { entry in
                    row(entry)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .searchable(text: $browser.filter, prompt: "Filter this folder")
        .navigationTitle("Host Files")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if let listing = browser.listing {
                    Button {
                        folderName = ""
                        showCreateFolder = true
                    } label: {
                        Image(systemName: "folder.badge.plus")
                    }
                    .disabled(
                        browser.isLoading || mutationPending || !HostFilesFolderPickerPolicy.canSelect(listing)
                    )
                    .accessibilityLabel("Create folder")
                }
                Button { Task { await reloadCurrent() } } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(browser.isLoading || mutationPending)
                .accessibilityLabel("Refresh files")
            }
            ToolbarItemGroup(placement: .topBarLeading) {
                Button { dismiss() } label: {
                    Image(systemName: "chevron.left")
                }
                .accessibilityLabel("Back")

                if let parent = parentPath {
                    Button {
                        Task { await load(path: parent) }
                    } label: {
                        Label("Up", systemImage: "arrow.up")
                    }
                    .disabled(browser.isLoading || mutationPending)
                }
            }
        }
        .refreshable { await reloadCurrent() }
        .task(id: scope) { await load(path: nil) }
        .alert("Create Folder", isPresented: $showCreateFolder) {
            TextField("Folder name", text: $folderName)
            Button("Create") { createFolder() }
                .disabled(folderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Create a directory inside the current server folder.")
        }
        .sheet(item: $preview) { item in
            NavigationStack {
                ScrollView {
                    if let text = item.text {
                        Text(text)
                            .font(.system(.body, design: .monospaced))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .textSelection(.enabled)
                    } else {
                        ContentUnavailableView(
                            "Preview unavailable",
                            systemImage: "doc",
                            description: Text("\(item.mimeType) cannot be shown as text.")
                        )
                    }
                }
                .navigationTitle(item.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { preview = nil }
                    }
                }
                .amoledScreen()
            }
        }
        .overlay {
            if previewLoading || mutationPending {
                ProgressView()
                    .padding(14)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .amoledScreen()
        .interactiveDismissDisabled(true)
    }

    @ViewBuilder
    private func row(_ entry: HostFileEntry) -> some View {
        HStack(spacing: 10) {
            Button {
                if entry.isDirectory {
                    Task { await load(path: entry.path) }
                } else if mode == .chatReference {
                    selectReference(entry)
                } else {
                    previewFile(entry)
                }
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: entry.isDirectory ? "folder.fill" : "doc.fill")
                        .foregroundStyle(entry.isDirectory ? Color.accentPrimary : Color.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.name).lineLimit(1)
                        if let size = entry.size, !entry.isDirectory {
                            Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                                .font(.caption2)
                                .foregroundStyle(Color.secondary)
                        }
                    }
                    Spacer()
                    if entry.isDirectory { Image(systemName: "chevron.right").font(.caption) }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(browser.isLoading || mutationPending)

            if mode == .chatReference && entry.isDirectory {
                Button { selectReference(entry) } label: { Image(systemName: "at.circle") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Reference folder \(entry.name)")
            }
        }
    }

    private func load(path: String?) async {
        let requestedScope = scope
        let scopeChanged = browser.scope != requestedScope
        if scopeChanged {
            client = nil
        }
        preview = nil
        previewLoading = false
        mutationPending = false
        // A path is only an identity within its origin/profile. A refresh
        // racing a transport switch must restart at the new server's root.
        let requestedPath = scopeChanged ? nil : path
        let request = browser.beginLoad(scope: requestedScope, path: requestedPath)
        do {
            let listing: HostFileListing
            if relayTarget != nil, mode == .projectFolder {
                listing = try await relayFoldersClient().list(path: requestedPath)
            } else {
                listing = try await filesClient().list(path: requestedPath)
            }
            guard request.scope == scope else { return }
            _ = browser.apply(listing, for: request)
        } catch is CancellationError {
            return
        } catch {
            guard request.scope == scope else { return }
            _ = browser.fail(safeFilesError(error), for: request)
        }
    }

    private func reloadCurrent() async {
        guard !mutationPending else { return }
        await load(path: browser.listing?.path)
    }

    private func filesClient() throws -> HostFilesClient {
        if let client { return client }
        let created = try HostFilesAccess.makeClient(
            origin: appModel.serverOrigin,
            relayActive: relayTarget != nil
        )
        client = created
        return created
    }

    private func relayFoldersClient() async throws -> RelayFoldersClient {
        guard let target = relayTarget else { throw CancellationError() }
        let expectedScope = scope
        let profile = appModel.activeProfile
        let selectionGeneration = appModel.relaySelectionGeneration
        // Borrow the metadata admission. Opening a competing device connection
        // here would displace another reader or a retained chat controller.
        let connection = try await RelayConnectionPool.shared.acquire(target: target, profile: profile)
        try Task.checkCancellation()
        guard scope == expectedScope, appModel.relaySelectionGeneration == selectionGeneration else {
            throw CancellationError()
        }
        return RelayFoldersClient(profile: profile) { method, params in
            try Task.checkCancellation()
            guard scope == expectedScope, appModel.relaySelectionGeneration == selectionGeneration else {
                throw CancellationError()
            }
            let result = try await connection.relayRequest(method, params: params)
            try Task.checkCancellation()
            guard scope == expectedScope, appModel.relaySelectionGeneration == selectionGeneration else {
                throw CancellationError()
            }
            return result
        }
    }

    private func selectReference(_ entry: HostFileEntry) {
        do {
            _ = try entry.reference
            onSelectReference?(entry)
            dismiss()
        } catch {
            browser = failingCurrentState("This server path cannot be referenced safely.")
        }
    }

    private func previewFile(_ entry: HostFileEntry) {
        guard !entry.isDirectory else { return }
        let request = browser.beginPreview(path: entry.path)
        previewLoading = true
        Task {
            guard request.scope == scope, browser.isCurrent(request) else { return }
            do {
                let content = try await filesClient().read(path: entry.path)
                let textual = content.mimeType.hasPrefix("text/")
                    || content.mimeType == "application/json"
                    || content.mimeType == "application/xml"
                let text = textual ? String(data: content.bytes, encoding: .utf8) : nil
                await MainActor.run {
                    guard request.scope == scope, browser.isCurrent(request) else { return }
                    previewLoading = false
                    preview = FilePreview(path: content.path, title: content.name, text: text, mimeType: content.mimeType)
                }
            } catch is CancellationError {
                await MainActor.run {
                    guard request.scope == scope, browser.isCurrent(request) else { return }
                    previewLoading = false
                }
            } catch {
                let message = safeFilesError(error)
                await MainActor.run {
                    guard request.scope == scope, browser.isCurrent(request) else { return }
                    previewLoading = false
                    browser = failingCurrentState(message)
                }
            }
        }
    }

    private func createFolder() {
        guard !mutationPending, !browser.isLoading, let listing = browser.listing,
              HostFilesFolderPickerPolicy.canSelect(listing),
              let parent = validCanonicalHostFilePath(listing.path) else { return }
        let request = browser.beginCreate(parentPath: parent)
        let requestedName = folderName
        mutationPending = true
        Task {
            guard request.scope == scope, browser.isCurrent(request) else { return }
            do {
                let created: HostFileListing
                if relayTarget != nil, mode == .projectFolder {
                    created = try await relayFoldersClient().createDirectory(parentPath: parent, name: requestedName)
                } else {
                    created = try await filesClient().createDirectory(parentPath: parent, name: requestedName)
                }
                await MainActor.run {
                    guard request.scope == scope, browser.isCurrent(request) else { return }
                    let loadRequest = browser.beginLoad(scope: request.scope, path: created.path)
                    _ = browser.apply(created, for: loadRequest)
                    mutationPending = false
                }
            } catch is CancellationError {
                await MainActor.run {
                    guard request.scope == scope, browser.isCurrent(request) else { return }
                    mutationPending = false
                }
            } catch {
                let message = safeFilesError(error)
                await MainActor.run {
                    guard request.scope == scope, browser.isCurrent(request) else { return }
                    mutationPending = false
                    browser = failingCurrentState(message)
                }
            }
        }
    }

    private func failingCurrentState(_ message: String) -> HostFilesBrowserState {
        var next = browser
        let request = next.beginLoad(scope: scope, path: next.listing?.path)
        _ = next.fail(message, for: request)
        return next
    }

    private func safeFilesError(_ error: Error) -> String {
        if let folders = error as? RelayFoldersError { return folders.localizedDescription }
        if let access = error as? HostFilesAccessError {
            switch access {
            case .relayUnsupported:
                return "Folder browsing and creation are unavailable through Mercury Relay; enter an existing server folder path manually."
            case .directOriginUnavailable:
                return "Connect directly to a Hermes server to browse folders."
            }
        }
        if let auth = error as? HermesAuthError {
            switch auth {
            case .authRejected:
                return "File access was rejected. Sign in again or choose an allowed folder."
            case .transient:
                return "The file service is temporarily unavailable."
            }
        }
        if let files = error as? HostFilesClientError {
            switch files {
            case .httpStatus(403): return "This folder is not available to the signed-in account."
            case .httpStatus(404): return "That server file no longer exists. Refresh and choose another."
            case .httpStatus(409): return "A file already uses that folder name."
            case .httpStatus(413), .responseTooLarge: return "That file is too large to preview."
            default: return "Could not load files from this server."
            }
        }
        return "Could not load files from this server."
    }
}
