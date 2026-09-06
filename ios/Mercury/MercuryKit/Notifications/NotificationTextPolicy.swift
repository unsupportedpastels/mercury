import Foundation
import MercuryCore

/// Facade over the shared KMP core's NotificationTextPolicy
/// (shared/mercury-core): previews, headings, wire status mapping, and
/// fallback prompts are decided once for both clients. The Swift API and
/// call sites are unchanged.
enum NotificationTextPolicy {
    private static let core = MercuryCore.NotificationTextPolicy.shared

    /// Shown when a blocking approval request carries no prompt text.
    static let approvalFallback = core.APPROVAL_FALLBACK
    /// Shown when a blocking clarification request carries no prompt text.
    static let clarificationFallback = core.CLARIFICATION_FALLBACK
    /// Shown when a blocking secure-input request carries no prompt text.
    static let secureInputFallback = core.SECURE_INPUT_FALLBACK

    static func finalResponsePreview(
        _ text: String,
        maxLines: Int = Int(MercuryCore.NotificationTextPolicy.shared.DEFAULT_PREVIEW_LINES)
    ) -> String {
        core.finalResponsePreview(text: text, maxLines: Int32(maxLines))
    }

    static func completionHeading(status: CompletionStatus) -> String {
        core.completionHeading(status: status.core)
    }

    static func completionStatus(fromWire status: String?) -> CompletionStatus {
        CompletionStatus(core.completionStatusFromWire(status: status))
    }

    static func inputHeading(for kind: NotificationKind) -> String {
        switch kind {
        case .approval:
            return core.inputHeading(kind: .approval)
        case .clarification:
            return core.inputHeading(kind: .clarification)
        case .secureInput:
            return core.inputHeading(kind: .secureInput)
        case .completion:
            // Completion notifications use completionHeading(status:).
            return ""
        }
    }

    static func inputPreview(_ text: String) -> String {
        core.inputPreview(text: text)
    }
}

private extension CompletionStatus {
    init(_ core: MercuryCore.CompletionStatus) {
        if core == MercuryCore.CompletionStatus.failed {
            self = .failed
        } else if core == MercuryCore.CompletionStatus.cancelled {
            self = .cancelled
        } else {
            self = .finished
        }
    }

    var core: MercuryCore.CompletionStatus {
        switch self {
        case .finished: return .finished
        case .failed: return .failed
        case .cancelled: return .cancelled
        }
    }
}
