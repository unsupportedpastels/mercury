package com.unsupportedpastels.mercury.core.transcript

/**
 * Deterministic decisions for the client-side prompt admission lifecycle.
 *
 * Hermes can publish an authoritative terminal event before the correlated
 * prompt.submit response reaches the client. Once that terminal event is
 * observed, the composer admission gate may be released, but a later failed
 * acknowledgement must not restore the already-admitted draft. The native
 * clients own attempt identity and lifecycle storage; this object owns only
 * the cross-platform decision table.
 */
enum class PromptSubmissionDecision {
    /** The event authoritatively ended an awaiting submission; release the gate. */
    RELEASE_PENDING,

    /** The acknowledgement accepted the prompt; keep the draft cleared. */
    ACCEPTED,

    /** The acknowledgement failed before any authoritative terminal event. */
    RESTORE_DRAFT,

    /** A stale/late callback has no state or draft effect. */
    IGNORE,
}

object PromptSubmissionPolicy {
    fun onTerminalEvent(awaitingAcceptance: Boolean): PromptSubmissionDecision =
        if (awaitingAcceptance) {
            PromptSubmissionDecision.RELEASE_PENDING
        } else {
            PromptSubmissionDecision.IGNORE
        }

    fun onAcknowledgement(
        authoritativeTerminal: Boolean,
        accepted: Boolean,
    ): PromptSubmissionDecision = when {
        accepted -> PromptSubmissionDecision.ACCEPTED
        authoritativeTerminal -> PromptSubmissionDecision.IGNORE
        else -> PromptSubmissionDecision.RESTORE_DRAFT
    }
}
