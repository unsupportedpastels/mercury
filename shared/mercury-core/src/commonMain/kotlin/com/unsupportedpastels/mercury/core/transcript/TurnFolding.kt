package com.unsupportedpastels.mercury.core.transcript

sealed interface FoldedTranscriptEntry {
    data class Message(val row: TranscriptRow) : FoldedTranscriptEntry

    /** Folded steps of one settled turn, excluding its answer row. */
    data class TurnActivity(
        val steps: List<TranscriptRow>,
        val answerReasoning: String?,
    ) : FoldedTranscriptEntry {
        val stepCount: Int get() = steps.size + if (answerReasoning.isNullOrBlank()) 0 else 1
    }
}

/** Fold completed turns without mutating rows or changing their identities. */
fun foldTranscriptTurns(rows: List<TranscriptRow>, turnActive: Boolean): List<FoldedTranscriptEntry> {
    // Match coalesceTranscriptEntries' Unicode-aware reasoning-only definition.
    fun isSpaceBlank(value: String): Boolean = value.all {
        it.isWhitespace() || it == '\u00A0' || it == '\u2007' || it == '\u202F'
    }
    val result = mutableListOf<FoldedTranscriptEntry>()
    fun emitTurn(turn: List<TranscriptRow>, active: Boolean) {
        // A completion starts its own server turn, but is not a human prompt.
        // Keep its disclosure separate from both the preceding and following
        // assistant summary, including while the new summary is streaming.
        if (turn.firstOrNull()?.let(InternalCompletionNotice::isNotice) == true) {
            result += FoldedTranscriptEntry.TurnActivity(listOf(turn.first()), null)
            emitTurn(turn.drop(1), active)
            return
        }
        if (active) {
            turn.forEach { row ->
                val role = row.role.lowercase()
                if (role != "tool" && (role != "assistant" || !isSpaceBlank(row.text))) {
                    result += FoldedTranscriptEntry.Message(row)
                }
            }
            return
        }
        val answerIndex = turn.indexOfLast { it.role.lowercase() == "assistant" && !isSpaceBlank(it.text) }
        val steps = turn.filterIndexed { index, row ->
            index != answerIndex && row.role.lowercase() in setOf("assistant", "tool")
        }
        val reasoning = turn.getOrNull(answerIndex)?.reasoningText?.takeUnless(::isSpaceBlank)
        val activity = FoldedTranscriptEntry.TurnActivity(steps, reasoning)
            .takeIf { it.stepCount > 0 }
        // Without an answer, anchor at the user or the first leading activity row.
        val anchor = when {
            answerIndex >= 0 -> answerIndex
            turn.firstOrNull()?.role?.lowercase() == "user" -> 0
            else -> turn.indexOfFirst { it.role.lowercase() in setOf("assistant", "tool") }
        }
        turn.forEachIndexed { index, row ->
            if (index == answerIndex || row.role.lowercase() !in setOf("assistant", "tool")) {
                result += FoldedTranscriptEntry.Message(row)
            }
            if (index == anchor && activity != null) result += activity
        }
    }
    var start = 0
    rows.forEachIndexed { index, row ->
        if (index > start && row.role.lowercase() == "user") {
            emitTurn(rows.subList(start, index), active = false)
            start = index
        }
    }
    if (start < rows.size) emitTurn(rows.subList(start, rows.size), active = turnActive)
    return result
}
