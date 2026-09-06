package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RelayTaskRecoveryTest {
    private fun snapshot(profile: String = "default", durable: String = "session", live: Boolean = false,
        kind: String = "subagent.complete", status: String = "completed", revision: Long = 1) = RelayLeaseSnapshot(
        "lease", 8, true, !live, emptyList(), listOf(Json.parseToJsonElement("""{
            "jsonrpc":"2.0","method":"event","params":{"session_id":"old-runtime","type":"$kind",
            "payload":{"subagent_id":"child","goal":"Bounded task","status":"$status"},"recovery_revision":$revision,
            "recovery_binding":{"runtime_session_id":"old-runtime","durable_session_id":"$durable","profile":"$profile","live":$live}}
        }""") as JsonObject), false)

    @Test fun processRelaunchImportsExplicitTerminalWithOwnedDurableScope() {
        val state = BackgroundTasks().recoverRelayTasks(snapshot(), "session", "default", RuntimeSessionId("new-runtime"))
        assertEquals(1, state.rows.size)
        assertEquals(BackgroundTaskStatus.Finished, state.rows.single().status)
        assertEquals(RuntimeSessionId("new-runtime"), state.rows.single().runtimeId)
        assertEquals(state, state.recoverRelayTasks(snapshot(), "session", "default", RuntimeSessionId("newer")))
    }

    @Test fun profileSessionAndUnboundEvidenceAreRejected() {
        assertTrue(BackgroundTasks().recoverRelayTasks(snapshot(profile = "foreign"), "session", "default").rows.isEmpty())
        assertTrue(BackgroundTasks().recoverRelayTasks(snapshot(durable = "foreign"), "session", "default").rows.isEmpty())
        val s = snapshot()
        val row = s.tasks.single()
        val params = row["params"] as JsonObject
        val unbound = s.copy(tasks = listOf(JsonObject(row + ("params" to JsonObject(params - "recovery_binding")))))
        assertTrue(BackgroundTasks().recoverRelayTasks(unbound, "session", "default").rows.isEmpty())
    }

    @Test fun historicalNonterminalCannotClaimActiveOrFinished() {
        val state = BackgroundTasks().recoverRelayTasks(snapshot(kind = "subagent.start", status = "running"), "session", "default")
        assertEquals(BackgroundTaskStatus.Unknown, state.rows.single().status)
        assertEquals(0, state.activeCount(1))
        assertFalse(state.rows.single().terminal)
    }

    @Test fun emptyOrTruncatedSnapshotNeverFinishesMissingChildren() {
        val runtime = RuntimeSessionId("old-runtime")
        val existing = BackgroundTasks().reduce(BackgroundTaskEvent(runtime, BackgroundTaskEventKind.Start, "child", "Task", null), runtime, 100)
        val result = existing.recoverRelayTasks(snapshot().copy(tasks = emptyList(), truncated = true), "session", "default")
        assertEquals(1, result.rows.size)
        assertFalse(result.rows.single().terminal)
        assertFalse(result.rows.single().available)
        assertEquals(100L, result.rows.single().observedAtMillis)
    }
}
