package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptToolGroupingTest {
    private fun message(role: ChatMessageRole, text: String) = ChatMessage(role, text)

    @Test
    fun consecutiveToolMessagesCoalesceIntoOneRunWithPreservedIndices() {
        val messages = listOf(
            message(ChatMessageRole.User, "deploy the site"),
            message(ChatMessageRole.Assistant, "on it"),
            message(ChatMessageRole.Tool, "web_extract · https://example.com/"),
            message(ChatMessageRole.Tool, "terminal · curl -fsSL https://example.com"),
            message(ChatMessageRole.Tool, "patch · /workspace/site/index.html"),
            message(ChatMessageRole.Assistant, "Deployed successfully."),
        )

        val entries = coalesceTranscriptEntries(messages)

        assertEquals(4, entries.size)
        assertEquals(TranscriptEntry.Single(0, messages[0]), entries[0])
        assertEquals(TranscriptEntry.Single(1, messages[1]), entries[1])
        assertEquals(
            TranscriptEntry.ToolRun(
                listOf(
                    IndexedChatMessage(2, messages[2]),
                    IndexedChatMessage(3, messages[3]),
                    IndexedChatMessage(4, messages[4]),
                ),
            ),
            entries[2],
        )
        assertEquals(TranscriptEntry.Single(5, messages[5]), entries[3])
    }

    @Test
    fun toolMessagesSplitByANonToolMessageFormSeparateRuns() {
        val messages = listOf(
            message(ChatMessageRole.Tool, "read_file · a.kt"),
            message(ChatMessageRole.Assistant, "thinking out loud"),
            message(ChatMessageRole.Tool, "write_file · a.kt"),
        )

        val entries = coalesceTranscriptEntries(messages)

        assertEquals(3, entries.size)
        assertEquals(TranscriptEntry.ToolRun(listOf(IndexedChatMessage(0, messages[0]))), entries[0])
        assertEquals(TranscriptEntry.Single(1, messages[1]), entries[1])
        assertEquals(TranscriptEntry.ToolRun(listOf(IndexedChatMessage(2, messages[2]))), entries[2])
    }

    @Test
    fun emptyTranscriptProducesNoEntries() {
        assertEquals(emptyList<TranscriptEntry>(), coalesceTranscriptEntries(emptyList()))
    }

    @Test
    fun toolNameIsParsedFromLeadingSegmentBeforeSeparator() {
        assertEquals("web_extract", transcriptToolName("web_extract · https://example.com/"))
        assertEquals("terminal", transcriptToolName("terminal · curl -fsSL https://example.com"))
        assertEquals("bare", transcriptToolName("bare"))
    }
}
