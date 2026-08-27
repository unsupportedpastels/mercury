import Foundation

enum NotificationTextPolicy {
    private static let maxNotificationPreviewCharacters = 240
    private static let defaultNotificationPreviewLines = 3

    /// Mirrors Android's `finalResponsePreview` policy.
    static func finalResponsePreview(
        _ text: String,
        maxLines: Int = defaultNotificationPreviewLines
    ) -> String {
        // The interrupt sentinel is cancellation metadata, not a response —
        // fall through to the generic fallback instead of quoting it.
        let source = TranscriptState.isInterruptSentinel(text) ? "" : text
        let cleaned = source
            .components(separatedBy: .newlines)
            .compactMap { line -> String? in
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !isMarkdownHeading(trimmed) else { return nil }
                return trimmed
                    .replacingOccurrences(of: "**", with: "")
                    .replacingOccurrences(of: "__", with: "")
                    .replacingOccurrences(of: "`", with: "")
            }
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .prefix(max(maxLines, 1))
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        let fallback = cleaned.isEmpty ? "Response completed" : cleaned
        return String(fallback.prefix(maxNotificationPreviewCharacters))
    }

    static func completionHeading(status: CompletionStatus) -> String {
        switch status {
        case .finished:
            return "Mercury finished"
        case .failed:
            return "Mercury task failed"
        case .cancelled:
            return "Mercury task was cancelled"
        }
    }

    static func completionStatus(fromWire status: String?) -> CompletionStatus {
        switch status?.lowercased() {
        case "error", "failed":
            return .failed
        case "cancelled", "canceled", "interrupted":
            return .cancelled
        default:
            return .finished
        }
    }

    static func inputHeading(for kind: NotificationKind) -> String {
        switch kind {
        case .approval:
            return "Hermes needs approval"
        case .clarification:
            return "Hermes needs your input"
        case .secureInput:
            return "Hermes needs secure input"
        case .completion:
            // Completion notifications use completionHeading(status:).
            return ""
        }
    }

    /// Mirrors Android's raw `preview.take(240)` input path while trimming the
    /// value for the platform notification payload.
    static func inputPreview(_ text: String) -> String {
        String(text.prefix(maxNotificationPreviewCharacters))
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isMarkdownHeading(_ line: String) -> Bool {
        line.range(of: "^#{1,6}\\s+.+", options: .regularExpression) != nil
    }
}
