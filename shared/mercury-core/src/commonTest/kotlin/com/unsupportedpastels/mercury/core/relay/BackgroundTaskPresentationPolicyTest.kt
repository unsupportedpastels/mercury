package com.unsupportedpastels.mercury.core.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackgroundTaskPresentationPolicyTest {
    @Test
    fun historicalIdentityLessRowsAreUnavailableAndDismissible() {
        val state = BackgroundTasks().reduce(
            BackgroundTaskEvent(
                sessionId = "runtime",
                kind = BackgroundTaskEventKind.Start,
                childId = null,
                goal = "Historical child",
                action = null,
                historical = true,
            ),
            expectedRuntime = "runtime",
            now = 0,
        )
        val row = state.rows.single()

        assertFalse(row.terminal)
        assertEquals(0, state.activeCount(1))
        assertEquals("Historical · status unavailable", row.label(1))
        assertTrue(row.isDismissible(1))
        assertFalse(BackgroundTaskPresentationPolicy.dismissalKey(row).contains("Historical"))
        assertEquals(
            "Background tasks · status unavailable",
            BackgroundTaskPresentationPolicy.summarize(state.rows, 1).headline,
        )
        assertEquals(
            "Some background task status is unavailable",
            BackgroundTaskPresentationPolicy.secondaryLabel(state.rows, 1),
        )
    }

    @Test
    fun aNewEvidenceShapeGetsASeparateDismissalKey() {
        val first = BackgroundTasks().reduce(
            BackgroundTaskEvent(
                sessionId = "runtime",
                kind = BackgroundTaskEventKind.Start,
                childId = null,
                goal = "Historical child",
                action = null,
                historical = true,
            ),
            expectedRuntime = "runtime",
            now = 0,
        )
        val firstRow = first.rows.single()
        val next = first.reduce(
            BackgroundTaskEvent(
                sessionId = "runtime",
                kind = BackgroundTaskEventKind.Tool,
                childId = null,
                goal = "Historical child",
                action = "New observed evidence",
                historical = false,
            ),
            expectedRuntime = "runtime",
            now = 1_000,
        )
        val nextRow = next.rows.single()

        assertNotEquals(
            BackgroundTaskPresentationPolicy.dismissalKey(firstRow),
            BackgroundTaskPresentationPolicy.dismissalKey(nextRow),
        )
        assertEquals("Status unavailable", nextRow.label(1_000))
        assertFalse(nextRow.terminal)
        assertTrue(nextRow.isDismissible(1_000))
    }

    @Test
    fun explicitTerminalRowsRemainHistoryAndUseOutcomeAge() {
        val row = BackgroundTaskRow(
            runtimeId = "runtime",
            id = "child",
            goal = "Build",
            action = "Verified",
            status = BackgroundTaskStatus.Finished,
            observedAtMillis = 1_000,
        )

        assertTrue(row.terminal)
        assertTrue(row.isDismissible(8_000))
        assertEquals("Finished 7s ago", BackgroundTaskPresentationPolicy.timeLabel(row, 8_000))
        val summary = BackgroundTaskPresentationPolicy.summarize(listOf(row), 8_000)
        assertEquals(0, summary.activeCount)
        assertTrue(summary.terminalOnly)
        assertEquals("Background tasks · completed details", summary.headline)
    }
}
