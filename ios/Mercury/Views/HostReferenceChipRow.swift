import SwiftUI

/// A server-issued host reference staged for the next prompt. Construction is
/// intentionally only available from HostFileEntry, whose canonical path came
/// from a managed-files response; callers cannot manufacture a path/reference
/// pair independently.
struct StagedHostReference: Identifiable, Equatable {
    let entry: HostFileEntry
    let text: String

    init(entry: HostFileEntry) throws {
        self.entry = entry
        self.text = try entry.reference
    }

    var id: String { (entry.isDirectory ? "folder\u{0}" : "file\u{0}") + entry.path }
}

struct HostReferenceChipRow: View {
    let references: [StagedHostReference]
    var onRemove: ((String) -> Void)?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(references) { reference in
                    HStack(spacing: 5) {
                        Image(systemName: reference.entry.isDirectory ? "folder.fill" : "doc.fill")
                        Text(reference.entry.name)
                            .lineLimit(1)
                        Button {
                            onRemove?(reference.id)
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Remove \(reference.entry.name) reference")
                    }
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(Color.surfaceMid, in: Capsule())
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel("\(reference.entry.isDirectory ? "Folder" : "File") reference: \(reference.entry.name)")
                }
            }
            .padding(.horizontal, 1)
        }
    }
}
