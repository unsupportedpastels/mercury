package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InternalCompletionNoticeTest {
    @Test fun officialMetadataWinsAndSurvivesRestoreAndReconcile() {
        val message = RestoredMessage("user", "Future formatted result", displayKind = "async_delegation_complete")
        val state = TranscriptEngine.loadTranscript(TranscriptEngine.initial(), listOf(message))
        assertTrue(InternalCompletionNotice.isNotice(state.rows.single()))
        assertEquals("async_delegation_complete", state.rows.single().displayKind)
        val reconciled = TranscriptEngine.reconcileForegroundTranscript(state, listOf(message), false).state
        assertTrue(foldTranscriptTurns(reconciled.rows, false).single() is FoldedTranscriptEntry.TurnActivity)
        assertFalse(InternalCompletionNotice.isNotice("assistant", process, "async_delegation_complete"))
        assertFalse(InternalCompletionNotice.isNotice("user", process, "future_other_kind"))
    }

    @Test fun processTerminalVariantsAndDelegatedAttributionAreRecognized() {
        for (status in listOf("completed normally", "exited", "terminated by Hermes", "failed to start",
            "marked lost because the process backend disappeared")) {
            val notice = process.replace("completed normally", status).replace("exit code 0", "exit code -15, SIGTERM")
                .replace("\nCommand:", "\nStarted by subagent sa-123 of delegation deleg_123. Task: \"Checks\"\nCommand:")
            assertTrue(InternalCompletionNotice.isNotice("user", notice, null), status)
        }
        assertFalse(InternalCompletionNotice.isNotice("user", process.removeSuffix("]"), null))
        assertFalse(InternalCompletionNotice.isNotice("user", process + " explain this", null))
        assertFalse(InternalCompletionNotice.isNotice("user", "[IMPORTANT: Background process proc_123 matched watch pattern \"ready\".\nCommand: run\nMatched output:\nready]", null))
    }

    @Test fun officialSingleBatchAndErrorDelegationsUseNarrowEnvelopeFallback() {
        val single = "[ASYNC DELEGATION COMPLETE — deleg_123]\nA background subagent you dispatched earlier has finished. You may have moved on since dispatching it; the full task source is below so you can act on the result or re-dispatch if things have changed.\n\nOriginal goal: checks\nRole: leaf   Model: model\nStatus: completed   API calls: 2   Duration: 3s\n--- RESULT ---\nPassed"
        val batch = "[ASYNC DELEGATION BATCH COMPLETE — deleg_123]\nA background fan-out of 1 subagent(s) you dispatched earlier has finished. All ran in parallel and waited on each other; their consolidated results are below. You may have moved on since dispatching — act on these or re-dispatch if things have changed.\n\nRole: leaf   Model: model   Total duration: 3s\n"
        for (notice in listOf(single, batch + "\n--- ✓ TASK 1/1: checks  (status=completed, 3s) ---\nPassed",
            batch + "--- ERROR ---\nThe batch did not complete successfully: stopped")) {
            assertTrue(InternalCompletionNotice.isNotice("user", notice, null))
            assertFalse(InternalCompletionNotice.isNotice("user", "Explain $notice", null))
            assertFalse(InternalCompletionNotice.isNotice("assistant", notice, null))
            assertFalse(InternalCompletionNotice.isNotice("user", notice.substringBefore('\n'), null))
        }
    }

    @Test fun actualLiveAppendAndAssistantDeltasKeepNoticeCollapsed() {
        val state = TranscriptEngine.appendUserMessage(TranscriptEngine.initial(), process)
        val live = TranscriptEngine.apply(state, ChatEvent.MessageDelta("s", "Checks passed."))
        val folded = foldTranscriptTurns(live.rows, true)
        assertTrue(folded.first() is FoldedTranscriptEntry.TurnActivity)
        assertEquals("Checks passed.", (folded.last() as FoldedTranscriptEntry.Message).row.text)
        assertEquals(process, live.rows.first().text)
    }

    private val process = "[IMPORTANT: Background process proc_f05837cd4d75 completed normally (exit code 0).\nCommand: python check.py\nOutput:\nverified]"
    private fun row(id: Long, role: String, text: String) = TranscriptRow(id, role, text, true)

    @Test fun completionIsActivityWhileLiveAndAfterHistoryRestoreWithoutLosingSummaries() {
        for (active in listOf(false, true)) {
            val messages = listOf(RestoredMessage("user", "run checks"), RestoredMessage("assistant", "Started checks."),
                RestoredMessage("user", process), RestoredMessage("assistant", "Checks passed."))
            val state = TranscriptEngine.loadTranscript(TranscriptEngine.initial(), messages)
            val folded = foldTranscriptTurns(state.rows, active)
            assertEquals(listOf("run checks", "Started checks.", "Checks passed."),
                folded.filterIsInstance<FoldedTranscriptEntry.Message>().map { it.row.text })
            val notice = folded.filterIsInstance<FoldedTranscriptEntry.TurnActivity>().single().steps.single()
            assertSame(state.rows[2], notice)
            assertEquals(process, notice.text)
            assertEquals("user", notice.role)
        }
    }

    @Test fun ordinaryUserTextQuotesAndAssistantNotificationsStayVisible() {
        for (text in listOf("IMPORTANT: run checks", "Please explain $process", "> $process", "```\n$process\n```",
            "\"$process\"", "[IMPORTANT: Background process proc_f05837cd4d75 completed normally (exit code 0).]")) {
            val source = row(1, "user", text)
            assertEquals(listOf(FoldedTranscriptEntry.Message(source)), foldTranscriptTurns(listOf(source), false))
        }
        val assistant = row(1, "assistant", process)
        assertEquals(listOf(FoldedTranscriptEntry.Message(assistant)), foldTranscriptTurns(listOf(assistant), false))
    }

    @Test fun noticeOnlyHistoryAndPaginationStillOfferDisclosure() {
        val state = TranscriptEngine.prependHistory(TranscriptEngine.initial(), listOf(RestoredMessage("user", process)))
        for (active in listOf(false, true)) {
            assertTrue(foldTranscriptTurns(state.rows, active).single() is FoldedTranscriptEntry.TurnActivity)
        }
    }
}
