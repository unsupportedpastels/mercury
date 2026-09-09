package com.unsupportedpastels.mercury.core.relay

import kotlinx.serialization.json.*
import kotlin.test.*

class RelayTaskRecoveryTest {
    private fun snapshot(profile: String = "default", durable: String = "session", live: Boolean = false,
        kind: String = "subagent.complete", status: String = "completed", revision: Long = 1) = RelayLeaseSnapshot(
        "lease", 8, true, !live, emptyList(), listOf("""{
            "jsonrpc":"2.0","method":"event","params":{"session_id":"old-runtime","type":"$kind",
            "payload":{"subagent_id":"child","goal":"Bounded task","status":"$status"},"recovery_revision":$revision,
            "recovery_binding":{"runtime_session_id":"old-runtime","durable_session_id":"$durable","profile":"$profile","live":$live}}
        }"""), false)

    @Test fun processRelaunchImportsExplicitTerminalWithOwnedDurableScope() {
        val state = BackgroundTasks().recoverRelayTasks(snapshot(), "session", "default", ("new-runtime"))
        assertEquals(1, state.rows.size)
        assertEquals(BackgroundTaskStatus.Finished, state.rows.single().status)
        assertEquals(("new-runtime"), state.rows.single().runtimeId)
        assertEquals(state, state.recoverRelayTasks(snapshot(), "session", "default", ("newer")))
    }

    @Test fun profileSessionAndUnboundEvidenceAreRejected() {
        assertTrue(BackgroundTasks().recoverRelayTasks(snapshot(profile = "foreign"), "session", "default").rows.isEmpty())
        assertTrue(BackgroundTasks().recoverRelayTasks(snapshot(durable = "foreign"), "session", "default").rows.isEmpty())
        val s = snapshot()
        val row = Json.parseToJsonElement(s.tasks.single()).jsonObject
        val params = row["params"] as JsonObject
        val unbound = s.copy(tasks = listOf(JsonObject(row + ("params" to JsonObject(params - "recovery_binding"))).toString()))
        assertTrue(BackgroundTasks().recoverRelayTasks(unbound, "session", "default").rows.isEmpty())
    }

    @Test fun historicalNonterminalCannotClaimActiveOrFinished() {
        val state = BackgroundTasks().recoverRelayTasks(snapshot(kind = "subagent.start", status = "running"), "session", "default")
        assertEquals(BackgroundTaskStatus.Unknown, state.rows.single().status)
        assertEquals(0, state.activeCount(1))
        assertFalse(state.rows.single().terminal)
    }

    @Test fun emptyOrTruncatedSnapshotNeverFinishesMissingChildren() {
        val runtime = ("old-runtime")
        val existing = BackgroundTasks().reduce(BackgroundTaskEvent(runtime, BackgroundTaskEventKind.Start, "child", "Task", null), runtime, 100)
        val result = existing.recoverRelayTasks(snapshot().copy(tasks = emptyList(), truncated = true), "session", "default")
        assertEquals(1, result.rows.size)
        assertFalse(result.rows.single().terminal)
        assertFalse(result.rows.single().available)
        assertEquals(100L, result.rows.single().observedAtMillis)
    }

    @Test fun recoveredLiveEvidenceCountsActiveOnlyWhileTheHostRegistryConfirmsRunning() {
        // Cold reopen mid-delegation: the snapshot proves the child existed, not that it
        // still runs. The gateway's registry answering "running" is authoritative liveness.
        val recovered = BackgroundTasks().recoverRelayTasks(
            snapshot(live = true, kind = "subagent.start", status = "running"), "session", "default", "old-runtime")
        assertEquals(BackgroundTaskStatus.Active, recovered.rows.single().status)
        assertFalse(recovered.rows.single().available)
        assertEquals(0, recovered.activeCount(50_000))
        val confirmed = recovered.reconcile(listOf(BackgroundTaskRegistryEntry("child", "running")), "old-runtime", now = 50_000)
        assertEquals(1, confirmed.activeCount(50_001))
        assertEquals(0L, confirmed.rows.single().observedAtMillis, "registry polling never counts as worker activity")
        assertEquals("Active · host reports running", confirmed.rows.single().label(50_001))
        assertEquals(0, confirmed.activeCount(50_000 + BackgroundTaskRow.ACTIVITY_WINDOW_MILLIS), "one confirmation is one window")
        val later = confirmed.reconcile(listOf(BackgroundTaskRegistryEntry("child", "running")), "old-runtime", now = 200_000)
        assertEquals(1, later.activeCount(200_001))
        val gone = later.reconcile(listOf(BackgroundTaskRegistryEntry("child", "completed")), "old-runtime", now = 300_000)
        assertEquals(0, gone.activeCount(300_001), "a non-running registry status is not active and not terminal")
        assertFalse(gone.rows.single().terminal)
        assertEquals(confirmed, confirmed.reconcile(emptyList(), "old-runtime", now = 60_000), "registry silence changes nothing")
    }

    @Test fun liveChildEventAfterRegistryConfirmationKeepsTheConfirmationAndRefreshesActivity() {
        val recovered = BackgroundTasks().recoverRelayTasks(
            snapshot(live = true, kind = "subagent.start", status = "running"), "session", "default", "old-runtime")
            .reconcile(listOf(BackgroundTaskRegistryEntry("child", "running")), "old-runtime", now = 50_000)
        val observed = recovered.reduce(
            BackgroundTaskEvent("old-runtime", BackgroundTaskEventKind.Tool, "child", null, "execute_code", eventId = "lease:9"),
            "old-runtime", 80_000)
        assertEquals(80_000L, observed.rows.single().observedAtMillis)
        assertEquals(50_000L, observed.rows.single().registryConfirmedAtMillis)
        assertEquals(1, observed.activeCount(190_000))
        assertEquals(0, observed.unavailable().activeCount(80_001), "a dead connection still hides confirmed children")
    }
}
