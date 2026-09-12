package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.unsupportedpastels.hermesandroid.connection.chatMessageFromJson
import com.unsupportedpastels.hermesandroid.connection.parseRelayTranscriptRows
import com.unsupportedpastels.hermesandroid.connection.applyTranscriptEvent
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InternalCompletionNoticePresentationTest {
    @get:Rule val compose = createComposeRule()
    private val notice = "[IMPORTANT: Background process proc_f05837cd4d75 completed normally (exit code 0).\nCommand: python check.py\nOutput:\nverified]"

    @Test fun liveAndRestoredNoticeKeepBothAssistantSummariesOutsideActivity() {
        for (active in listOf(false, true)) {
            val messages = listOf(ChatMessage(ChatMessageRole.User, "run checks"),
                ChatMessage(ChatMessageRole.Assistant, "Started checks."),
                ChatMessage(ChatMessageRole.User, notice),
                ChatMessage(ChatMessageRole.Assistant, "Checks passed.", isStreaming = active))
            val entries = foldTranscriptTurns(messages, active)
            assertEquals(listOf("run checks", "Started checks.", "Checks passed."),
                entries.filterIsInstance<FoldedEntry.Single>().map { it.message.text })
            assertSame(messages[2], entries.filterIsInstance<FoldedEntry.TurnActivity>().single().steps.single().message)
        }
    }

    @Test fun officialResumeAndRelayMetadataReachSharedFoldingAndSurviveLiveReduction() {
        val row = buildJsonObject { put("role", "user"); put("content", "New envelope"); put("display_kind", "async_delegation_complete") }
        val direct = requireNotNull(chatMessageFromJson(row))
        val relay = parseRelayTranscriptRows(buildJsonObject { put("messages", JsonArray(listOf(row))) }).single()
        assertEquals(direct, relay)
        for (message in listOf(direct, relay)) {
            val snapshot = ChatSessionSnapshot(messages = listOf(message)).applyTranscriptEvent(
                HermesChatEvent.MessageDelta(RuntimeSessionId("s"), "Summary"))
            assertEquals("async_delegation_complete", snapshot.messages.first().displayKind)
            assertTrue(foldTranscriptTurns(snapshot.messages, true).first() is FoldedEntry.TurnActivity)
        }
    }

    @Test fun noticeDisclosureStartsCollapsedExpandsAndRestores() {
        val entry = foldTranscriptTurns(listOf(ChatMessage(ChatMessageRole.User, notice)), true).single() as FoldedEntry.TurnActivity
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            HermesAndroidTheme {
                var expanded by rememberSaveable { mutableStateOf(false) }
                TurnActivityGroup(entry, expanded, { expanded = !expanded }, "notice-session")
            }
        }
        compose.onNodeWithContentDescription("Activity, 1 step, collapsed").assertIsDisplayed()
        compose.onNodeWithText("verified", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("Turn activity").performClick()
        compose.onNodeWithContentDescription("Activity, 1 step, expanded").assertIsDisplayed()
        compose.onNodeWithText("verified", substring = true).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Activity, 1 step, expanded").assertIsDisplayed()
        compose.onNodeWithTag("Turn activity").performClick()
        compose.onNodeWithText("verified", substring = true).assertDoesNotExist()
    }
}
