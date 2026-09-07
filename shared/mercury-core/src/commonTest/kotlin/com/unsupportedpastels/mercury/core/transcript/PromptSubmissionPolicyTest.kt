package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals

class PromptSubmissionPolicyTest {
    @Test
    fun terminalWhileAwaitingAcceptanceReleasesPendingGate() {
        assertEquals(
            PromptSubmissionDecision.RELEASE_PENDING,
            PromptSubmissionPolicy.onTerminalEvent(awaitingAcceptance = true),
        )
    }

    @Test
    fun terminalWithoutAwaitingSubmissionHasNoEffect() {
        assertEquals(
            PromptSubmissionDecision.IGNORE,
            PromptSubmissionPolicy.onTerminalEvent(awaitingAcceptance = false),
        )
    }

    @Test
    fun acceptedAcknowledgementKeepsDraftClearedEvenAfterTerminal() {
        assertEquals(
            PromptSubmissionDecision.ACCEPTED,
            PromptSubmissionPolicy.onAcknowledgement(
                authoritativeTerminal = true,
                accepted = true,
            ),
        )
    }

    @Test
    fun failedAcknowledgementBeforeTerminalRestoresDraft() {
        assertEquals(
            PromptSubmissionDecision.RESTORE_DRAFT,
            PromptSubmissionPolicy.onAcknowledgement(
                authoritativeTerminal = false,
                accepted = false,
            ),
        )
    }

    @Test
    fun failedAcknowledgementAfterTerminalDoesNotRestoreDraft() {
        assertEquals(
            PromptSubmissionDecision.IGNORE,
            PromptSubmissionPolicy.onAcknowledgement(
                authoritativeTerminal = true,
                accepted = false,
            ),
        )
    }
}
