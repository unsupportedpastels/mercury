package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.*
import org.junit.Assert.*
import org.junit.Test

class RetainedTranscriptEngineAdapterTest {
    private val runtime = RuntimeSessionId("runtime-test")

    @Test fun streamedEventsRetainSharedStateAndHistoricalMessageIdentities() {
        val historical = ChatMessage(ChatMessageRole.User, "earlier")
        val start = ChatSessionSnapshot(messages = listOf(historical))
            .applyTranscriptEvent(HermesChatEvent.MessageStart(runtime, ""))
        val next = start.applyTranscriptEvent(HermesChatEvent.MessageDelta(runtime, "hello"))
        assertSame(historical, next.messages.first())
        assertSame(start.transcriptPresentation!!.state.rows.first(), next.transcriptPresentation!!.state.rows.first())
        assertEquals(start.transcriptPresentation!!.state.rows.last().id, next.transcriptPresentation!!.state.rows.last().id)
        assertEquals("hello", next.messages.last().text)
    }

    @Test fun externallyReplacedHistoryInvalidatesProjectionWithoutReusingRemovedRowIds() {
        val first = ChatSessionSnapshot().applyTranscriptEvent(HermesChatEvent.MessageStart(runtime, "old"))
        val oldId = first.transcriptPresentation!!.state.rows.single().id
        val restored = first.copy(messages = listOf(ChatMessage(ChatMessageRole.User, "restored")))
        val next = restored.applyTranscriptEvent(HermesChatEvent.MessageStart(runtime, "new"))
        assertEquals(listOf("restored", "new"), next.messages.map { it.text })
        assertTrue(next.transcriptPresentation!!.state.rows.all { it.id > oldId })
        assertEquals(2, next.transcriptPresentation!!.state.rows.map { it.id }.toSet().size)
    }

    @Test fun retainedProjectionDoesNotChangeExistingTranscriptSemantics() {
        val messages = listOf(ChatMessage(ChatMessageRole.User, "question"))
        var state = ChatSessionSnapshot(messages = messages)
        state = state.applyTranscriptEvent(HermesChatEvent.ReasoningDelta(runtime, "thinking", false))
        state = state.applyTranscriptEvent(HermesChatEvent.MessageDelta(runtime, "partial"))
        state = state.applyTranscriptEvent(HermesChatEvent.MessageComplete(runtime, "final", "complete"))
        assertEquals("final", state.messages.last().text)
        assertEquals("thinking", state.messages.last().reasoningText)
        assertFalse(state.messages.last().isStreaming)
        assertSame(messages.first(), state.messages.first())
    }
}
