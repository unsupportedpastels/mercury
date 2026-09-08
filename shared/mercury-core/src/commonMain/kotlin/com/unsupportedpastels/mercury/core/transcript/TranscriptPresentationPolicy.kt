package com.unsupportedpastels.mercury.core.transcript

/**
 * Deterministic transcript presentation text shared by both clients: the
 * collapsed tool-activity summary, the activity-stack label, and the
 * presentation-safe reasoning text and preview.
 */
object TranscriptPresentationPolicy {
    const val MAX_SUMMARY_PHRASES = 3
    const val REASONING_PREVIEW_CHARS = 120
    const val MISSING_FINAL_RESPONSE_NOTICE =
        "Background work continues. The final response is not available yet."

    /**
     * Explain a missing final response only when the current turn is identifiable,
     * has no assistant prose, and a live child was recently observed. This is a
     * presentation notice, not an assistant transcript row or a completion claim.
     */
    fun missingFinalResponseNotice(
        rows: List<TranscriptRow>,
        activeChildCount: Int,
        parentTurnSending: Boolean,
    ): String? {
        if (parentTurnSending || activeChildCount <= 0) return null
        val latestUserIndex = rows.indexOfLast { it.role.lowercase() == "user" }
        if (latestUserIndex < 0) return null
        val hasAssistantProse = rows.drop(latestUserIndex + 1).any { row ->
            row.role.lowercase() == "assistant" && row.text.hasVisibleText()
        }
        return MISSING_FINAL_RESPONSE_NOTICE.takeUnless { hasAssistantProse }
    }

    /**
     * Claude-app style activity summary: known tools compress into verb
     * phrases ("edited 2 files, ran a command"), unknown names fall back to
     * counted display names so a server-side rename degrades to less prose,
     * never a lie. Running tools lead with "running <name>".
     */
    fun toolActivitySummary(completedNames: List<String>, runningNames: List<String> = emptyList()): String {
        val buckets = LinkedHashMap<String, Int>()
        val unknown = LinkedHashMap<String, Int>()
        for (rawName in completedNames) {
            val name = normalizedToolName(rawName)
            val bucket = toolVerbBucket(name)
            if (bucket != null) {
                buckets[bucket] = (buckets[bucket] ?: 0) + 1
            } else {
                unknown[name] = (unknown[name] ?: 0) + 1
            }
        }
        val phrases = mutableListOf<String>()
        val running = runningNames.map(::normalizedToolName).distinct()
        if (running.isNotEmpty()) phrases += "running ${running.joinToString(", ")}"
        val bucketOrder = buckets.keys.toList()
        bucketOrder
            .sortedWith(compareByDescending<String> { buckets[it] ?: 0 }.thenBy { bucketOrder.indexOf(it) })
            .forEach { bucket -> phrases += toolVerbPhrase(bucket, buckets[bucket] ?: 0) }
        for ((name, count) in unknown) {
            phrases += if (count > 1) "${displayToolName(name)} ×$count" else displayToolName(name)
        }
        val visible = phrases.take(MAX_SUMMARY_PHRASES)
        val overflow = phrases.size - visible.size
        val joined = buildString {
            append(visible.joinToString(", "))
            if (overflow > 0) append(", +$overflow more")
        }
        return joined.replaceFirstChar { it.uppercase() }
    }

    /** "Activity · 3 tools · 1/2 tasks · 1 loop · 1 process-local process". */
    fun activitySummary(
        toolCount: Int,
        completedTodos: Int,
        todoCount: Int,
        loopCount: Int = 0,
        processCount: Int,
    ): String {
        val parts = mutableListOf(
            "Activity",
            "$toolCount ${if (toolCount == 1) "tool" else "tools"}",
            "$completedTodos/$todoCount tasks",
        )
        if (loopCount > 0) parts += "$loopCount ${if (loopCount == 1) "loop" else "loops"}"
        if (processCount > 0) {
            parts += "$processCount process-local ${if (processCount == 1) "process" else "processes"}"
        }
        return parts.joinToString(" · ")
    }

    /**
     * Reasoning text safe to show: blank lines and `MEDIA:` transport
     * directives are dropped, raw emphasis markers and wrapping backticks are
     * stripped line by line.
     */
    fun reasoningDisplayText(reasoning: String): String =
        reasoning.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.uppercase().startsWith("MEDIA:") }
            .map { line -> line.replace("**", "").replace("__", "").trim('`') }
            .joinToString("\n")

    /** One-line preview of [reasoningDisplayText], whitespace collapsed. */
    fun reasoningPreview(reasoning: String, maxChars: Int = REASONING_PREVIEW_CHARS): String =
        reasoningDisplayText(reasoning)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(maxChars)

    private fun toolVerbBucket(name: String): String? = when (name) {
        "read_file", "read", "cat" -> "read"
        "write_file", "patch", "edit_file", "apply_patch", "edit", "write" -> "edit"
        "shell", "terminal", "bash", "exec", "run_command" -> "command"
        "web_search", "search_web" -> "web_search"
        "web_fetch", "fetch", "http_get" -> "fetch"
        "skill_view", "skill" -> "skill"
        "list_files", "ls", "glob" -> "list"
        "grep", "search_files", "search" -> "grep"
        else -> null
    }

    private fun toolVerbPhrase(bucket: String, count: Int): String = when (bucket) {
        "read" -> if (count == 1) "read a file" else "read $count files"
        "edit" -> if (count == 1) "edited a file" else "edited $count files"
        "command" -> if (count == 1) "ran a command" else "ran $count commands"
        "web_search" -> if (count == 1) "searched the web" else "searched the web ×$count"
        "fetch" -> if (count == 1) "fetched a page" else "fetched $count pages"
        "skill" -> if (count == 1) "loaded a skill" else "loaded $count skills"
        "list" -> if (count == 1) "listed files" else "listed files ×$count"
        else -> if (count == 1) "searched files" else "searched files ×$count"
    }

    private fun normalizedToolName(value: String): String = value.trim().lowercase()

    private fun displayToolName(value: String): String = value.replace('_', ' ').replace('-', ' ')

    private fun String.hasVisibleText(): Boolean = any {
        !it.isWhitespace() && it != '\u00A0' && it != '\u2007' && it != '\u202F'
    }
}
