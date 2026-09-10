import SwiftUI
import UIKit

#if DEBUG
/// Synthetic simulator fixture for the production artifact policy and image
/// renderer. It performs no network, credential, or private transcript access.
struct ManagedImageFixtureView: View {
    private let message = """
    Standalone MEDIA and local Markdown images should both render below.

    MEDIA:/tmp/fixture-media.png
    ![Local Markdown image](/tmp/fixture-markdown.png)
    /tmp/bare-path-must-not-render.png
    ![Remote image stays a link](https://cdn.example/remote.png)
    """

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Synthetic image fixture")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    MessageBubble(role: "assistant", text: message, isStreaming: false)
                    ForEach(ChatManagedImagePolicy.artifacts(in: message), id: \.stableIdentity) { artifact in
                        if artifact.origin == .managedPath, artifact.type == .image {
                            RemoteManagedImage(path: artifact.source, scope: "synthetic-inline-images") { path in
                                Self.png(for: path)
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("Inline images")
            .navigationBarTitleDisplayMode(.inline)
        }
        .preferredColorScheme(.dark)
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
