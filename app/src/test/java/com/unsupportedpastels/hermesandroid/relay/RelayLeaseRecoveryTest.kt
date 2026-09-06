package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.HermesChatSocket
import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RelayLeaseRecoveryTest {
    private val child = """{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime","type":"subagent.complete","payload":{"subagent_id":"child","status":"completed"}}}"""
    private val response = """{"jsonrpc":"2.0","id":1,"result":{"accepted":true}}"""
    private fun attached(last: Int = 2, gap: Boolean = false, lease: String = "lease") =
        """{"jsonrpc":"2.0","method":"relay.lease.attached","params":{"recovery_version":1,"lease_id":"$lease","last_seq":$last,"resume_cursor":0,"replay_gap":$gap,"recovery_reset":false,"bindings":[],"task_snapshot":[]}}"""
    private fun frame(seq: Int, text: String, replay: Boolean = true, lease: String = "lease") = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "relay.lease.frame")
        put("params", buildJsonObject { put("lease_id", lease); put("seq", seq); put("replay", replay); put("frame", text) })
    }.toString()
    private fun watermark(last: Int = 2) = """{"jsonrpc":"2.0","method":"relay.lease.replay_complete","params":{"lease_id":"lease","last_seq":$last}}"""
    private class Socket(vararg frames: String) : HermesChatSocket {
        val frames = ArrayDeque(frames.toList())
        override suspend fun receiveText(): String? = frames.removeFirstOrNull()
        override suspend fun sendText(text: String) {}
        override suspend fun close() {}
    }

    @Test fun snapshotThenWrappedReplayDoesNotInventFreshActivity() = runTest {
        val runtime = com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId("runtime")
        val progress = """{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime","type":"subagent.progress","payload":{"subagent_id":"child","text":"Recorded work"},"recovery_revision":1,"recovery_binding":{"runtime_session_id":"runtime","durable_session_id":"session","profile":"default","live":true}}}"""
        val preamble = Json.parseToJsonElement(attached(1)).jsonObject
        val preambleParams = preamble["params"]!!.jsonObject
        val withSnapshot = JsonObject(preamble + ("params" to JsonObject(preambleParams +
            ("task_snapshot" to JsonArray(listOf(Json.parseToJsonElement(progress)))))))
        val socket = RelayLeaseRecoverySocket(Socket(withSnapshot.toString(), frame(1, progress)), "default", RelayLeaseCheckpoint())
        socket.initialize()
        val initial = com.unsupportedpastels.hermesandroid.gateway.BackgroundTasks()
            .recoverRelayTasks(socket.snapshot!!, "session", "default", runtime)
        val connection = com.unsupportedpastels.hermesandroid.gateway.HermesChatConnection(socket, 1_000_000, backgroundScope)
        val events = mutableListOf<com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent>()
        connection.events.collect { events += it }
        val replayed = initial.reduce(events.single(), runtime, 999_000L)
        assertEquals("snapshot and retained event have different IDs but neither is fresh", 0L, replayed.rows.single().observedAtMillis)
        assertFalse(replayed.rows.single().available)
        assertEquals(0, replayed.activeCount(999_000L))
        connection.close()
    }

    @Test fun historicalRpcCannotResolveNewRequestAndChildAckFencesCursor() = runTest {
        val checkpoint = RelayLeaseCheckpoint()
        val socket = RelayLeaseRecoverySocket(Socket(attached(), frame(1, child), frame(2, response), watermark(), response), "default", checkpoint)
        socket.initialize()
        assertEquals(0L, checkpoint.cursor) // preamble last_seq is NOT consumed
        val event = Json.parseToJsonElement(socket.receiveText()!!).jsonObject
        assertEquals("lease:1", event["params"]!!.jsonObject["relay_event_id"]!!.jsonPrimitive.content)
        assertEquals(response, socket.receiveText()) // only the new, unretained reply
        assertTrue(socket.replayComplete)
        assertEquals(0L, checkpoint.cursor) // seq2 cannot leap over unapplied seq1
        socket.acknowledge("lease:1")
        assertEquals(2L, checkpoint.cursor)
    }

    @Test fun duplicateReplayChildDoesNotReachReducerTwice() = runTest {
        val checkpoint = RelayLeaseCheckpoint()
        val socket = RelayLeaseRecoverySocket(Socket(attached(1), frame(1, child), frame(1, child), watermark(1)), "default", checkpoint)
        socket.initialize()
        assertNotNull(socket.receiveText())
        assertNull(socket.receiveText())
        socket.acknowledge("lease:1")
        assertEquals(1L, checkpoint.cursor)
    }

    @Test fun checkpointGenerationAndLeaseScopeRejectStaleAcknowledgement() = runTest {
        val checkpoint = RelayLeaseCheckpoint()
        val old = RelayLeaseRecoverySocket(Socket(attached(1), frame(1, child)), "default", checkpoint)
        old.initialize(); old.receiveText()
        val current = RelayLeaseRecoverySocket(Socket(attached(0, lease = "new")), "default", checkpoint)
        current.initialize()
        old.acknowledge("lease:1")
        assertEquals("new", checkpoint.leaseId)
        assertEquals(0L, checkpoint.cursor)
        assertEquals(0L, RelayLeaseCheckpoint().cursor) // process relaunch has no orphan high cursor
    }

    @Test fun leaseMismatchFailsClosed() = runTest {
        val socket = RelayLeaseRecoverySocket(Socket(attached(1), frame(1, child, lease = "foreign")), "default", RelayLeaseCheckpoint())
        socket.initialize()
        assertTrue(runCatching { socket.receiveText() }.exceptionOrNull() is HermesChatProtocolException)
    }

    @Test fun gapNeverSilentlyAdvancesCursorAndDoesNotAccumulateAcknowledgedFrames() = runTest {
        val frames = mutableListOf(attached(5001, gap = true))
        frames += (2..5001).map { frame(it, response) }
        frames += watermark(5001)
        val checkpoint = RelayLeaseCheckpoint()
        val socket = RelayLeaseRecoverySocket(Socket(*frames.toTypedArray()), "default", checkpoint)
        socket.initialize()
        assertNull(socket.receiveText())
        assertTrue(socket.snapshot!!.gap)
        assertEquals(0L, checkpoint.cursor)
    }
}
