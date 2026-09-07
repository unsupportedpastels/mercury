package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.mercury.core.transcript.PromptSubmissionDecision
import com.unsupportedpastels.mercury.core.transcript.PromptSubmissionPolicy

/**
 * Native owner for one per-session prompt.submit admission attempt.
 *
 * The event stream and the correlated prompt.submit response are independent
 * asynchronous results. A terminal event may therefore arrive while the ACK
 * is still pending. Keep the attempt identity and terminal-before-ACK fact
 * together so a late callback cannot settle a replacement draft.
 */
internal class PromptSubmissionLifecycle {
    /** Each Attempt instance is an identity token; fields are never used to settle callbacks. */
    internal class Attempt(
        val draft: String,
        val attachmentIds: List<String>,
    )

    internal enum class Phase {
        Idle,
        AwaitingAcceptance,
        TerminalBeforeAcceptance,
    }

    internal sealed interface TerminalEffect {
        data class ReleasedPending(
            val draft: String,
            val attachmentIds: List<String>,
        ) : TerminalEffect

        data object Ignored : TerminalEffect
    }

    internal sealed interface Resolution {
        data class Accepted(
            val draft: String,
            val attachmentIds: List<String>,
            val terminalAlreadyObserved: Boolean,
        ) : Resolution

        data class RestoreDraft(val draft: String) : Resolution

        data object AuthoritativeTerminal : Resolution

        data object Stale : Resolution
    }

    var phase: Phase = Phase.Idle
        private set

    private var currentAttempt: Attempt? = null

    /** Starts a new attempt unless the current attempt still awaits its ACK. */
    fun begin(draft: String, attachmentIds: List<String>): Attempt? {
        if (phase == Phase.AwaitingAcceptance) return null
        val attempt = Attempt(
            draft = draft,
            attachmentIds = attachmentIds.toList(),
        )
        currentAttempt = attempt
        phase = Phase.AwaitingAcceptance
        return attempt
    }

    /** Releases admission only for a terminal event on the current attempt. */
    fun observeTerminal(): TerminalEffect {
        val attempt = currentAttempt ?: return TerminalEffect.Ignored
        if (phase != Phase.AwaitingAcceptance) return TerminalEffect.Ignored
        if (PromptSubmissionPolicy.onTerminalEvent(awaitingAcceptance = true) !=
            PromptSubmissionDecision.RELEASE_PENDING
        ) {
            return TerminalEffect.Ignored
        }
        phase = Phase.TerminalBeforeAcceptance
        return TerminalEffect.ReleasedPending(
            draft = attempt.draft,
            attachmentIds = attempt.attachmentIds,
        )
    }

    /**
     * Settles one asynchronous acknowledgement. An attempt identity mismatch
     * is a strict no-op; it cannot touch the current replacement attempt.
     */
    fun resolve(attempt: Attempt, accepted: Boolean): Resolution {
        val current = currentAttempt
        if (current == null || current !== attempt) return Resolution.Stale

        val terminalAlreadyObserved = phase == Phase.TerminalBeforeAcceptance
        val decision = PromptSubmissionPolicy.onAcknowledgement(
            authoritativeTerminal = terminalAlreadyObserved,
            accepted = accepted,
        )
        currentAttempt = null
        phase = Phase.Idle

        return when (decision) {
            PromptSubmissionDecision.ACCEPTED -> Resolution.Accepted(
                draft = current.draft,
                attachmentIds = current.attachmentIds,
                terminalAlreadyObserved = terminalAlreadyObserved,
            )
            PromptSubmissionDecision.RESTORE_DRAFT -> Resolution.RestoreDraft(current.draft)
            PromptSubmissionDecision.IGNORE,
            PromptSubmissionDecision.RELEASE_PENDING,
            -> Resolution.AuthoritativeTerminal
        }
    }
}
