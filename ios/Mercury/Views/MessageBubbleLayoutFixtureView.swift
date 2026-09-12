import SwiftUI

#if DEBUG
private struct MeasureMessageBubbleKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var measureMessageBubble: Bool {
        get { self[MeasureMessageBubbleKey.self] }
        set { self[MeasureMessageBubbleKey.self] = newValue }
    }
}

struct MessageBubbleFramesKey: PreferenceKey {
    static let defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}

/// Reads the padded/background bounds, independently of Text accessibility.
/// Inert unless the synthetic fixture explicitly opts in; never logs chat text.
struct MessageBubbleFrameProbe: ViewModifier {
    @Environment(\.measureMessageBubble) private var enabled
    let role: String
    let text: String
    let part: String

    func body(content: Content) -> some View {
        content.background {
            if enabled {
                GeometryReader { geometry in
                    Color.clear.preference(
                        key: MessageBubbleFramesKey.self,
                        value: ["\(role)-\(text == "test" ? "short" : "long")-\(part)":
                                    geometry.frame(in: .global)]
                    )
                }
            }
        }
    }
}

/// Uses the real transcript and its row wrappers/padding, with no transport.
struct MessageBubbleLayoutFixtureView: View {
    static let longText = "A longer outgoing message should wrap naturally within the available width.\nEvery line stays readable.\nThis final sentence must appear completely, including END OF MESSAGE."
    @State private var state = ChatSessionState(
        sessionID: "bubble-layout-fixture", title: "Bubble layout", isNewSession: false, incomingShare: nil
    )
    @State private var frames: [String: CGRect] = [:]

    private var largeText: Bool {
        ProcessInfo.processInfo.arguments.contains("-uitest-bubble-large-text")
    }

    var body: some View {
        NavigationStack {
            ChatView(fixtureState: state).transcriptList(backgroundTasks: BackgroundTasks())
                .environment(\.measureMessageBubble, true)
                .dynamicTypeSize(largeText ? .accessibility1 : .large)
                .background {
                    GeometryReader { geometry in
                        // The parent has already proposed the safe-area width;
                        // unlike ScrollView's accessibility frame, this frame
                        // excludes the landscape unsafe edges. Do not inset twice.
                        Color.clear.preference(key: MessageBubbleFramesKey.self, value: [
                            "transcript-safe": geometry.frame(in: .global)
                        ])
                    }
                }
                .onPreferenceChange(MessageBubbleFramesKey.self) { frames = $0 }
                .navigationTitle("Bubble layout · synthetic")
                .navigationBarTitleDisplayMode(.inline)
                .overlay(alignment: .bottomLeading) {
                    Text("Layout measurements")
                        .font(.system(size: 1))
                        .accessibilityIdentifier("Bubble layout measurements")
                        .accessibilityValue(frameJSON)
                }
        }
        .preferredColorScheme(.dark)
        .task {
            guard state.transcript.rows.isEmpty else { return }
            state.followBottom = false
            state.initialScrollDone = true
            state.transcript.loadTranscript([
                (role: "user", content: "test"),
                (role: "user", content: Self.longText),
                (role: "assistant", content: "Assistant replies remain left aligned, without bubble chrome.")
            ])
        }
    }

    private var frameJSON: String {
        let values = frames.mapValues { [$0.minX, $0.minY, $0.width, $0.height] }
        guard let data = try? JSONSerialization.data(withJSONObject: values, options: .sortedKeys),
              let json = String(data: data, encoding: .utf8) else { return "{}" }
        return json
    }
}
#endif
