package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.mercury.core.progress.ProgressObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reduction itself is proven in `shared/mercury-core`; this covers only the
 * Android event-model mapping that feeds it.
 */
class DurableProgressTest {
    private val runtime = RuntimeSessionId("runtime")

    @Test fun toolEventsCarryReplayAndSnapshotFieldsIntoTheSharedAlphabet() {
        val snapshot = DurableProgress(hasMilestoneSnapshot = true, revision = 4)
        val started = HermesChatEvent.ToolStart(runtime, "call-1", "terminal", "ls", historical = true)
            .toProgressObservation()
        assertEquals(ProgressObservation.ToolStarted("call-1", "terminal", "ls", historical = true), started)
        val completed = HermesChatEvent.ToolComplete(runtime, "call-1", "terminal", "exit 0", progressSnapshot = snapshot)
            .toProgressObservation()
        assertEquals(ProgressObservation.ToolCompleted("call-1", "terminal", "exit 0", snapshot), completed)
    }

    @Test fun blockingAndStatusEventsAreLivenessWhileUnrelatedEventsAreIgnored() {
        val liveness = listOf(
            HermesChatEvent.StatusUpdate(runtime, "thinking", "Working"),
            HermesChatEvent.ClarifyRequest(runtime, "req", "Which?", listOf("a"), false),
            HermesChatEvent.ApprovalRequest(runtime, "req", "rm", null, listOf("Allow")),
        )
        assertTrue(liveness.all { it.toProgressObservation() == ProgressObservation.Liveness })
        assertEquals(
            ProgressObservation.Unrelated,
            HermesChatEvent.MessageDelta(runtime, "partial answer").toProgressObservation(),
        )
    }

    @Test fun androidEventsReachTheSharedReductionThroughTheExtension() {
        val observed = DurableProgress()
            .observe(HermesChatEvent.ToolComplete(runtime, "call-1", "terminal", "exit 0"), 2000)
        assertEquals(2000L, observed.lastObservedAtEpochMillis)
        assertEquals("terminal", observed.evidence.single().toolName)
        val replayed = observed.observe(
            HermesChatEvent.ToolStart(runtime, "call-2", "read_file", null, historical = true),
            9000,
        )
        assertEquals(observed, replayed)
    }
}
