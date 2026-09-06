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
) {
    val terminal: Boolean get() = status in setOf(BackgroundTaskStatus.Finished, BackgroundTaskStatus.Failed, BackgroundTaskStatus.Stopped)
    fun recentlyActive(now: Long): Boolean = identityKnown && available && status == BackgroundTaskStatus.Active && now - observedAtMillis < 120_000
    fun label(now: Long): String = when {
        terminal -> when (status) {
            BackgroundTaskStatus.Finished -> "Finished"
            BackgroundTaskStatus.Failed -> "Failed"
            else -> "Stopped"
        }
        !available -> "Last known · updates unavailable"
        status == BackgroundTaskStatus.Unknown -> "Status unavailable"
        !recentlyActive(now) -> "Last known active · no recent activity"
        else -> "Active · observed activity"
    }
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
     * Registry polling is not child activity and must not refresh observedAtMillis.
     */
    fun reconcile(
        active: List<BackgroundTaskRegistryEntry>,
        runtime: String,
        previousRuntime: String = runtime,
    ): BackgroundTasks = copy(rows = rows.map { row ->
        val observed = active.singleOrNull { it.subagentId == row.id }
        val running = observed?.status == "running" || observed?.status == "active"
        when {
            !row.identityKnown || row.terminal || observed == null -> row
            row.runtimeId == runtime -> row.copy(available = running)
            row.runtimeId == previousRuntime && running && rows.none {
                it.runtimeId == runtime && it.id == row.id && it.identityKnown
            } -> row.copy(runtimeId = runtime, available = true)
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
            available = !event.historical, identityKnown = childId != null)
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
    fun text(key: String, max: Int): String? = (payload[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= max }
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
    return BackgroundTaskEvent(runtime, kind, child, text("goal", 240),
        text("tool_preview", 320) ?: text("text", 320) ?: text("tool_name", 120) ?: text("summary", 320), terminal)
}

data class BackgroundTaskRegistryEntry(val subagentId: String, val status: String)
