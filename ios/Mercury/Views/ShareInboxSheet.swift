import MercuryShareKit
import SwiftUI

struct ShareInboxSheet: View {
    let entry: ShareInboxEntry
    let onNewChat: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section("Shared content") {
                    if !entry.payload.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Label("Shared text", systemImage: "text.quote")
                    }
                    ForEach(entry.payload.attachments) { attachment in
                        Label(attachment.displayName, systemImage: attachment.kind == .image ? "photo" : "doc")
                    }
                }
                Section {
                    Button(action: onNewChat) {
                        Label("New chat", systemImage: "square.and.pencil")
                    }
                } footer: {
                    Text("Shared content is staged in the composer. Mercury never sends it automatically.")
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle("Send to chat")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Not now", action: onCancel)
                }
            }
            .amoledScreen()
        }
    }
}
