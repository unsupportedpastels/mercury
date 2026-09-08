package com.unsupportedpastels.hermesandroid.ui

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** iOS WorkBurstView parity, decided once in the shared engine. */
    @Test
    fun reasoningOnlyStepsAndTheirToolsCoalesceIntoAWorkBurst() {
        val messages = listOf(
            message(ChatMessageRole.User, "find the bug"),
            ChatMessage(ChatMessageRole.Assistant, "", reasoningText = "let me look"),
            message(ChatMessageRole.Tool, "read_file · Main.kt"),
            message(ChatMessageRole.Tool, "grep · TODO"),
            message(ChatMessageRole.Assistant, "Found it."),
            message(ChatMessageRole.Tool, "patch · Main.kt"),
        )

        val entries = coalesceTranscriptEntries(messages)

        assertEquals(4, entries.size)
        assertEquals(TranscriptEntry.Single(0, messages[0]), entries[0])
        assertEquals(
            TranscriptEntry.WorkBurst(
                reasoning = listOf(IndexedChatMessage(1, messages[1])),
                tools = listOf(IndexedChatMessage(2, messages[2]), IndexedChatMessage(3, messages[3])),
            ),
            entries[1],
        )
        assertEquals(TranscriptEntry.Single(4, messages[4]), entries[2])
        assertEquals(TranscriptEntry.ToolRun(listOf(IndexedChatMessage(5, messages[5]))), entries[3])
    }

    @Test
    fun streamingReasoningOnlyStepIsStillAWorkBurst() {
        val messages = listOf(
            ChatMessage(ChatMessageRole.Assistant, "", isStreaming = true, reasoningText = "thinking"),
        )
        val entries = coalesceTranscriptEntries(messages)
        assertEquals(
            listOf(TranscriptEntry.WorkBurst(reasoning = listOf(IndexedChatMessage(0, messages[0])), tools = emptyList())),
            entries,
        )
    }

    @Test
    fun partialLiveCoverageRetainsEveryPersistedToolRowAndAssistantProse() {
        val messages = buildList {
            add(message(ChatMessageRole.User, "older"))
            add(message(ChatMessageRole.Tool, "read_file · old result"))
            add(message(ChatMessageRole.Assistant, "old answer"))
            add(message(ChatMessageRole.User, "current"))
            repeat(10) { index ->
                add(message(ChatMessageRole.Tool, "tool-$index · current result $index"))
            }
            add(message(ChatMessageRole.Assistant, "current answer"))
        }

        val entries = coalesceTranscriptEntries(messages)

        assertEquals(6, entries.size)
        assertEquals(
            listOf("older", "old answer", "current", "current answer"),
            entries.filterIsInstance<TranscriptEntry.Single>().map { it.message.text },
        )
        val toolRuns = entries.filterIsInstance<TranscriptEntry.ToolRun>()
        assertEquals(listOf(1, 10), toolRuns.map { it.tools.size })
        assertEquals((4..13).toList(), toolRuns.last().tools.map { it.index })
    }

    @Test
    fun paginatedToolOnlyPageRetainsRowsWithoutAUserMarker() {
        val messages = (0 until 10).map { index ->
            message(ChatMessageRole.Tool, "tool-$index · page result $index")
        }

        val entries = coalesceTranscriptEntries(messages)

        val toolRun = entries.single() as TranscriptEntry.ToolRun
        assertEquals(10, toolRun.tools.size)
        assertEquals(0, toolRun.tools.first().index)
        assertEquals(9, toolRun.tools.last().index)
    }

    @Test
    fun finalLiveWindowDoesNotEraseEarlierPersistedRows() {
        val messages = buildList {
            add(message(ChatMessageRole.User, "current"))
            repeat(60) { index ->
                add(message(ChatMessageRole.Tool, "tool-$index · persisted result $index"))
            }
            add(message(ChatMessageRole.Assistant, "final answer"))
        }

        val entries = coalesceTranscriptEntries(messages)

        val toolRun = entries.filterIsInstance<TranscriptEntry.ToolRun>().single()
        assertEquals(60, toolRun.tools.size)
        assertEquals(1, toolRun.tools.first().index)
        assertEquals(60, toolRun.tools.last().index)
        assertEquals("final answer", entries.filterIsInstance<TranscriptEntry.Single>().last().message.text)
    }

    @Test
    fun missingFinalResponseNoticeIsOnlyShownForActiveChildWithoutCurrentProse() {
        val messages = listOf(
            message(ChatMessageRole.User, "older"),
            message(ChatMessageRole.Assistant, "older answer"),
            message(ChatMessageRole.User, "current"),
            message(ChatMessageRole.Tool, "delegate_task · still running"),
            ChatMessage(ChatMessageRole.Assistant, "", reasoningText = "waiting"),
        )

        assertEquals(
            "Background work continues. The final response is not available yet.",
            missingFinalResponseNotice(messages, activeChildCount = 1, parentTurnSending = false),
        )
        assertNull(missingFinalResponseNotice(messages, activeChildCount = 0, parentTurnSending = false))
        assertNull(missingFinalResponseNotice(messages, activeChildCount = 1, parentTurnSending = true))
        assertNull(
            missingFinalResponseNotice(
                messages + message(ChatMessageRole.Assistant, "final answer"),
                activeChildCount = 1,
                parentTurnSending = false,
            ),
        )
        assertNull(missingFinalResponseNotice(messages.drop(3), activeChildCount = 1, parentTurnSending = false))
    }
}
