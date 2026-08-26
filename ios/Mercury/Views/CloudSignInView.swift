import SwiftUI

/// Screen shown after ConnectView's "Sign in to Nous Portal": the short user
/// code to enter at the Portal, a button that opens the verification URL, and
/// live progress/error state while the device-code poll loop runs.
///
/// The view is deliberately dumb: it owns only a local phase machine
/// (idle → polling → success / error) and delegates every network side
/// effect to the `onStartPolling` closure the caller wires into
/// `AppModel`/`ConnectionController`.
struct CloudSignInView: View {

    /// Local UI phase machine.
    enum Phase: Equatable {
        case idle
        case polling
        case success
        case error(String)
    }

    /// Short human-readable code the user types at the Portal.
    let userCode: String
    /// Verification URL (pre-filled variant preferred by the caller).
    let verificationURL: URL

    /// Async hook into AppModel/controller. Returns `nil` when sign-in
    /// completed (tokens persisted) or a friendly, token-free error message;
    /// the view transitions its phase accordingly.
    var onStartPolling: () async -> String?

    @State private var phase: Phase = .idle
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            Text("Sign in to Nous Portal")
                .font(.largeTitle.bold())
                .foregroundStyle(Color.primary)

            Text("Enter this code on the Portal to authorize Mercury.")
                .font(.subheadline)
                .foregroundStyle(Color.secondary)
                .multilineTextAlignment(.center)

            Text(userCode)
                .font(.system(.largeTitle, design: .monospaced, weight: .bold))
                .textSelection(.enabled)
                .minimumScaleFactor(0.5)
                .lineLimit(1)
                .padding(.horizontal, 28)
                .padding(.vertical, 16)
                .background(Color.surfaceLow, in: RoundedRectangle(cornerRadius: 14))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(Color.separatorSubtle)
                )

            Button {
                openURL(verificationURL)
            } label: {
                Label("Open portal", systemImage: "safari")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
            .tint(Color.accentPrimary)

            statusArea

            Spacer()
        }
        .padding(24)
        .amoledScreen()
    }

    // MARK: - Status area

    @ViewBuilder
    private var statusArea: some View {
        switch phase {
        case .idle:
            Button(action: startPolling) {
                Text("I've entered the code")
                    .fontWeight(.semibold)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.accentPrimary)

        case .polling:
            VStack(spacing: 12) {
                ProgressView()
                Text("Waiting for authorization…")
                    .font(.footnote)
                    .foregroundStyle(Color.secondary)
            }

        case .success:
            Label("Signed in to Nous Portal", systemImage: "checkmark.circle.fill")
                .font(.headline)
                .foregroundStyle(Color.statusHealthy)

        case .error(let message):
            VStack(spacing: 12) {
                Label(message, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(Color.statusAlert)
                Button("Try again", action: startPolling)
                    .buttonStyle(.bordered)
            }
        }
    }

    private func startPolling() {
        phase = .polling
        Task {
            if let message = await onStartPolling() {
                phase = .error(message)
            } else {
                phase = .success
            }
        }
    }
}

#Preview {
    CloudSignInView(
        userCode: "ABCD-1234",
        verificationURL: URL(string: "https://portal.nousresearch.com/activate")!,
        onStartPolling: { nil }
    )
}
