import AVFoundation
import Speech

@MainActor
final class SFSpeechDictationRecognizer: DictationRecognizing {
    private let recognizer: SFSpeechRecognizer?
    private let audioEngine: AVAudioEngine
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?

    init(locale: Locale = .current, audioEngine: AVAudioEngine = AVAudioEngine()) {
        self.recognizer = SFSpeechRecognizer(locale: locale)
        self.audioEngine = audioEngine
    }

    var isAvailable: Bool { recognizer?.isAvailable == true }

    func start(
        onTranscript: @escaping (String, Bool) -> Void,
        onLevel: @escaping (Float) -> Void,
        onFailure: @escaping (DictationFailure) -> Void
    ) throws {
        cancel()
        guard let recognizer, recognizer.isAvailable else {
            throw DictationRecognizerError.unavailable
        }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = false
        self.request = request

        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1_024, format: format) { buffer, _ in
            request.append(buffer)
            let level = Self.normalizedLevel(buffer)
            Task { @MainActor in onLevel(level) }
        }

        task = recognizer.recognitionTask(with: request) { result, error in
            Task { @MainActor in
                if let result {
                    onTranscript(result.bestTranscription.formattedString, result.isFinal)
                }
                if error != nil, result?.isFinal != true {
                    onFailure(.recognitionFailed)
                }
            }
        }
        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            cancel()
            throw error
        }
    }

    func stop() {
        request?.endAudio()
        tearDownAudio(cancelRecognition: false)
    }

    func cancel() {
        tearDownAudio(cancelRecognition: true)
    }

    private func tearDownAudio(cancelRecognition: Bool) {
        if audioEngine.isRunning { audioEngine.stop() }
        audioEngine.inputNode.removeTap(onBus: 0)
        if cancelRecognition { task?.cancel() }
        task = nil
        request = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated private static func normalizedLevel(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let channel = buffer.floatChannelData?.pointee else { return 0 }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return 0 }
        var sum: Float = 0
        for index in 0..<count {
            let sample = channel[index]
            sum += sample * sample
        }
        let rms = sqrt(sum / Float(count))
        let decibels = 20 * log10(max(rms, 0.000_001))
        return min(1, max(0, (decibels + 60) / 60))
    }
}

enum DictationRecognizerError: Error {
    case unavailable
}
