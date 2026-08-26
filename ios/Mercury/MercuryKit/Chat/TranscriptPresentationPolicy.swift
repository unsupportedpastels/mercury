import Foundation

enum TranscriptPresentationPolicy {
    static func toolActivitySummary(
        completedNames: [String],
        runningNames: [String] = []
    ) -> String {
        var buckets: [String: Int] = [:]
        var bucketOrder: [String] = []
        var unknown: [String: Int] = [:]
        var unknownOrder: [String] = []

        for rawName in completedNames {
            let name = normalizedToolName(rawName)
            if let bucket = toolVerbBucket(name) {
                if buckets[bucket] == nil { bucketOrder.append(bucket) }
                buckets[bucket, default: 0] += 1
            } else {
                if unknown[name] == nil { unknownOrder.append(name) }
                unknown[name, default: 0] += 1
            }
        }

        var phrases: [String] = []
        let running = unique(runningNames.map(normalizedToolName))
        if !running.isEmpty { phrases.append("running \(running.joined(separator: ", "))") }
        for bucket in bucketOrder.sorted(by: {
            let lhs = buckets[$0, default: 0]
            let rhs = buckets[$1, default: 0]
            return lhs == rhs ? bucketOrder.firstIndex(of: $0)! < bucketOrder.firstIndex(of: $1)! : lhs > rhs
        }) {
            phrases.append(toolVerbPhrase(bucket, count: buckets[bucket, default: 0]))
        }
        for name in unknownOrder {
            let count = unknown[name, default: 0]
            phrases.append(count > 1 ? "\(displayToolName(name)) ×\(count)" : displayToolName(name))
        }
        let visible = Array(phrases.prefix(3))
        let overflow = phrases.count - visible.count
        var result = visible.joined(separator: ", ")
        if overflow > 0 { result += ", +\(overflow) more" }
        return result.prefix(1).uppercased() + result.dropFirst()
    }

    static func activitySummary(
        toolCount: Int,
        completedTodos: Int,
        todoCount: Int,
        loopCount: Int = 0,
        processCount: Int
    ) -> String {
        var parts = [
            "Activity",
            "\(toolCount) \(toolCount == 1 ? "tool" : "tools")",
            "\(completedTodos)/\(todoCount) tasks",
        ]
        if loopCount > 0 { parts.append("\(loopCount) \(loopCount == 1 ? "loop" : "loops")") }
        if processCount > 0 {
            parts.append("\(processCount) process-local \(processCount == 1 ? "process" : "processes")")
        }
        return parts.joined(separator: " · ")
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
        reasoning
            .components(separatedBy: .newlines)
            .compactMap { rawLine -> String? in
                let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !line.isEmpty, !line.uppercased().hasPrefix("MEDIA:") else { return nil }
                return line
                    .replacingOccurrences(of: "**", with: "")
                    .replacingOccurrences(of: "__", with: "")
                    .trimmingCharacters(in: CharacterSet(charactersIn: "`"))
            }
            .joined(separator: "\n")
    }

    static func reasoningPreview(_ reasoning: String) -> String {
        let flattened = reasoningDisplayText(reasoning)
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
        return String(flattened.prefix(120))
    }

    private static func toolVerbBucket(_ name: String) -> String? {
        switch name.lowercased() {
        case "read_file", "read", "cat": "read"
        case "write_file", "patch", "edit_file", "apply_patch", "edit", "write": "edit"
        case "shell", "terminal", "bash", "exec", "run_command": "command"
        case "web_search", "search_web": "web_search"
        case "web_fetch", "fetch", "http_get": "fetch"
        case "skill_view", "skill": "skill"
        case "list_files", "ls", "glob": "list"
        case "grep", "search_files", "search": "grep"
        default: nil
        }
    }

    private static func toolVerbPhrase(_ bucket: String, count: Int) -> String {
        switch bucket {
        case "read": count == 1 ? "read a file" : "read \(count) files"
        case "edit": count == 1 ? "edited a file" : "edited \(count) files"
        case "command": count == 1 ? "ran a command" : "ran \(count) commands"
        case "web_search": count == 1 ? "searched the web" : "searched the web ×\(count)"
        case "fetch": count == 1 ? "fetched a page" : "fetched \(count) pages"
        case "skill": count == 1 ? "loaded a skill" : "loaded \(count) skills"
        case "list": count == 1 ? "listed files" : "listed files ×\(count)"
        default: count == 1 ? "searched files" : "searched files ×\(count)"
        }
    }

    private static func normalizedToolName(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func displayToolName(_ value: String) -> String {
        value.replacingOccurrences(of: "_", with: " ").replacingOccurrences(of: "-", with: " ")
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { seen.insert($0).inserted }
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
        guard !isSending, !dictationActive else { return false }
        let hasText = !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
