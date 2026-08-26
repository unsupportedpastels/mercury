import SwiftUI

/// Stateless server switcher. Every mutation is an explicit callback; this view
/// intentionally owns no connection, credential, cache, teardown, or generation
/// behavior so the parent can integrate those shared concerns atomically.
struct ServerListView: View {
    let catalog: ServerCatalog
    let onSelect: (ServerCatalogEntry) -> Void
    let onAdd: (_ origin: String, _ label: String) -> Void
    let onEditLabel: (_ entry: ServerCatalogEntry, _ label: String) -> Void
    let onRemove: (ServerCatalogEntry) -> Void

    @State private var presentingAdd = false
    @State private var editingEntry: ServerCatalogEntry?

    var body: some View {
        List {
            Section("Servers") {
                if catalog.entries.isEmpty {
                    ContentUnavailableView(
                        "No servers",
                        systemImage: "server.rack",
                        description: Text("Add a Hermes server to get started.")
                    )
                    .listRowBackground(Color.clear)
                }

                ForEach(catalog.entries) { entry in
                    Button {
                        onSelect(entry)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: entry.id == catalog.activeID ? "checkmark.circle.fill" : "server.rack")
                                .foregroundStyle(entry.id == catalog.activeID ? Color.statusHealthy : Color.secondary)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(entry.displayLabel)
                                    .font(.headline)
                                    .foregroundStyle(Color.primary)
                                if !entry.label.isEmpty {
                                    Text(entry.origin)
                                        .font(.caption.monospaced())
                                        .foregroundStyle(Color.secondary)
                                        .lineLimit(1)
                                }
                            }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(entry.id == catalog.activeID
                        ? "\(entry.displayLabel), active server"
                        : entry.displayLabel)
                    .contextMenu {
                        Button("Rename", systemImage: "pencil") { editingEntry = entry }
                        Button("Remove", systemImage: "trash", role: .destructive) { onRemove(entry) }
                            .disabled(entry.id == catalog.activeID)
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) { onRemove(entry) } label: {
                            Label("Remove", systemImage: "trash")
                        }
                        .disabled(entry.id == catalog.activeID)
                        Button { editingEntry = entry } label: {
                            Label("Rename", systemImage: "pencil")
                        }
                        .tint(.accentPrimary)
                    }
                    .listRowBackground(Color.surfaceLow)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Servers")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { presentingAdd = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add server")
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
        .amoledScreen()
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

    private var normalizedOrigin: String? { ServerOrigin.normalize(origin) }
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
