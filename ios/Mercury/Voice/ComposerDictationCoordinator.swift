import Combine
import Foundation

/// Owns one composer dictation attempt. Recognized text is written only through
/// `setDraft`; this type intentionally has no send/submit/session dependency.
@MainActor
final class ComposerDictationCoordinator: ObservableObject {
    typealias Sleep = @Sendable (Duration) async throws -> Void

    @Published private(set) var state: DictationState = .idle

    let maxRecordingSeconds: Int
    private let permissions: DictationPermissionAuthorizing
    private let recognizer: DictationRecognizing
    private let getDraft: () -> String
    private let setDraft: (String) -> Void
    private let sleep: Sleep
    private var baselineDraft = ""
    private var timeoutTask: Task<Void, Never>?
    private var generation: UInt64 = 0

    init(
        maxRecordingSeconds: Int = 120,
        permissions: DictationPermissionAuthorizing,
        recognizer: DictationRecognizing,
        getDraft: @escaping () -> String,
        setDraft: @escaping (String) -> Void,
        sleep: @escaping Sleep = { duration in try await Task.sleep(for: duration) }
    ) {
        self.maxRecordingSeconds = max(1, maxRecordingSeconds)
        self.permissions = permissions
        self.recognizer = recognizer
        self.getDraft = getDraft
        self.setDraft = setDraft
        self.sleep = sleep
    }

    convenience init(getDraft: @escaping () -> String, setDraft: @escaping (String) -> Void) {
        self.init(
            permissions: SystemDictationPermissionAuthorizer(),
            recognizer: SFSpeechDictationRecognizer(),
            getDraft: getDraft,
            setDraft: setDraft
        )
    }

    var isActive: Bool {
        switch state {
        case .requestingPermission, .recording: return true
        case .idle, .failed: return false
        }
    }

    func start() async {
        guard !isActive else { return }
        invalidateAttempt(cancelRecognizer: true)
        state = .requestingPermission
        switch await permissions.requestPermissions() {
        case .authorized:
            break
        case .denied, .notDetermined:
            state = .failed(.permissionDenied)
            return
        case .restricted:
            state = .failed(.restricted)
            return
        }
        guard recognizer.isAvailable else {
            state = .failed(.unavailable)
            return
        }

        baselineDraft = getDraft()
        generation &+= 1
        let attempt = generation
        do {
            try recognizer.start(
                onTranscript: { [weak self] transcript, isFinal in
                    self?.receive(transcript: transcript, isFinal: isFinal, generation: attempt)
                },
                onLevel: { [weak self] level in
                    guard let self, case .recording = self.state, self.generation == attempt else { return }
                    self.state = .recording(level: min(1, max(0, level)))
                },
                onFailure: { [weak self] failure in
                    guard let self, self.generation == attempt, self.isActive else { return }
                    self.invalidateAttempt(cancelRecognizer: true)
                    self.state = .failed(failure)
                }
            )
            state = .recording(level: 0)
            let duration = Duration.seconds(maxRecordingSeconds)
            timeoutTask = Task { [weak self, sleep] in
                do {
                    try await sleep(duration)
                    guard !Task.isCancelled else { return }
                    self?.stop()
                } catch {
                    // Cancellation is the expected end for normal stop/final.
                }
            }
        } catch {
            invalidateAttempt(cancelRecognizer: true)
            state = .failed(error is DictationRecognizerError ? .unavailable : .recordingFailed)
        }
    }

    /// Explicit user stop. Partial text already inserted remains a draft; no
    /// prompt is sent and the draft is never cleared.
    func stop() {
        guard isActive else { return }
        generation &+= 1
        timeoutTask?.cancel()
        timeoutTask = nil
        recognizer.stop()
        state = .idle
    }

    func cancel() {
        invalidateAttempt(cancelRecognizer: true)
        state = .idle
    }

    func dismissError() {
        if case .failed = state { state = .idle }
    }

    private func receive(transcript: String, isFinal: Bool, generation attempt: UInt64) {
        guard generation == attempt, case .recording = state else { return }
        let normalized = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        if !normalized.isEmpty {
            let separator = baselineDraft.isEmpty || baselineDraft.last?.isWhitespace == true ? "" : " "
            setDraft(baselineDraft + separator + normalized)
        }
        if isFinal {
            timeoutTask?.cancel()
            timeoutTask = nil
            recognizer.stop()
            generation &+= 1
            state = normalized.isEmpty ? .failed(.noSpeech) : .idle
        }
    }

    private func invalidateAttempt(cancelRecognizer: Bool) {
        generation &+= 1
        timeoutTask?.cancel()
        timeoutTask = nil
        if cancelRecognizer { recognizer.cancel() }
    }
}
