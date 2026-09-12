#if DEBUG
import Combine
import SwiftUI

@MainActor
private final class DictationSendFixtureModel: ObservableObject {
    final class AuthorizedPermissions: DictationPermissionAuthorizing {
        func requestPermissions() async -> DictationPermissionState { .authorized }
    }

    final class Recognizer: DictationRecognizing {
        var isAvailable = true
        private var transcript: ((String, Bool) -> Void)?
        private var failure: ((DictationFailure) -> Void)?

        func start(
            onTranscript: @escaping (String, Bool) -> Void,
            onLevel: @escaping (Float) -> Void,
            onFailure: @escaping (DictationFailure) -> Void
        ) throws {
            transcript = onTranscript
            failure = onFailure
        }

        func stop() {}
        func cancel() {}
        func emit(_ text: String, final: Bool = false) { transcript?(text, final) }
        func fail() { failure?(.recognitionFailed) }
    }

    @Published var draft = ""
    @Published var error: String?
    @Published var submitted = "Not submitted"
    @Published var submitCount = 0
    let recognizer = Recognizer()
    private(set) var dictation: ComposerDictationCoordinator!

    init() {
        dictation = ComposerDictationCoordinator(
            permissions: AuthorizedPermissions(),
            recognizer: recognizer,
            getDraft: { [weak self] in self?.draft ?? "" },
            setDraft: { [weak self] in self?.draft = $0 }
        )
    }

    func submit() {
        submitCount += 1
        submitted = draft
        draft = ""
    }
}

/// Hermetic UI fixture for the app-owned dictation submission boundary. It
/// drives the production coordinator and ComposerBar without microphone,
/// account, transport, or server dependencies.
struct DictationSendFixtureView: View {
    @StateObject private var model = DictationSendFixtureModel()

    var body: some View {
        VStack(spacing: 16) {
            Button("Start simulated dictation") {
                Task { await model.dictation.start() }
            }
            Button("Emit partial transcript") {
                model.recognizer.emit("send this dictated message")
            }
            Button("Emit late transcript") {
                model.recognizer.emit("late transcript must be ignored", final: true)
                model.recognizer.fail()
            }
            Text(model.submitted)
                .accessibilityIdentifier("Submitted dictated text")
            Text("Submit count: \(model.submitCount)")
                .accessibilityIdentifier("Dictation submit count")
            Text("Original active response remains streaming")
                .accessibilityIdentifier("Original active response")
            ComposerBar(
                draft: $model.draft,
                errorMessage: $model.error,
                isSending: false,
                onSend: model.submit,
                isQueueing: true,
                dictation: model.dictation
            )
        }
        .padding()
        .preferredColorScheme(.dark)
    }
}
#endif
