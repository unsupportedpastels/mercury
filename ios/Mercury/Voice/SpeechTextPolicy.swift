import Foundation

/// Bounded subset of Android/Desktop speech sanitization: omit fenced code and
/// markdown tables, read link labels, replace bare URLs with "link", remove a
/// leading thinking marker and residual markdown punctuation, then collapse
/// whitespace. Callers must still pass assistant-message text only.
enum SpeechTextPolicy {
    static let maxInputCharacters = SpeechSynthesisRequestPolicy.maxTextCharacters

    static func sanitize(_ source: String) -> String {
        var text = String(source.prefix(maxInputCharacters))
        text = replace(#"```[\s\S]*?```"#, in: text, with: " code block omitted ")
        text = text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
        text = text.split(separator: "\n", omittingEmptySubsequences: false)
            .filter { line in
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                guard trimmed.contains("|") else { return true }
                let cells = trimmed.split(separator: "|", omittingEmptySubsequences: true)
                return !cells.allSatisfy {
                    $0.trimmingCharacters(in: .whitespaces).allSatisfy { $0 == "-" || $0 == ":" }
                }
            }
            .joined(separator: "\n")
        text = replace(#"(?i)^\s*(thinking|reasoning)\s*[.…:—-]*\s*"#, in: text, with: "")
        text = replace(#"\[([^\]]+)\]\([^\)]+\)"#, in: text, with: "$1")
        text = replace(#"`([^`]+)`"#, in: text, with: "$1")
        text = replace(#"https?://\S+|www\.\S+"#, in: text, with: " link ")
        text = replace(#"(?m)^\s{0,3}#{1,6}\s*"#, in: text, with: "")
        text = replace(#"(?m)^\s*(?:[-*+]\s+|\d+[.)]\s+)"#, in: text, with: "")
        text = text.replacingOccurrences(of: "*", with: "")
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "~", with: "")
            .replacingOccurrences(of: ">", with: "")
        text = replace(#"\s+"#, in: text, with: " ")
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func replace(_ pattern: String, in value: String, with replacement: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return value }
        return regex.stringByReplacingMatches(
            in: value,
            range: NSRange(value.startIndex..., in: value),
            withTemplate: replacement
        )
    }
}
