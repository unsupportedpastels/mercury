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
        // Expand the row, not the painted bubble. The leading spacer keeps
        // outgoing text at the trailing edge while reserving a wrapping gutter.
        .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
        #if DEBUG
        .modifier(MessageBubbleFrameProbe(role: role, text: text, part: "row"))
        #endif
    }

    @ViewBuilder
    private var content: some View {
        if isUser {
            Text(text)
                .font(.body)
                .foregroundStyle(Color.primary)
                .textSelection(.enabled)
                // Accept the HStack's bounded width, but retain the text's
                // ideal height so wrapped lines never truncate. Short text
                // keeps its intrinsic width inside the background.
                .fixedSize(horizontal: false, vertical: true)
                #if DEBUG
                .modifier(MessageBubbleFrameProbe(role: role, text: text, part: "text"))
                #endif
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.surfaceMid)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                #if DEBUG
                .modifier(MessageBubbleFrameProbe(role: role, text: text, part: "bubble"))
                #endif
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
            #if DEBUG
            .modifier(MessageBubbleFrameProbe(role: role, text: text, part: "content"))
            #endif
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
