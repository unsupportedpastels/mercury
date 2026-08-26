import SwiftUI

/// Dynamic `complete.slash` results. No native fallback catalog is rendered.
struct SlashCompletionOverlay: View {
    let items: [SlashCompletionItem]
    let onSelect: (SlashCompletionItem) -> Void

    var body: some View {
        if !items.isEmpty {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        Button {
                            onSelect(item)
                        } label: {
                            HStack(alignment: .firstTextBaseline, spacing: 10) {
                                Text(item.display)
                                    .font(.body.monospaced())
                                    .foregroundStyle(.primary)
                                Spacer(minLength: 8)
                                if let meta = item.meta, !meta.isEmpty {
                                    Text(meta)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(1)
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        if index < items.count - 1 { Divider() }
                    }
                }
            }
            .frame(maxHeight: 220)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.separatorSubtle, lineWidth: 1)
            }
            .shadow(radius: 8, y: -2)
            .padding(.horizontal, 12)
            .padding(.bottom, 2)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Slash command suggestions")
        }
    }
}
