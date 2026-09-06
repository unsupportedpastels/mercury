package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class HostReferenceAcknowledgmentTest {
    @get:Rule val rule = createComposeRule()
    private val id = DurableSessionId("reference-session")
    private val otherId = DurableSessionId("other-session")
    private val snapshot = mutableStateOf(HermesGatewaySnapshot(
        connectionState = ConnectionState.Connected,
        authenticationState = AuthenticationState.Authenticated,
        durableSessions = listOf(SessionSummary(id, "References"), SessionSummary(otherId, "Other")),
    ))
    private val origin = mutableStateOf(ServerOrigin.parse("https://one.example"))
    private val requested = mutableStateOf<DurableSessionId?>(null)
    private val requestNumber = mutableStateOf(0L)
    private var filename = "notes.txt"
    private var submitted = ""

    private fun render(withReference: Boolean = true) {
        rule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot.value,
                    serverSettingsState = ServerSettingsState.Ready(origin.value),
                    initialRoute = SessionDetailRoute(id),
                    requestedSessionId = requested.value,
                    requestedSessionRequestId = requestNumber.value,
                    onSendMessage = { sentId, text -> assertEquals(id, sentId); submitted = text },
                    onLoadHostFiles = {
                        Result.success(HostFileListing(path = "/srv", parentPath = null, entries = listOf(
                            HostFileEntry(filename, "/srv/$filename", false, 12, "text/plain"),
                        )))
                    },
                )
            }
        }
        if (withReference) attach("notes.txt")
        rule.onNode(hasSetTextAction()).performTextReplacement("Explain")
        rule.onNodeWithContentDescription("Send message").performClick()
        rule.runOnIdle { assertEquals(if (withReference) "@file:/srv/notes.txt\nExplain" else "Explain", submitted) }
    }

    private fun attach(name: String) {
        filename = name
        rule.onNodeWithContentDescription("Attach files").performClick()
        rule.onNodeWithText("Host files").performClick()
        rule.onNodeWithText(name).assertIsDisplayed()
        rule.onNodeWithText("Attach").performClick()
    }

    private fun references(name: String, count: Int = 1) =
        rule.onAllNodesWithContentDescription("Remove host reference @file:/srv/$name").assertCountEquals(count)

    private fun update(chat: ChatSessionSnapshot, session: DurableSessionId = id) {
        rule.runOnIdle { snapshot.value = snapshot.value.copy(chatSessions = snapshot.value.chatSessions + (session to chat)) }
    }

    private fun navigate(session: DurableSessionId) {
        rule.runOnIdle { requested.value = session; requestNumber.value++ }
        rule.waitForIdle()
    }

    @Test fun textAcceptanceAwayFromRouteClearsCapturedDraft() {
        render(withReference = false)
        navigate(otherId)
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        navigate(id)
        rule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")),
        )
    }

    @Test fun optimisticTranscriptWithOldActivityNeverClearsText() {
        render(withReference = false)
        update(ChatSessionSnapshot(
            isSending = true,
            messages = listOf(ChatMessage(ChatMessageRole.User, "Explain"), ChatMessage(ChatMessageRole.Assistant, "Old activity")),
        ))
        rule.onNode(hasSetTextAction()).assertTextEquals("Explain")
    }

    @Test fun rejectionAwayFromRouteRetainsText() {
        render(withReference = false)
        navigate(otherId)
        update(ChatSessionSnapshot(error = "Rejected", rejectedSubmissionCount = 1, rejectedSubmissionText = submitted))
        navigate(id)
        rule.onNode(hasSetTextAction()).assertTextEquals("Explain")
    }

    @Test fun textEditedBeforeRouteExitSurvivesAcceptance() {
        render(withReference = false)
        rule.onNode(hasSetTextAction()).performTextReplacement("New draft")
        navigate(otherId)
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        navigate(id)
        rule.onNode(hasSetTextAction()).assertTextEquals("New draft")
    }

    @Test fun staleRejectionDoesNotDiscardNewPendingAcknowledgment() {
        snapshot.value = snapshot.value.copy(chatSessions = mapOf(id to ChatSessionSnapshot(
            error = "Old rejection", rejectedSubmissionCount = 1,
            rejectedSubmissionText = "@file:/srv/notes.txt\nExplain",
        )))
        render()
        navigate(otherId)
        update(ChatSessionSnapshot(
            acceptedSubmissionCount = 1, acceptedSubmissionText = submitted,
            rejectedSubmissionCount = 1, rejectedSubmissionText = submitted,
            error = "Old rejection",
        ))
        navigate(id)
        references("notes.txt", 0)
        rule.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")),
        )
    }

    @Test fun retainsReferencesUntilMatchingAdvancedAcceptance() {
        render()
        references("notes.txt")
        update(ChatSessionSnapshot(acceptedSubmissionText = submitted))
        references("notes.txt")
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        references("notes.txt", 0)
    }

    @Test fun rejectionAndUnrelatedAcceptancePreserveReferences() {
        render()
        update(ChatSessionSnapshot(error = "Submission rejected", rejectedSubmissionCount = 1, rejectedSubmissionText = submitted))
        references("notes.txt")
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        references("notes.txt")
    }

    @Test fun mismatchingTextAndOtherSessionCannotAcknowledge() {
        render()
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted), otherId)
        references("notes.txt")
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = "Different"))
        references("notes.txt")
    }

    @Test fun clearsOnlyCapturedReferencesAfterAcceptance() {
        render()
        navigate(otherId)
        navigate(id)
        attach("new.txt")
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        references("notes.txt", 0)
        references("new.txt")
    }

    @Test fun acceptanceIsObservedAwayFromDetailRoute() {
        render()
        navigate(otherId)
        attach("other.txt")
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        references("other.txt")
        navigate(id)
        references("notes.txt", 0)
    }

    @Test fun profileChangeCannotConsumePreviousProfileReferences() {
        render()
        rule.runOnIdle { snapshot.value = snapshot.value.copy(selectedProfile = "other") }
        references("notes.txt", 0)
        navigate(otherId)
        navigate(id)
        attach("notes.txt")
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        references("notes.txt")
        rule.runOnIdle { snapshot.value = snapshot.value.copy(selectedProfile = "default") }
        references("notes.txt")
    }

    @Test fun originChangeInvalidatesPendingAcknowledgment() {
        render()
        rule.runOnIdle { origin.value = ServerOrigin.parse("https://two.example") }
        navigate(id)
        references("notes.txt", 0)
        attach("notes.txt")
        update(ChatSessionSnapshot(acceptedSubmissionCount = 1, acceptedSubmissionText = submitted))
        references("notes.txt")
        rule.runOnIdle { origin.value = ServerOrigin.parse("https://one.example") }
        navigate(id)
        references("notes.txt")
    }
}
