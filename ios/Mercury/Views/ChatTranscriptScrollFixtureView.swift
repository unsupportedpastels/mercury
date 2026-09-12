import SwiftUI

#if DEBUG
/// Synthetic XCUITest host for the production transcript view. It has no
/// transport, credentials, persistence, or live-session side effects.
struct ChatTranscriptScrollFixtureView: View {
    @State private var state = ChatSessionState(
        sessionID: "fixture",
        title: "Transcript scroll fixture",
        isNewSession: false,
        incomingShare: nil
    )
    @State private var loaded = false
    @State private var streamChunk = 0

    private let fixtureLine = "A deliberately uneven Markdown line makes lazy row estimates diverge from their measured heights."
    private var usesStressTranscript: Bool {
        ProcessInfo.processInfo.arguments.contains("-uitest-chat-scroll-stress")
    }

    var body: some View {
        if ProcessInfo.processInfo.arguments.contains("-uitest-bubble-layout") {
            MessageBubbleLayoutFixtureView()
        } else {
            scrollFixture
        }
    }

    private var scrollFixture: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Button("Append synthetic stream chunk") {
                    state.isSending = true
                    streamChunk += 1
                    state.transcript.ensureInflightAssistantRow(
                        text: syntheticStreamingText(lineCount: streamChunk * 120),
                        completed: false
                    )
                }
                .buttonStyle(.bordered)
                .padding(8)

                if usesStressTranscript {
                    Button("Start synthetic stream burst") {
                        Task { @MainActor in
                            state.isSending = true
                            for chunk in 1...8 {
                                streamChunk = chunk
                                state.transcript.ensureInflightAssistantRow(
                                    text: syntheticStreamingText(lineCount: chunk * 120),
                                    completed: false
                                )
                                try? await Task.sleep(for: .milliseconds(80))
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                    .padding(.bottom, 8)
                }

                ChatView(fixtureState: state).transcriptList(backgroundTasks: BackgroundTasks())
            }
            .navigationTitle("Transcript fixture")
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
        .task {
            guard !loaded else { return }
            loaded = true
            // Ensure the production transcript has mounted before its async
            // load changes row count, matching the real REST-load lifecycle.
            try? await Task.sleep(for: .milliseconds(250))
            var messages: [(role: String, content: String)] = []
            for index in 1...(usesStressTranscript ? 120 : 35) {
                messages.append((
                    role: index.isMultiple(of: 2) ? "assistant" : "user",
                    content: syntheticHistoryText(index: index)
                ))
            }
            messages.append((role: "assistant", content: "Synthetic latest message"))
            state.transcript.loadTranscript(messages)
        }
    }

    private func syntheticHistoryText(index: Int) -> String {
        let lineCount = (index * 17).quotientAndRemainder(dividingBy: 23).remainder + 1
        let body = String(repeating: "\(fixtureLine)\n", count: lineCount)
        if index.isMultiple(of: 5) {
            return "Synthetic message \(index)\n\n### Variable-height section\n\n\(body)"
        }
        return "Synthetic message \(index)\n\(body)"
    }

    private func syntheticStreamingText(lineCount: Int) -> String {
        "Synthetic streaming response\n" + String(
            repeating: "A growing response line exercises follow scrolling.\n",
            count: lineCount
        )
    }
}
#endif
