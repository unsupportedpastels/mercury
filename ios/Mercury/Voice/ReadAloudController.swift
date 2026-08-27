import Combine
import Foundation

public enum ReadAloudFailure: Equatable, Sendable {
    case synthesis
    case authentication
    case unsupported
    case requestRejected(Int)
    case transient
    case invalidResponse
    case transport
    case playback
}

public enum ReadAloudState: Equatable, Sendable {
    case idle
    case preparing(messageID: String)
    case playing(messageID: String)
    case failed(messageID: String, reason: ReadAloudFailure)
}

/// One playback owner for a chat/session. Starting another message cancels the
/// prior synthesis, stops AVAudioPlayer, and generation-guards late results.
/// This is finalized-message read-aloud only; it has no chat-stream listener or
/// hands-free conversation loop.
@MainActor
final class ReadAloudController: ObservableObject {
    typealias Synthesize = (String) async throws -> SpeechAudio

    @Published private(set) var state: ReadAloudState = .idle

    private let player: SpeechAudioPlaying
    private let synthesize: Synthesize
    private var synthesisTask: Task<Void, Never>?
    private var generation: UInt64 = 0

    init(player: SpeechAudioPlaying, synthesize: @escaping Synthesize) {
        self.player = player
        self.synthesize = synthesize
    }

    convenience init(synthesize: @escaping Synthesize) {
        self.init(player: AVAudioPlayerOwner(), synthesize: synthesize)
    }

    convenience init(synthesizer: SpeechSynthesizing, player: SpeechAudioPlaying) {
        self.init(player: player) { text in
            try await synthesizer.synthesize(text: text)
        }
    }

    convenience init(synthesizer: SpeechSynthesizing) {
        self.init(synthesizer: synthesizer, player: AVAudioPlayerOwner())
    }

    var activeMessageID: String? {
        switch state {
        case .preparing(let id), .playing(let id): return id
        case .idle, .failed: return nil
        }
    }

    func toggle(messageID: String, text: String) {
        if activeMessageID == messageID {
            stop()
            return
        }
        let speechText = SpeechTextPolicy.sanitize(text)
        guard !speechText.isEmpty else { return }

        invalidateAndStopPlayer()
        state = .preparing(messageID: messageID)
        let attempt = generation
        synthesisTask = Task { [weak self, synthesize] in
            let audio: SpeechAudio
            do {
                audio = try await synthesize(speechText)
            } catch is CancellationError {
                return // Replacement and explicit stop are normal lifecycle events.
            } catch let error as SpeechSynthesisError {
                guard let self,
                      self.generation == attempt,
                      self.activeMessageID == messageID else { return }
                self.state = .failed(messageID: messageID, reason: Self.failure(for: error))
                return
            } catch {
                guard let self,
                      self.generation == attempt,
                      self.activeMessageID == messageID else { return }
                self.state = .failed(messageID: messageID, reason: .synthesis)
                return
            }

            guard !Task.isCancelled,
                  let self,
                  self.generation == attempt,
                  self.activeMessageID == messageID else { return }
            do {
                try self.player.play(data: audio.data) { [weak self] in
                    guard let self,
                          self.generation == attempt,
                          self.activeMessageID == messageID else { return }
                    self.state = .idle
                }
                guard self.generation == attempt, self.activeMessageID == messageID else {
                    self.player.stop()
                    return
                }
                self.state = .playing(messageID: messageID)
            } catch {
                guard self.generation == attempt, self.activeMessageID == messageID else { return }
                self.player.stop()
                self.state = .failed(messageID: messageID, reason: .playback)
            }
        }
    }

    func stop() {
        invalidateAndStopPlayer()
        state = .idle
    }

    func dismissError() {
        if case .failed = state { state = .idle }
    }

    private func invalidateAndStopPlayer() {
        generation &+= 1
        synthesisTask?.cancel()
        synthesisTask = nil
        player.stop()
    }

    private static func failure(for error: SpeechSynthesisError) -> ReadAloudFailure {
        switch error {
        case .authenticationMissing, .authenticationRejected: return .authentication
        case .unsupported: return .unsupported
        case .requestRejected(let status): return .requestRejected(status)
        case .emptyText, .invalidOrigin: return .requestRejected(0)
        case .transient, .unexpectedStatus: return .transient
        case .responseTooLarge, .audioTooLarge, .invalidResponse: return .invalidResponse
        case .transport: return .transport
        }
    }
}
