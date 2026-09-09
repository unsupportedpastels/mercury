package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.mercury.core.interaction.ClarifyAnswerPolicy
import com.unsupportedpastels.mercury.core.transcript.ClarifyQuestion

const val MAX_RUN_TOOL_ROWS = 50
const val MAX_RUN_TODO_ITEMS = 50

private const val MAX_RUN_TOOL_ID_CHARS = 256
private const val MAX_RUN_TOOL_NAME_CHARS = 256
private const val MAX_RUN_TOOL_CONTEXT_CHARS = 4_096
private const val MAX_RUN_TOOL_SUMMARY_CHARS = 4_096
private const val MAX_RUN_TODO_ID_CHARS = 256
private const val MAX_RUN_TODO_CONTENT_CHARS = 4_096
private const val MAX_RUN_STATUS_KIND_CHARS = 256
private const val MAX_RUN_STATUS_TEXT_CHARS = 4_096
private const val MAX_RUN_INTERACTION_ID_CHARS = 256
private const val MAX_RUN_INTERACTION_TEXT_CHARS = 4_096
private const val MAX_RUN_INTERACTION_CHOICE_CHARS = 256
private const val MAX_RUN_INTERACTION_CHOICES = 32

enum class RunToolState {
    Running,
    Completed,
}

data class RunToolRow(
    val toolId: String,
    val name: String,
    val context: String? = null,
    val summary: String? = null,
    val state: RunToolState,
)

/** Milestone shape is a shared decision: the same rows drive Android and iOS. */
typealias RunTodoStatus = com.unsupportedpastels.mercury.core.progress.RunTodoStatus

typealias RunTodoItem = com.unsupportedpastels.mercury.core.progress.RunTodoItem

data class RunStatus(
    val kind: String,
    val text: String,
)

enum class RunInteractionLifecycle {
    Pending,
    Responding,
    Resolved,
    Expired,
    Failed,
}

data class ClarificationInteraction(
    val runtimeSessionId: RuntimeSessionId,
    val requestId: String,
    val question: String,
    val choices: List<String>,
    val multiSelect: Boolean,
    val lifecycle: RunInteractionLifecycle = RunInteractionLifecycle.Pending,
    /** Batch form; empty for a single question. */
    val questions: List<ClarifyQuestion> = emptyList(),
    val answeredQuestionIds: Set<String> = emptySet(),
) {
    val isBatch: Boolean get() = questions.isNotEmpty()

    /** The question to show now: the next unanswered one of a batch (shared decision). */
    val currentQuestion: ClarifyQuestion?
        get() = ClarifyAnswerPolicy.nextQuestion(questions, answeredQuestionIds)

    /** Text, choices and mode to render: the current batch question or the single question. */
    val displayQuestion: String get() = currentQuestion?.question ?: question
    val displayChoices: List<String> get() = currentQuestion?.choices ?: choices
    val displayMultiSelect: Boolean get() = currentQuestion?.multiSelect ?: multiSelect
}

data class ApprovalInteraction(
    val runtimeSessionId: RuntimeSessionId,
    val requestId: String?,
    val commandPreview: String?,
    val descriptionPreview: String?,
    val choices: List<String>,
    val lifecycle: RunInteractionLifecycle = RunInteractionLifecycle.Pending,
)

data class UnsupportedBlockingInteraction(
    val runtimeSessionId: RuntimeSessionId,
    val kind: UnsupportedBlockingKind,
    val requestId: String,
    val prompt: String?,
    val lifecycle: RunInteractionLifecycle = RunInteractionLifecycle.Pending,
)

data class RunEventState(
    val tools: List<RunToolRow> = emptyList(),
    val todos: List<RunTodoItem> = emptyList(),
    val status: RunStatus? = null,
    val clarification: ClarificationInteraction? = null,
    val approval: ApprovalInteraction? = null,
    val unsupportedBlocking: UnsupportedBlockingInteraction? = null,
) {
    init {
        require(tools.size <= MAX_RUN_TOOL_ROWS) { "Run tool rows exceed the bounded limit" }
        require(todos.size <= MAX_RUN_TODO_ITEMS) { "Run todo items exceed the bounded limit" }
    }

    fun reduce(event: HermesChatEvent): RunEventState = when (event) {
        is HermesChatEvent.ToolStart -> {
            val next = reduceToolStart(event)
            if (next === this) this else next.reduceTodoUpdate(event.todos)
        }
        is HermesChatEvent.ToolComplete -> reduceToolComplete(event).reduceTodoUpdate(event.todos)
        is HermesChatEvent.MessageComplete,
        is HermesChatEvent.Error,
        -> finishRunningTools()
        is HermesChatEvent.StatusUpdate -> copy(
            status = RunStatus(
                kind = event.kind.take(MAX_RUN_STATUS_KIND_CHARS),
                text = event.text.take(MAX_RUN_STATUS_TEXT_CHARS),
            ),
        )
        is HermesChatEvent.ToolGenerating -> copy(
            status = RunStatus(
                kind = "tool.generating",
                text = "Generating ${event.name.take(MAX_RUN_TOOL_NAME_CHARS)} arguments…"
                    .take(MAX_RUN_STATUS_TEXT_CHARS),
            ),
        )
        is HermesChatEvent.ClarifyRequest -> reduceClarificationRequest(event)
        is HermesChatEvent.ClarifyExpire -> reduceClarificationExpiry(event)
        is HermesChatEvent.ApprovalRequest -> reduceApprovalRequest(event)
        is HermesChatEvent.ApprovalExpire -> reduceApprovalExpiry(event)
        is HermesChatEvent.UnsupportedBlockingRequest -> reduceUnsupportedBlockingRequest(event)
        is HermesChatEvent.UnsupportedBlockingExpire -> reduceUnsupportedBlockingExpiry(event)
        else -> this
    }

    fun finishRunningTools(): RunEventState {
        val nextTools = tools.map { tool ->
            if (tool.state == RunToolState.Running) {
                tool.copy(state = RunToolState.Completed)
            } else {
                tool
            }
        }
        val nextStatus = status?.takeUnless { it.kind == "tool.generating" }
        if (nextTools == tools && nextStatus == status) return this
        return copy(
            tools = nextTools,
            status = nextStatus,
        )
    }

    fun transitionClarificationLifecycle(
        requestId: String,
        lifecycle: RunInteractionLifecycle,
    ): RunEventState {
        val current = clarification ?: return this
        if (!current.isActive() || current.requestId != requestId.take(MAX_RUN_INTERACTION_ID_CHARS)) {
            return this
        }
        return copy(clarification = current.copy(lifecycle = lifecycle))
    }

    fun transitionApprovalLifecycle(
        runtimeSessionId: RuntimeSessionId,
        requestId: String?,
        lifecycle: RunInteractionLifecycle,
    ): RunEventState {
        val current = approval ?: return this
        if (!current.isActive() ||
            current.runtimeSessionId != runtimeSessionId ||
            current.requestId != requestId?.take(MAX_RUN_INTERACTION_ID_CHARS)
        ) {
            return this
        }
        return copy(approval = current.copy(lifecycle = lifecycle))
    }

    fun transitionUnsupportedBlockingLifecycle(
        runtimeSessionId: RuntimeSessionId,
        kind: UnsupportedBlockingKind,
        requestId: String,
        lifecycle: RunInteractionLifecycle,
    ): RunEventState {
        val current = unsupportedBlocking ?: return this
        if (!current.isActive() ||
            current.runtimeSessionId != runtimeSessionId ||
            current.kind != kind ||
            current.requestId != requestId.take(MAX_RUN_INTERACTION_ID_CHARS)
        ) {
            return this
        }
        return copy(unsupportedBlocking = current.copy(lifecycle = lifecycle))
    }

    private fun ClarificationInteraction.isActive(): Boolean = lifecycle.isActive()

    private fun ApprovalInteraction.isActive(): Boolean = lifecycle.isActive()

    private fun UnsupportedBlockingInteraction.isActive(): Boolean = lifecycle.isActive()

    private fun RunInteractionLifecycle.isActive(): Boolean = this == RunInteractionLifecycle.Pending ||
        this == RunInteractionLifecycle.Responding

    private fun reduceClarificationRequest(event: HermesChatEvent.ClarifyRequest): RunEventState =
        copy(
            clarification = ClarificationInteraction(
                runtimeSessionId = event.sessionId,
                requestId = event.requestId.take(MAX_RUN_INTERACTION_ID_CHARS),
                question = event.question.take(MAX_RUN_INTERACTION_TEXT_CHARS),
                choices = event.choices
                    .take(MAX_RUN_INTERACTION_CHOICES)
                    .map { it.take(MAX_RUN_INTERACTION_CHOICE_CHARS) },
                multiSelect = event.multiSelect,
                questions = event.questions.take(MAX_RUN_INTERACTION_CHOICES).map { entry ->
                    ClarifyQuestion(
                        qid = entry.qid.take(MAX_RUN_INTERACTION_ID_CHARS),
                        question = entry.question.take(MAX_RUN_INTERACTION_TEXT_CHARS),
                        choices = entry.choices
                            .take(MAX_RUN_INTERACTION_CHOICES)
                            .map { it.take(MAX_RUN_INTERACTION_CHOICE_CHARS) },
                        multiSelect = entry.multiSelect,
                    )
                },
            ),
        )

    /**
     * Records one answered batch question. The card stays pending while
     * questions remain; the host resolves the request after the last one.
     */
    fun markClarificationQuestionAnswered(requestId: String, questionId: String): RunEventState {
        val current = clarification ?: return this
        if (current.requestId != requestId.take(MAX_RUN_INTERACTION_ID_CHARS)) return this
        return copy(
            clarification = current.copy(
                answeredQuestionIds = current.answeredQuestionIds + questionId,
                lifecycle = RunInteractionLifecycle.Pending,
            ),
        )
    }

    private fun reduceClarificationExpiry(event: HermesChatEvent.ClarifyExpire): RunEventState {
        val current = clarification ?: return this
        if (current.runtimeSessionId != event.sessionId ||
            current.requestId != event.requestId.take(MAX_RUN_INTERACTION_ID_CHARS) ||
            current.lifecycle !in setOf(
                RunInteractionLifecycle.Pending,
                RunInteractionLifecycle.Responding,
            )
        ) {
            return this
        }
        return copy(clarification = current.copy(lifecycle = RunInteractionLifecycle.Expired))
    }

    private fun reduceApprovalRequest(event: HermesChatEvent.ApprovalRequest): RunEventState =
        copy(
            approval = ApprovalInteraction(
                runtimeSessionId = event.sessionId,
                requestId = event.requestId?.take(MAX_RUN_INTERACTION_ID_CHARS),
                commandPreview = event.command?.take(MAX_RUN_INTERACTION_TEXT_CHARS),
                descriptionPreview = event.description?.take(MAX_RUN_INTERACTION_TEXT_CHARS),
                choices = event.choices
                    .take(MAX_RUN_INTERACTION_CHOICES)
                    .map { it.take(MAX_RUN_INTERACTION_CHOICE_CHARS) },
            ),
        )

    private fun reduceApprovalExpiry(event: HermesChatEvent.ApprovalExpire): RunEventState {
        val current = approval ?: return this
        if (current.runtimeSessionId != event.sessionId ||
            current.requestId == null ||
            current.requestId != event.requestId.take(MAX_RUN_INTERACTION_ID_CHARS) ||
            current.lifecycle !in setOf(
                RunInteractionLifecycle.Pending,
                RunInteractionLifecycle.Responding,
            )
        ) {
            return this
        }
        return copy(approval = current.copy(lifecycle = RunInteractionLifecycle.Expired))
    }

    private fun reduceUnsupportedBlockingRequest(
        event: HermesChatEvent.UnsupportedBlockingRequest,
    ): RunEventState = copy(
        unsupportedBlocking = UnsupportedBlockingInteraction(
            runtimeSessionId = event.sessionId,
            kind = event.kind,
            requestId = event.requestId.take(MAX_RUN_INTERACTION_ID_CHARS),
            prompt = event.prompt?.take(MAX_RUN_INTERACTION_TEXT_CHARS),
        ),
    )

    private fun reduceUnsupportedBlockingExpiry(
        event: HermesChatEvent.UnsupportedBlockingExpire,
    ): RunEventState {
        val current = unsupportedBlocking ?: return this
        if (current.runtimeSessionId != event.sessionId ||
            current.kind != event.kind ||
            current.requestId != event.requestId.take(MAX_RUN_INTERACTION_ID_CHARS) ||
            current.lifecycle !in setOf(
                RunInteractionLifecycle.Pending,
                RunInteractionLifecycle.Responding,
            )
        ) {
            return this
        }
        return copy(
            unsupportedBlocking = current.copy(lifecycle = RunInteractionLifecycle.Expired),
        )
    }

    private fun reduceTodoUpdate(items: List<RunTodoItem>?): RunEventState {
        if (items == null) return this
        val normalized = items
            .asSequence()
            .map { item ->
                item.copy(
                    id = item.id.take(MAX_RUN_TODO_ID_CHARS),
                    content = item.content.take(MAX_RUN_TODO_CONTENT_CHARS),
                )
            }
            .filter { it.id.isNotBlank() && it.content.isNotBlank() }
            .distinctBy(RunTodoItem::id)
            .take(MAX_RUN_TODO_ITEMS)
            .toList()
        return if (normalized == todos) this else copy(todos = normalized)
    }

    private fun reduceToolStart(event: HermesChatEvent.ToolStart): RunEventState {
        val index = tools.indexOfFirst { it.toolId == event.toolId }
        if (index >= 0 && tools[index].state == RunToolState.Completed) return this

        val row = RunToolRow(
            toolId = event.toolId.take(MAX_RUN_TOOL_ID_CHARS),
            name = event.name.take(MAX_RUN_TOOL_NAME_CHARS),
            context = event.context?.take(MAX_RUN_TOOL_CONTEXT_CHARS),
            state = RunToolState.Running,
        )
        return replaceOrAppend(index, row).clearGeneratingStatus()
    }

    private fun reduceToolComplete(event: HermesChatEvent.ToolComplete): RunEventState {
        val index = tools.indexOfFirst { it.toolId == event.toolId }
        val previous = index.takeIf { it >= 0 }?.let(tools::get)
        val row = RunToolRow(
            toolId = event.toolId.take(MAX_RUN_TOOL_ID_CHARS),
            name = event.name.take(MAX_RUN_TOOL_NAME_CHARS),
            context = previous?.context,
            summary = event.summary?.take(MAX_RUN_TOOL_SUMMARY_CHARS),
            state = RunToolState.Completed,
        )
        return replaceOrAppend(index, row).clearGeneratingStatus()
    }

    private fun replaceOrAppend(index: Int, row: RunToolRow): RunEventState {
        val next = if (index >= 0) {
            tools.toMutableList().also { it[index] = row }
        } else {
            (tools + row).takeLast(MAX_RUN_TOOL_ROWS)
        }
        return copy(tools = next)
    }

    private fun clearGeneratingStatus(): RunEventState =
        if (status?.kind == "tool.generating") copy(status = null) else this
}
