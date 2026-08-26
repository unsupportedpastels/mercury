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

    private var scope: String {
        "\(appModel.serverOrigin ?? "")\u{0}\(appModel.activeProfile)"
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
                        .disabled(mutationPending)
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
                } else if browser.isEmpty {
                    Label(browser.filter.isEmpty ? "This folder is empty" : "No matching files", systemImage: "tray")
                        .foregroundStyle(Color.secondary)
                }

                ForEach(browser.visibleEntries, id: \.path) { entry in
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
                if browser.listing != nil {
                    Button {
                        folderName = ""
                        showCreateFolder = true
                    } label: {
                        Image(systemName: "folder.badge.plus")
                    }
                    .disabled(mutationPending)
                    .accessibilityLabel("Create folder")
                }
                Button { Task { await reloadCurrent() } } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(browser.isLoading || mutationPending)
                .accessibilityLabel("Refresh files")
            }
            ToolbarItem(placement: .topBarLeading) {
                if let parent = browser.listing?.parentPath {
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

            if mode == .chatReference && entry.isDirectory {
                Button { selectReference(entry) } label: { Image(systemName: "at.circle") }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Reference folder \(entry.name)")
            }
        }
    }

    private func load(path: String?) async {
        if browser.scope != scope { client = nil }
        let request = browser.beginLoad(scope: scope, path: path)
        do {
            let activeClient = try filesClient()
            let listing = try await activeClient.list(path: path)
            _ = browser.apply(listing, for: request)
        } catch is CancellationError {
            return
        } catch {
            _ = browser.fail(safeFilesError(error), for: request)
        }
    }

    private func reloadCurrent() async {
        await load(path: browser.listing?.path)
    }

    private func filesClient() throws -> HostFilesClient {
        if let client { return client }
        guard let origin = appModel.serverOrigin else { throw HermesAuthError.authRejected }
        let created: HostFilesClient
        if let pair = KeychainCredentialStore().tokens(for: origin),
           let token = String(data: pair.accessToken, encoding: .utf8),
           !token.isEmpty {
            created = try HostFilesClient(origin: origin, bearerToken: token)
        } else {
            created = try HostFilesClient(cookieAuthenticatedOrigin: origin)
        }
        client = created
        return created
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
        previewLoading = true
        Task {
            do {
                let content = try await filesClient().read(path: entry.path)
                let textual = content.mimeType.hasPrefix("text/")
                    || content.mimeType == "application/json"
                    || content.mimeType == "application/xml"
                let text = textual ? String(data: content.bytes, encoding: .utf8) : nil
                await MainActor.run {
                    previewLoading = false
                    preview = FilePreview(path: content.path, title: content.name, text: text, mimeType: content.mimeType)
                }
            } catch {
                await MainActor.run {
                    previewLoading = false
                    browser = failingCurrentState(safeFilesError(error))
                }
            }
        }
    }

    private func createFolder() {
        guard let parent = browser.listing?.path else { return }
        let requestedName = folderName
        mutationPending = true
        Task {
            do {
                let listing = try await filesClient().createDirectory(parentPath: parent, name: requestedName)
                let request = browser.beginLoad(scope: scope, path: listing.path)
                _ = browser.apply(listing, for: request)
                mutationPending = false
            } catch {
                mutationPending = false
                browser = failingCurrentState(safeFilesError(error))
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
