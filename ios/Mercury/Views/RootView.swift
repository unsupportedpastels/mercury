import SwiftUI

/// Top-level routing over the connection lifecycle.
struct RootView: View {
    @Environment(AppModel.self) private var appModel: AppModel

    var body: some View {
        Group {
            switch appModel.connectionPhase {
            case .disconnected, .probing, .connecting:
                ConnectView()
            case .signInRequired:
                SignInView()
            case .connected:
                SessionListView()
            case .failed(let message):
                if appModel.sessions.isEmpty {
                    ConnectView(errorMessage: message)
                } else {
                    SessionListView()
                }
            }
        }
        .amoledScreen()
    }
}

#Preview {
    RootView()
        .environment(AppModel())
        .preferredColorScheme(.dark)
}
