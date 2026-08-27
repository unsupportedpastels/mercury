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

    /// One-shot bridge between Apple's callback and Swift concurrency.
    /// `ASWebAuthenticationSession.cancel()` does not guarantee that its
    /// completion handler runs, so cancellation must resolve the checked
    /// continuation directly. Resolution is idempotent because a late Apple
    /// callback can race with explicit teardown after the loopback path wins.
    final class CompletionGate {
        private var continuation: CheckedContinuation<URL, Error>?
        private var pendingResult: Result<URL, Error>?
        private var resolved = false

        func install(_ continuation: CheckedContinuation<URL, Error>) {
            precondition(self.continuation == nil)
            if let pendingResult {
                self.pendingResult = nil
                continuation.resume(with: pendingResult)
            } else {
                self.continuation = continuation
            }
        }

        func resolve(_ result: Result<URL, Error>) {
            guard !resolved else { return }
            resolved = true
            if let continuation {
                self.continuation = nil
                continuation.resume(with: result)
            } else {
                pendingResult = result
            }
        }
    }

    private var session: ASWebAuthenticationSession?
    private var completionGate: CompletionGate?

    func authenticate(url: URL, callbackURLScheme: String) async throws -> URL {
        cancel()

        let gate = CompletionGate()
        completionGate = gate

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                let session = ASWebAuthenticationSession(
                    url: url,
                    callbackURLScheme: callbackURLScheme
                ) { [weak self] callbackURL, error in
                    self?.session = nil
                    self?.completionGate = nil
                    if let callbackURL {
                        gate.resolve(.success(callbackURL))
                    } else if let error {
                        gate.resolve(.failure(error))
                    } else {
                        gate.resolve(.failure(FlowError.badResponse))
                    }
                }
                session.presentationContextProvider = self
                session.prefersEphemeralWebBrowserSession = false
                self.session = session

                guard session.start() else {
                    self.session = nil
                    self.completionGate = nil
                    gate.resolve(.failure(FlowError.transient("could not start browser sign-in")))
                    return
                }
            }
        } onCancel: { [weak self] in
            Task { @MainActor in
                self?.cancel()
            }
        }
    }

    /// Detects the system authentication UI yielding control back to Mercury.
    /// A system authentication sheet can yield control back to the app without
    /// invoking the ASWebAuthenticationSession completion handler, even when
    /// the browser-side login looked successful. That handler therefore cannot
    /// be the only signal that starts the bounded callback grace period.
    func waitForReturnToApp() async -> Bool {
        for await _ in NotificationCenter.default.notifications(
            named: UIApplication.willResignActiveNotification
        ) {
            if Task.isCancelled { return false }
            break
        }
        if Task.isCancelled { return false }

        for await _ in NotificationCenter.default.notifications(
            named: UIApplication.didBecomeActiveNotification
        ) {
            return !Task.isCancelled
        }
        return false
    }

    func cancel() {
        let pendingSession = session
        session = nil
        let gate = completionGate
        completionGate = nil
        gate?.resolve(.failure(CancellationError()))
        pendingSession?.cancel()
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
            ?? UIWindow(frame: UIScreen.main.bounds)
    }
}
