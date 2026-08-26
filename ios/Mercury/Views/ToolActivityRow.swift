import SwiftUI

/// Compact one-line activity row for a single tool invocation
/// (`TranscriptState.ToolRow`). Dumb view: takes the value directly — no
/// environment, no AppModel.
struct ToolActivityRow: View {

    let toolRow: TranscriptState.ToolRow

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: symbolName)
                .font(.caption)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 1) {
                Text(toolRow.name)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                if let summary = toolRow.summary, !summary.isEmpty {
                    Text(summary)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 0)

            switch toolRow.state {
            case .running:
                ProgressView()
                    .controlSize(.small)
            case .completed:
                Image(systemName: "checkmark")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    private var symbolName: String {
        // Wrench reads as "tool work"; keep it static so rows don't churn
        // glyphs across state changes.
        "wrench.and.screwdriver"
    }

    private var accessibilityText: String {
        switch toolRow.state {
        case .running:
            return "Tool \(toolRow.name) running"
        case .completed:
            if let summary = toolRow.summary, !summary.isEmpty {
                return "Tool \(toolRow.name) completed: \(summary)"
            }
            return "Tool \(toolRow.name) completed"
        }
    }
}

#Preview("States") {
    List {
        ToolActivityRow(toolRow: TranscriptState.ToolRow(
            toolID: "t1", name: "shell", context: nil, summary: nil, state: .running
        ))
        ToolActivityRow(toolRow: TranscriptState.ToolRow(
            toolID: "t2", name: "web_search",
            context: nil, summary: "3 results", state: .completed
        ))
    }
}
