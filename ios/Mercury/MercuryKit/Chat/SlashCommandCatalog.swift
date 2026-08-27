import Foundation

/// The platform-neutral shape used by Hermes' `complete.slash` results.
/// Presentation layers may ignore `display` and `meta`; completion application
/// deliberately depends only on `text`.
struct SlashCompletionItem: Equatable, Sendable {
    let text: String
    let display: String
    let meta: String?

    init(text: String, display: String? = nil, meta: String? = nil) {
        self.text = text
        self.display = display ?? (text.hasPrefix("/") ? text : "/\(text)")
        self.meta = meta
    }
}

// Completion rows are deliberately not cached in a static native catalog.
// `complete.slash` is the authoritative, capability-aware source; when that
// request fails the composer hides completion rather than exposing stale rows.

private let validReasoningEfforts: Set<String> = [
    "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"
]

func isModelPickerCommand(_ text: String) -> Bool {
    text.trimmingCharacters(in: .whitespacesAndNewlines) == "/model"
}

func isSteerCommand(_ text: String) -> Bool {
    let command = String(text.drop(while: { $0.isWhitespace }))
    return command == "/steer" || command.hasPrefix("/steer ")
}

func reasoningEffortCommand(_ text: String) -> String? {
    let tokens = text
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .components(separatedBy: .whitespacesAndNewlines)
        .filter { !$0.isEmpty }

    guard tokens.count == 2, tokens[0] == "/reasoning" else {
        return nil
    }

    let canonical = tokens[1].lowercased()
    return validReasoningEfforts.contains(canonical) ? canonical : nil
}

/// Mirrors desktop's anchored `looksLikeSlashCommand`: the composer must start
/// with one slash command segment. Arguments may follow whitespace, but a
/// second slash in the command token identifies an absolute path instead.
func isSlashCommandContext(_ text: String) -> Bool {
    guard text.first == "/" else {
        return false
    }

    for character in text.dropFirst() {
        if character.isWhitespace {
            return true
        }
        if character == "/" {
            return false
        }
    }

    return true
}

/// Applies Hermes `replace_from` semantics using UTF-16 offsets, matching the
/// Kotlin and desktop wire contract. The remainder at and after the replacement
/// point is discarded. A row's slash is removed only when the retained prefix
/// already ends in slash.
func applySlashCompletion(
    _ current: String,
    item: SlashCompletionItem,
    replaceFrom: Int
) -> String {
    let requestedOffset = min(max(replaceFrom, 0), current.utf16.count)
    var safeOffset = requestedOffset
    var boundary: String.Index?

    // A peer should send offsets at Unicode-scalar boundaries. If it does not,
    // clamp backward rather than manufacturing invalid Swift text.
    while boundary == nil {
        let utf16Index = current.utf16.index(current.utf16.startIndex, offsetBy: safeOffset)
        boundary = utf16Index.samePosition(in: current)
        if boundary == nil {
            safeOffset -= 1
        }
    }

    let prefix = String(current[..<boundary!])
    let addition: String
    if prefix.last == "/", item.text.hasPrefix("/") {
        addition = String(item.text.dropFirst())
    } else {
        addition = item.text
    }

    return prefix + addition
}
