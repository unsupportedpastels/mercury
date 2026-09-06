import SwiftUI

/// Top-level routing over the connection lifecycle.
struct RootView: View {
    @Environment(AppModel.self) private var appModel: AppModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var splitSelection: HomeChatDestination?

    var body: some View {
        Group {
            switch appModel.connectionPhase {
            case .disconnected, .probing, .connecting:
                ConnectView()
            case .signInRequired:
                SignInView()
            case .connected:
                home
            case .failed(let message):
                if appModel.sessions.isEmpty {
                    ConnectView(errorMessage: message)
                } else {
                    home
                }
            }
        }
        .amoledScreen()
    }

    /// Compact widths keep the single home stack. Regular widths (iPad, large
    /// phones in landscape) use a native split view: the home list is the
    /// sidebar and the selected chat is the detail column, the same
    /// destinations Android's list/detail layout shows side by side.
    @ViewBuilder
    private var home: some View {
        if horizontalSizeClass == .regular {
            NavigationSplitView {
                SessionListView(splitSelection: $splitSelection)
            } detail: {
                if let selection = splitSelection {
                    NavigationStack {
                        if selection.isNewSession {
                            ChatView.newSession()
                        } else {
                            ChatView(sessionID: selection.sessionID, title: selection.title)
                        }
                    }
                    .id(selection)
                } else {
                    ContentUnavailableView(
                        "Select a session",
                        systemImage: "bubble.left.and.bubble.right",
                        description: Text("Pick a project or a recent session to open it here.")
                    )
                    .amoledScreen()
                }
            }
            .navigationSplitViewStyle(.balanced)
        } else {
            SessionListView()
        }
    }
}

#Preview {
    RootView()
        .environment(AppModel())
        .preferredColorScheme(.dark)
}

#Preview("Light") {
    RootView()
        .environment(AppModel())
        .preferredColorScheme(.light)
}
