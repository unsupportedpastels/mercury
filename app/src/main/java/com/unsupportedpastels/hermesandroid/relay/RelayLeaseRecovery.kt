package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSocket
import com.unsupportedpastels.mercury.core.relay.RelayLeaseCheckpointState
import com.unsupportedpastels.mercury.core.relay.RelayLeaseRecoveryEngine
import com.unsupportedpastels.mercury.core.relay.RelayRecoveryDecoder
import kotlinx.serialization.json.*

typealias RelayLeaseBinding = com.unsupportedpastels.mercury.core.relay.RelayLeaseBinding

/** Compatibility projection for native callers; shared boundary exports strings, not JSON. */
data class RelayLeaseSnapshot(
    val leaseId: String,
    val lastSeq: Long,
    val gap: Boolean,
    val reset: Boolean,
    val bindings: List<RelayLeaseBinding>,
    val tasks: List<JsonObject>,
    val truncated: Boolean,
) {
    fun hasLiveBinding(durableId: String, profile: String): Boolean = shared().hasLiveBinding(durableId, profile)
    internal fun shared() = com.unsupportedpastels.mercury.core.relay.RelayLeaseSnapshot(
        leaseId, lastSeq, gap, reset, bindings, tasks.map { it.toString() }, truncated)
}

/** Native synchronization, shared immutable checkpoint decisions. Never persist this state. */
class RelayLeaseCheckpoint {
    private var state = RelayLeaseCheckpointState()
    val leaseId: String? @Synchronized get() = state.leaseId
    val cursor: Long @Synchronized get() = state.cursor
    @Synchronized fun begin(): Long { state = state.begin(); return state.generation }
    @Synchronized fun bind(owner: Long, id: String) { state = state.bind(owner, id) }
    @Synchronized fun applied(owner: Long, id: String, seq: Long) { state = state.applied(owner, id, seq) }
}

/** Only socket I/O and native serialization remain here. */
class RelayLeaseRecoverySocket(
    private val delegate: HermesChatSocket,
    profile: String,
    private val checkpoint: RelayLeaseCheckpoint,
) : HermesChatSocket {
    private val owner = checkpoint.begin()
    private val engine = RelayLeaseRecoveryEngine(profile)
    var snapshot: RelayLeaseSnapshot? = null
        private set
    val replayComplete: Boolean @Synchronized get() = engine.replayComplete

    suspend fun initialize() {
        val raw = delegate.receiveText() ?: fail()
        synchronized(this) {
            val shared = protocol { engine.initialize(raw) }
            snapshot = RelayLeaseSnapshot(shared.leaseId, shared.lastSeq, shared.gap, shared.reset,
                shared.bindings, shared.tasks.map { Json.parseToJsonElement(it).jsonObject }, shared.truncated)
            checkpoint.bind(owner, shared.leaseId)
        }
    }
    override suspend fun sendText(text: String) = delegate.sendText(text)
    override suspend fun close() = delegate.close()
    override suspend fun receiveText(): String? {
        while (true) {
            val raw = delegate.receiveText() ?: return null
            val delivered = synchronized(this) {
                protocol { engine.receive(raw) }.also { applyCheckpoint() }
            }
            if (delivered != null) return delivered
        }
    }
    /** Called only after exact durable reducer applied/rejected the event. */
    @Synchronized fun acknowledge(eventId: String?) {
        engine.acknowledge(eventId)
        applyCheckpoint()
    }
    private fun applyCheckpoint() {
        engine.snapshot?.let { checkpoint.applied(owner, it.leaseId, engine.acknowledgedCursor) }
    }
    companion object {
        val CHILD_EVENTS = RelayLeaseRecoveryEngine.CHILD_EVENTS
        fun binding(value: JsonElement?): RelayLeaseBinding? = value?.let { RelayRecoveryDecoder.binding(it.toString()) }
        private fun fail(): Nothing = throw HermesChatProtocolException("Invalid Mercury lease recovery frame")
        private inline fun <T> protocol(block: () -> T): T = try { block() } catch (_: IllegalArgumentException) { fail() }
    }
}
