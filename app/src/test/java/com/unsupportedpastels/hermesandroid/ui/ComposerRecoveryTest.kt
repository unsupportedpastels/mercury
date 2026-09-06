package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComposerRecoveryTest {
    @get:Rule val rule = createComposeRule()
    private val chat = mutableStateOf(ChatSessionSnapshot())
    private val draft = mutableStateOf("  Keep my draft  ")
    private var sends = 0
    private val scopeKey = mutableStateOf("origin/default/composer")

    private fun render(controller: Boolean = false, references: List<String> = emptyList()) {
        rule.setContent {
            HermesAndroidTheme {
                SessionDetailScreen(
                    session = SessionSummary(id = DurableSessionId("composer"), title = "Composer", preview = ""),
                    chat = chat.value, voiceInputScopeKey = scopeKey.value, draft = draft.value,
                    onDraftChanged = { draft.value = it }, canSend = true,
                    attachments = emptyList(), hostReferences = references,
                    onAddAttachments = { emptyList() }, onRemoveAttachment = {},
                    onRemoveHostReference = {}, onSend = { sends++ },
                    onReasoningSelected = {}, onFastSelected = {}, onOpenModelPicker = {},
                    onClarificationResponse = { _, _ -> }, onApprovalResponse = { _, _ -> },
                    onBlockingResponse = { _, _, _ -> }, showStop = controller, stopping = false,
                    onStop = {}, onLoadSessionInsights = {}, maintenanceAvailable = false,
                    maintenanceEnabled = false, onCompressSession = {}, onUndoSession = {},
                    onBranchSession = { _, _ -> }, showBack = false, onBack = {},
                    onLoadManagedImage = { Result.failure(Exception("unused")) },
                    onLoadHostFiles = { Result.failure(Exception("unused")) },
                    onLoadManagedFile = { Result.failure(Exception("unused")) },
                    onAttachHostReference = {},
                )
            }
        }
    }

    @Test fun profileSwitchDropsPendingSendWithoutLeavingDetail() {
        render()
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.runOnIdle {
            scopeKey.value = "origin/other/composer"
            chat.value = ChatSessionSnapshot()
        }
        rule.onNodeWithContentDescription("Send message").assertIsEnabled()
        rule.runOnIdle {
            chat.value = chat.value.copy(acceptedSubmissionCount = 1, acceptedSubmissionText = "Keep my draft")
        }
        rule.runOnIdle { assertEquals("  Keep my draft  ", draft.value) }
    }

    @Test fun reconnectingKeepsTextEditableButDisablesSendAndStop() {
        chat.value = ChatSessionSnapshot(connectionPhase = ChatConnectionPhase.Reconnecting, isLoading = true, isSending = true)
        render(controller = true)
        rule.onNode(hasSetTextAction()).assertIsEnabled().performTextReplacement("Edited during recovery")
        rule.onNodeWithText("Reconnecting…").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.onAllNodesWithContentDescription("Stop Hermes response").assertCountEquals(0)
        rule.runOnIdle { assertEquals("Edited during recovery", draft.value); assertEquals(0, sends) }
    }

    @Test fun connectingGatesSendWithoutClaimingResponse() {
        chat.value = ChatSessionSnapshot(connectionPhase = ChatConnectionPhase.Connecting, isSending = true)
        render()
        rule.onNodeWithText("Connecting…").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.onAllNodesWithText("Hermes is responding…").assertCountEquals(0)
    }

    @Test fun idleLazySendRetainsDraftUntilAuthoritativeAcceptance() {
        render()
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, sends)
            assertEquals("  Keep my draft  ", draft.value)
            chat.value = chat.value.copy(isSending = true, messages = listOf(ChatMessage(ChatMessageRole.User, "Keep my draft")))
        }
        rule.runOnIdle { assertEquals("  Keep my draft  ", draft.value) }
        rule.runOnIdle { chat.value = chat.value.copy(acceptedSubmissionCount = 1, acceptedSubmissionText = "Keep my draft") }
        rule.runOnIdle { assertEquals("", draft.value) }
    }

    @Test fun acceptanceDoesNotEraseEditsMadeWhileWaiting() {
        render()
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("New draft")
        rule.runOnIdle { chat.value = chat.value.copy(acceptedSubmissionCount = 1, acceptedSubmissionText = "Keep my draft") }
        rule.runOnIdle { assertEquals("New draft", draft.value) }
    }

    @Test fun rejectionKeepsOriginalDraftAndShowsError() {
        render()
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.runOnIdle { chat.value = chat.value.copy(error = "Could not send. Retry when connected.", rejectedSubmissionCount = 1, rejectedSubmissionText = "Keep my draft") }
        rule.onNodeWithText("Could not send. Retry when connected.").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertIsEnabled()
        rule.runOnIdle { assertEquals("  Keep my draft  ", draft.value) }
    }

    @Test fun repeatedRejectionAfterConflatedPhasesUnlocksEachAttempt() {
        render()
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.runOnIdle { chat.value = chat.value.copy(error = "Rejected", rejectedSubmissionCount = 1, rejectedSubmissionText = "Keep my draft") }
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.runOnIdle {
            // No recomposition between Connecting and terminal Idle.
            chat.value = chat.value.copy(connectionPhase = ChatConnectionPhase.Connecting, error = null)
            chat.value = chat.value.copy(connectionPhase = ChatConnectionPhase.Idle, error = "Rejected", rejectedSubmissionCount = 2)
        }
        rule.onNodeWithContentDescription("Send message").assertIsEnabled()
        rule.runOnIdle { assertEquals(2, sends); assertEquals("  Keep my draft  ", draft.value) }
    }

    @Test fun staleErrorCannotImmediatelyUnlockNewAttempt() {
        chat.value = ChatSessionSnapshot(error = "Old rejection")
        render()
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("Edited")
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test fun submittingShowsProgressAndPreventsDuplicateSend() {
        render()
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.runOnIdle { chat.value = chat.value.copy(connectionPhase = ChatConnectionPhase.Submitting) }
        rule.onNodeWithText("Sending…").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertIsEnabled()
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, sends) }
    }

    @Test fun hostReferencePrefixedAcknowledgmentClearsOnlyAcceptedDraft() {
        render(references = listOf("@file:notes.md"))
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.runOnIdle {
            chat.value = chat.value.copy(acceptedSubmissionCount = 1, acceptedSubmissionText = "@file:notes.md\nKeep my draft")
        }
        rule.runOnIdle { assertEquals("", draft.value) }
    }

    @Test fun unrelatedAcceptanceDoesNotClearDraft() {
        render()
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.runOnIdle { chat.value = chat.value.copy(acceptedSubmissionCount = 1, acceptedSubmissionText = "Different submission") }
        rule.runOnIdle { assertEquals("  Keep my draft  ", draft.value) }
    }

    @Test fun activeTurnWithoutCurrentControllerCannotStopOrSteer() {
        chat.value = ChatSessionSnapshot(isSending = true)
        render()
        rule.onAllNodesWithContentDescription("Stop Hermes response").assertCountEquals(0)
        rule.onNode(hasSetTextAction()).performTextReplacement("/steer focus")
        rule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test fun controlledActiveTurnKeepsSteerAndStopBehavior() {
        chat.value = ChatSessionSnapshot(isSending = true)
        render(controller = true)
        rule.onNodeWithContentDescription("Stop Hermes response").assertIsEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("/steer focus")
        rule.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, sends); assertEquals("/steer focus", draft.value) }
    }
}
