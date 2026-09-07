import SwiftUI

/// Unified configured-server picker used by startup and Settings. Direct rows
/// retain catalog ID-based rename/remove behavior; relay rows retain their own
/// pairing store and approval status. Credentials and relay keys never enter
/// this view's persistence or shared selection policy.
struct ServerListView: View {
    let catalog: ServerCatalog
    let relayTargets: [RelayPairedTarget]
    let activeIdentity: StartupConnectionIdentity?
    let onSelect: (ServerCatalogEntry) -> Void
    let onSelectRelay: (RelayPairedTarget) -> Void
    let onAdd: (_ origin: String, _ label: String) -> Void
    let onPairRelay: () -> Void
    let onEditLabel: (_ entry: ServerCatalogEntry, _ label: String) -> Void
    let onEditRelayLabel: (_ target: RelayPairedTarget, _ label: String) -> Void
    let onRemove: (ServerCatalogEntry) -> Void
    let onRemoveRelay: (RelayPairedTarget) -> Void
    /// Embedded startup mode omits NavigationStack chrome and exposes add/pair
    /// actions as rows so it can live inside the root connection screen.
    var embedded = false

    @State private var presentingAdd = false
    @State private var editingEntry: ServerCatalogEntry?
    @State private var editingRelay: RelayPairedTarget?
    @State private var removingRelay: RelayPairedTarget?

    var body: some View {
        Group {
            if embedded {
                listContent
            } else {
                listContent
                    .navigationTitle("Servers")
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            addMenu
                        }
                    }
            }
        }
        .sheet(isPresented: $presentingAdd) {
            ServerEntrySheet(title: "Add Server", initialOrigin: "", initialLabel: "") { origin, label in
                onAdd(origin, label)
            }
        }
        .sheet(item: $editingEntry) { entry in
            ServerEntrySheet(
                title: "Rename Server",
                initialOrigin: entry.origin,
                initialLabel: entry.label,
                originIsEditable: false
            ) { _, label in
                onEditLabel(entry, label)
            }
        }
        .sheet(item: $editingRelay) { target in
            ServerEntrySheet(
                title: "Rename Relay",
                initialOrigin: target.relayOrigin,
                initialLabel: target.label,
                originIsEditable: false
            ) { _, label in
                onEditRelayLabel(target, label)
            }
        }
        .amoledScreen()
    }

    @ViewBuilder
    private var listContent: some View {
        List {
            Section("Configured connections") {
                if catalog.entries.isEmpty && relayTargets.isEmpty {
                    ContentUnavailableView(
                        "No configured connections",
                        systemImage: "server.rack",
                        description: Text("Add a Hermes server or pair a Mercury Relay device to get started.")
                    )
                    .listRowBackground(Color.clear)
                }

                ForEach(catalog.entries) { entry in
                    directRow(entry)
                }

                ForEach(relayTargets) { target in
                    relayRow(target)
                }
            }

            if embedded {
                Section("Add a connection") {
                    Button {
                        presentingAdd = true
                    } label: {
                        Label("Add server", systemImage: "plus.circle")
                    }
                    Button {
                        onPairRelay()
                    } label: {
                        Label("Pair relay", systemImage: "qrcode.viewfinder")
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .toolbar {
            if embedded {
                ToolbarItem(placement: .topBarTrailing) {
                    addMenu
                }
            }
        }
    }

    private var addMenu: some View {
        Menu {
            Button("Add server", systemImage: "server.rack") {
                presentingAdd = true
            }
            Button("Pair relay", systemImage: "qrcode.viewfinder") {
                onPairRelay()
            }
        } label: {
            Image(systemName: "plus")
        }
        .accessibilityLabel("Add server or pair relay")
    }

    private func directRow(_ entry: ServerCatalogEntry) -> some View {
        let isActive = activeIdentity == StartupConnectionIdentity(kind: .direct, id: entry.id)
        return Button {
            onSelect(entry)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isActive ? "checkmark.circle.fill" : "server.rack")
                    .foregroundStyle(isActive ? Color.statusHealthy : Color.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.displayLabel)
                        .font(.headline)
                        .foregroundStyle(Color.primary)
                    Text(entry.origin)
                        .font(.caption.monospaced())
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                }
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isActive ? "\(entry.displayLabel), active server" : entry.displayLabel)
        .contextMenu {
            Button("Rename", systemImage: "pencil") { editingEntry = entry }
            Button("Remove", systemImage: "trash", role: .destructive) { onRemove(entry) }
                .disabled(isActive)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { onRemove(entry) } label: {
                Label("Remove", systemImage: "trash")
            }
            .disabled(isActive)
            Button { editingEntry = entry } label: {
                Label("Rename", systemImage: "pencil")
            }
            .tint(.accentPrimary)
        }
        .listRowBackground(Color.surfaceLow)
    }

    private func relayRow(_ target: RelayPairedTarget) -> some View {
        let isActive = activeIdentity == StartupConnectionIdentity(kind: .relay, id: target.id)
        let isApproved = target.status == .approved
        let status = isApproved ? "Mercury Relay · Ready" : "Waiting for host approval"
        return Button {
            guard isApproved else { return }
            onSelectRelay(target)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isActive ? "checkmark.circle.fill" : "qrcode.viewfinder")
                    .foregroundStyle(isActive ? Color.statusHealthy : Color.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(target.displayLabel)
                        .font(.headline)
                        .foregroundStyle(Color.primary)
                    Text(status)
                        .font(.caption)
                        .foregroundStyle(isApproved ? Color.secondary : Color.statusAlert)
                    Text(target.relayOrigin)
                        .font(.caption2.monospaced())
                        .foregroundStyle(Color.secondary)
                        .lineLimit(1)
                }
                Spacer()
                if isApproved {
                    Image(systemName: "chevron.right")
                        .foregroundStyle(Color.secondary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isApproved)
        .accessibilityLabel(
            isApproved
                ? (isActive ? "\(target.displayLabel), active relay" : target.displayLabel)
                : "\(target.displayLabel), waiting for host approval"
        )
        .contextMenu {
            Button("Rename", systemImage: "pencil") { editingRelay = target }
            Button("Remove", systemImage: "trash", role: .destructive) { removingRelay = target }
                .disabled(isActive)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { removingRelay = target } label: {
                Label("Remove", systemImage: "trash")
            }
            .disabled(isActive)
            Button { editingRelay = target } label: {
                Label("Rename", systemImage: "pencil")
            }
            .tint(.accentPrimary)
        }
        .confirmationDialog(
            "Remove \(target.displayLabel) from this phone?",
            isPresented: Binding(
                get: { removingRelay?.id == target.id },
                set: { if !$0, removingRelay?.id == target.id { removingRelay = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Remove pairing", role: .destructive) {
                onRemoveRelay(target)
                removingRelay = nil
            }
        } message: {
            Text("The host still lists this device until you revoke it there. You can pair again with a new QR code.")
        }
        .listRowBackground(Color.surfaceLow)
    }
}

private struct ServerEntrySheet: View {
    let title: String
    let initialOrigin: String
    let initialLabel: String
    var originIsEditable = true
    let onSave: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var origin: String
    @State private var label: String

    init(
        title: String,
        initialOrigin: String,
        initialLabel: String,
        originIsEditable: Bool = true,
        onSave: @escaping (String, String) -> Void
    ) {
        self.title = title
        self.initialOrigin = initialOrigin
        self.initialLabel = initialLabel
        self.originIsEditable = originIsEditable
        self.onSave = onSave
        _origin = State(initialValue: initialOrigin)
        _label = State(initialValue: initialLabel)
    }

    private var normalizedOrigin: String? {
        originIsEditable ? ServerOrigin.normalize(origin) : origin
    }

    private var labelIsValid: Bool {
        label.trimmingCharacters(in: .whitespacesAndNewlines).count <= ServerCatalogPolicy.maxLabelCharacters
            && !label.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("hermes.example.com", text: $origin)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .disabled(!originIsEditable)
                    TextField("Label (optional)", text: $label)
                        .textInputAutocapitalization(.words)
                    if originIsEditable, !origin.isEmpty, normalizedOrigin == nil {
                        Text("Enter an HTTP(S) origin without a path, query, or fragment.")
                            .font(.caption)
                            .foregroundStyle(Color.statusAlert)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let normalizedOrigin else { return }
                        onSave(normalizedOrigin, label.trimmingCharacters(in: .whitespacesAndNewlines))
                        dismiss()
                    }
                    .disabled(normalizedOrigin == nil || !labelIsValid)
                }
            }
            .amoledScreen()
        }
    }
}
