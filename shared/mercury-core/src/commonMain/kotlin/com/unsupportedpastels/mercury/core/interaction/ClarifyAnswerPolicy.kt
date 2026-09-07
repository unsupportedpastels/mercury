package com.unsupportedpastels.mercury.core.interaction

import com.unsupportedpastels.mercury.core.transcript.ClarifyQuestion

/**
 * Answer state for a clarify request, shared by the Android card and the iOS
 * sheet (both grounded in the desktop clarify card): choices are selectable
 * rows, an "Other" free-text field is always offered, typing and picking are
 * mutually exclusive, Skip sends an empty answer, and multi-select answers
 * join the picked choices with ", ".
 */
data class ClarifyAnswerState(
    val choices: List<String>,
    val multiSelect: Boolean,
    val answer: String = "",
    val selectedChoices: Set<String> = emptySet(),
) {
    fun select(choice: String): ClarifyAnswerState {
        val next = if (multiSelect) {
            if (choice in selectedChoices) selectedChoices - choice else selectedChoices + choice
        } else {
            setOf(choice)
        }
        return copy(answer = "", selectedChoices = next)
    }

    /** Typing is its own answer: a non-blank text clears any picked choice. */
    fun typeAnswer(text: String): ClarifyAnswerState =
        copy(answer = text, selectedChoices = if (text.isBlank()) selectedChoices else emptySet())

    val pendingAnswer: String?
        get() = when {
            multiSelect && selectedChoices.isNotEmpty() -> choices.filter { it in selectedChoices }.joinToString(", ")
            !multiSelect && selectedChoices.isNotEmpty() -> selectedChoices.first()
            else -> answer.trim().takeIf(String::isNotEmpty)
        }

    val canContinue: Boolean get() = pendingAnswer != null
}

object ClarifyAnswerPolicy {
    /** Skip sends an empty answer: the agent treats it as "no preference / proceed". */
    const val SKIP_ANSWER = ""

    fun otherFieldLabel(hasChoices: Boolean): String = if (hasChoices) "Other" else "Response"

    /**
     * The next question of a batch to present: the first one not yet
     * answered, in wire order. Null when every question has an answer (the
     * host then resolves the request) or when the request is not a batch.
     */
    fun nextQuestion(questions: List<ClarifyQuestion>, answeredIds: Set<String>): ClarifyQuestion? =
        questions.firstOrNull { it.qid !in answeredIds }

    /** 1-based position of [question] for a "Question 2 of 3" label. */
    fun positionOf(questions: List<ClarifyQuestion>, question: ClarifyQuestion): Int =
        questions.indexOfFirst { it.qid == question.qid } + 1
}
