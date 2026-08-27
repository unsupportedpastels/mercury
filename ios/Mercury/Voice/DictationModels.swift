import Foundation

public enum DictationPermissionState: Equatable, Sendable {
    case notDetermined
    case authorized
    case denied
    case restricted
}

public enum DictationFailure: Equatable, Sendable {
    case permissionDenied
    case restricted
    case unavailable
    case recordingFailed
    case recognitionFailed
    case noSpeech
}

public enum DictationState: Equatable, Sendable {
    case idle
    case requestingPermission
    case recording(level: Float)
    case failed(DictationFailure)
}

@MainActor
protocol DictationPermissionAuthorizing: AnyObject {
    func requestPermissions() async -> DictationPermissionState
}

@MainActor
protocol DictationRecognizing: AnyObject {
    var isAvailable: Bool { get }
    func start(
        onTranscript: @escaping (_ transcript: String, _ isFinal: Bool) -> Void,
        onLevel: @escaping (_ normalizedLevel: Float) -> Void,
        onFailure: @escaping (_ failure: DictationFailure) -> Void
    ) throws
    func stop()
    func cancel()
}
