package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptPresentationPolicyTest {
    @Test
    fun bucketsKnownToolsIntoVerbPhrases() {
        assertEquals(
            "Edited 2 files, ran a command, read a file",
            TranscriptPresentationPolicy.toolActivitySummary(listOf("write_file", "patch", "shell", "read_file")),
        )
        assertEquals(
            "Ran 2 commands, read a file",
            TranscriptPresentationPolicy.toolActivitySummary(listOf("read_file", "terminal", "terminal")),
        )
    }

    @Test
    fun unknownNamesAreNormalisedAndCountedTheSameOnBothPlatforms() {
        assertEquals(
            "Browser exec ×2",
            TranscriptPresentationPolicy.toolActivitySummary(listOf("browser_exec", " Browser_Exec ")),
        )
        assertEquals("Process ×2", TranscriptPresentationPolicy.toolActivitySummary(listOf("process", "process")))
    }

    @Test
    fun runningToolsLeadAndOverflowIsBounded() {
        assertEquals(
            "Running shell, read a file",
            TranscriptPresentationPolicy.toolActivitySummary(listOf("read_file"), listOf("shell")),
        )
        assertEquals(
            "Running terminal, edited a file, searched the web, +2 more",
            TranscriptPresentationPolicy.toolActivitySummary(
                listOf("write_file", "web_search", "mystery", "other"),
                listOf("terminal"),
            ),
        )
        assertEquals(
            "Read a file, edited a file, ran a command, +1 more",
            TranscriptPresentationPolicy.toolActivitySummary(listOf("read_file", "write_file", "shell", "web_search")),
        )
    }

    @Test
    fun activitySummaryIncludesToolsTasksLoopsAndProcesses() {
        assertEquals(
            "Activity · 3 tools · 1/2 tasks · 1 process-local process",
            TranscriptPresentationPolicy.activitySummary(toolCount = 3, completedTodos = 1, todoCount = 2, processCount = 1),
        )
        assertEquals(
            "Activity · 1 tool · 0/1 tasks · 2 loops",
            TranscriptPresentationPolicy.activitySummary(1, 0, 1, loopCount = 2, processCount = 0),
        )
    }

    @Test
    fun reasoningDisplayRemovesMarkdownMarkersAndMediaDirectives() {
        assertEquals(
            "Planning code fix and UI tests",
            TranscriptPresentationPolicy.reasoningDisplayText("**Planning code fix and UI tests**\nMEDIA:/tmp/private/render.png"),
        )
        assertEquals("", TranscriptPresentationPolicy.reasoningDisplayText("MEDIA:/tmp/private/render.png"))
        assertEquals(
            "Planning unified activity stack implementation More detail",
            TranscriptPresentationPolicy.reasoningPreview("**Planning unified activity stack implementation**\nMore detail"),
        )
        assertEquals("a b c", TranscriptPresentationPolicy.reasoningPreview("a b   c\n\nd", maxChars = 5))
    }
}
