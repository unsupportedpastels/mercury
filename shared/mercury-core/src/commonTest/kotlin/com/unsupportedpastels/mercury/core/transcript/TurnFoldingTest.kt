package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TurnFoldingTest {
    private fun row(id: Long, role: String, text: String = "", reasoning: String = "") =
        TranscriptRow(id, role, text, completed = true, reasoningText = reasoning)
    private fun message(row: TranscriptRow) = FoldedTranscriptEntry.Message(row)

    @Test fun settledTurnFoldsReasoningAndToolsBelowAnswer() {
        val rows = listOf(row(10, "user", "ask"), row(20, "assistant", reasoning = "think"),
            row(30, "tool", "read"), row(40, "tool", "run"), row(50, "assistant", "answer"))
        val activity = FoldedTranscriptEntry.TurnActivity(rows.subList(1, 4), null)
        assertEquals(listOf(message(rows[0]), message(rows[4]), activity), foldTranscriptTurns(rows, false))
        assertEquals(3, activity.stepCount)
    }

    @Test fun answerReasoningCountsAsAnExtraStep() {
        val answer = row(9, "Assistant", "answer", "own thinking")
        val folded = foldTranscriptTurns(listOf(answer), false)
        assertEquals(listOf(message(answer), FoldedTranscriptEntry.TurnActivity(emptyList(), "own thinking")), folded)
        assertEquals(1, (folded.last() as FoldedTranscriptEntry.TurnActivity).stepCount)
        assertEquals(2, FoldedTranscriptEntry.TurnActivity(listOf(answer), "think").stepCount)
        assertEquals(0, FoldedTranscriptEntry.TurnActivity(emptyList(), " \n").stepCount)
    }

    @Test fun noAnswerPlacesActivityAfterUser() {
        val rows = listOf(row(1, "user", "ask"), row(2, "tool", "run"))
        assertEquals(listOf(message(rows[0]), FoldedTranscriptEntry.TurnActivity(listOf(rows[1]), null)), foldTranscriptTurns(rows, false))
        assertEquals(listOf(message(rows[0])), foldTranscriptTurns(rows.take(1), false))
    }

    @Test fun activeLastTurnKeepsOnlyProseInOrder() {
        val rows = listOf(row(1, "USER", "ask"), row(2, "assistant", "\u00a0\u2007\u202f", "think"),
            row(3, "TOOL", "run"), row(4, "assistant", "interim"),
            row(5, "assistant", "streaming").copy(completed = false))
        assertEquals(listOf(message(rows[0]), message(rows[3]), message(rows[4])), foldTranscriptTurns(rows, true))
    }

    @Test fun earlierTurnsStayFoldedWhileLastTurnIsActive() {
        val rows = listOf(row(1, "user", "old"), row(2, "tool", "run"), row(3, "assistant", "answer"),
            row(4, "user", "new"), row(5, "tool", "run"), row(6, "assistant", "partial"))
        assertEquals(listOf(message(rows[0]), message(rows[2]), FoldedTranscriptEntry.TurnActivity(listOf(rows[1]), null),
            message(rows[3]), message(rows[5])), foldTranscriptTurns(rows, true))
    }

    @Test fun systemRowsRemainInPlaceAroundAnswerAndAcrossTurns() {
        val rows = listOf(row(1, "system", "before"), row(2, "user", "ask"), row(3, "tool", "run"),
            row(4, "SYSTEM", "middle"), row(5, "assistant", "answer"), row(6, "system", "after"))
        assertEquals(listOf(message(rows[0]), message(rows[1]), message(rows[3]), message(rows[4]),
            FoldedTranscriptEntry.TurnActivity(listOf(rows[2]), null), message(rows[5])), foldTranscriptTurns(rows, false))
        assertEquals(rows.filter { it.role.lowercase() != "tool" }.map(::message), foldTranscriptTurns(rows, true))
    }

    @Test fun leadingRowsFormTheirOwnSettledTurn() {
        val rows = listOf(row(11, "assistant", "interim"), row(12, "tool", "run"),
            row(13, "assistant", "answer"), row(14, "user", "next"))
        assertEquals(listOf(message(rows[2]), FoldedTranscriptEntry.TurnActivity(rows.take(2), null), message(rows[3])),
            foldTranscriptTurns(rows, true))
    }

    @Test fun leadingSystemStaysBeforeAnswerlessActivity() {
        val rows = listOf(row(1, "system", "before"), row(2, "tool", "run"), row(3, "system", "after"))
        assertEquals(listOf(message(rows[0]), FoldedTranscriptEntry.TurnActivity(listOf(rows[1]), null), message(rows[2])),
            foldTranscriptTurns(rows, false))
    }

    @Test fun emptyInputIsEmpty() {
        assertEquals(emptyList(), foldTranscriptTurns(emptyList(), false))
        assertEquals(emptyList(), foldTranscriptTurns(emptyList(), true))
    }

    @Test fun originalRowsAndIdsArePreserved() {
        val rows = listOf(row(99, "user", "ask"), row(721, "assistant", "interim"), row(300, "assistant", "answer"))
        val folded = foldTranscriptTurns(rows, false)
        assertSame(rows[0], (folded[0] as FoldedTranscriptEntry.Message).row)
        assertSame(rows[2], (folded[1] as FoldedTranscriptEntry.Message).row)
        assertSame(rows[1], (folded[2] as FoldedTranscriptEntry.TurnActivity).steps.single())
    }
}
