import AuthenticationServices
import UIKit

/// App-owned wrapper around ASWebAuthenticationSession.
///
/// Unlike opening Safari directly, ASWebAuthenticationSession keeps the
/// authorization transaction owned by Mercury while Safari renders the web
/// pages. That matters for the Hermes loopback callback: iOS may suspend the
/// app when a standalone Safari window is foreground, but the authentication
/// session still delivers the callback to this process.
@MainActor
final class BrowserAuthenticationSession: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = BrowserAuthenticationSession()

    private var session: ASWebAuthenticationSession?

    func authenticate(url: URL, callbackURLScheme: String) async throws -> URL {
        cancel()

        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: callbackURLScheme
            ) { [weak self] callbackURL, error in
                self?.session = nil
                if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(throwing: FlowError.badResponse)
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            self.session = session

            guard session.start() else {
                self.session = nil
                continuation.resume(throwing: FlowError.transient("could not start browser sign-in"))
                return
            }
        }
    }

    func cancel() {
        session?.cancel()
        session = nil
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
            ?? UIWindow(frame: UIScreen.main.bounds)
    }
}
