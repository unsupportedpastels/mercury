import SwiftUI
import UIKit

#if DEBUG
/// Synthetic simulator fixture for the production artifact policy and image
/// renderer. It performs no network, credential, or private transcript access.
struct ManagedImageFixtureView: View {
    @State private var state = ChatSessionState(
        sessionID: "managed-image-fixture",
        title: "Inline images",
        isNewSession: false,
        incomingShare: nil
    )
    @State private var loaded = false

    private let message = """
    PROSE BEFORE
    ![Local Markdown image](/tmp/fixture-markdown.png)
    PROSE BETWEEN
    MEDIA:/tmp/fixture-media.png
    PROSE AFTER
    ``multiline code starts
    ![Code image must not render](/tmp/fixture-code.png)
    and ends``
    !\\[example](/tmp/synthetic.png)
    /tmp/bare-path-must-not-render.png
    ![Remote image stays a link](https://cdn.example/remote.png)
    """

    var body: some View {
        NavigationStack {
            productionTranscript
            .navigationTitle("Inline images")
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
        .task {
            guard !loaded else { return }
            loaded = true
            state.transcript.loadTranscript([(role: "assistant", content: message)])
        }
    }

    private var productionTranscript: some View {
        var chat = ChatView(fixtureState: state)
        chat.fixtureManagedImageLoader = { path in Self.png(for: path) }
        return chat.transcriptList(backgroundTasks: BackgroundTasks())
    }

    private static func png(for path: String) -> Data {
        let color: UIColor = path.contains("markdown") ? .systemTeal : .systemIndigo
        return UIGraphicsImageRenderer(size: CGSize(width: 720, height: 360)).pngData { context in
            color.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 720, height: 360))
            UIColor.white.setFill()
            let title = path.contains("markdown") ? "LOCAL MARKDOWN" : "MEDIA"
            title.draw(at: CGPoint(x: 36, y: 145), withAttributes: [
                .font: UIFont.boldSystemFont(ofSize: 42),
                .foregroundColor: UIColor.white,
            ])
        }
    }
}
#endif
