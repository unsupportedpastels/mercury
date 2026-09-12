package com.unsupportedpastels.mercury.core.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerRoutingPolicyTest {
    @Test
    fun ordinaryActivePromptIsASeparateQueuedTurn() {
        val action = ComposerRoutingPolicy.route("next task", true, false)
        assertEquals("Queue", action::class.simpleName)
        assertEquals(ComposerAction.OpenModelPicker, ComposerRoutingPolicy.route("/model", true, true))
        assertEquals(ComposerAction.SetReasoning("high"), ComposerRoutingPolicy.route("/reasoning HIGH", true, true))
    }

    @Test
    fun normalDraftSubmitsTrimmedPrompt() {
        assertEquals(ComposerAction.Submit("Ship it"), ComposerRoutingPolicy.route("  Ship it  ", turnActive = false, hasAttachments = false))
        assertEquals(ComposerAction.Submit(""), ComposerRoutingPolicy.route("  ", turnActive = false, hasAttachments = true))
        assertEquals(
            ComposerAction.Reject(ComposerRejection.BlankPrompt),
            ComposerRoutingPolicy.route("  ", turnActive = false, hasAttachments = false),
        )
    }

    @Test
    fun activeTurnRoutesGuidanceToSteer() {
        assertEquals(
            ComposerAction.Queue("Focus on the failing test"),
            ComposerRoutingPolicy.route("  Focus on the failing test  ", turnActive = true, hasAttachments = false),
        )
        assertEquals(
            ComposerAction.Steer("preserve the public API"),
            ComposerRoutingPolicy.route("/steer   preserve the public API", turnActive = true, hasAttachments = false),
        )
        assertEquals(
            ComposerAction.Reject(ComposerRejection.BlankSteer),
            ComposerRoutingPolicy.route(" /steer   ", turnActive = true, hasAttachments = false),
        )
        assertEquals(
            ComposerAction.Reject(ComposerRejection.NoActiveTurnToSteer),
            ComposerRoutingPolicy.route("/steer go", turnActive = false, hasAttachments = false),
        )
        assertEquals(
            ComposerAction.Reject(ComposerRejection.AttachmentsUnavailableWhileSteering),
            ComposerRoutingPolicy.route("Use this", turnActive = true, hasAttachments = true),
        )
    }

    @Test
    fun localCommandsNeverReachTheServer() {
        assertEquals(ComposerAction.OpenModelPicker, ComposerRoutingPolicy.route("  /model\n", false, false))
        assertEquals(ComposerAction.SetReasoning("high"), ComposerRoutingPolicy.route("/reasoning HIGH", false, false))
    }

    @Test
    fun stopButtonAndDraftRestoreRules() {
        assertTrue(ComposerRoutingPolicy.shouldShowStopButton(isSending = true, turnActive = false, draft = ""))
        assertTrue(ComposerRoutingPolicy.shouldShowStopButton(isSending = false, turnActive = true, draft = "  \n"))
        assertFalse(ComposerRoutingPolicy.shouldShowStopButton(isSending = false, turnActive = false, draft = ""))
        assertFalse(ComposerRoutingPolicy.shouldShowStopButton(isSending = true, turnActive = true, draft = "guide"))
        assertFalse(ComposerRoutingPolicy.shouldRestoreDraftAfterSubmissionFailure(submissionAccepted = true))
        assertTrue(ComposerRoutingPolicy.shouldRestoreDraftAfterSubmissionFailure(submissionAccepted = false))
    }
}
