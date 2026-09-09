package com.unsupportedpastels.mercury.core.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Separate from turn/tool state: only child evidence can finish a child. */
enum class BackgroundTaskStatus { Active, Finished, Failed, Stopped, Unknown }
enum class BackgroundTaskEventKind { Start, Tool, Complete }
data class BackgroundTaskEvent(
    val sessionId: String,
    val kind: BackgroundTaskEventKind,
    val childId: String?,
    val goal: String?,
    val action: String?,
    val terminalStatus: BackgroundTaskStatus = BackgroundTaskStatus.Unknown,
    val eventId: String? = null,
    val historical: Boolean = false,
)

data class BackgroundTaskRow(
    val runtimeId: String,
    val id: String,
    val goal: String,
    val action: String?,
    val status: BackgroundTaskStatus,
    val observedAtMillis: Long,
    val available: Boolean = true,
    val identityKnown: Boolean = true,
    /**
     * When the gateway's own subagent registry last reported this child as
     * running. Registry polling is not worker activity (it never moves
     * [observedAtMillis]), but it is authoritative liveness: a reopened app
     * whose only child evidence is a recovered snapshot has no fresh event to
     * observe, and the host saying "still running" must count as active.
     */
    val registryConfirmedAtMillis: Long = 0L,
) {
    val terminal: Boolean get() = status in setOf(BackgroundTaskStatus.Finished, BackgroundTaskStatus.Failed, BackgroundTaskStatus.Stopped)
    fun recentlyActive(now: Long): Boolean = identityKnown && available && status == BackgroundTaskStatus.Active &&
        (observedRecently(now) || registryConfirmedRecently(now))
    fun observedRecently(now: Long): Boolean =
        observedAtMillis > 0L && now - observedAtMillis < ACTIVITY_WINDOW_MILLIS
    fun registryConfirmedRecently(now: Long): Boolean =
        registryConfirmedAtMillis > 0L && now - registryConfirmedAtMillis < ACTIVITY_WINDOW_MILLIS
    fun label(now: Long): String = when {
        terminal -> when (status) {
            BackgroundTaskStatus.Finished -> "Finished"
            BackgroundTaskStatus.Failed -> "Failed"
            else -> "Stopped"
        }
        // A recovered snapshot without a child identity or timestamp is
        // historical evidence, not a live task whose status merely happens to
        // be unknown. Keep that distinction visible without inventing a
        // terminal status.
        status == BackgroundTaskStatus.Unknown && !available && observedAtMillis <= 0L -> "Historical · status unavailable"
        !available -> "Last known · updates unavailable"
        status == BackgroundTaskStatus.Unknown -> "Status unavailable"
        !recentlyActive(now) -> "Last known active · no recent activity"
        !observedRecently(now) -> "Active · host reports running"
        else -> "Active · observed activity"
    }

    fun isDismissible(now: Long): Boolean = BackgroundTaskPresentationPolicy.isDismissible(this, now)

    /** Opaque, evidence-scoped key for a native UI's in-memory dismissal set. */
    fun dismissalKey(): String = BackgroundTaskPresentationPolicy.dismissalKey(this)

    fun timeLabel(now: Long): String = BackgroundTaskPresentationPolicy.timeLabel(this, now)

    companion object {
        /** How long one observation (a child event or a registry confirmation) counts as live. */
        const val ACTIVITY_WINDOW_MILLIS = 120_000L
    }
}

/** Shared summary and dismissal policy for unresolved child-task evidence. */
data class BackgroundTaskPresentation(
    val activeCount: Int,
    val unresolvedCount: Int,
    val terminalCount: Int,
    val unavailableCount: Int,
    val unknownCount: Int,
    val terminalOnly: Boolean,
    val statusUnavailable: Boolean,
    val headline: String,
)

/**
 * Child-task evidence is deliberately not completed by parent completion,
 * process-registry absence, or silence. This policy only decides what can be
 * said about rows already reduced by the shared relay state machine.
 */
object BackgroundTaskPresentationPolicy {
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    fun summarize(rows: List<BackgroundTaskRow>, now: Long): BackgroundTaskPresentation {
        val active = rows.count { it.recentlyActive(now) }
        val unresolved = rows.count { !it.terminal }
        val terminal = rows.size - unresolved
        val unavailable = rows.count { !it.terminal && isStatusUnavailable(it, now) }
        val unknown = rows.count {
            !it.terminal && (it.status == BackgroundTaskStatus.Unknown || !it.identityKnown)
        }
        val terminalOnly = rows.isNotEmpty() && unresolved == 0
        val statusUnavailable = unavailable > 0
        val headline = when {
            rows.isEmpty() -> ""
            terminalOnly -> "Background tasks · completed details"
            statusUnavailable && active == 0 -> "Background tasks · status unavailable"
            statusUnavailable -> "Background tasks · $active active · other status unavailable"
            else -> "Background tasks · $active active"
        }
        return BackgroundTaskPresentation(
            activeCount = active,
            unresolvedCount = unresolved,
            terminalCount = terminal,
            unavailableCount = unavailable,
            unknownCount = unknown,
            terminalOnly = terminalOnly,
            statusUnavailable = statusUnavailable,
            headline = headline,
        )
    }

    fun isDismissible(row: BackgroundTaskRow, now: Long): Boolean =
        row.terminal || (!row.terminal && isStatusUnavailable(row, now))

    /**
     * Dismissal is scoped to the complete observed row, rather than only the
     * child ID. A later event that changes its evidence (or runtime binding)
     * therefore becomes visible again. The key is never rendered.
     */
    fun dismissalKey(row: BackgroundTaskRow): String {
        val evidence = listOf(
            row.runtimeId,
            row.id,
            row.goal,
            row.action.orEmpty(),
            row.status.name,
            row.observedAtMillis.toString(),
            row.available.toString(),
            row.identityKnown.toString(),
        ).joinToString("\u0000")
        var hash = FNV_OFFSET_BASIS
        evidence.encodeToByteArray().forEach { byte ->
            hash = (hash xor (byte.toLong() and 0xffL)) * FNV_PRIME
        }
        // Do not put goals, actions, IDs, or other task text into native
        // saved-state keys. The fingerprint is only a local dismissal token.
        return "background-task:$hash"
    }

    fun timeLabel(row: BackgroundTaskRow, now: Long): String {
        if (row.observedAtMillis <= 0L) return "${row.label(now)} · time unavailable"
        val age = (now - row.observedAtMillis).coerceAtLeast(0L) / 1000L
        return when {
            row.terminal -> "${row.label(now)} ${age}s ago"
            !row.recentlyActive(now) -> "${row.label(now)} · last observed ${age}s ago"
            else -> "Last observed activity ${age}s ago"
        }
    }

    fun secondaryLabel(rows: List<BackgroundTaskRow>, now: Long): String {
        if (rows.isEmpty()) return ""
        val unresolved = rows.filterNot(BackgroundTaskRow::terminal)
        if (unresolved.isNotEmpty()) {
            if (unresolved.any { isStatusUnavailable(it, now) }) {
                return "Some background task status is unavailable"
            }
            return timeLabel(unresolved.maxBy { it.observedAtMillis }, now)
        }
        return timeLabel(rows.maxBy { it.observedAtMillis }, now)
    }

    /**
     * Rows a host-wide "running" surface may show: identity known, still
     * observable, and with worker activity inside the recency window. A stale
     * or unavailable row is evidence for the owning chat's strip, not proof
     * that anything is running now.
     */
    fun runningRows(rows: List<BackgroundTaskRow>, now: Long): List<BackgroundTaskRow> =
        rows.filter { it.recentlyActive(now) }.sortedByDescending { it.observedAtMillis }

    private fun isStatusUnavailable(row: BackgroundTaskRow, now: Long): Boolean =
        !row.available || !row.identityKnown || row.status == BackgroundTaskStatus.Unknown || !row.recentlyActive(now)
}

data class BackgroundTasks(
    val rows: List<BackgroundTaskRow> = emptyList(),
    val processedEventIds: List<String> = emptyList(),
) {
    fun activeCount(now: Long): Int = rows.count { it.recentlyActive(now) }
    fun unavailable(): BackgroundTasks = copy(rows = rows.map { if (it.terminal) it else it.copy(available = false) })
    /**
     * The registry is process-global: never import rows by title, parent completion, or absence.
     * [previousRuntime] is opt-in proof from the caller that this is a rebind of the SAME
     * durable session and origin/controller generation, not a different session's runtime.
     * Only exact previously observed, still-active IDs may move to the replacement binding.
     * Registry polling is not child activity and must not refresh observedAtMillis; a
     * "running" report is recorded separately as registry-confirmed liveness at [now].
     */
    fun reconcile(
        active: List<BackgroundTaskRegistryEntry>,
        runtime: String,
        previousRuntime: String = runtime,
        now: Long = 0L,
    ): BackgroundTasks = copy(rows = rows.map { row ->
        val observed = active.singleOrNull { it.subagentId == row.id }
        val running = observed?.status == "running" || observed?.status == "active"
        val confirmedAt = if (running) maxOf(now, row.registryConfirmedAtMillis) else row.registryConfirmedAtMillis
        when {
            !row.identityKnown || row.terminal || observed == null -> row
            row.runtimeId == runtime -> row.copy(available = running, registryConfirmedAtMillis = confirmedAt)
            row.runtimeId == previousRuntime && running && rows.none {
                it.runtimeId == runtime && it.id == row.id && it.identityKnown
            } -> row.copy(runtimeId = runtime, available = true, registryConfirmedAtMillis = confirmedAt)
            else -> row
        }
    })
    fun reduce(event: BackgroundTaskEvent, expectedRuntime: String, now: Long): BackgroundTasks {
        if (event.sessionId != expectedRuntime) return this
        val eventId = event.eventId?.takeIf { it.isNotBlank() && it.length <= 512 }
        if (eventId != null && eventId in processedEventIds) return this
        val childId = event.childId?.takeIf { it.isNotBlank() && it.length <= 512 }
        val id = childId ?: "identity-unavailable"
        // This reducer is owned by one origin/profile/durable session. Runtime IDs
        // are replaceable bindings, not child identity. The caller proves current
        // event ownership above; replay must update the old child, not add a copy.
        val index = rows.indexOfFirst { it.id == id && it.identityKnown == (childId != null) &&
            (childId != null || it.runtimeId == event.sessionId) }
        val old = rows.getOrNull(index)
        if (old?.terminal == true) return this
        // Older emitters have no safe identity: do not invent a child count or correlate terminals.
        val status = if (childId == null) BackgroundTaskStatus.Unknown else when (event.kind) {
            BackgroundTaskEventKind.Complete -> event.terminalStatus
            else -> BackgroundTaskStatus.Active
        }
        val row = BackgroundTaskRow(event.sessionId, id,
            event.goal?.take(240)?.takeIf(String::isNotBlank) ?: old?.goal ?: "Background task · details unavailable",
            event.action?.take(320) ?: old?.action, status,
            // Retained delivery proves lifecycle, not recent worker activity. A
            // historical terminal has no known completion time in protocol v1.
            if (event.historical) {
                if (event.kind == BackgroundTaskEventKind.Complete) 0L else old?.observedAtMillis ?: 0L
            } else now,
            available = !event.historical, identityKnown = childId != null,
            registryConfirmedAtMillis = old?.registryConfirmedAtMillis ?: 0L)
        if (index < 0 && rows.size >= 64) return this // never evict unresolved work to invent a complete list
        return copy(
            rows = if (index < 0) rows + row else rows.toMutableList().also { it[index] = row },
            processedEventIds = if (eventId == null) processedEventIds else (processedEventIds + eventId).takeLast(512),
        )
    }

    /** Import only explicit plugin-owned durable bindings; no registry/absence inference. */
    fun recoverRelayTasks(
        snapshot: RelayLeaseSnapshot,
        durableId: String,
        profile: String,
        runtime: String? = null,
    ): BackgroundTasks {
        var result = unavailable()
        for (raw in snapshot.tasks) {
            val frame = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: continue
            if ((frame["method"] as? JsonPrimitive)?.content != "event") continue
            val params = frame["params"] as? JsonObject ?: continue
            val binding = RelayRecoveryDecoder.binding(params["recovery_binding"]?.toString() ?: continue) ?: continue
            if (binding.durableId != durableId || binding.profile != profile) continue
            val originalRuntime = (params["session_id"] as? JsonPrimitive)?.content ?: continue
            if (originalRuntime != binding.runtimeId) continue
            val type = (params["type"] as? JsonPrimitive)?.content ?: continue
            val payload = params["payload"] as? JsonObject ?: continue
            val selectedRuntime = runtime ?: binding.runtimeId
            val decoded = decodeBackgroundTaskEvent(type, selectedRuntime, payload) ?: continue
            val revision = (params["recovery_revision"] as? JsonPrimitive)?.longOrNull
            val event = decoded.copy(
                eventId = revision?.let { "snapshot:$durableId:$it" },
                kind = if (!binding.live && decoded.kind != BackgroundTaskEventKind.Complete)
                    BackgroundTaskEventKind.Complete else decoded.kind,
                terminalStatus = if (!binding.live && decoded.kind != BackgroundTaskEventKind.Complete)
                    BackgroundTaskStatus.Unknown else decoded.terminalStatus,
            )
            val prior = result.rows.firstOrNull { it.id == event.childId }
            val priorAge = if (event.kind == BackgroundTaskEventKind.Complete && prior?.terminal != true)
                0L else prior?.observedAtMillis ?: 0L
            // Receipt of a snapshot is not fresh worker activity. Preserve observation age.
            result = result.reduce(event, selectedRuntime, priorAge)
        }
        return result.unavailable()
    }

}

internal fun decodeBackgroundTaskEvent(type: String, runtime: String, payload: JsonObject): BackgroundTaskEvent? {
    val kind = when (type) {
        "subagent.start" -> BackgroundTaskEventKind.Start
        "subagent.tool", "subagent.progress" -> BackgroundTaskEventKind.Tool
        "subagent.complete" -> BackgroundTaskEventKind.Complete
        else -> return null
    }
    if (runtime.isBlank() || runtime.length > 512) return null
    // Identifiers and enums must be exact: an over-long value is rejected.
    fun text(key: String, max: Int): String? = (payload[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= max }
    // Free text is display evidence: an over-long value is clipped, never
    // dropped, so a long delegation goal still yields a legible title.
    fun clipped(key: String, max: Int): String? = (payload[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?.let { clipDisplayText(it, max) }
    val child = text("subagent_id", 256) ?: text("child_session_id", 256)?.let { "session:$it" }
        ?: text("delegation_id", 256)?.let { batch ->
            (payload["task_index"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..255 }?.let { "$batch:$it" }
        }
    val terminal = when (text("status", 40)) {
        "completed" -> BackgroundTaskStatus.Finished
        "failed", "error", "timeout" -> BackgroundTaskStatus.Failed
        "interrupted", "cancelled", "canceled", "stopped" -> BackgroundTaskStatus.Stopped
        else -> BackgroundTaskStatus.Unknown
    }
    return BackgroundTaskEvent(runtime, kind, child, clipped("goal", GOAL_DISPLAY_CHARS),
        clipped("tool_preview", ACTION_DISPLAY_CHARS) ?: clipped("text", ACTION_DISPLAY_CHARS)
            ?: text("tool_name", 120) ?: clipped("summary", ACTION_DISPLAY_CHARS), terminal)
}

internal const val GOAL_DISPLAY_CHARS = 240
internal const val ACTION_DISPLAY_CHARS = 320

/**
 * Bounds free text for a one-line native row: only the first line is kept and
 * anything beyond [max] is cut at a word boundary with an ellipsis.
 */
internal fun clipDisplayText(value: String, max: Int = Int.MAX_VALUE): String {
    val firstLine = value.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty) ?: return ""
    if (firstLine.length <= max) return firstLine
    val budget = (max - 1).coerceAtLeast(1)
    val cut = firstLine.take(budget)
    val boundary = cut.lastIndexOf(' ')
    val head = if (boundary >= budget / 2) cut.substring(0, boundary) else cut
    return head.trimEnd() + "\u2026"
}

data class BackgroundTaskRegistryEntry(val subagentId: String, val status: String)
