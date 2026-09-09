package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.RunTodoStatus
import com.unsupportedpastels.hermesandroid.app.RunToolState

/** One evidence-backed row in the progress sheet. Detail is only ever reported text. */
data class SessionProgressItem(
    val label: String,
    val detail: String? = null,
)

/**
 * A deterministic projection of already-authoritative run state into the
 * progress concept's sections. It never invents progress: every row maps to an
 * observed todo, tool event, status update, or pending interaction. Absence of
 * evidence produces absence of rows, not guesses.
 */
data class SessionProgressSummary(
    val completed: List<SessionProgressItem>,
    val inProgress: List<SessionProgressItem>,
    val remaining: List<SessionProgressItem>,
    val blocked: List<SessionProgressItem>,
    val currentStep: String?,
    val active: Boolean,
    val headline: String?,
    /** True when milestone (todo) evidence exists, regardless of its states. */
    val planned: Boolean = false,
) {
    val hasContent: Boolean
        get() = completed.isNotEmpty() || inProgress.isNotEmpty() ||
            remaining.isNotEmpty() || blocked.isNotEmpty()

    /**
     * Plan-level progress: milestone (todo) or user-blocking evidence. Status
     * text and tool rows alone already have their own surfaces (status pill,
     * tool group, activity stack); the strip must not duplicate them.
     */
    val planPresent: Boolean
        get() = blocked.isNotEmpty() || planned
}

/** Display age of caller-owned observations; never infer receipt from rendering. */
internal fun progressObservationLabel(lastObservedAt: Long?, now: Long, restored: Boolean): String {
    val fallback = if (restored) "Saved" else ""
    if (lastObservedAt == null || lastObservedAt < 0L || now < 0L || lastObservedAt > now) return fallback
    // Nonnegative ordered timestamps keep subtraction safe even at Long.MAX_VALUE.
    val seconds = (now - lastObservedAt) / 1_000L
    val age = when {
        seconds < 60L -> "${seconds}s ago"
        seconds < 3_600L -> "${seconds / 60L}m ago"
        else -> "${seconds / 3_600L}h ago"
    }
    return if (restored) "Saved · $age" else age
}

object SessionProgressPolicy {

    private const val MAX_STEP_CHARS = 120

    fun summarize(runState: RunEventState, isSending: Boolean): SessionProgressSummary {
        val countedTodos = runState.todos.filter { it.status != RunTodoStatus.Cancelled }
        val completedTodos = countedTodos
            .filter { it.status == RunTodoStatus.Completed }
            .map { SessionProgressItem(it.content) }
        val inProgressTodos = countedTodos
            .filter { it.status == RunTodoStatus.InProgress }
            .map { SessionProgressItem(it.content) }
        val remainingTodos = countedTodos
            .filter { it.status == RunTodoStatus.Pending }
            .map { SessionProgressItem(it.content) }

        // Tool executions have their own surfaces (tool group, activity
        // stack); they are not milestones and never pad these sections. A
        // running tool's name only serves as the current-step fallback below.
        val runningToolStep = runState.tools
            .firstOrNull { it.state == RunToolState.Running }
            ?.name?.takeIf(String::isNotBlank)

        val blocked = buildList {
            runState.clarification?.takeIf { it.lifecycle.awaitingUser() }?.let {
                add(SessionProgressItem("Question needs your answer", it.displayQuestion))
            }
            runState.approval?.takeIf { it.lifecycle.awaitingUser() }?.let {
                add(
                    SessionProgressItem(
                        "Approval needed",
                        it.commandPreview ?: it.descriptionPreview,
                    ),
                )
            }
            runState.unsupportedBlocking?.takeIf { it.lifecycle.awaitingUser() }?.let {
                add(SessionProgressItem("Input needed on another surface", it.prompt))
            }
        }

        // The current step is only ever named from observed evidence, ranked:
        // a user-blocking request, then the latest status text, then an
        // in-progress todo, then a running tool. isSending alone names nothing.
        val currentStep = when {
            blocked.isNotEmpty() -> "Needs you"
            runState.status != null -> runState.status.text.take(MAX_STEP_CHARS)
            inProgressTodos.isNotEmpty() -> inProgressTodos.first().label.take(MAX_STEP_CHARS)
            runningToolStep != null -> runningToolStep.take(MAX_STEP_CHARS)
            else -> null
        }

        val headline = when {
            blocked.isNotEmpty() -> "Needs you"
            currentStep != null -> currentStep
            countedTodos.isNotEmpty() ->
                "${completedTodos.size}/${countedTodos.size} done"
            else -> null
        }

        return SessionProgressSummary(
            completed = completedTodos,
            inProgress = inProgressTodos,
            remaining = remainingTodos,
            blocked = blocked,
            currentStep = currentStep,
            active = isSending,
            headline = headline,
            planned = countedTodos.isNotEmpty(),
        )
    }

    private fun RunInteractionLifecycle.awaitingUser(): Boolean =
        this == RunInteractionLifecycle.Pending || this == RunInteractionLifecycle.Responding
}
