package com.unsupportedpastels.mercury.core.interaction

import com.unsupportedpastels.mercury.core.transcript.ClarifyQuestion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClarifyAnswerPolicyTest {
    @Test
    fun typingAndPickingAreMutuallyExclusive() {
        val typedOver = ClarifyAnswerState(listOf("Option one", "Option two"), multiSelect = false)
            .select("Option one").typeAnswer("custom")
        assertEquals("custom", typedOver.pendingAnswer)
        val pickedOver = ClarifyAnswerState(listOf("Option one", "Option two"), multiSelect = false)
            .typeAnswer("custom").select("Option two")
        assertEquals("Option two", pickedOver.pendingAnswer)
        assertEquals("", pickedOver.answer)
    }

    @Test
    fun multiSelectJoinsInChoiceOrderAndToggles() {
        val state = ClarifyAnswerState(listOf("A", "B", "C"), multiSelect = true).select("C").select("A")
        assertEquals("A, C", state.pendingAnswer)
        assertFalse(state.select("A").select("C").canContinue)
    }

    @Test
    fun batchQuestionsArePresentedInWireOrderUntilAllAreAnswered() {
        val questions = listOf(
            ClarifyQuestion("q0", "first", emptyList(), false),
            ClarifyQuestion("q1", "second", listOf("a"), true),
        )
        assertEquals("q0", ClarifyAnswerPolicy.nextQuestion(questions, emptySet())?.qid)
        assertEquals("q1", ClarifyAnswerPolicy.nextQuestion(questions, setOf("q0"))?.qid)
        assertEquals("q0", ClarifyAnswerPolicy.nextQuestion(questions, setOf("q1"))?.qid)
        assertEquals(null, ClarifyAnswerPolicy.nextQuestion(questions, setOf("q0", "q1")))
        assertEquals(null, ClarifyAnswerPolicy.nextQuestion(emptyList(), emptySet()))
        assertEquals(2, ClarifyAnswerPolicy.positionOf(questions, questions[1]))
    }

    @Test
    fun continueNeedsAnAnswerAndSkipIsEmpty() {
        val state = ClarifyAnswerState(listOf("Only choice"), multiSelect = false)
        assertFalse(state.canContinue)
        assertTrue(state.select("Only choice").canContinue)
        assertEquals("", ClarifyAnswerPolicy.SKIP_ANSWER)
        assertEquals("Other", ClarifyAnswerPolicy.otherFieldLabel(hasChoices = true))
        assertEquals("Response", ClarifyAnswerPolicy.otherFieldLabel(hasChoices = false))
    }
}
