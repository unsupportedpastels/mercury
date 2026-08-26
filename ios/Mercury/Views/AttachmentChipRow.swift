import SwiftUI

/// Horizontal scrollable row of staged-attachment chips shown above the
/// composer text field (M6.3).
///
/// Pure presentation: each chip shows an SF Symbol by kind, the display name
/// (single line, middle-truncated), a byte-size caption, and an xmark remove
/// button. Removal is delegated through `onRemove` with the attachment id —
/// this view never mutates the composer's attachment list itself.
struct AttachmentChipRow: View {
    let attachments: [StagedAttachment]
    var onRemove: ((String) -> Void)? = nil

    private static let byteFormatter: ByteCountFormatter = {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter
    }()

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(attachments) { attachment in
                    chip(for: attachment)
                }
            }
            .padding(.vertical, 2)
        }
    }

    private func chip(for attachment: StagedAttachment) -> some View {
        HStack(spacing: 6) {
            Image(systemName: symbolName(for: attachment.kind))
                .font(.footnote)
                .foregroundStyle(Color.secondary)

            VStack(alignment: .leading, spacing: 1) {
                Text(attachment.displayName)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Color.primary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(Self.byteFormatter.string(fromByteCount: attachment.sizeBytes))
                    .font(.caption2)
                    .foregroundStyle(Color.secondary)
            }

            Button {
                onRemove?(attachment.id)
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Color.secondary)
                    .padding(3)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Remove \(attachment.displayName)")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
    }

    private func symbolName(for kind: AttachmentKind) -> String {
        switch kind {
        case .image: return "photo"
        case .file: return "doc.text"
        }
    }
}

#if DEBUG
#Preview {
    VStack {
        Spacer()
        AttachmentChipRow(
            attachments: [
                StagedAttachment(id: "a", displayName: "diagram-flow.png", mimeType: "image/png", sizeBytes: 245_760),
                StagedAttachment(id: "b", displayName: "quarterly-report-final-v2.pdf", mimeType: "application/pdf", sizeBytes: 1_887_436),
                StagedAttachment(id: "c", displayName: "a-very-long-filename-without-any-spaces-at-all.txt", mimeType: "text/plain", sizeBytes: 2_048),
            ],
            onRemove: { _ in }
        )
        .padding()
    }
    .amoledScreen()
}
#endif
