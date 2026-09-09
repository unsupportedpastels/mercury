package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.app.ApprovalInteraction
import com.unsupportedpastels.hermesandroid.app.ClarificationInteraction
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.RunStatus
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.hermesandroid.app.RunTodoStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProgressPolicyTest {
    @Test
    fun observationAgeNeverInventsFreshnessAndHandlesClockSkew() {
        assertEquals("", progressObservationLabel(null, 10_000, false))
        assertEquals("Saved", progressObservationLabel(null, 10_000, true))
        assertEquals("Saved · 0s ago", progressObservationLabel(10_000, 10_000, true))
        assertEquals("10s ago", progressObservationLabel(0, 10_000, false))
        assertEquals("2m ago", progressObservationLabel(0, 120_000, false))
        assertEquals("Saved · 2h ago", progressObservationLabel(0, 7_200_000, true))
        assertEquals("Saved", progressObservationLabel(Long.MAX_VALUE, 0, true))
        assertEquals("", progressObservationLabel(Long.MAX_VALUE, 0, false))
        assertEquals("", progressObservationLabel(Long.MIN_VALUE, Long.MAX_VALUE, false))
        assertEquals("2562047788015h ago", progressObservationLabel(0, Long.MAX_VALUE, false))
    }


    @Test
    fun emptyRunStateHasNoContentAndNoInventedStep() {
        val summary = SessionProgressPolicy.summarize(RunEventState(), isSending = false)
        assertFalse(summary.hasContent)
        assertNull(summary.currentStep)
        assertTrue(summary.completed.isEmpty())
        assertTrue(summary.inProgress.isEmpty())
        assertTrue(summary.blocked.isEmpty())
        assertTrue(summary.remaining.isEmpty())
    }

    @Test
    fun todosSplitIntoCompletedInProgressAndRemaining() {
        val state = RunEventState(
            todos = listOf(
                RunTodoItem("1", "Reconnect fix", RunTodoStatus.Completed),
                RunTodoItem("2", "Verify iOS", RunTodoStatus.InProgress),
                RunTodoItem("3", "Install on phone", RunTodoStatus.Pending),
                RunTodoItem("4", "Old idea", RunTodoStatus.Cancelled),
            ),
        )
        val summary = SessionProgressPolicy.summarize(state, isSending = true)
        assertEquals(listOf("Reconnect fix"), summary.completed.map(SessionProgressItem::label))
        assertEquals(listOf("Verify iOS"), summary.inProgress.map(SessionProgressItem::label))
        assertEquals(listOf("Install on phone"), summary.remaining.map(SessionProgressItem::label))
        // Cancelled work is neither remaining nor completed.
        assertTrue(summary.completed.none { it.label == "Old idea" })
        assertTrue(summary.remaining.none { it.label == "Old idea" })
        assertEquals("Verify iOS", summary.currentStep)
    }

    @Test
    fun toolRowsAreNotListedAsMilestones() {
        // Tool executions already have the tool group / activity stack. The
        // progress sections list only plan-level items, so completed tools
        // like `terminal` never pad the Reported done list.
        val state = RunEventState(
            todos = listOf(RunTodoItem("1", "Reconnect fix", RunTodoStatus.Completed)),
            tools = listOf(
                RunToolRow("t1", "terminal", summary = "989 tests passed", state = RunToolState.Completed),
                RunToolRow("t2", "vision_analyze", state = RunToolState.Completed),
                RunToolRow("t3", "gradle build", context = "assembleDebug", state = RunToolState.Running),
            ),
        )
        val summary = SessionProgressPolicy.summarize(state, isSending = true)
        assertEquals(listOf("Reconnect fix"), summary.completed.map(SessionProgressItem::label))
        assertTrue(summary.inProgress.none { it.label == "gradle build" })
        // A running tool may still name the current step when nothing else does.
        assertEquals("gradle build", summary.currentStep)
    }

    @Test
    fun toolOnlyStateHasNoMilestoneSections() {
        val state = RunEventState(
            tools = listOf(
                RunToolRow("t1", "terminal", summary = "989 tests passed", state = RunToolState.Completed),
                RunToolRow("t2", "read_file", state = RunToolState.Completed),
            ),
        )
        val summary = SessionProgressPolicy.summarize(state, isSending = false)
        assertTrue(summary.completed.isEmpty())
        assertFalse(summary.hasContent)
    }

    @Test
    fun runningToolAndStatusDriveCurrentStepWithStatusWinning() {
        val state = RunEventState(
            tools = listOf(RunToolRow("t1", "gradle build", context = "assembleDebug", state = RunToolState.Running)),
            status = RunStatus("status.update", "Verifying iOS"),
        )
        val summary = SessionProgressPolicy.summarize(state, isSending = true)
        assertEquals("Verifying iOS", summary.currentStep)
        // The running tool names the step but is not itself a milestone row.
        assertTrue(summary.inProgress.isEmpty())
    }

    @Test
    fun pendingClarificationIsBlockedAndOutranksOtherSteps() {
        val state = RunEventState(
            status = RunStatus("status.update", "Working"),
            clarification = ClarificationInteraction(
                runtimeSessionId = RuntimeSessionId("rt"),
                requestId = "r1",
                question = "Which keystore?",
                choices = emptyList(),
                multiSelect = false,
            ),
        )
        val summary = SessionProgressPolicy.summarize(state, isSending = true)
        assertEquals("Needs you", summary.currentStep)
        assertEquals("Which keystore?", summary.blocked.single().detail)
    }

    @Test
    fun resolvedInteractionIsNotBlocked() {
        val state = RunEventState(
            approval = ApprovalInteraction(
                runtimeSessionId = RuntimeSessionId("rt"),
                requestId = "r1",
                commandPreview = "rm -rf build",
                descriptionPreview = null,
                choices = emptyList(),
                lifecycle = RunInteractionLifecycle.Resolved,
            ),
        )
        val summary = SessionProgressPolicy.summarize(state, isSending = true)
        assertTrue(summary.blocked.isEmpty())
    }

    @Test
    fun idleWithNoActivityEvidenceDoesNotClaimAStepFromSendingAlone() {
        // isSending without any observed activity must not invent a named step.
        val summary = SessionProgressPolicy.summarize(RunEventState(), isSending = true)
        assertNull(summary.currentStep)
        assertTrue(summary.active)
    }

    @Test
    fun stripHeadlineSeparatesActiveBlockedAndSettledStates() {
        val blocked = SessionProgressPolicy.summarize(
            RunEventState(
                clarification = ClarificationInteraction(
                    RuntimeSessionId("rt"), "r1", "Q?", emptyList(), false,
                ),
            ),
            isSending = true,
        )
        assertEquals("Needs you", blocked.headline)

        val active = SessionProgressPolicy.summarize(
            RunEventState(status = RunStatus("s", "Verifying iOS")),
            isSending = true,
        )
        assertEquals("Verifying iOS", active.headline)

        val settled = SessionProgressPolicy.summarize(
            RunEventState(
                todos = listOf(
                    RunTodoItem("1", "Done thing", RunTodoStatus.Completed),
                    RunTodoItem("2", "Later thing", RunTodoStatus.Pending),
                ),
            ),
            isSending = false,
        )
        assertEquals("1/2 done", settled.headline)
    }
}
