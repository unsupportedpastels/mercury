import Foundation
import MercuryCore

/// The platform-neutral shape used by Hermes' `complete.slash` results.
/// Presentation layers may ignore `display` and `meta`; completion application
/// deliberately depends only on `text`.
struct SlashCompletionItem: Equatable, Sendable {
    let text: String
    let display: String
    let meta: String?

    init(text: String, display: String? = nil, meta: String? = nil) {
        self.text = text
        self.display = display ?? MercuryCore.SlashCommandPolicy.shared.defaultDisplay(text: text)
        self.meta = meta
    }
}

// Completion rows are deliberately not cached in a static native catalog.
// `complete.slash` is the authoritative, capability-aware source; when that
// request fails the composer hides completion rather than exposing stale rows.

// Slash predicates and completion application decide in the shared KMP core
// (shared/mercury-core); these free functions keep existing call sites.

private var core: MercuryCore.SlashCommandPolicy { MercuryCore.SlashCommandPolicy.shared }

func isModelPickerCommand(_ text: String) -> Bool {
    core.isModelPickerCommand(text: text)
}

func isSteerCommand(_ text: String) -> Bool {
    core.isSteerCommand(text: text)
}

func reasoningEffortCommand(_ text: String) -> String? {
    core.reasoningEffortCommand(text: text)
}

func isSlashCommandContext(_ text: String) -> Bool {
    core.isSlashCommandContext(text: text)
}

func applySlashCompletion(
    _ current: String,
    item: SlashCompletionItem,
    replaceFrom: Int
) -> String {
    core.applySlashCompletion(current: current, itemText: item.text, replaceFrom: Int32(replaceFrom))
}
