import AVFoundation
import Foundation

@MainActor
protocol SpeechAudioPlaying: AnyObject {
    func play(data: Data, onFinished: @escaping () -> Void) throws
    func stop()
}

@MainActor
final class AVAudioPlayerOwner: NSObject, SpeechAudioPlaying, AVAudioPlayerDelegate {
    private var player: AVAudioPlayer?
    private var onFinished: (() -> Void)?

    func play(data: Data, onFinished: @escaping () -> Void) throws {
        stop()
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try session.setActive(true)
        let player = try AVAudioPlayer(data: data)
        player.delegate = self
        player.prepareToPlay()
        guard player.play() else {
            try? session.setActive(false, options: .notifyOthersOnDeactivation)
            throw ReadAloudPlaybackError.couldNotStart
        }
        self.player = player
        self.onFinished = onFinished
    }

    func stop() {
        player?.stop()
        player = nil
        onFinished = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            guard self.player === player else { return }
            let completion = self.onFinished
            self.player = nil
            self.onFinished = nil
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            completion?()
        }
    }
}

enum ReadAloudPlaybackError: Error {
    case couldNotStart
}
