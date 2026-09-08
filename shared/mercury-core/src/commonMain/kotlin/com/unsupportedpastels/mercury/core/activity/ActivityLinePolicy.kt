package com.unsupportedpastels.mercury.core.activity

/** Native callers project authoritative run state into this transport-free input. */
data class ActivityLineInput(
    val isSending: Boolean,
    val isStopping: Boolean,
    val connectionPhase: String,
    val pendingSubmission: Boolean,
    val connectionLost: Boolean,
    val awaitingUser: Boolean,
    val runningToolNames: List<String>,
    val runningToolContext: String?,
    val statusText: String?,
    val statusKind: String?,
    val streamingAnswer: Boolean,
    val streamingReasoning: Boolean,
    val inProgressTodo: String?,
    val activeChildCount: Int,
)

enum class ActivityLineKind {
    Hidden, NeedsYou, Stopping, ConnectionLost, Connecting, Reconnecting, Sending, Working, Background,
}

data class ActivityLineState(
    val kind: ActivityLineKind,
    val label: String,
    val animated: Boolean,
    val showTimer: Boolean,
)

object ActivityLinePolicy {
    fun decide(input: ActivityLineInput): ActivityLineState = with(input) {
        when {
            awaitingUser -> ActivityLineState(ActivityLineKind.NeedsYou, "Needs you", false, false)
            isStopping -> ActivityLineState(ActivityLineKind.Stopping, "Stopping", isSending && !connectionLost && connectionPhase == "idle", false)
            connectionLost -> ActivityLineState(ActivityLineKind.ConnectionLost, "Connection lost", false, false)
            connectionPhase == "connecting" -> ActivityLineState(ActivityLineKind.Connecting, "Connecting", false, false)
            connectionPhase == "reconnecting" -> ActivityLineState(ActivityLineKind.Reconnecting, "Reconnecting", false, false)
            connectionPhase == "submitting" || pendingSubmission -> ActivityLineState(ActivityLineKind.Sending, "Sending", isSending, false)
            isSending -> ActivityLineState(ActivityLineKind.Working, workingLabel(input), true, true)
            activeChildCount > 0 -> ActivityLineState(ActivityLineKind.Background,
                "$activeChildCount background ${if (activeChildCount == 1) "task" else "tasks"}", true, false)
            else -> ActivityLineState(ActivityLineKind.Hidden, "", false, false)
        }
    }

    private val generating = Regex("^Generating\\s+(\\S+)\\s+arguments(?:…|\\.\\.\\.)?$", RegexOption.IGNORE_CASE)

    private fun workingLabel(input: ActivityLineInput): String = with(input) {
        when {
            runningToolNames.isNotEmpty() -> toolPresentVerb(runningToolNames.first()) +
                runningToolContext?.trim()?.takeIf(String::isNotBlank)?.take(48)?.let { " · $it" }.orEmpty()
            streamingAnswer -> "Writing"
            statusKind == "tool.generating" -> generating.matchEntire(statusText.orEmpty().trim())
                ?.groupValues?.get(1)?.let(::toolPresentVerb) ?: "Working"
            !statusText.isNullOrBlank() -> statusText.trim().take(80)
            !inProgressTodo.isNullOrBlank() -> inProgressTodo.trim().take(80)
            streamingReasoning -> "Thinking"
            else -> "Thinking"
        }
    }

    fun toolPresentVerb(toolName: String): String {
        val name = toolName.lowercase()
        fun matches(vararg tokens: String) = tokens.any { name.contains(it) }
        return when {
            matches("delegate", "spawn", "subagent") -> "Delegating"
            matches("write_file", "patch", "edit", "create_file") -> "Editing"
            // todo_list must not be swallowed by the broader list category.
            matches("todo") -> "Planning"
            matches("read_file", "search_files", "glob", "grep", "list", "ls", "find", "web_search", "web_extract", "fetch", "browser") -> "Exploring"
            matches("terminal", "shell", "bash", "execute_code", "process", "run") -> "Running"
            else -> "Using $toolName"
        }
    }

    fun formatElapsed(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        fun padded(value: Long) = value.toString().padStart(2, '0')
        return when {
            safe < 60 -> "${safe}s"
            safe < 3600 -> "${safe / 60}:${padded(safe % 60)}"
            else -> "${safe / 3600}:${padded(safe / 60 % 60)}:${padded(safe % 60)}"
        }
    }
}

data class ActivityLineHoldState(
    val shown: ActivityLineState?,
    val candidate: ActivityLineState?,
    val candidateSinceMillis: Long,
)

/** Only Working label churn is delayed; blocking, connection and idle changes are immediate. */
object ActivityLineHold {
    const val QUIET_MILLIS = 1_200L

    fun step(previous: ActivityLineHoldState, candidate: ActivityLineState, nowMillis: Long): ActivityLineHoldState {
        val shown = previous.shown
        if (shown == null || candidate.kind != shown.kind || candidate.kind != ActivityLineKind.Working ||
            candidate.label == shown.label || candidate.animated != shown.animated || candidate.showTimer != shown.showTimer
        ) return ActivityLineHoldState(candidate, null, nowMillis)
        if (previous.candidate?.label != candidate.label || nowMillis < previous.candidateSinceMillis) {
            return ActivityLineHoldState(shown, candidate, nowMillis)
        }
        return if (nowMillis - previous.candidateSinceMillis >= QUIET_MILLIS) {
            ActivityLineHoldState(candidate, null, nowMillis)
        } else previous.copy(candidate = candidate)
    }
}
