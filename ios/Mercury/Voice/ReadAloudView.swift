import SwiftUI

/// Per-message control backed by a shared `ReadAloudController`. Parent
/// integration should create one controller per visible chat/session and pass
/// it to every finalized assistant message.
struct ReadAloudButton: View {
    @ObservedObject var controller: ReadAloudController
    let messageID: String
    let text: String
    var enabled: Bool = true

    var body: some View {
        Button {
            controller.toggle(messageID: messageID, text: text)
        } label: {
            switch localPhase {
            case .preparing:
                ProgressView().controlSize(.small)
            case .playing:
                Image(systemName: "stop.fill")
            case .failed:
                Image(systemName: "exclamationmark.triangle")
            case .idle:
                Image(systemName: "speaker.wave.2")
            }
        }
        .disabled(!enabled)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(localPhase.accessibilityValue)
    }

    private var localPhase: LocalPhase {
        switch controller.state {
        case .preparing(let id) where id == messageID: return .preparing
        case .playing(let id) where id == messageID: return .playing
        case .failed(let id, let reason) where id == messageID: return .failed(reason)
        default: return .idle
        }
    }

    private var accessibilityLabel: String {
        switch localPhase {
        case .idle: return "Read message aloud"
        case .preparing, .playing: return "Stop reading aloud"
        case .failed: return "Retry read aloud"
        }
    }

    private enum LocalPhase {
        case idle
        case preparing
        case playing
        case failed(ReadAloudFailure)

        var accessibilityValue: String {
            switch self {
            case .idle: return "Idle"
            case .preparing: return "Preparing"
            case .playing: return "Playing"
            case .failed(.synthesis): return "Synthesis failed"
            case .failed(.authentication): return "Authentication failed"
            case .failed(.unsupported): return "Read aloud unsupported"
            case .failed(.requestRejected(let status)):
                return status == 0 ? "Speech request rejected" : "Speech request rejected (HTTP \(status))"
            case .failed(.transient): return "Speech service unavailable"
            case .failed(.invalidResponse): return "Invalid speech response"
            case .failed(.transport): return "Speech transport failed"
            case .failed(.playback): return "Playback failed"
            }
        }
    }
}
