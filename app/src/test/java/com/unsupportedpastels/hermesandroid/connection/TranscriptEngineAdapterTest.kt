package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranscriptEngineAdapterTest {
    private val runtime = RuntimeSessionId("runtime-1")

    @Test
    fun sharedEngineReusesTheOptimisticAssistantAndPreservesSegments() {
        var snapshot = ChatSessionSnapshot(
            messages = listOf(
                ChatMessage(ChatMessageRole.User, "question"),
                ChatMessage(ChatMessageRole.Assistant, "", isStreaming = true),
            ),
        )
        snapshot = snapshot.applyTranscriptEvent(HermesChatEvent.MessageStart(runtime, null))
        snapshot = snapshot.applyTranscriptEvent(HermesChatEvent.MessageDelta(runtime, "first"))
        snapshot = snapshot.applyTranscriptEvent(
            HermesChatEvent.MessageInterim(runtime, "interim", alreadyStreamed = true),
        )
        snapshot = snapshot.applyTranscriptEvent(HermesChatEvent.MessageDelta(runtime, " final"))
        snapshot = snapshot.applyTranscriptEvent(
            HermesChatEvent.MessageComplete(runtime, "final answer", status = "completed"),
        )

        assertEquals(listOf("question", "interim", "final answer"), snapshot.messages.map { it.text })
        assertFalse(snapshot.messages.any { it.isStreaming })
    }

    @Test
    fun completionReasoningAndInterruptSentinelUseSharedRules() {
        var snapshot = ChatSessionSnapshot(
            messages = listOf(ChatMessage(ChatMessageRole.Assistant, "partial", isStreaming = true)),
        )
        snapshot = snapshot.applyTranscriptEvent(
            HermesChatEvent.MessageComplete(
                runtime,
                "Operation interrupted: waiting for model response (1s elapsed).",
                status = "interrupted",
                reasoning = "retained reasoning",
            ),
        )
        assertEquals("partial", snapshot.messages.single().text)
        assertEquals("retained reasoning", snapshot.messages.single().reasoningText)
        assertFalse(snapshot.messages.single().isStreaming)
    }
}