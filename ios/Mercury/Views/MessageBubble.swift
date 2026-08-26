import SwiftUI

/// One transcript row.
///
/// Layout policy ported from Android's chat pane: user messages sit in a
/// raised bubble aligned right; assistant text sits flush on the AMOLED black
/// background at full width (no bubble chrome). Streaming assistant rows
/// render PLAIN text only — markdown is applied on completion, because
/// streaming-markdown re-parsing flickers (an Android-solved bug).
struct MessageBubble: View {
    let role: String
    let text: String
    let isStreaming: Bool

    private var isUser: Bool { role.lowercased() == "user" }

    var body: some View {
        HStack(alignment: .bottom) {
            if isUser { Spacer(minLength: 48) }
            content
            if !isUser { Spacer(minLength: 48) }
        }
    }

    @ViewBuilder
    private var content: some View {
        if isUser {
            Text(text)
                .font(.body)
                .foregroundStyle(Color.primary)
                .textSelection(.enabled)
                // The bubble is inside an HStack with a leading spacer. Give
                // Text an explicit width proposal and let every line expand
                // vertically instead of allowing SwiftUI to truncate it.
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.surfaceMid)
                .clipShape(RoundedRectangle(cornerRadius: 16))
        } else {
            VStack(alignment: .leading, spacing: 4) {
                if isStreaming {
                    // Plain text during deltas; a soft cursor marks liveness.
                    Text(text + "▍")
                        .font(.body)
                        .foregroundStyle(Color.primary)
                        .textSelection(.enabled)
                } else {
                    MessageMarkdownView(text: text)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

#if DEBUG
#Preview {
    ScrollView {
        VStack(spacing: 12) {
            MessageBubble(role: "user", text: "Summarize this repo", isStreaming: false)
            MessageBubble(role: "assistant", text: "It is a Hermes client.", isStreaming: false)
            MessageBubble(role: "assistant", text: "Working on it… ", isStreaming: true)
        }
        .padding()
    }
    .amoledScreen()
}
#endif
