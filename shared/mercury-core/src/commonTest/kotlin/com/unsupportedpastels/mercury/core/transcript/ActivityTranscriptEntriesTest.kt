package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityTranscriptEntriesTest {
    @Test fun disclosedActivityPreservesInterleavedOrderWithoutNestedBursts() {
        val rows = listOf(
            TranscriptRow(8, "assistant", "", true, reasoningText = "First thought"),
            TranscriptRow(4, "tool", "First tool", true),
            TranscriptRow(2, "assistant", "", true, reasoningText = "Second thought"),
            TranscriptRow(9, "tool", "Second tool", true),
            TranscriptRow(6, "tool", "Third tool", true),
        )
        val entries = activityTranscriptEntries(rows)
        assertTrue(entries.none { it is TranscriptEntry.WorkBurst }, "The turn is already the disclosure")
        assertEquals(listOf(8L, 4L, 2L, 9L, 6L), entries.flatMap {
            when (it) {
                is TranscriptEntry.Message -> listOf(it.row.id)
                is TranscriptEntry.ToolRun -> it.rows.map { row -> row.id }
                is TranscriptEntry.WorkBurst -> (it.reasoning + it.tools).map { row -> row.id }
            }
        })
        assertEquals(2, (entries.last() as TranscriptEntry.ToolRun).rows.size)
    }
}
