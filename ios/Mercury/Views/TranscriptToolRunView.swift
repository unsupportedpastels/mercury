import SwiftUI

/// Collapsed presentation for consecutive persisted `role: tool` messages.
///
/// The session endpoint intentionally returns complete tool payloads, which can
/// be large JSON documents. They stay available on demand, but never enter the
/// ordinary assistant-text renderer or dominate the conversation timeline.
struct TranscriptToolRunView: View {
    let rows: [TranscriptState.Row]

    @State private var isExpanded = false

    private var summary: String {
        TranscriptPresentationPolicy.toolActivitySummary(
            completedNames: rows.map { $0.toolName ?? "tool" }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "checkmark.circle")
                    Text(summary)
                        .font(.caption.weight(.medium))
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Image(systemName: "chevron.down")
                        .font(.caption2)
                        .rotationEffect(.degrees(isExpanded ? 180 : 0))
                }
                .foregroundStyle(Color.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if isExpanded {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(rows) { row in
                        TranscriptToolResultDisclosure(row: row)
                        if row.id != rows.last?.id {
                            Divider().overlay(Color.separatorSubtle)
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            }
        }
        .background(Color.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityLabel("\(rows.count) completed \(rows.count == 1 ? "action" : "actions")")
        .accessibilityValue(isExpanded ? "Expanded" : "Collapsed")
    }

}

private struct TranscriptToolResultDisclosure: View {
    let row: TranscriptState.Row
    @State private var isExpanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            if !row.text.isEmpty {
                Text(row.text)
                    .font(.caption.monospaced())
                    .foregroundStyle(Color.primary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
            }
        } label: {
            Text(displayName.capitalized)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.secondary)
        }
        .tint(Color.secondary)
        .accessibilityValue(isExpanded ? "Expanded" : "Collapsed")
    }

    private var displayName: String {
        guard let value = row.toolName, !value.isEmpty else { return "tool" }
        return value.replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
    }
}

#if DEBUG
#Preview {
    VStack {
        TranscriptToolRunView(rows: [
            TranscriptState.Row(
                role: "tool",
                text: "{\"success\":true,\"content\":\"README loaded\"}",
                completed: true,
                toolName: "read_file"
            ),
            TranscriptState.Row(
                role: "tool",
                text: "{\"output\":\"BUILD SUCCEEDED\"}",
                completed: true,
                toolName: "terminal"
            ),
        ])
    }
    .padding()
    .amoledScreen()
}
#endif
