package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.mercury.core.progress.ProgressObservation

/**
 * Native wiring only. Decoding, reduction and reconciliation live in
 * `shared/mercury-core` so iOS consumes the same policy instead of a copy;
 * this file maps the Android event model onto the shared observation alphabet.
 */
typealias DurableProgress = com.unsupportedpastels.mercury.core.progress.DurableProgress

typealias ProgressToolEvidence = com.unsupportedpastels.mercury.core.progress.ProgressToolEvidence

/** Android events carry replay and snapshot fields the shared event alphabet does not. */
fun HermesChatEvent.toProgressObservation(): ProgressObservation = when (this) {
    is HermesChatEvent.ToolStart -> ProgressObservation.ToolStarted(toolId, name, context, historical)
    is HermesChatEvent.ToolComplete ->
        ProgressObservation.ToolCompleted(toolId, name, summary, progressSnapshot, historical)
    is HermesChatEvent.StatusUpdate,
    is HermesChatEvent.ClarifyRequest,
    is HermesChatEvent.ApprovalRequest,
    -> ProgressObservation.Liveness
    else -> ProgressObservation.Unrelated
}

/** The owning event reducer supplies receipt time, not composition or a refresh timer. */
fun DurableProgress.observe(event: HermesChatEvent, atEpochMillis: Long): DurableProgress =
    observe(event.toProgressObservation(), atEpochMillis)
