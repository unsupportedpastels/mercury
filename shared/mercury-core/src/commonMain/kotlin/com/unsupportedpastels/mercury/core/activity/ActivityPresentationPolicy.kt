package com.unsupportedpastels.mercury.core.activity

import com.unsupportedpastels.mercury.core.transcript.TranscriptPresentationPolicy

/** The indicator shown by a bounded activity surface. */
enum class ActivityIndicator {
    ActiveWork,
    CompletedWork,
    ProcessesOnly,
}

/**
 * A process status is deliberately classified from the status field only. The
 * command, exit code, and age are not evidence that the assistant is working.
 */
data class ActivityPresentation(
    val activityPresent: Boolean,
    val assistantActive: Boolean,
    val processOnly: Boolean,
    val indicator: ActivityIndicator,
    val processRunningCount: Int,
    val processExitedCount: Int,
    val processUnknownCount: Int,
    val summary: String,
)

/**
 * Shared activity-stack decision for Android and iOS.
 *
 * Assistant work and process-local rows have different ownership. A running
 * process is a last-reported observation from the host, not proof that the
 * selected assistant turn is still inferring, so it never drives the active
 * work indicator. Process-only surfaces use a neutral indicator and explicitly
 * qualify their counts as last reported.
 */
object ActivityPresentationPolicy {
    private val exitedProcessStatuses = setOf(
        "cancelled",
        "canceled",
        "completed",
        "done",
        "error",
        "exited",
        "failed",
        "finished",
        "interrupted",
        "killed",
        "stopped",
        "terminated",
        "timeout",
        "timed_out",
    )

    /**
     * Build one platform-independent presentation decision. Counts are clamped
     * at the boundary because this is a UI policy, not a source of authority.
     */
    fun decide(
        assistantActivityPresent: Boolean,
        turnActive: Boolean,
        toolCount: Int,
        runningToolCount: Int,
        completedTodoCount: Int,
        todoCount: Int,
        activeTodoCount: Int,
        loopCount: Int,
        activeLoopCount: Int,
        processStatuses: List<String>,
    ): ActivityPresentation {
        val safeToolCount = toolCount.coerceAtLeast(0)
        val safeRunningToolCount = runningToolCount.coerceIn(0, safeToolCount)
        val safeTodoCount = todoCount.coerceAtLeast(0)
        val safeCompletedTodoCount = completedTodoCount.coerceIn(0, safeTodoCount)
        val safeActiveTodoCount = activeTodoCount.coerceIn(0, safeTodoCount)
        val safeLoopCount = loopCount.coerceAtLeast(0)
        val safeActiveLoopCount = activeLoopCount.coerceIn(0, safeLoopCount)

        var runningProcesses = 0
        var exitedProcesses = 0
        var unknownProcesses = 0
        processStatuses.forEach { rawStatus ->
            when (rawStatus.trim().lowercase()) {
                "running" -> runningProcesses++
                in exitedProcessStatuses -> exitedProcesses++
                else -> unknownProcesses++
            }
        }
        val processCount = runningProcesses + exitedProcesses + unknownProcesses
        val assistantPresent = assistantActivityPresent ||
            turnActive ||
            safeToolCount > 0 ||
            safeTodoCount > 0 ||
            safeLoopCount > 0
        val assistantActive = turnActive ||
            safeRunningToolCount > 0 ||
            safeActiveTodoCount > 0 ||
            safeActiveLoopCount > 0
        val processOnly = !assistantPresent && processCount > 0
        val activityPresent = assistantPresent || processCount > 0
        val processSummary = processSummary(
            running = runningProcesses,
            exited = exitedProcesses,
            unknown = unknownProcesses,
        )
        val assistantSummary = TranscriptPresentationPolicy.activitySummary(
            toolCount = safeToolCount,
            completedTodos = safeCompletedTodoCount,
            todoCount = safeTodoCount,
            loopCount = safeLoopCount,
            processCount = 0,
        )
        val summary = when {
            !activityPresent -> ""
            processOnly -> processSummary
            assistantPresent && processCount > 0 -> "$assistantSummary · $processSummary"
            assistantPresent -> assistantSummary
            else -> ""
        }
        val indicator = when {
            processOnly -> ActivityIndicator.ProcessesOnly
            assistantActive -> ActivityIndicator.ActiveWork
            else -> ActivityIndicator.CompletedWork
        }
        return ActivityPresentation(
            activityPresent = activityPresent,
            assistantActive = assistantActive,
            processOnly = processOnly,
            indicator = indicator,
            processRunningCount = runningProcesses,
            processExitedCount = exitedProcesses,
            processUnknownCount = unknownProcesses,
            summary = summary,
        )
    }

    private fun processSummary(running: Int, exited: Int, unknown: Int): String {
        val counts = buildList {
            if (running > 0) add("$running running")
            if (exited > 0) add("$exited exited")
            if (unknown > 0) add("$unknown unknown")
        }
        return "Processes · last reported: ${counts.joinToString(" · ")}"
    }
}
