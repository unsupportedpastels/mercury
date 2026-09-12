package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptReadAuthorityTest {
    @Test fun nonemptyResumeOwnsDisplayAndEmptyResumeFallsBack() {
        assertEquals(TranscriptReadAuthority.Resume, TranscriptReadPolicy.afterResume(true))
        assertFalse(TranscriptReadPolicy.afterResume(true).publishesTranscript)
        assertEquals(TranscriptReadAuthority.History, TranscriptReadPolicy.afterResume(false))
        assertTrue(TranscriptReadPolicy.afterResume(false).publishesTranscript)
    }

    @Test fun historyAuthorityIsNotStickyAndAllowsInitialReplacementAndRewind() {
        val initial = listOf(RestoredMessage("user", "Question"), RestoredMessage("assistant", "Alpha"),
            RestoredMessage("assistant", "Omega"))
        var state = TranscriptEngine.initial()
        if (TranscriptReadAuthority.History.publishesTranscript) {
            state = TranscriptEngine.loadTranscript(state, initial)
        }
        val authoritative = state
        for (durable in listOf(initial.take(1), initial, initial + initial)) {
            if (TranscriptReadPolicy.afterResume(true).publishesTranscript) {
                state = TranscriptEngine.loadTranscript(state, durable)
            }
            assertEquals(authoritative, state)
        }
        // A subsequent explicit history operation is free to replace even with zero rows.
        if (TranscriptReadAuthority.History.publishesTranscript) {
            state = TranscriptEngine.loadTranscript(state, emptyList())
        }
        assertTrue(state.rows.isEmpty())
        if (TranscriptReadPolicy.afterResume(false).publishesTranscript) {
            state = TranscriptEngine.loadTranscript(state, initial)
        }
        assertEquals(initial.map { it.content }, state.rows.map { it.text })
    }
}
