package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic lifecycle characterization, not a claim of device reproduction. */
class FirstResponseReopenInvestigationTest {
    private val sid = "first-response-synthetic"
    private val prompt = "Explain the synthetic result."
    private val answer = "FIRST ANSWER BEGIN\n\nThe synthetic result includes every paragraph.\n\nFIRST ANSWER END"

    private fun completedFirstTurn(tools: Boolean = false): TranscriptSnapshot {
        var state = TranscriptEngine.appendUserMessage(TranscriptEngine.initial(isNewSession = true), prompt)
        if (tools) {
            state = TranscriptEngine.apply(state, ChatEvent.MessageInterim(sid, "Checking the synthetic input.", false))
            state = TranscriptEngine.apply(state, ChatEvent.ToolStart(sid, "tool-1", "fixture", "synthetic input"))
            state = TranscriptEngine.apply(state, ChatEvent.ToolComplete(sid, "tool-1", "fixture", "synthetic result"))
        }
        state = TranscriptEngine.apply(state, ChatEvent.MessageStart(sid, null))
        state = TranscriptEngine.apply(state, ChatEvent.MessageDelta(sid, answer))
        state = TranscriptEngine.apply(state, ChatEvent.MessageComplete(sid, text = answer))
        assertFalse(state.hasStreamingAssistant)
        assertVisibleAnswer(state)
        return state
    }

    private fun history(tools: Boolean = false): List<RestoredMessage> = buildList {
        add(RestoredMessage("user", prompt))
        if (tools) {
            add(RestoredMessage("assistant", "Checking the synthetic input."))
            add(RestoredMessage("tool", "synthetic result", toolName = "fixture"))
        }
        add(RestoredMessage("assistant", answer))
    }

    private fun assertVisibleAnswer(state: TranscriptSnapshot) {
        val visibleAnswers = foldTranscriptTurns(state.rows, turnActive = false)
            .filterIsInstance<FoldedTranscriptEntry.Message>()
            .map { it.row }.filter { it.role == "assistant" }.map { it.text }
        assertTrue(answer in visibleAnswers, "Full first answer must be a visible message, not merely an assistant row or hidden step")
        assertEquals(1, visibleAnswers.count { it == answer })
    }

    @Test fun completedFirstResponseSurvivesLeaveAndFreshRestore() {
        completedFirstTurn()
        // Leaving releases the detail owner; reopening creates a fresh snapshot.
        val reopened = TranscriptEngine.loadTranscript(TranscriptEngine.initial(), history())
        assertVisibleAnswer(reopened)
        assertFalse(reopened.hasStreamingAssistant)
    }

    @Test fun completedFirstToolTurnSurvivesLeaveAndFreshRestore() {
        completedFirstTurn(tools = true)
        val reopened = TranscriptEngine.loadTranscript(TranscriptEngine.initial(), history(tools = true))
        assertVisibleAnswer(reopened)
        assertEquals(listOf("Checking the synthetic input.", "synthetic result"),
            foldTranscriptTurns(reopened.rows, false).filterIsInstance<FoldedTranscriptEntry.TurnActivity>()
                .flatMap { it.steps }.map { it.text })
    }

    @Test fun completedFirstResponseSurvivesForegroundReconciliation() {
        val live = completedFirstTurn(tools = true)
        val result = TranscriptEngine.reconcileForegroundTranscript(live, history(tools = true), turnWasActive = false)
        assertVisibleAnswer(result.state)
        assertFalse(result.keptLocalSuffix)
    }

    @Test fun firstResponseSurvivesResumeThenSecondDurableLoad() {
        completedFirstTurn(tools = true)
        var reopened = TranscriptEngine.loadTranscript(TranscriptEngine.initial(), history(tools = true))
        reopened = TranscriptEngine.loadTranscript(reopened, history(tools = true))
        reopened = TranscriptEngine.finishStreamingAssistant(reopened)
        assertVisibleAnswer(reopened)
    }

    @Test fun firstResponseSurvivesNoticeAndFollowupSummaryOnReopen() {
        completedFirstTurn(tools = true)
        val persisted = history(tools = true) + listOf(
            RestoredMessage("user", "Synthetic completion envelope", displayKind = "async_delegation_complete"),
            RestoredMessage("assistant", "Later synthetic summary.")
        )
        assertVisibleAnswer(TranscriptEngine.loadTranscript(TranscriptEngine.initial(), persisted))
    }

    @Test fun firstResponseSurvivesOlderPagePrepend() {
        completedFirstTurn(tools = true)
        val persisted = history(tools = true)
        var reopened = TranscriptEngine.loadTranscript(TranscriptEngine.initial(), persisted.takeLast(1))
        assertVisibleAnswer(reopened)
        reopened = TranscriptEngine.prependHistory(reopened, persisted.dropLast(1))
        assertVisibleAnswer(reopened)
        assertEquals(persisted.map { it.content }, reopened.rows.map { it.text })
    }
}
