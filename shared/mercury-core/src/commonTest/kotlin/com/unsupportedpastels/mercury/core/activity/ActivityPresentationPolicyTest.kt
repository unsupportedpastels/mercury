package com.unsupportedpastels.mercury.core.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityPresentationPolicyTest {
    @Test
    fun processOnlyRowsAreNeutralAndTheirLastReportedStatesAreCounted() {
        val decision = ActivityPresentationPolicy.decide(
            assistantActivityPresent = false,
            turnActive = false,
            toolCount = 0,
            runningToolCount = 0,
            completedTodoCount = 0,
            todoCount = 0,
            activeTodoCount = 0,
            loopCount = 0,
            activeLoopCount = 0,
            processStatuses = listOf("running") + List(7) { "exited" },
        )

        assertTrue(decision.activityPresent)
        assertFalse(decision.assistantActive)
        assertTrue(decision.processOnly)
        assertEquals(ActivityIndicator.ProcessesOnly, decision.indicator)
        assertEquals(1, decision.processRunningCount)
        assertEquals(7, decision.processExitedCount)
        assertEquals(0, decision.processUnknownCount)
        assertEquals(
            "Processes · last reported: 1 running · 7 exited",
            decision.summary,
        )
    }

    @Test
    fun processRowsNeverTurnARealAssistantSurfaceIntoProcessOnlyOrChangeItsActivity() {
        val decision = ActivityPresentationPolicy.decide(
            assistantActivityPresent = true,
            turnActive = false,
            toolCount = 2,
            runningToolCount = 1,
            completedTodoCount = 1,
            todoCount = 2,
            activeTodoCount = 0,
            loopCount = 0,
            activeLoopCount = 0,
            processStatuses = listOf("running", "exited", "future-state"),
        )

        assertTrue(decision.activityPresent)
        assertTrue(decision.assistantActive)
        assertFalse(decision.processOnly)
        assertEquals(ActivityIndicator.ActiveWork, decision.indicator)
        assertEquals(1, decision.processRunningCount)
        assertEquals(1, decision.processExitedCount)
        assertEquals(1, decision.processUnknownCount)
        assertEquals(
            "Activity · 2 tools · 1/2 tasks · Processes · last reported: 1 running · 1 exited · 1 unknown",
            decision.summary,
        )
    }

    @Test
    fun noAssistantStateWithUnknownProcessesDoesNotInventRunningWork() {
        val decision = ActivityPresentationPolicy.decide(
            assistantActivityPresent = false,
            turnActive = false,
            toolCount = 0,
            runningToolCount = 0,
            completedTodoCount = 0,
            todoCount = 0,
            activeTodoCount = 0,
            loopCount = 0,
            activeLoopCount = 0,
            processStatuses = listOf("python", "paused"),
        )

        assertTrue(decision.processOnly)
        assertFalse(decision.assistantActive)
        assertEquals(0, decision.processRunningCount)
        assertEquals(0, decision.processExitedCount)
        assertEquals(2, decision.processUnknownCount)
        assertEquals("Processes · last reported: 2 unknown", decision.summary)
    }

    @Test
    fun assistantLoopHistoryRemainsInTheSharedSummary() {
        val decision = ActivityPresentationPolicy.decide(
            assistantActivityPresent = true,
            turnActive = false,
            toolCount = 0,
            runningToolCount = 0,
            completedTodoCount = 0,
            todoCount = 0,
            activeTodoCount = 0,
            loopCount = 2,
            activeLoopCount = 0,
            processStatuses = emptyList(),
        )

        assertEquals("Activity · 0 tools · 0/0 tasks · 2 loops", decision.summary)
        assertFalse(decision.assistantActive)
        assertFalse(decision.processOnly)
        assertEquals(ActivityIndicator.CompletedWork, decision.indicator)
    }
}