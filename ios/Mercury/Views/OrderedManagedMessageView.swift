import SwiftUI

/// Completed assistant-message renderer shared by the real transcript and its
/// synthetic UI fixture. Text and authenticated managed images stay in the
/// exact order selected by the shared KMP policy.
struct OrderedManagedMessageView: View {
    let text: String
    var image: (_ source: String) -> AnyView

    private var segments: [ManagedMessageContentSegment] {
        MediaDirectiveExtractor.orderedManagedImageSegments(text)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(segments.enumerated()), id: \.offset) { index, segment in
                switch segment {
                case .text(let content):
                    if !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        MessageBubble(role: "assistant", text: content, isStreaming: false)
                            .accessibilityIdentifier("Ordered message text \(index)")
                    }
                case .image(let source, let stableIdentity):
                    image(source)
                        .id(stableIdentity)
                        .accessibilityIdentifier("Ordered managed image \(index)")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
