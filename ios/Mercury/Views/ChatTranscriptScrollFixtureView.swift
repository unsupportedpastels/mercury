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

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Button("Append synthetic stream chunk") {
                    state.isSending = true
                    streamChunk += 1
                    state.transcript.ensureInflightAssistantRow(
                        text: "Synthetic streaming response\n" + String(
                            repeating: "A growing response line exercises follow scrolling.\n",
                            count: streamChunk * 12
                        ),
                        completed: false
                    )
                }
                .buttonStyle(.bordered)
                .padding(8)

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
            for index in 1...35 {
                messages.append((
                    role: index.isMultiple(of: 2) ? "assistant" : "user",
                    content: "Synthetic message \(index)\nA second line makes the transcript tall enough to scroll."
                ))
            }
            messages.append((role: "assistant", content: "Synthetic latest message"))
            state.transcript.loadTranscript(messages)
        }
    }
}
#endif
