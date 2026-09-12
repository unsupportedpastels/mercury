package com.unsupportedpastels.mercury.core.transcript

/**
 * Pure transcript state machine shared by both clients (Phase 3 of
 * AGENTS.md cross-platform rule), ported from the iOS TranscriptReducer —
 * the hermetically tested implementation — with immutable snapshots so
 * platforms keep value semantics.
 *
 * Deliberate parity notes — do not "simplify" these:
 * - Only SessionTitle is filtered by session identity. Streaming, completion,
 *   and error events mutate state regardless of session id.
 * - No text bounding happens here: bounds are enforced upstream in each
 *   platform's frame decoding.
 * - MessageComplete with null text keeps the streamed buffer; only a non-null
 *   final text replaces it. The interrupt sentinel counts as null final text,
 *   and a sentinel completion that streamed nothing drops the row entirely.
 * - An approval/clarify expire clears the pending request only when both the
 *   kind and the request id match (Android RunEventModels parity). A stale or
 *   unrelated expire must never dismiss a newer prompt the agent is still
 *   blocked on.
 * - Reasoning deltas land on the last incomplete assistant row (or open a
 *   fresh reasoning-only row); reasoning text survives completion.
 * - Interim commentary seals the current streaming segment as completed;
 *   `alreadyStreamed` carries no extra reducer behavior.
 * - Tool activity rows are bounded: at most 50 rows, ids/names at 256 chars,
 *   context/summary at 4096 chars.
 * - MessageComplete and Error finalize every running tool row and clear the
 *   transient "generating arguments" status.
 * - SessionInfo and unsupported-blocking events are not modeled here.
 */
data class TranscriptRow(
    val id: Long,
    val role: String,
    val text: String,
    val completed: Boolean,
    /** Persisted tool identity for collapsed historical activity rows. */
    val toolName: String? = null,
    /** Accumulated chain-of-thought for this assistant segment. */
    val reasoningText: String = "",
    /** Official Hermes presentation metadata; never changes the wire role/content. */
    val displayKind: String? = null,
)

enum class ToolRowState { Running, Completed }

/** One tool invocation shown in the transcript's activity feed. */
data class TranscriptToolRow(
    val toolId: String,
    val name: String,
    val context: String? = null,
    val summary: String? = null,
    val state: ToolRowState,
)

/** The currently presented blocking request. */
sealed interface PendingTranscriptRequest {
    data class Approval(val event: ChatEvent.ApprovalRequest) : PendingTranscriptRequest
    data class Clarify(val event: ChatEvent.ClarifyRequest) : PendingTranscriptRequest
}

/** A REST-restored history message. */
data class RestoredMessage(
    val role: String,
    val content: String,
    val toolName: String? = null,
    val reasoningText: String = "",
    val displayKind: String? = null,
)

data class TranscriptSnapshot(
    val rows: List<TranscriptRow> = emptyList(),
    val lastError: String? = null,
    val pendingRequest: PendingTranscriptRequest? = null,
    val adoptedTitle: String? = null,
    val latestStatusText: String? = null,
    val statusUpdateCount: Int = 0,
    val tools: List<TranscriptToolRow> = emptyList(),
    val generatingStatusText: String? = null,
    /** Live title renames are adopted only in the new-chat flow. */
    val adoptsLiveTitles: Boolean = false,
    /** Session ids considered "ours" for title-adoption filtering. */
    val ownSessionIds: Set<String> = emptySet(),
    /** Monotonic row-identity source; ids are stable across snapshots. */
    val nextRowId: Long = 1,
) {
    /** A turn is executing when any assistant row is still streaming. */
    val hasStreamingAssistant: Boolean
        get() = rows.any { !it.completed && it.role == "assistant" }

    fun isOwnSession(eventSessionId: String): Boolean = eventSessionId in ownSessionIds
}

/** Result of a foreground reconcile: the new state and whether the local active-turn suffix was kept. */
data class ReconcileResult(
    val state: TranscriptSnapshot,
    val keptLocalSuffix: Boolean,
)

object TranscriptEngine {
    const val MAX_TOOL_ROWS = 50
    const val MAX_TOOL_FIELD_LENGTH = 256
    const val MAX_TOOL_DETAIL_LENGTH = 4096

    private val restorableRoles = setOf("user", "assistant", "system", "tool")

    fun initial(isNewSession: Boolean = false): TranscriptSnapshot =
        TranscriptSnapshot(adoptsLiveTitles = isNewSession)

    fun matchesPendingRequest(state: TranscriptSnapshot, expected: PendingTranscriptRequest?): Boolean =
        expected != null && state.pendingRequest == expected

    /** A native RPC acknowledgement resolves only the exact request it answered. */
    fun resolvePendingRequest(state: TranscriptSnapshot, expected: PendingTranscriptRequest?): TranscriptSnapshot =
        if (matchesPendingRequest(state, expected)) state.copy(pendingRequest = null) else state

    fun apply(state: TranscriptSnapshot, event: ChatEvent): TranscriptSnapshot = when (event) {
        is ChatEvent.MessageStart -> {
            val last = state.lastOpenAssistantIndex()
            if (last != null) {
                state.updateRow(last) { row ->
                    event.text?.let { row.copy(text = it) } ?: row
                }
            } else {
                state.appendRow(role = "assistant", text = event.text ?: "", completed = false)
            }
        }

        is ChatEvent.MessageDelta -> {
            val last = state.lastOpenAssistantIndex()
            if (last != null) {
                state.updateRow(last) { it.copy(text = it.text + event.text) }
            } else {
                // Deltas without a start frame still need a home.
                state.appendRow(role = "assistant", text = event.text, completed = false)
            }
        }

        is ChatEvent.MessageComplete -> {
            // The interrupt sentinel is cancellation metadata, not assistant
            // prose — treat it as null final text so an interrupted turn keeps
            // its streamed buffer, and drop the row when nothing was streamed.
            val sentinelSuppressed = event.text?.let(InterruptSentinel::isInterruptSentinel) ?: false
            val finalText = if (sentinelSuppressed) null else event.text
            val finalReasoning = event.reasoning?.takeUnless { it.isSpaceBlank() }
            val last = state.lastOpenAssistantIndex()
            val next = when {
                last != null -> {
                    var updated = state.updateRow(last) {
                        it.copy(
                            text = finalText ?: it.text,
                            reasoningText = if (it.reasoningText.isSpaceBlank()) {
                                finalReasoning ?: it.reasoningText
                            } else {
                                it.reasoningText
                            },
                            completed = true,
                        )
                    }
                    val row = updated.rows[last]
                    if (sentinelSuppressed && row.text.isSpaceBlank() && row.reasoningText.isSpaceBlank()) {
                        updated = updated.copy(rows = updated.rows.toMutableList().apply { removeAt(last) })
                    }
                    updated
                }
                finalText != null ->
                    state.appendRow(
                        role = "assistant",
                        text = finalText,
                        completed = true,
                        reasoningText = finalReasoning.orEmpty(),
                    )
                finalReasoning != null ->
                    state.appendRow(role = "assistant", text = "", completed = true, reasoningText = finalReasoning)
                else -> state
            }
            next.finishRunningTools().copy(pendingRequest = null)
        }

        is ChatEvent.ReasoningDelta -> {
            if (event.text.isSpaceBlank()) {
                state
            } else {
                val last = state.lastOpenAssistantIndex()
                if (last != null) {
                    state.updateRow(last) {
                        it.copy(reasoningText = if (event.replace) event.text else it.reasoningText + event.text)
                    }
                } else {
                    // Reasoning before any streamed content opens its own row.
                    state.appendRow(role = "assistant", text = "", completed = false, reasoningText = event.text)
                }
            }
        }

        is ChatEvent.MessageInterim -> {
            if (event.text.isSpaceBlank()) {
                state
            } else {
                val last = state.lastOpenAssistantIndex()
                if (last != null) {
                    state.updateRow(last) { it.copy(text = event.text, completed = true) }
                } else {
                    state.appendRow(role = "assistant", text = event.text, completed = true)
                }
            }
        }

        is ChatEvent.ToolGenerating -> {
            val boundedName = event.name.take(MAX_TOOL_FIELD_LENGTH)
            state.copy(
                generatingStatusText = ("Generating $boundedName arguments…").take(MAX_TOOL_DETAIL_LENGTH),
            )
        }

        is ChatEvent.Error ->
            finishStreamingAssistant(state.copy(lastError = event.message, pendingRequest = null))

        is ChatEvent.ToolStart -> {
            val boundedId = event.toolId.take(MAX_TOOL_FIELD_LENGTH)
            val boundedName = event.name.take(MAX_TOOL_FIELD_LENGTH)
            val boundedContext = event.context?.take(MAX_TOOL_DETAIL_LENGTH)
            val index = state.tools.indexOfFirst { it.toolId == boundedId }
            val next = when {
                index >= 0 && state.tools[index].state == ToolRowState.Completed ->
                    // Completed tool invocations are final; a late start for an
                    // already-finished id is ignored entirely.
                    state
                index >= 0 -> state.copy(
                    tools = state.tools.toMutableList().apply {
                        this[index] = this[index].copy(
                            name = boundedName,
                            context = boundedContext,
                            state = ToolRowState.Running,
                        )
                    },
                )
                else -> state.appendToolRow(
                    TranscriptToolRow(
                        toolId = boundedId,
                        name = boundedName,
                        context = boundedContext,
                        summary = null,
                        state = ToolRowState.Running,
                    ),
                )
            }
            next.copy(generatingStatusText = null)
        }

        is ChatEvent.ToolComplete -> {
            val boundedId = event.toolId.take(MAX_TOOL_FIELD_LENGTH)
            val boundedName = event.name.take(MAX_TOOL_FIELD_LENGTH)
            val boundedSummary = event.summary?.take(MAX_TOOL_DETAIL_LENGTH)
            val index = state.tools.indexOfFirst { it.toolId == boundedId }
            val next = if (index >= 0) {
                // Preserve the start frame's context through completion.
                state.copy(
                    tools = state.tools.toMutableList().apply {
                        this[index] = this[index].copy(
                            name = boundedName,
                            summary = boundedSummary,
                            state = ToolRowState.Completed,
                        )
                    },
                )
            } else {
                state.appendToolRow(
                    TranscriptToolRow(
                        toolId = boundedId,
                        name = boundedName,
                        context = null,
                        summary = boundedSummary,
                        state = ToolRowState.Completed,
                    ),
                )
            }
            next.copy(generatingStatusText = null)
        }

        is ChatEvent.ApprovalRequest ->
            state.copy(pendingRequest = PendingTranscriptRequest.Approval(event))

        is ChatEvent.ClarifyRequest ->
            state.copy(pendingRequest = PendingTranscriptRequest.Clarify(event))

        is ChatEvent.ApprovalExpire ->
            when (val pending = state.pendingRequest) {
                is PendingTranscriptRequest.Approval ->
                    if (pending.event.requestId == event.requestId) state.copy(pendingRequest = null) else state
                else -> state
            }

        is ChatEvent.ClarifyExpire ->
            when (val pending = state.pendingRequest) {
                is PendingTranscriptRequest.Clarify ->
                    if (pending.event.requestId == event.requestId) state.copy(pendingRequest = null) else state
                else -> state
            }

        is ChatEvent.SessionTitle ->
            // New-chat flow only: adopt live title renames for our own session.
            if (state.adoptsLiveTitles && state.isOwnSession(event.sessionId)) {
                state.copy(adoptedTitle = event.title)
            } else {
                state
            }

        is ChatEvent.StatusUpdate ->
            state.copy(latestStatusText = event.text, statusUpdateCount = state.statusUpdateCount + 1)

        is ChatEvent.SessionInfo,
        is ChatEvent.UnsupportedBlockingRequest,
        is ChatEvent.UnsupportedBlockingExpire,
        -> state
    }

    // MARK: Direct transcript mutations (non-event paths)

    /** Restores REST-loaded history, replacing any live rows. */
    fun loadTranscript(state: TranscriptSnapshot, messages: List<RestoredMessage>): TranscriptSnapshot {
        var nextId = state.nextRowId
        val rows = messages.mapNotNull { message ->
            restoredRow(message, nextId)?.also { nextId += 1 }
        }
        return state.copy(rows = rows, nextRowId = nextId)
    }

    /**
     * Replaces history after foregrounding while preserving a local
     * active-turn suffix until REST proves the matching turn completed.
     */
    fun reconcileForegroundTranscript(
        state: TranscriptSnapshot,
        messages: List<RestoredMessage>,
        turnWasActive: Boolean,
    ): ReconcileResult {
        val priorRows = state.rows
        val latestUserIndex = if (turnWasActive) priorRows.indexOfLast { it.role == "user" }.takeIf { it >= 0 } else null
        val localTurnSuffix: List<TranscriptRow> = when {
            latestUserIndex != null -> priorRows.subList(latestUserIndex, priorRows.size).toList()
            turnWasActive -> {
                val streamingIndex = priorRows.indexOfLast { it.role == "assistant" && !it.completed }
                if (streamingIndex >= 0) priorRows.subList(streamingIndex, priorRows.size).toList() else emptyList()
            }
            else -> emptyList()
        }

        var next = loadTranscript(state, messages)
        if (!turnWasActive || localTurnSuffix.isEmpty()) {
            if (turnWasActive) next = next.finishRunningTools()
            return ReconcileResult(next, keptLocalSuffix = false)
        }

        val localUser = localTurnSuffix.firstOrNull { it.role == "user" }
        if (localUser != null) {
            val restoredUserIndex = next.rows.indexOfLast { it.role == "user" && it.text == localUser.text }
            if (restoredUserIndex >= 0) {
                val replyWasPersisted = next.rows.drop(restoredUserIndex + 1).any { it.role == "assistant" }
                if (replyWasPersisted) {
                    return ReconcileResult(next.finishRunningTools(), keptLocalSuffix = false)
                }
                return ReconcileResult(
                    next.copy(rows = next.rows + localTurnSuffix.drop(1)),
                    keptLocalSuffix = true,
                )
            }
        }
        return ReconcileResult(next.copy(rows = next.rows + localTurnSuffix), keptLocalSuffix = true)
    }

    /** Prepends an older history window for "Load earlier" (windows are disjoint; no dedup). */
    fun prependHistory(state: TranscriptSnapshot, messages: List<RestoredMessage>): TranscriptSnapshot {
        var nextId = state.nextRowId
        val older = messages.mapNotNull { message ->
            restoredRow(message, nextId)?.also { nextId += 1 }
        }
        if (older.isEmpty()) return state
        return state.copy(rows = older + state.rows, nextRowId = nextId)
    }

    /**
     * Resume parity: a turn was already executing when we attached; make sure
     * the current assistant row is open so deltas have somewhere to land.
     */
    fun ensureInflightAssistantRow(
        state: TranscriptSnapshot,
        text: String,
        completed: Boolean,
    ): TranscriptSnapshot {
        val streamingIndex = state.lastOpenAssistantIndex()
        if (streamingIndex != null) {
            val currentText = state.rows[streamingIndex].text
            return if (text.length > currentText.length && text.startsWith(currentText)) {
                state.updateRow(streamingIndex) { it.copy(text = text) }
            } else {
                state
            }
        }

        if (!completed) {
            val latestUserIndex = state.rows.indexOfLast { it.role == "user" }
            if (latestUserIndex >= 0) {
                val assistantIndex = (state.rows.indices.reversed()).firstOrNull {
                    it > latestUserIndex && state.rows[it].role == "assistant"
                }
                if (assistantIndex != null) {
                    return state.updateRow(assistantIndex) {
                        it.copy(completed = false, text = if (text.isNotEmpty()) text else it.text)
                    }
                }
            }
        }

        return state.appendRow(role = "assistant", text = text, completed = completed)
    }

    /** Marks an active assistant turn complete when resume proves the server is done. */
    fun finishStreamingAssistant(state: TranscriptSnapshot): TranscriptSnapshot =
        state.copy(
            rows = state.rows.map {
                if (it.role == "assistant" && !it.completed) it.copy(completed = true) else it
            },
        ).finishRunningTools()

    /** Optimistic local echo of a submitted user prompt. */
    fun appendUserMessage(state: TranscriptSnapshot, text: String): TranscriptSnapshot =
        state.copy(
            tools = emptyList(),
            latestStatusText = null,
            statusUpdateCount = 0,
            generatingStatusText = null,
        ).appendRow(role = "user", text = text, completed = true)

    // MARK: Internal helpers

    private fun restoredRow(message: RestoredMessage, id: Long): TranscriptRow? {
        val role = message.role.lowercase()
        if (role !in restorableRoles) return null
        // Persisted interrupt sentinels are cancellation metadata written by
        // servers that predate the upstream transcript fix; never render them.
        if (role == "assistant" && InterruptSentinel.isInterruptSentinel(message.content)) return null
        val hasText = !message.content.isSpaceBlank()
        val hasReasoning = !message.reasoningText.isSpaceBlank()
        val hasToolIdentity = role == "tool" && !message.toolName.isNullOrEmpty()
        if (!hasText && !hasReasoning && !hasToolIdentity) return null
        return TranscriptRow(
            id = id,
            role = role,
            text = message.content,
            completed = true,
            toolName = message.toolName,
            reasoningText = message.reasoningText,
            displayKind = message.displayKind,
        )
    }

    private fun TranscriptSnapshot.lastOpenAssistantIndex(): Int? =
        rows.indexOfLast { !it.completed && it.role == "assistant" }.takeIf { it >= 0 }

    private fun TranscriptSnapshot.appendRow(
        role: String,
        text: String,
        completed: Boolean,
        reasoningText: String = "",
    ): TranscriptSnapshot = copy(
        rows = rows + TranscriptRow(
            id = nextRowId,
            role = role,
            text = text,
            completed = completed,
            reasoningText = reasoningText,
        ),
        nextRowId = nextRowId + 1,
    )

    private fun TranscriptSnapshot.updateRow(
        index: Int,
        transform: (TranscriptRow) -> TranscriptRow,
    ): TranscriptSnapshot = copy(
        rows = rows.toMutableList().apply { this[index] = transform(this[index]) },
    )

    private fun TranscriptSnapshot.appendToolRow(row: TranscriptToolRow): TranscriptSnapshot {
        val appended = tools + row
        return copy(
            tools = if (appended.size > MAX_TOOL_ROWS) appended.takeLast(MAX_TOOL_ROWS) else appended,
        )
    }

    private fun TranscriptSnapshot.finishRunningTools(): TranscriptSnapshot = copy(
        tools = tools.map {
            if (it.state == ToolRowState.Running) it.copy(state = ToolRowState.Completed) else it
        },
        generatingStatusText = null,
    )

    /** Deterministic across JVM and Native: Unicode whitespace incl. the no-break family. */
    private fun String.isSpaceBlank(): Boolean = all {
        it.isWhitespace() || it == '\u00A0' || it == '\u2007' || it == '\u202F'
    }
}

/** Renderable timeline units; tool runs and reasoning-only bursts collapse. */
sealed interface TranscriptEntry {
    data class Message(val row: TranscriptRow) : TranscriptEntry
    data class ToolRun(val rows: List<TranscriptRow>) : TranscriptEntry
    data class WorkBurst(val reasoning: List<TranscriptRow>, val tools: List<TranscriptRow>) : TranscriptEntry
}

fun coalesceTranscriptEntries(rows: List<TranscriptRow>): List<TranscriptEntry> {
    // Live structured tool activity is rendered separately by each native shell.
    // REST/relay ChatMessage DTOs currently discard tool_id. Never turn
    // "some live tools exist" into deletion of persisted transcript rows.
    // Native shells render these rows through their historical tool-result
    // presentation instead.
    val entries = mutableListOf<TranscriptEntry>()
    val toolRun = mutableListOf<TranscriptRow>()
    val burstReasoning = mutableListOf<TranscriptRow>()

    fun isSpaceBlank(value: String): Boolean = value.all {
        it.isWhitespace() || it == '\u00A0' || it == '\u2007' || it == '\u202F'
    }

    // A "work burst" is a reasoning-only assistant row followed by one or more
    // tool rows with no visible prose in between — the common agent loop.
    fun isReasoningOnly(row: TranscriptRow): Boolean =
        row.role.lowercase() == "assistant" &&
            !isSpaceBlank(row.reasoningText) &&
            isSpaceBlank(row.text)

    fun flushWork() {
        if (toolRun.isEmpty() && burstReasoning.isEmpty()) return
        if (burstReasoning.isNotEmpty()) {
            entries += TranscriptEntry.WorkBurst(reasoning = burstReasoning.toList(), tools = toolRun.toList())
        } else {
            entries += TranscriptEntry.ToolRun(toolRun.toList())
        }
        burstReasoning.clear()
        toolRun.clear()
    }

    for (row in rows) {
        when {
            row.role.lowercase() == "tool" -> toolRun += row
            isReasoningOnly(row) -> burstReasoning += row
            else -> {
                flushWork()
                entries += TranscriptEntry.Message(row)
            }
        }
    }
    flushWork()
    return entries
}

/** Config setters for platforms whose facade mutates these directly. */
fun TranscriptSnapshot.withAdoptsLiveTitles(value: Boolean): TranscriptSnapshot =
    copy(adoptsLiveTitles = value)

fun TranscriptSnapshot.withOwnSessionIds(ids: Set<String>): TranscriptSnapshot =
    copy(ownSessionIds = ids)
