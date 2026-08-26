import AVFoundation
import Speech

@MainActor
final class SystemDictationPermissionAuthorizer: DictationPermissionAuthorizing {
    func requestPermissions() async -> DictationPermissionState {
        let speech = await speechAuthorization()
        guard speech == .authorized else { return speech }
        return await microphoneAuthorization()
    }

    private func speechAuthorization() async -> DictationPermissionState {
        let current = SFSpeechRecognizer.authorizationStatus()
        if current != .notDetermined { return Self.map(current) }
        return await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: Self.map(status))
            }
        }
    }

    private func microphoneAuthorization() async -> DictationPermissionState {
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted: return .authorized
        case .denied: return .denied
        case .undetermined:
            return await withCheckedContinuation { continuation in
                session.requestRecordPermission { granted in
                    continuation.resume(returning: granted ? .authorized : .denied)
                }
            }
        @unknown default:
            return .restricted
        }
    }

    private static func map(_ status: SFSpeechRecognizerAuthorizationStatus) -> DictationPermissionState {
        switch status {
        case .authorized: return .authorized
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notDetermined
        @unknown default: return .restricted
        }
    }
}
