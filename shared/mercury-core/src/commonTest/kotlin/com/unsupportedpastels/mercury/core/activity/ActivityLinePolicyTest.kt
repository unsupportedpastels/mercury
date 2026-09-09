package com.unsupportedpastels.mercury.core.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityLinePolicyTest {
    private val idle = ActivityLineInput(false, false, "idle", false, false, false,
        emptyList(), null, null, null, false, false, null, 0)
    private fun working(label: String) = ActivityLineState(ActivityLineKind.Working, label, true, true)

    @Test fun priorityAndFlags() {
        var input = idle.copy(isSending = true, isStopping = true, awaitingUser = true,
            connectionLost = true, connectionPhase = "connecting", pendingSubmission = true, activeChildCount = 2)
        fun expect(kind: ActivityLineKind, label: String, animated: Boolean, timer: Boolean = false) {
            assertEquals(ActivityLineState(kind, label, animated, timer), ActivityLinePolicy.decide(input))
        }
        expect(ActivityLineKind.NeedsYou, "Needs you", false)
        input = input.copy(awaitingUser = false)
        expect(ActivityLineKind.Stopping, "Stopping", false)
        input = input.copy(isStopping = false)
        expect(ActivityLineKind.ConnectionLost, "Connection lost", false)
        input = input.copy(connectionLost = false)
        expect(ActivityLineKind.Connecting, "Connecting", false)
        input = input.copy(connectionPhase = "reconnecting")
        expect(ActivityLineKind.Reconnecting, "Reconnecting", false)
        input = input.copy(connectionPhase = "submitting", pendingSubmission = false)
        expect(ActivityLineKind.Sending, "Sending", true)
        input = input.copy(connectionPhase = "idle", pendingSubmission = true)
        expect(ActivityLineKind.Sending, "Sending", true)
        input = input.copy(pendingSubmission = false)
        expect(ActivityLineKind.Working, "Thinking", true, true)
        input = input.copy(isSending = false, connectionLost = true)
        expect(ActivityLineKind.ConnectionLost, "Connection lost", false)
        input = input.copy(connectionLost = false)
        expect(ActivityLineKind.Background, "2 background tasks", true)
        input = input.copy(activeChildCount = 1)
        expect(ActivityLineKind.Background, "1 background task", true)
        input = input.copy(activeChildCount = 0)
        expect(ActivityLineKind.Hidden, "", false)
    }

    @Test fun workingLabelRanksAndBounds() {
        var input = idle.copy(isSending = true, runningToolNames = listOf("terminal", "patch"),
            runningToolContext = "  " + "x".repeat(60) + "  ", statusKind = "tool.generating",
            statusText = "Generating read_file arguments…", streamingAnswer = true,
            inProgressTodo = "Milestone", streamingReasoning = true)
        fun label(value: String) = assertEquals(working(value), ActivityLinePolicy.decide(input))
        label("Running · " + "x".repeat(48))
        input = input.copy(runningToolContext = "  ")
        label("Running")
        input = input.copy(runningToolNames = emptyList())
        label("Writing")
        input = input.copy(streamingAnswer = false)
        label("Exploring")
        input = input.copy(statusText = "Malformed")
        label("Working")
        input = input.copy(statusKind = "status.update", statusText = "  " + "s".repeat(90) + "  ")
        label("s".repeat(80))
        input = input.copy(statusText = " ", streamingAnswer = true)
        label("Writing")
        input = input.copy(streamingAnswer = false, inProgressTodo = "t".repeat(90))
        label("t".repeat(80))
        input = input.copy(inProgressTodo = " ")
        label("Thinking")
        input = input.copy(streamingReasoning = false)
        label("Thinking")
    }

    @Test fun localPendingAndReconnectAreNotProofOfServerWork() {
        assertEquals(false, ActivityLinePolicy.decide(idle.copy(pendingSubmission = true)).animated)
        assertEquals(false, ActivityLinePolicy.decide(idle.copy(isSending = true, connectionPhase = "reconnecting")).animated)
        assertEquals(true, ActivityLinePolicy.decide(idle.copy(isSending = true, connectionPhase = "submitting")).animated)
        assertEquals("Writing", ActivityLinePolicy.decide(idle.copy(isSending = true, streamingAnswer = true,
            statusText = "Old status", statusKind = "status.update")).label)
    }

    @Test fun verbsAreCaseInsensitiveAndCategorized() {
        mapOf(
            "Delegating" to listOf("delegate", "delegate_task", "spawn", "subagent"),
            "Editing" to listOf("write_file", "patch", "edit", "apply_patch", "create_file"),
            "Exploring" to listOf("read_file", "search_files", "glob", "grep", "list", "ls", "find", "web_search", "web_extract", "fetch", "browser"),
            "Running" to listOf("terminal", "shell", "bash", "execute_code", "process", "run"),
            "Planning" to listOf("todo", "todo_list"),
        ).forEach { (verb, names) -> names.forEach { assertEquals(verb, ActivityLinePolicy.toolPresentVerb(it.uppercase())) } }
        assertEquals("Exploring", ActivityLinePolicy.toolPresentVerb("mcp__web_search"))
        assertEquals("Using custom", ActivityLinePolicy.toolPresentVerb("custom"))
    }

    @Test fun elapsedBoundaries() {
        mapOf(-1L to "0s", 0L to "0s", 12L to "12s", 59L to "59s", 60L to "1:00",
            65L to "1:05", 3599L to "59:59", 3600L to "1:00:00", 3723L to "1:02:03")
            .forEach { (seconds, expected) -> assertEquals(expected, ActivityLinePolicy.formatElapsed(seconds)) }
    }

    @Test fun holdQuietWindowAndCandidateReset() {
        val first = working("Thinking")
        val next = working("Running")
        var hold = ActivityLineHold.step(ActivityLineHoldState(null, null, 0), first, 0)
        assertEquals(first, hold.shown)
        hold = ActivityLineHold.step(hold, next, 100)
        assertEquals(first, hold.shown)
        assertEquals(next, hold.candidate)
        hold = ActivityLineHold.step(hold, next, 1299)
        assertEquals(first, hold.shown)
        hold = ActivityLineHold.step(hold, next, 1300)
        assertEquals(next, hold.shown)
        assertNull(hold.candidate)
        hold = ActivityLineHold.step(hold, first, 1400)
        hold = ActivityLineHold.step(hold, working("Editing"), 1500)
        assertEquals(1500, hold.candidateSinceMillis)
        hold = ActivityLineHold.step(hold, next, 1600)
        assertNull(hold.candidate)
        assertEquals(next, hold.shown)
    }

    @Test fun kindChangesAndHiddenAdoptImmediately() {
        val shown = ActivityLineHoldState(working("Thinking"), working("Running"), 100)
        for (kind in ActivityLineKind.entries.filter { it != ActivityLineKind.Working }) {
            val candidate = ActivityLineState(kind, kind.name, false, false)
            assertEquals(candidate, ActivityLineHold.step(shown, candidate, 101).shown)
            assertEquals(candidate.copy(label = "New"), ActivityLineHold.step(
                ActivityLineHoldState(candidate, null, 0), candidate.copy(label = "New"), 102).shown)
        }
    }
}
