import SwiftUI

/// Quiet per-turn disclosure below the settled answer, not a persistent work card.
struct TurnActivityView: View {
    let steps: [TranscriptState.Row]
    let answerReasoning: String?
    let stepCount: Int
    var media: (String) -> AnyView = { _ in AnyView(EmptyView()) }
    @State private var expanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.15)) { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Text("Activity · \(stepCount) \(stepCount == 1 ? "step" : "steps")")
                        .font(.caption)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .medium))
                        .rotationEffect(.degrees(expanded ? 90 : 0))
                }
                .foregroundStyle(Color.secondary)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("Turn activity")
            .accessibilityLabel("Activity, \(stepCount) \(stepCount == 1 ? "step" : "steps"), \(expanded ? "expanded" : "collapsed")")
            .accessibilityValue(expanded ? "Expanded" : "Collapsed")
            if expanded {
                VStack(alignment: .leading, spacing: 10) {
                    if let answerReasoning, !answerReasoning.isEmpty {
                        ReasoningDisclosure(reasoningText: answerReasoning)
                    }
                    ActivityTranscriptContent(rows: steps, media: media)
                }
                .padding(.leading, 12)
                .overlay(alignment: .leading) { Rectangle().fill(Color.separatorSubtle).frame(width: 2) }
            }
        }
    }
}

/// Both disclosures and the live details sheet use the existing media-aware
/// transcript renderers. No reduction or tool-result parsing in SwiftUI.
struct ActivityTranscriptContent: View {
    let rows: [TranscriptState.Row]
    var media: (String) -> AnyView = { _ in AnyView(EmptyView()) }
    var body: some View {
        ForEach(coalesceTranscriptEntries(rows, withinTurnActivity: true)) { entry in
            switch entry {
            case .message(let row):
                VStack(alignment: .leading, spacing: 6) {
                    if !row.reasoningText.isEmpty && row.reasoningText != row.text {
                        ReasoningDisclosure(reasoningText: row.reasoningText, streaming: !row.completed)
                    }
                    if !row.text.isEmpty {
                        MessageBubble(role: row.role, text: row.text, isStreaming: !row.completed)
                        if row.completed { media(row.text) }
                    }
                }
            case .toolRun(let rows):
                TranscriptToolRunView(rows: rows)
            case .workBurst(let reasoning, let tools):
                WorkBurstView(reasoning: reasoning, tools: tools)
            }
        }
    }
}
