import SwiftUI

/// Collapsed-by-default disclosure for a transcript row's reasoning text,
/// styled to match Android's "Thinking" pill: a quiet full-width surface
/// row with a brain icon, the label, a one-line inline preview of the
/// latest reasoning text, and a trailing chevron that flips when expanded.
/// Dumb view: takes plain values, no environment, no AppModel.
///
/// The content is rendered as a plain monospaced caption `Text` — no
/// markdown interpretation — since reasoning streams are model output, not
/// authored UI copy. Reasoning is retained on completed rows (the reducer
/// keeps `reasoningText` after completion), so this stays expandable even
/// after the segment seals.
struct ReasoningDisclosure: View {

    let reasoningText: String
    /// True while the owning assistant row is still streaming; only used to
    /// soften the label while content is arriving.
    var streaming: Bool = false

    @State private var isExpanded = false

    private var displayText: String {
        TranscriptPresentationPolicy.reasoningDisplayText(reasoningText)
    }

    /// Flattened, presentation-safe reasoning preview. Transport directives
    /// and raw emphasis delimiters never become visible Thought labels.
    private var previewText: String {
        TranscriptPresentationPolicy.reasoningPreview(reasoningText)
    }

    var body: some View {
        if !displayText.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Button {
                    withAnimation(.easeInOut(duration: 0.15)) {
                        isExpanded.toggle()
                    }
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "brain.head.profile")
                            .font(.caption)
                        Text(streaming ? "Thinking" : "Thought")
                            .font(.caption.weight(.semibold))
                        if !isExpanded {
                            Text(previewText)
                                .font(.caption)
                                .lineLimit(1)
                                .truncationMode(.tail)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: "chevron.down")
                            .font(.caption2)
                            .rotationEffect(.degrees(isExpanded ? 180 : 0))
                    }
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(streaming ? "Thinking, expandable" : "Reasoning, expandable")

                if isExpanded {
                    Text(displayText)
                        .font(.caption)
                        .fontDesign(.monospaced)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12)
                        .padding(.bottom, 10)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.surfaceLow)
            .clipShape(RoundedCornerPill())
        }
    }
}

/// Rounded shape for the reasoning pill (softer than a capsule so multi-line
/// expansion still looks intentional).
private struct RoundedCornerPill: Shape {
    func path(in rect: CGRect) -> Path {
        RoundedRectangle(cornerRadius: 14, style: .continuous).path(in: rect)
    }
}

#Preview("Collapsed + expanded") {
    VStack(spacing: 12) {
        ReasoningDisclosure(
            reasoningText: "Step 1: parse the request.\nStep 2: fetch context.",
            streaming: false
        )
        ReasoningDisclosure(reasoningText: "Still thinking…", streaming: true)
    }
    .padding()
    .amoledScreen()
}
