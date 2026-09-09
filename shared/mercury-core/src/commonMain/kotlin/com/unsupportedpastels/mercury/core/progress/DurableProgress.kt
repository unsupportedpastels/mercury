package com.unsupportedpastels.mercury.core.progress

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Reported milestone status from the official checklist tool; not a verification claim. */
enum class RunTodoStatus {
    Pending,
    InProgress,
    Completed,
    Cancelled,
}

data class RunTodoItem(
    val id: String,
    val content: String,
    val status: RunTodoStatus,
)

/**
 * Transport-free progress input. Native layers map their own event models onto
 * this alphabet so the reduction below stays the single cross-platform decision.
 */
sealed interface ProgressObservation {
    /** Replayed history must never be mistaken for a fresh receipt on this device. */
    val historical: Boolean

    data class ToolStarted(
        val toolCallId: String,
        val toolName: String,
        val context: String?,
        override val historical: Boolean = false,
    ) : ProgressObservation

    data class ToolCompleted(
        val toolCallId: String,
        val toolName: String,
        val summary: String?,
        val snapshot: DurableProgress? = null,
        override val historical: Boolean = false,
    ) : ProgressObservation

    /** Liveness only: status, clarify and approval move the clock, adding no evidence. */
    data object Liveness : ProgressObservation {
        override val historical: Boolean = false
    }

    /** Everything this policy deliberately ignores. */
    data object Unrelated : ProgressObservation {
        override val historical: Boolean = false
    }
}

/** Acceptance slice of durable session progress; intentionally not persisted outside the server transcript. */
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

    /**
     * Roll back a speculative [beginTurn] whose prompt was never accepted, while
     * never discarding newer live or recovered evidence observed since the reset.
     */
    fun restoreUnstartedTurn(previous: DurableProgress, resetVersion: Long): DurableProgress =
        if (observationVersion == resetVersion) previous else this

    /** The owning event reducer supplies receipt time, not composition or a refresh timer. */
    fun observe(observation: ProgressObservation, atEpochMillis: Long): DurableProgress {
        if (observation.historical) return this
        val item = when (observation) {
            is ProgressObservation.ToolStarted -> ProgressToolEvidence(
                observation.toolCallId, observation.toolName, observation.context, false, atEpochMillis,
            )
            is ProgressObservation.ToolCompleted -> ProgressToolEvidence(
                observation.toolCallId, observation.toolName, observation.summary, true, atEpochMillis,
            )
            ProgressObservation.Liveness ->
                return copy(lastObservedAtEpochMillis = atEpochMillis, observationVersion = observationVersion + 1)
            ProgressObservation.Unrelated -> return this
        }.bounded()
        val snapshot = (observation as? ProgressObservation.ToolCompleted)?.snapshot?.takeUnless {
            revision != null && it.revision != null && it.revision < revision
        }
        val previous = evidence.firstOrNull { it.toolCallId == item.toolCallId }
        if (previous?.completed == true && !item.completed) return this
        if (previous != null && previous.copy(observedAtEpochMillis = item.observedAtEpochMillis) == item &&
            (snapshot == null || (snapshot.milestones == milestones && snapshot.revision == revision))
        ) {
            return this
        }
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
        if (milestones == history.milestones && evidence == history.evidence && revision == history.revision &&
            hasMilestoneSnapshot == history.hasMilestoneSnapshot
        ) {
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
    internal fun bounded() =
        copy(toolCallId = toolCallId.take(256), toolName = toolName.take(120), summary = summary?.take(400))
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
                state = state.copy(
                    milestones = snapshot.milestones, revision = snapshot.revision, hasMilestoneSnapshot = true,
                    lastObservedAtEpochMillis = at ?: state.lastObservedAtEpochMillis,
                )
            }
            val summary = if (snapshot != null) {
                "Reported checklist snapshot (${snapshot.milestones.size} items)"
            } else {
                (
                    result?.text("summary") ?: result?.text("output") ?: result?.text("result")
                        ?: (content as? JsonPrimitive)?.contentOrNull ?: result?.toString()
                    )?.let { "Observed result: ${it.take(380)}" }
            }
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
        return parseIsoInstantMillis(text)?.takeIf { it > 0 }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}

private val ISO_INSTANT = Regex(
    """^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(?:[Zz]|([+-])(\d{2}):?(\d{2}))$""",
)

/**
 * Host timestamps arrive as ISO-8601 instants. Parsed here in common code so the
 * same reduction runs on every platform instead of a JVM-only date library.
 */
internal fun parseIsoInstantMillis(text: String): Long? {
    val match = ISO_INSTANT.matchEntire(text.trim()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    val hour = match.groupValues[4].toIntOrNull() ?: return null
    val minute = match.groupValues[5].toIntOrNull() ?: return null
    val second = match.groupValues[6].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    if (hour > 23 || minute > 59 || second > 59) return null
    val fraction = match.groupValues[7]
    val millisOfSecond = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').take(3).toInt()
    val offsetSeconds = if (match.groupValues[8].isEmpty()) {
        0
    } else {
        val offsetHours = match.groupValues[9].toInt()
        val offsetMinutes = match.groupValues[10].toInt()
        if (offsetHours > 18 || offsetMinutes > 59) return null
        val magnitude = offsetHours * 3600 + offsetMinutes * 60
        if (match.groupValues[8] == "-") -magnitude else magnitude
    }
    val days = daysFromCivil(year, month, day)
    val seconds = days * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds
    return seconds * 1000L + millisOfSecond
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
}

/** Howard Hinnant's days-from-civil: proleptic Gregorian days since 1970-01-01. */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val shiftedYear = if (month <= 2) year - 1 else year
    val era = (if (shiftedYear >= 0) shiftedYear else shiftedYear - 399) / 400
    val yearOfEra = shiftedYear - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
}
