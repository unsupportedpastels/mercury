package com.unsupportedpastels.mercury.core.transcript

/**
 * Presentation only. Verified against official tools/process_registry.py
 * format_process_notification/_format_async_delegation and tui_gateway/server.py.
 * Delegations carry display_kind=async_delegation_complete; process notices and
 * older history have only text. Do not infer from generic IMPORTANT/delegate prose.
 * Exact pasted envelopes are indistinguishable on older servers; retain every byte
 * in the disclosure, and prefer explicit metadata (including unknown kinds).
 */
object InternalCompletionNotice {
    fun isNotice(row: TranscriptRow): Boolean = isNotice(row.role, row.text, row.displayKind)

    fun isNotice(role: String, text: String, displayKind: String?): Boolean {
        if (role.lowercase() != "user") return false
        if (!displayKind.isNullOrEmpty()) return displayKind == "async_delegation_complete"
        if (processHeader.containsMatchIn(text) && text.endsWith("]")) {
            val body = text.substringAfter('\n')
            val command = if (body.startsWith("Started by subagent sa-")) {
                body.substringAfter("\nCommand: ", "")
            } else if (body.startsWith("Command: ")) body.removePrefix("Command: ") else return false
            return command.contains("\nOutput:\n")
        }
        if (singleHeader.containsMatchIn(text)) {
            return text.contains("\nOriginal goal: ") && text.contains("\nRole: ") &&
                text.contains("\nStatus: ") && text.contains("   API calls: ") &&
                text.contains("\n--- RESULT ---\n")
        }
        if (batchHeader.containsMatchIn(text)) {
            return text.contains("\nRole: ") && text.contains("   Total duration: ") &&
                (batchTask.containsMatchIn(text) || text.contains("\n--- ERROR ---\nThe batch did not complete successfully: "))
        }
        return false
    }

    private val processHeader = Regex(
        """^\[IMPORTANT: Background process proc_[A-Za-z0-9]+ (?:completed normally|exited|failed to start|marked lost because the process backend disappeared|terminated by [^\n]+) \(exit code (?:-?\d+|\?)(?:, SIGTERM)?\)\.\n"""
    )
    private val singleHeader = Regex(
        """^\[ASYNC DELEGATION COMPLETE — [A-Za-z0-9_-]+\]\nA background subagent you dispatched earlier has finished\. """
    )
    private val batchHeader = Regex(
        """^\[ASYNC DELEGATION BATCH COMPLETE — [A-Za-z0-9_-]+\]\nA background fan-out of \d+ subagent\(s\) you dispatched earlier has finished\. """
    )
    private val batchTask = Regex("""\n--- [✓✗⚠] TASK \d+/\d+[^\n]* \(status=[^\n]+\) ---\n""")
}
