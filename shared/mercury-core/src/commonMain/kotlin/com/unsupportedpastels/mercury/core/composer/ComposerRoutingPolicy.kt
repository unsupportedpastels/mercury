package com.unsupportedpastels.mercury.core.composer

import com.unsupportedpastels.mercury.core.slash.SlashCommandPolicy

/** Why a draft was refused locally instead of being sent. */
enum class ComposerRejection {
    BlankPrompt,
    BlankSteer,
    NoActiveTurnToSteer,
    AttachmentsUnavailableWhileSteering,
}

/** What the composer should do with the current draft. */
sealed interface ComposerAction {
    data class Submit(val text: String) : ComposerAction
    data class Steer(val text: String) : ComposerAction
    data object OpenModelPicker : ComposerAction
    data class SetReasoning(val effort: String) : ComposerAction
    data class Reject(val reason: ComposerRejection) : ComposerAction
}

/**
 * Deterministic composer routing shared by both clients. Local commands never
 * leak into `prompt.submit`, and text typed during an active turn always goes
 * to `session.steer`.
 */
object ComposerRoutingPolicy {
    fun route(draft: String, turnActive: Boolean, hasAttachments: Boolean): ComposerAction {
        if (SlashCommandPolicy.isModelPickerCommand(draft)) return ComposerAction.OpenModelPicker
        SlashCommandPolicy.reasoningEffortCommand(draft)?.let { return ComposerAction.SetReasoning(it) }

        val trimmed = draft.trim()
        if (SlashCommandPolicy.isSteerCommand(draft)) {
            val payload = steerPayload(draft)
            if (payload.isEmpty()) return ComposerAction.Reject(ComposerRejection.BlankSteer)
            if (!turnActive) return ComposerAction.Reject(ComposerRejection.NoActiveTurnToSteer)
            if (hasAttachments) return ComposerAction.Reject(ComposerRejection.AttachmentsUnavailableWhileSteering)
            return ComposerAction.Steer(payload)
        }
        if (turnActive) {
            if (hasAttachments) return ComposerAction.Reject(ComposerRejection.AttachmentsUnavailableWhileSteering)
            if (trimmed.isEmpty()) return ComposerAction.Reject(ComposerRejection.BlankSteer)
            return ComposerAction.Steer(trimmed)
        }
        if (trimmed.isEmpty() && !hasAttachments) return ComposerAction.Reject(ComposerRejection.BlankPrompt)
        return ComposerAction.Submit(trimmed)
    }

    /** The guidance after a leading `/steer`, or empty when there is none. */
    fun steerPayload(text: String): String {
        val command = text.trimStart()
        if (command != "/steer" && !command.startsWith("/steer ")) return ""
        return command.removePrefix("/steer").trim()
    }

    /**
     * The send affordance becomes Stop while a turn is active and the composer
     * is empty; typing guidance restores send/steer without a second control.
     */
    fun shouldShowStopButton(isSending: Boolean, turnActive: Boolean, draft: String): Boolean =
        (isSending || turnActive) && draft.isBlank()

    /** Once the server accepted the prompt the cleared draft stays cleared. */
    fun shouldRestoreDraftAfterSubmissionFailure(submissionAccepted: Boolean): Boolean = !submissionAccepted
}
