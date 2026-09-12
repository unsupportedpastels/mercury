package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.files.HostFileEntry
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises the production app's saved draft/reference + pending acknowledgement boundary. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueuedComposerRestorationTest {
    @get:Rule val rule = createComposeRule()
    private val id = DurableSessionId("queue-restoration")
    private var chat by mutableStateOf(ChatSessionSnapshot())
    private var target by mutableStateOf("fixture-target")
    private var profile by mutableStateOf("default")
    private var submitted = ""
    private var sends = 0
    private var clears = 0
    private lateinit var restoration: StateRestorationTester

    private fun queueWithReference(activeTurn: Boolean = false) {
        restoration = StateRestorationTester(rule)
        restoration.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        authenticationState = AuthenticationState.Authenticated,
                        relayTargetId = target,
                        selectedProfile = profile,
                        durableSessions = listOf(SessionSummary(id = id, title = "Fixture")),
                        activeRuntimes = listOf(ActiveRuntimeSession(
                            runtimeSessionId = RuntimeSessionId("fixture-controller"),
                            durableSessionId = id, title = "Fixture", access = RuntimeAccess.Controller,
                        )),
                        chatSessions = mapOf(id to chat),
                    ),
                    initialRoute = SessionDetailRoute(id),
                    onLoadHostFiles = {
                        Result.success(HostFileListing(path = "/fixture", parentPath = null, entries = listOf(
                            HostFileEntry("notes.txt", "/fixture/notes.txt", false, 12, "text/plain"),
                        )))
                    },
                    onSendMessage = { _, prompt ->
                        sends++
                        submitted = prompt
                        chat = chat.copy(isQueueSubmitting = true)
                    },
                    onSlashCompletionRequested = { _, text -> if (text.isEmpty()) clears++ },
                )
            }
        }
        if (!activeTurn) {
            rule.onNodeWithContentDescription("Attach files").performClick()
            rule.onNodeWithText("Host files").performClick()
            rule.onNodeWithText("notes.txt").assertIsDisplayed()
            rule.onNodeWithText("Attach").performClick()
        }
        rule.onNode(hasSetTextAction()).performTextInput("Captured draft")
        rule.runOnIdle { chat = chat.copy(isSending = activeTurn) }
        rule.onNodeWithContentDescription(if (activeTurn) "Queue message" else "Send message").performClick()
        rule.runOnIdle {
            assertEquals(1, sends)
            assertEquals(if (activeTurn) "Captured draft" else "@file:/fixture/notes.txt\nCaptured draft", submitted)
            clears = 0
        }
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("Captured draft")
        assertReference(if (activeTurn) 0 else 1)
    }

    private fun assertDraft(expected: String) {
        assertEquals(expected, rule.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.InputText].text)
    }

    private fun assertReference(count: Int) = rule.onAllNodesWithContentDescription(
        "Remove host reference @file:/fixture/notes.txt",
    ).assertCountEquals(count)

    private fun accept() = rule.runOnIdle {
        chat = chat.copy(isQueueSubmitting = false, acceptedSubmissionCount = 1,
            acceptedSubmissionText = submitted)
    }

    @Test fun activeTurnQueueAcceptanceSurvivesRestorationWithoutResend() {
        queueWithReference(activeTurn = true)
        accept()
        assertDraft("")
        rule.runOnIdle { assertEquals(1, clears); assertEquals(1, sends) }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(1, clears); assertEquals(1, sends) }
    }

    @Test fun activeQueuePreservesNewerDraftAfterRestoration() {
        queueWithReference(activeTurn = true)
        rule.onNode(hasSetTextAction()).performTextReplacement("Newer draft")
        restoration.emulateSavedInstanceStateRestore()
        accept()
        assertDraft("Newer draft")
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }

    @Test fun activeQueueRejectedAfterRestorationIsRecoverable() {
        queueWithReference(activeTurn = true)
        rule.runOnIdle {
            chat = chat.copy(isQueueSubmitting = false, rejectedSubmissionCount = 1,
                rejectedSubmissionText = submitted, error = "Queue unsupported")
        }
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("Captured draft")
        rule.onNodeWithContentDescription("Queue message").assertIsEnabled()
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }

    @Test fun activeQueueUnknownAfterRestorationDoesNotResend() {
        queueWithReference(activeTurn = true)
        rule.runOnIdle {
            chat = chat.copy(queueAcknowledgementUncertain = true,
                error = "Check transcript before sending again")
        }
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("Captured draft")
        rule.onNodeWithContentDescription("Queue message").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }

    @Test fun differentReceiptCannotClearCapturedDraftOrReferences() {
        queueWithReference()
        rule.runOnIdle {
            chat = chat.copy(isQueueSubmitting = false, acceptedSubmissionCount = 1,
                acceptedSubmissionText = "Different attempt")
        }
        assertDraft("Captured draft")
        assertReference(1)
        accept()
        assertDraft("Captured draft")
        assertReference(1)
    }

    @Test fun acceptedAfterRestorationClearsCapturedSavedInputsExactlyOnce() {
        queueWithReference()
        accept()
        assertDraft("")
        assertReference(0)
        rule.runOnIdle { assertEquals(1, clears) }
        rule.onNode(hasSetTextAction()).performTextInput("Captured draft")
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("Captured draft")
        rule.runOnIdle { assertEquals(1, clears); assertEquals(1, sends) }
    }

    @Test fun acceptedAfterRestorationPreservesNewerDraft() {
        queueWithReference()
        rule.onNode(hasSetTextAction()).performTextReplacement("Newer draft")
        restoration.emulateSavedInstanceStateRestore()
        accept()
        assertDraft("Newer draft")
        assertReference(0)
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }

    @Test fun rejectedAfterRestorationLeavesInputsRecoverable() {
        queueWithReference()
        rule.runOnIdle {
            chat = chat.copy(isQueueSubmitting = false, rejectedSubmissionCount = 1,
                rejectedSubmissionText = submitted, error = "Queue unsupported")
        }
        assertDraft("Captured draft")
        assertReference(1)
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("Captured draft")
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }

    @Test fun unknownAfterRestorationPreservesInputsWithoutResend() {
        queueWithReference()
        rule.runOnIdle {
            chat = chat.copy(isQueueSubmitting = false, queueAcknowledgementUncertain = true,
                error = "Check transcript before sending again")
        }
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("Captured draft")
        assertReference(1)
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }

    @Test fun targetSwitchInvalidatesRestoredOwnership() {
        queueWithReference()
        rule.runOnIdle { target = "other-fixture-target" }
        accept()
        rule.runOnIdle { target = "fixture-target" }
        assertDraft("Captured draft")
        assertReference(1)
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }

    @Test fun profileSwitchInvalidatesRestoredOwnership() {
        queueWithReference()
        rule.runOnIdle { profile = "other-profile" }
        accept()
        rule.runOnIdle { profile = "default" }
        assertDraft("Captured draft")
        assertReference(1)
        rule.runOnIdle { assertEquals(0, clears); assertEquals(1, sends) }
    }
}
