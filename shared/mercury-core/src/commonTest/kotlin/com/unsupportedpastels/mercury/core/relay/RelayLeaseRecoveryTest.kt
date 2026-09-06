package com.unsupportedpastels.mercury.core.relay

import kotlinx.serialization.json.*
import kotlin.test.*

/** Callable on every target; host tests supply the one resource corpus. */
object RelayRecoveryCorpus {
    fun verify(raw: String) {
        val corpus = Json.parseToJsonElement(raw).jsonObject
        assertEquals(1, corpus["schema_version"]!!.jsonPrimitive.int)
        for (entry in corpus["cases"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val engine = RelayLeaseRecoveryEngine(c["profile"]!!.jsonPrimitive.content)
            var delivered = 0
            val error = runCatching {
                engine.initialize(c["attached"].toString())
                for (frame in c["frames"]!!.jsonArray) {
                    val result = engine.receive(frame.toString()) ?: continue
                    delivered++
                    val params = Json.parseToJsonElement(result).jsonObject["params"] as? JsonObject
                    engine.acknowledge((params?.get("relay_event_id") as? JsonPrimitive)?.content)
                }
            }.exceptionOrNull()
            assertEquals(c["expectedFailure"]!!.jsonPrimitive.boolean, error != null, name)
            if (error == null) {
                assertEquals(c["expectedDelivered"]!!.jsonPrimitive.int, delivered, name)
                assertEquals(c["expectedCursor"]!!.jsonPrimitive.long, engine.acknowledgedCursor, name)
                assertEquals(c["expectedReplayComplete"]!!.jsonPrimitive.boolean, engine.replayComplete, name)
            } else assertTrue(error is IllegalArgumentException, name)
        }
        for (entry in corpus["taskCases"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val snapshot = RelayLeaseRecoveryEngine(c["profile"]!!.jsonPrimitive.content).initialize(c["attached"].toString())
            val state = BackgroundTasks().recoverRelayTasks(snapshot, c["durableId"]!!.jsonPrimitive.content,
                c["profile"]!!.jsonPrimitive.content, c["runtime"]!!.jsonPrimitive.content)
            assertEquals(c["expectedRows"]!!.jsonPrimitive.int, state.rows.size, name)
            state.rows.singleOrNull()?.let {
                assertEquals(c["expectedStatus"]!!.jsonPrimitive.content, it.status.name, name)
                assertEquals(c["expectedAge"]!!.jsonPrimitive.long, it.observedAtMillis, name)
            }
            assertEquals(c["expectedActiveCount"]!!.jsonPrimitive.int, state.activeCount(999000), name)
        }
    }
}

class RelayLeaseRecoveryTest {
    private fun attached(last: Int = 2, gap: Boolean = false) =
        """{"method":"relay.lease.attached","params":{"recovery_version":1,"lease_id":"lease","last_seq":$last,"resume_cursor":0,"replay_gap":$gap}}"""
    private val child = """{"method":"event","params":{"type":"subagent.start","session_id":"runtime","payload":{"subagent_id":"child"}}}"""
    private val reply = """{"jsonrpc":"2.0","id":1,"result":{}}"""
    private fun frame(seq: Int, text: String) = buildJsonObject {
        put("method", "relay.lease.frame")
        putJsonObject("params") { put("lease_id", "lease"); put("seq", seq); put("replay", true); put("frame", text) }
    }.toString()

    @Test fun retainedOwnershipRequiresExactLiveDurableAndProfileBinding() {
        val snapshot = RelayLeaseSnapshot("lease", 1, false, false, listOf(
            RelayLeaseBinding("runtime", "session", "default", true),
            RelayLeaseBinding("historical", "old", "default", false),
        ), emptyList(), false)
        assertTrue(snapshot.hasLiveBinding("session", "default"))
        assertFalse(snapshot.hasLiveBinding("session", "foreign"))
        assertFalse(snapshot.hasLiveBinding("foreign", "default"))
        assertFalse(snapshot.hasLiveBinding("old", "default"))
    }

    @Test fun acknowledgementFencesUnappliedChildrenAndReplayedReplies() {
        val engine = RelayLeaseRecoveryEngine("default")
        engine.initialize(attached())
        assertNotNull(engine.receive(frame(1, child)))
        assertNull(engine.receive(frame(2, reply)))
        assertEquals(0L, engine.acknowledgedCursor)
        engine.acknowledge("foreign:1")
        assertEquals(0L, engine.acknowledgedCursor)
        engine.acknowledge("lease:1")
        assertEquals(2L, engine.acknowledgedCursor)
    }
    @Test fun checkpointGenerationAndLeaseFenceOldAcknowledgement() {
        val first = RelayLeaseCheckpointState().begin().bind(1, "old")
        val replacement = first.begin().bind(2, "new")
        assertEquals(replacement, replacement.applied(1, "old", 99))
        assertEquals(replacement, replacement.applied(2, "old", 99))
        assertEquals(1L, replacement.applied(2, "new", 1).cursor)
        assertEquals(0L, RelayLeaseCheckpointState().cursor)
    }
    @Test fun gapsDrainAcknowledgedFramesButNeverAdvanceCursor() {
        val engine = RelayLeaseRecoveryEngine("default")
        engine.initialize(attached(5001, true))
        for (seq in 2..5001) assertNull(engine.receive(frame(seq, reply)))
        assertEquals(0L, engine.acknowledgedCursor)
    }
    @Test fun pendingChildBoundFailsClosed() {
        val engine = RelayLeaseRecoveryEngine("default")
        engine.initialize(attached(4097))
        for (seq in 1..4096) assertNotNull(engine.receive(frame(seq, child)))
        assertFailsWith<IllegalArgumentException> { engine.receive(frame(4097, child)) }
    }
    @Test fun historicalReplayNeverRefreshesSnapshotAgeOrTerminalTime() {
        val start = RelayRecoveryDecoder.backgroundTaskEvent("subagent.start", "runtime", """{"subagent_id":"child"}""")!!
        val original = BackgroundTasks().reduce(start, "runtime", 100)
        val replay = original.reduce(start.copy(historical = true, eventId = "lease:1"), "runtime", 999000)
        assertEquals(100L, replay.rows.single().observedAtMillis)
        assertFalse(replay.rows.single().available)
        val completed = replay.reduce(start.copy(kind = BackgroundTaskEventKind.Complete,
            terminalStatus = BackgroundTaskStatus.Finished, historical = true, eventId = "lease:2"), "runtime", 999000)
        assertEquals(0L, completed.rows.single().observedAtMillis)
        assertTrue(completed.rows.single().terminal)
    }
}
