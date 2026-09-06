package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Same canonical synthetic file as Swift; exercise real native socket/task adapters. */
class RelayRecoveryCorpusTest {
    private fun corpus() = checkNotNull(javaClass.classLoader!!.getResourceAsStream("adapter-parity/relay-recovery.json"))
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private class Socket(frames: List<String>) : HermesChatSocket {
        private val frames = ArrayDeque(frames)
        override suspend fun receiveText(): String? = frames.removeFirstOrNull()
        override suspend fun sendText(text: String) = Unit
        override suspend fun close() = Unit
    }
    @Test fun leaseCorpusThroughNativeSocket() = runTest {
        val corpus = corpus()
        assertEquals(1, corpus["schema_version"]!!.jsonPrimitive.int)
        for (entry in corpus["cases"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val checkpoint = RelayLeaseCheckpoint()
            val socket = RelayLeaseRecoverySocket(Socket(listOf(c["attached"].toString()) +
                c["frames"]!!.jsonArray.map { it.toString() }), c["profile"]!!.jsonPrimitive.content, checkpoint)
            var delivered = 0
            val error = runCatching {
                socket.initialize()
                while (true) {
                    val raw = socket.receiveText() ?: break
                    delivered++
                    val params = Json.parseToJsonElement(raw).jsonObject["params"] as? JsonObject
                    socket.acknowledge((params?.get("relay_event_id") as? JsonPrimitive)?.content)
                }
            }.exceptionOrNull()
            assertEquals(name, c["expectedFailure"]!!.jsonPrimitive.boolean, error != null)
            if (error != null) assertTrue(name, error is HermesChatProtocolException)
            else {
                assertEquals(name, c["expectedDelivered"]!!.jsonPrimitive.int, delivered)
                assertEquals(name, c["expectedCursor"]!!.jsonPrimitive.long, checkpoint.cursor)
                assertEquals(name, c["expectedReplayComplete"]!!.jsonPrimitive.boolean, socket.replayComplete)
            }
        }
    }
    @Test fun snapshotCorpusThroughNativeReducer() = runTest {
        for (entry in corpus()["taskCases"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val socket = RelayLeaseRecoverySocket(Socket(listOf(c["attached"].toString())),
                c["profile"]!!.jsonPrimitive.content, RelayLeaseCheckpoint())
            socket.initialize()
            val state = BackgroundTasks().recoverRelayTasks(socket.snapshot!!,
                c["durableId"]!!.jsonPrimitive.content, c["profile"]!!.jsonPrimitive.content,
                RuntimeSessionId(c["runtime"]!!.jsonPrimitive.content))
            assertEquals(name, c["expectedRows"]!!.jsonPrimitive.int, state.rows.size)
            state.rows.singleOrNull()?.let {
                assertEquals(name, c["expectedStatus"]!!.jsonPrimitive.content, it.status.name)
                assertEquals(name, c["expectedAge"]!!.jsonPrimitive.long, it.observedAtMillis)
            }
            assertEquals(name, c["expectedActiveCount"]!!.jsonPrimitive.int, state.activeCount(999000))
        }
    }
}
