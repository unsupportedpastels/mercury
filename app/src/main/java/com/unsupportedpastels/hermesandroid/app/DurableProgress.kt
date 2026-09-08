package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import kotlinx.serialization.json.*
import java.time.Instant

/** Android acceptance slice; intentionally not persisted outside the server transcript. */
data class DurableProgress(
    val milestones: List<RunTodoItem> = emptyList(),
    val evidence: List<ProgressToolEvidence> = emptyList(),
    val revision: Long? = null,
    val hasMilestoneSnapshot: Boolean = false,
    val lastObservedAtEpochMillis: Long? = null,
    val restored: Boolean = false,
    val refreshing: Boolean = false,
    val refreshRequestId: Long? = null,
    val refreshError: String? = null,
    /** This is a bounded history window, never a claim to have read the entire session. */
    val partialHistory: Boolean = true,
    val observationVersion: Long = 0,
    val turnStartedAtEpochMillis: Long? = null,
) {
    fun beginTurn(atEpochMillis: Long) = DurableProgress(
        observationVersion = observationVersion + 1, turnStartedAtEpochMillis = atEpochMillis,
    )

    /** The owning event reducer supplies receipt time, not composition or a refresh timer. */
    fun observe(event: HermesChatEvent, atEpochMillis: Long): DurableProgress {
        if ((event is HermesChatEvent.ToolStart && event.historical) ||
            (event is HermesChatEvent.ToolComplete && event.historical)) return this
        val item = when (event) {
            is HermesChatEvent.ToolStart -> ProgressToolEvidence(event.toolId, event.name, event.context, false, atEpochMillis)
            is HermesChatEvent.ToolComplete -> ProgressToolEvidence(event.toolId, event.name, event.summary, true, atEpochMillis)
            is HermesChatEvent.StatusUpdate, is HermesChatEvent.ClarifyRequest, is HermesChatEvent.ApprovalRequest ->
                return copy(lastObservedAtEpochMillis = atEpochMillis, observationVersion = observationVersion + 1)
            else -> return this
        }.bounded()
        val snapshot = (event as? HermesChatEvent.ToolComplete)?.progressSnapshot?.takeUnless {
            revision != null && it.revision != null && it.revision < revision
        }
        val previous = evidence.firstOrNull { it.toolCallId == item.toolCallId }
        if (previous?.completed == true && !item.completed) return this
        if (previous != null && previous.copy(observedAtEpochMillis = item.observedAtEpochMillis) == item &&
            (snapshot == null || (snapshot.milestones == milestones && snapshot.revision == revision))) return this
        return copy(
            milestones = snapshot?.milestones ?: milestones,
            revision = if (snapshot != null) snapshot.revision else revision,
            hasMilestoneSnapshot = snapshot?.hasMilestoneSnapshot ?: hasMilestoneSnapshot,
            evidence = (evidence.filterNot { it.toolCallId == item.toolCallId } + item).takeLast(20),
            lastObservedAtEpochMillis = atEpochMillis,
            restored = if (snapshot != null) false else restored, refreshError = null,
            observationVersion = observationVersion + 1,
        )
    }

    /** A live event/new turn after the request began always wins, even with clock skew. */
    fun recover(history: DurableProgress, expectedVersion: Long): DurableProgress {
        if (observationVersion != expectedVersion) return copy(refreshing = false)
        if (!history.hasMilestoneSnapshot && history.evidence.isEmpty()) return copy(refreshing = false, refreshError = null)
        // Historical recovery is always labeled restored, including a preceding turn.
        // Never compare a host timestamp to this device's receipt clock.
        if (revision != null && history.revision != null && history.revision < revision) {
            return copy(refreshing = false, refreshError = null)
        }
        if (milestones == history.milestones && evidence == history.evidence && revision == history.revision && hasMilestoneSnapshot == history.hasMilestoneSnapshot) {
            return copy(refreshing = false, refreshError = null)
        }
        return history.copy(
            milestones = if (history.hasMilestoneSnapshot) history.milestones else milestones,
            hasMilestoneSnapshot = history.hasMilestoneSnapshot || hasMilestoneSnapshot,
            revision = if (history.hasMilestoneSnapshot) history.revision else revision,
            restored = true,
            lastObservedAtEpochMillis = history.lastObservedAtEpochMillis ?: lastObservedAtEpochMillis,
            observationVersion = observationVersion + 1,
            turnStartedAtEpochMillis = turnStartedAtEpochMillis,
        )
    }
}

/** Reported tool output only. A successful tool exit is NOT independently verified tests. */
data class ProgressToolEvidence(
    val toolCallId: String,
    val toolName: String,
    val summary: String?,
    val completed: Boolean,
    val observedAtEpochMillis: Long?,
) {
    internal fun bounded() = copy(toolCallId = toolCallId.take(256), toolName = toolName.take(120), summary = summary?.take(400))
}

object DurableProgressParser {
    private const val MAX_ROWS = 500
    private const val MAX_RESULT_CHARS = 131072
    private val json = Json { ignoreUnknownKeys = true }

    /** Rows are in the official endpoint's chronological order, including a latest window. */
    fun parse(rows: List<JsonObject>): DurableProgress {
        val window = rows.takeLast(MAX_ROWS)
        val names = mutableMapOf<String, String>()
        window.forEach { row ->
            if (row.text("role") != "assistant") return@forEach
            (row["tool_calls"] as? JsonArray)?.take(100)?.forEach callLoop@ { element ->
                val call = element as? JsonObject ?: return@callLoop
                val name = (call["function"] as? JsonObject)?.text("name") ?: return@callLoop
                call.text("id")?.takeIf { it.length <= 256 }?.let { names[it] = name.take(120) }
            }
        }
        var state = DurableProgress(restored = true)
        window.forEachIndexed { _, row ->
            if (row.text("role") != "tool") return@forEachIndexed
            val id = row.text("tool_call_id") ?: row.text("id")
            val name = row.text("tool_name") ?: row.text("name") ?: id?.let { names[it] } ?: return@forEachIndexed
            val content = row["content"]
            val result = resultObject(content)
            val at = timestamp(row["timestamp"])
            val snapshot = if (name == "todo_list" || name == "todo") result?.let(::snapshot) else null
            if (snapshot != null && (state.revision == null || snapshot.revision == null || snapshot.revision >= state.revision)) {
                state = state.copy(milestones = snapshot.milestones, revision = snapshot.revision, hasMilestoneSnapshot = true,
                    lastObservedAtEpochMillis = at ?: state.lastObservedAtEpochMillis)
            }
            val summary = if (snapshot != null) "Reported checklist snapshot (${snapshot.milestones.size} items)"
                else (result?.text("summary") ?: result?.text("output") ?: result?.text("result")
                    ?: (content as? JsonPrimitive)?.contentOrNull ?: result?.toString())
                    ?.let { "Observed result: ${it.take(380)}" }
            if (id != null && (summary != null || snapshot != null)) {
                val item = ProgressToolEvidence(id, name, summary, true, at).bounded()
                state = state.copy(
                    evidence = (state.evidence.filterNot { it.toolCallId == item.toolCallId } + item).takeLast(20),
                    lastObservedAtEpochMillis = listOfNotNull(state.lastObservedAtEpochMillis, at).maxOrNull(),
                )
            }
        }
        return state
    }

    /** Official tool completion snapshot; deliberately never inspects args/arguments. */
    fun liveSnapshot(payload: JsonObject): DurableProgress? {
        if (payload.text("name") !in setOf("todo", "todo_list")) return null
        return snapshot(payload) ?: resultObject(payload["result"])?.let(::snapshot)
    }

    private fun snapshot(result: JsonObject): DurableProgress? {
        val revision = (result["revision"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        if (result.containsKey("revision") && revision == null) return null
        val todos = result["todos"] as? JsonArray ?: return null
        if (todos.size > 50) return null
        val items = todos.map { element ->
            val row = element as? JsonObject ?: return null
            val id = row.text("id")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
            val content = row.text("content")?.takeIf { it.isNotBlank() }?.take(1000) ?: return null
            val status = when (row.text("status")) {
                "pending" -> RunTodoStatus.Pending
                "in_progress" -> RunTodoStatus.InProgress
                "completed" -> RunTodoStatus.Completed
                "cancelled" -> RunTodoStatus.Cancelled
                else -> return null
            }
            RunTodoItem(id, content, status)
        }
        if (items.distinctBy { it.id }.size != items.size) return null
        return DurableProgress(milestones = items, revision = revision, hasMilestoneSnapshot = true, restored = true)
    }

    private fun resultObject(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value.takeIf { it.toString().length <= MAX_RESULT_CHARS }
        is JsonPrimitive -> value.contentOrNull?.takeIf { it.length <= MAX_RESULT_CHARS }
            ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        else -> null
    }

    private fun timestamp(value: JsonElement?): Long? {
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return null
        val number = text.toDoubleOrNull()
        if (number != null) {
            if (!number.isFinite() || number <= 0) return null
            val millis = if (number < 100_000_000_000) number * 1000 else number
            return millis.takeIf { it < Long.MAX_VALUE }?.toLong()
        }
        return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.takeIf { it > 0 }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
