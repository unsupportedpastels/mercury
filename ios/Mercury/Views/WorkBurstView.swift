import SwiftUI

/// One collapsed work burst: consecutive reasoning-only steps bundled with
/// their tool runs into a single compact activity line. Expanding reveals
/// each Thought disclosure and individual tool result in original order.
struct WorkBurstView: View {
    let reasoning: [TranscriptState.Row]
    let tools: [TranscriptState.Row]

    @State private var isExpanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                withAnimation(.easeInOut(duration: 0.15)) { isExpanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "brain.head.profile")
                        .font(.caption)
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
                    ForEach(reasoning) { row in
                        ReasoningDisclosure(reasoningText: row.reasoningText, streaming: !row.completed)
                    }
                    if !tools.isEmpty {
                        TranscriptToolRunView(rows: tools)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
            }
        }
        .background(Color.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityLabel(summary)
    }

    private var summary: String {
        let stepCount = reasoning.count + tools.count
        let noun = stepCount == 1 ? "step" : "steps"
        let label = reasoning.contains(where: { !$0.completed }) ? "Working" : "Activity"
        return "\(label) · \(stepCount) \(noun)"
    }
}
