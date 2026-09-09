package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewTurnActivityTest {
    @Test fun newPromptClearsPreviousTurnsTransientActivityWithoutDroppingHistory() {
        var state = TranscriptEngine.loadTranscript(TranscriptEngine.initial(), listOf(RestoredMessage("assistant", "Previous answer")))
        state = TranscriptEngine.apply(state, ChatEvent.StatusUpdate("runtime", "working", "Old turn status"))
        state = TranscriptEngine.apply(state, ChatEvent.ToolStart("runtime", "old", "terminal", "Old tool"))
        state = TranscriptEngine.apply(state, ChatEvent.ToolGenerating("runtime", "read_file"))
        val next = TranscriptEngine.appendUserMessage(state, "New prompt")
        assertNull(next.latestStatusText)
        assertNull(next.generatingStatusText)
        assertTrue(next.tools.isEmpty())
        assertEquals(0, next.statusUpdateCount)
        assertEquals(listOf("Previous answer", "New prompt"), next.rows.map { it.text })
    }
}
