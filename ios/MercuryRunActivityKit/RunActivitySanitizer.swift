import Foundation

/// Foundation-only privacy and presentation helpers for Mercury run activities.
public enum RunActivitySanitizer {
    public static func sanitizeTitle(_ value: String) -> String {
        let cleaned = singleLine(value)
        guard !cleaned.isEmpty else { return "Mercury session" }
        return bounded(cleaned, to: 60)
    }

    public static func sanitizeActivityLine(_ value: String) -> String {
        bounded(singleLine(value), to: 80)
    }

    /// Converts a tool name into a generic category. Only `forToolName` is
    /// inspected; callers must never pass arguments, context, or paths here.
    public static func toolLabel(forToolName name: String) -> String {
        let lowercased = name.lowercased()
        if ["terminal", "bash", "shell", "command"].contains(where: { lowercased.contains($0) }) {
            return "Running command"
        }
        if ["read", "file", "write", "patch"].contains(where: { lowercased.contains($0) }) {
            return "Reading files"
        }
        if ["search", "grep", "glob", "web_search"].contains(where: { lowercased.contains($0) }) {
            return "Searching"
        }
        return "Using tool"
    }

    /// Produces the stricter Live Activity response preview. This intentionally
    /// matches NotificationTextPolicy's heading/markdown cleaning and fallback,
    /// but joins lines into one paragraph and caps at 120 characters instead
    /// of notifications' 240-character cap.
    public static func responseExcerpt(from text: String) -> String {
        let cleaned = text
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
            .prefix(3)
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        let fallback = cleaned.isEmpty ? "Response completed" : cleaned
        return bounded(fallback, to: 120)
    }

    private static func singleLine(_ value: String) -> String {
        value
            .components(separatedBy: .newlines)
            .joined(separator: " ")
            .components(separatedBy: .controlCharacters)
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func bounded(_ value: String, to limit: Int) -> String {
        String(value.prefix(limit)).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isMarkdownHeading(_ line: String) -> Bool {
        line.range(of: "^#{1,6}\\s+.+", options: .regularExpression) != nil
    }
}
