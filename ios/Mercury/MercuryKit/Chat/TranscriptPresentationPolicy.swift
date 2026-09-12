import Foundation
import MercuryCore

/// Transcript presentation text is a shared decision (`MercuryCore.TranscriptPresentationPolicy`);
/// this facade keeps the Swift call sites and the view-only rules.
enum TranscriptPresentationPolicy {
    private static var core: MercuryCore.TranscriptPresentationPolicy { MercuryCore.TranscriptPresentationPolicy.shared }

    static func toolActivitySummary(
        completedNames: [String],
        runningNames: [String] = []
    ) -> String {
        core.toolActivitySummary(completedNames: completedNames, runningNames: runningNames)
    }

    static func activitySummary(
        toolCount: Int,
        completedTodos: Int,
        todoCount: Int,
        loopCount: Int = 0,
        processCount: Int
    ) -> String {
        core.activitySummary(
            toolCount: Int32(toolCount),
            completedTodos: Int32(completedTodos),
            todoCount: Int32(todoCount),
            loopCount: Int32(loopCount),
            processCount: Int32(processCount)
        )
    }

    static func shouldRenderMessageBubble(role: String, text: String) -> Bool {
        role.lowercased() != "assistant" || !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func shouldShowPlaybackControl(
        enabled: Bool,
        role: String,
        text: String,
        completed: Bool
    ) -> Bool {
        enabled && completed && role.lowercased() == "assistant"
            && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    static func reasoningDisplayText(_ reasoning: String) -> String {
        core.reasoningDisplayText(reasoning: reasoning)
    }

    static func reasoningPreview(_ reasoning: String) -> String {
        core.reasoningPreview(reasoning: reasoning, maxChars: MercuryCore.TranscriptPresentationPolicy.shared.REASONING_PREVIEW_CHARS)
    }
}

enum ComposerSendPolicy {
    static func canSend(
        draft: String,
        isSending: Bool,
        dictationActive: Bool,
        isSteering: Bool,
        hasAttachments: Bool,
        hasHostReferences: Bool
    ) -> Bool {
        guard !isSending else { return false }
        let hasText = !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        // App-owned dictation is an input source, not an active assistant turn.
        // Once partial text exists, Send remains available and atomically
        // freezes capture at the action boundary before prompt submission.
        if dictationActive { return hasText }
        if isSteering { return hasText }
        return hasText || hasAttachments || hasHostReferences
    }
}

final class VoiceDisplayPreferences {
    static let playbackControlsKey = "voice.showMessagePlaybackControls"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var showMessagePlaybackControls: Bool {
        get { defaults.bool(forKey: Self.playbackControlsKey) }
        set { defaults.set(newValue, forKey: Self.playbackControlsKey) }
    }
}
