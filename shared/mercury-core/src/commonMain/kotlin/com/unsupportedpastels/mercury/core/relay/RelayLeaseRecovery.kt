package com.unsupportedpastels.mercury.core.relay

import kotlinx.serialization.json.*

/** Authenticated plugin-owned binding; historical bindings never grant live control. */
data class RelayLeaseBinding(val runtimeId: String, val durableId: String, val profile: String, val live: Boolean)
data class RelayLeaseSnapshot(
    val leaseId: String,
    val lastSeq: Long,
    val gap: Boolean,
    val reset: Boolean,
    val bindings: List<RelayLeaseBinding>,
    val tasks: List<String>,
    val truncated: Boolean,
) {
    /** Recorded bindings are evidence, not permission to take over another runtime. */
    fun hasLiveBinding(durableId: String, profile: String): Boolean =
        bindings.any { it.live && it.durableId == durableId && it.profile == profile }
}

/** Immutable memory-only cursor decisions; native owner supplies serialization. */
data class RelayLeaseCheckpointState(
    val leaseId: String? = null,
    val cursor: Long = 0,
    val generation: Long = 0,
) {
    fun begin(): RelayLeaseCheckpointState = copy(generation = generation + 1)
    fun bind(owner: Long, id: String): RelayLeaseCheckpointState =
        if (owner != generation || leaseId == id) this else copy(leaseId = id, cursor = 0)
    fun applied(owner: Long, id: String, seq: Long): RelayLeaseCheckpointState =
        if (owner == generation && leaseId == id && seq > cursor) copy(cursor = seq) else this
}

/** JSON stays internal; exported APIs use ordinary DTOs and strings. */
object RelayRecoveryDecoder {
    fun binding(raw: String): RelayLeaseBinding? {
        val row = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return RelayLeaseBinding(row.text("runtime_session_id") ?: return null,
            row.text("durable_session_id") ?: return null, row.text("profile") ?: return null,
            row.flag("live") ?: return null)
    }
    fun backgroundTaskEvent(type: String, runtime: String, payloadJson: String): BackgroundTaskEvent? {
        val payload = runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull() ?: return null
        return decodeBackgroundTaskEvent(type, runtime, payload)
    }
}

/**
 * Pure bounded frame/replay reducer. One native serialized owner per attachment.
 * No transport, coroutine, clock, persistence, or platform lifecycle ownership.
 * A null receive result consumes control/replayed foreground/duplicate frames.
 */
class RelayLeaseRecoveryEngine(private val profile: String) {
    private val pending = linkedMapOf<Long, Boolean>()
    private val eventSequences = mutableMapOf<String, Long>()
    private var received = 0L
    var snapshot: RelayLeaseSnapshot? = null
        private set
    var replayComplete: Boolean = false
        private set
    var acknowledgedCursor: Long = 0
        private set

    @Throws(IllegalArgumentException::class)
    fun initialize(raw: String): RelayLeaseSnapshot {
        if (snapshot != null) fail()
        val message = parse(raw)
        if (message.text("method") != "relay.lease.attached") fail()
        val p = message["params"] as? JsonObject ?: fail()
        if (p.number("recovery_version") != 1L) fail()
        val id = p.text("lease_id") ?: fail()
        val last = p.number("last_seq")?.takeIf { it >= 0 } ?: fail()
        val cursor = p.number("resume_cursor")?.takeIf { it in 0..last } ?: fail()
        val bindings = (p["bindings"] as? JsonArray).orEmpty().take(64)
            .mapNotNull { RelayRecoveryDecoder.binding(it.toString()) }
            .filter { it.profile == profile }.distinctBy { it.runtimeId }
        val tasks = (p["task_snapshot"] as? JsonArray).orEmpty().take(64)
            .mapNotNull { (it as? JsonObject)?.toString() }
        val attached = RelayLeaseSnapshot(id, last, p.flag("replay_gap") ?: fail(),
            p.flag("recovery_reset") ?: false, bindings, tasks, p.flag("snapshot_truncated") ?: false)
        snapshot = attached
        received = cursor
        return attached
    }

    @Throws(IllegalArgumentException::class)
    fun receive(raw: String): String? {
        val m = parse(raw)
        when (m.text("method")) {
            "relay.lease.replay_complete" -> {
                val p = m["params"] as? JsonObject ?: fail()
                if (p.text("lease_id") != snapshot?.leaseId || p.number("last_seq") != snapshot?.lastSeq) fail()
                replayComplete = true
                return null
            }
            "relay.lease.frame" -> {
                val p = m["params"] as? JsonObject ?: fail()
                val id = p.text("lease_id") ?: fail()
                if (id != snapshot?.leaseId) fail()
                val seq = p.number("seq")?.takeIf { it > 0 } ?: fail()
                val replay = p.flag("replay") ?: fail()
                val frame = (p["frame"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
                if (seq <= received) return null
                if (seq != received + 1 && snapshot?.gap != true) fail()
                received = seq
                val inner = parse(frame)
                val params = inner["params"] as? JsonObject
                val child = inner.text("method") == "event" && params?.text("type") in CHILD_EVENTS
                // Replayed replies must never match this attachment's new pending RPC.
                if (pending.size >= 4096) fail()
                pending[seq] = !child
                if (replay && !child) { advance(); return null }
                if (!child) { advance(); return frame }
                val eventId = "$id:$seq"
                eventSequences[eventId] = seq
                return JsonObject(inner + ("params" to JsonObject(params!! +
                    ("relay_event_id" to JsonPrimitive(eventId)) +
                    ("relay_replay" to JsonPrimitive(replay))))).toString()
            }
            "relay.lease.attached" -> fail()
            else -> return raw
        }
    }

    /** Only after durable-scoped application or explicit rejection, never on receipt. */
    fun acknowledge(eventId: String?) {
        val seq = eventSequences.remove(eventId) ?: return
        pending[seq] = true
        advance()
    }
    private fun advance() {
        // Accepted sequences are strictly increasing: insertion order equals TreeMap order.
        while (pending.entries.firstOrNull()?.value == true) {
            val seq = pending.keys.first()
            pending.remove(seq)
            if (snapshot?.gap != true) acknowledgedCursor = seq
        }
    }
    companion object {
        val CHILD_EVENTS = setOf("subagent.start", "subagent.tool", "subagent.progress", "subagent.complete")
    }
}

private fun parse(raw: String): JsonObject = try {
    Json.parseToJsonElement(raw) as? JsonObject ?: fail()
} catch (_: Exception) { fail() }
private fun fail(): Nothing = throw IllegalArgumentException("Invalid Mercury lease recovery frame")
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 512 }
private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
