import Foundation
import MercuryCore

/// Speech sanitisation is a shared decision (`MercuryCore.SpeechTextPolicy`,
/// the desktop `sanitizeTextForSpeech` port). Callers must still pass
/// assistant-message text only.
enum SpeechTextPolicy {
    static let maxInputCharacters = SpeechSynthesisRequestPolicy.maxTextCharacters

    static func sanitize(_ source: String) -> String {
        MercuryCore.SpeechTextPolicy.shared.sanitize(text: source, maxInputChars: Int32(maxInputCharacters))
    }
}
