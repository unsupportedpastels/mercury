import SwiftUI

/// Drop-in composer affordance for parent integration. It owns no draft beyond
/// the injected coordinator and exposes no send action.
struct DictationButton: View {
    @ObservedObject var dictation: ComposerDictationCoordinator
    var enabled: Bool = true

    var body: some View {
        Button(action: toggle) {
            Group {
                switch dictation.state {
                case .requestingPermission:
                    ProgressView()
                        .controlSize(.small)
                        .tint(Color.composerPrimary)
                case .recording:
                    Image(systemName: "stop.fill")
                        .foregroundStyle(Color.composerPrimary)
                case .idle, .failed:
                    Image(systemName: "mic.fill")
                        .foregroundStyle(
                            enabled ? Color.composerSecondaryContent : Color.white.opacity(0.38)
                        )
                }
            }
            .font(.title3)
            .frame(width: 40, height: 40)
        }
        .disabled(!enabled || dictation.state == .requestingPermission)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint("Recognized speech is inserted into the draft and is never sent automatically")
    }

    private var accessibilityLabel: String {
        if case .recording = dictation.state { return "Stop dictation" }
        return "Dictate message"
    }

    private func toggle() {
        if case .recording = dictation.state {
            dictation.stop()
        } else {
            Task { await dictation.start() }
        }
    }
}

struct DictationFailureLabel: View {
    let failure: DictationFailure

    var body: some View {
        Label(message, systemImage: "exclamationmark.triangle.fill")
            .font(.footnote)
            .foregroundStyle(Color.statusAlert)
    }

    private var message: String {
        switch failure {
        case .permissionDenied: return "Microphone and Speech Recognition permission are required."
        case .restricted: return "Dictation is restricted on this device."
        case .unavailable: return "Speech recognition is currently unavailable."
        case .recordingFailed: return "Could not start microphone recording."
        case .recognitionFailed: return "Speech recognition failed."
        case .noSpeech: return "No speech was recognized."
        }
    }
}
