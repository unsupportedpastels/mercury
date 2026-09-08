package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.TranscriptPresentation
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.mercury.core.transcript.TranscriptRow
import com.unsupportedpastels.mercury.core.transcript.TranscriptSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptTurnFoldingTest {
    @get:Rule val compose = createComposeRule()
    private val messages = listOf(
        ChatMessage(ChatMessageRole.User, "ask"),
        ChatMessage(ChatMessageRole.Assistant, "", reasoningText = "thinking"),
        ChatMessage(ChatMessageRole.Tool, "read_file · file"),
        ChatMessage(ChatMessageRole.Tool, "terminal · command"),
        ChatMessage(ChatMessageRole.Assistant, "answer"),
    )

    @Test fun collapsedSemanticsAndTapRevealThinkingAndCompletedTools() {
        val entry = foldTranscriptTurns(messages, false).last() as FoldedEntry.TurnActivity
        compose.setContent {
            HermesAndroidTheme {
                var expanded by remember { mutableStateOf(false) }
                TurnActivityGroup(entry, expanded, { expanded = !expanded }, "session")
            }
        }
        compose.onNodeWithTag("Turn activity")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithContentDescription("Activity, 3 steps, collapsed").assertIsDisplayed()
        compose.onNodeWithText("Activity · 3 steps").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Show thinking").assertCountEquals(0)
        compose.onNodeWithTag("Turn activity").performClick()
        compose.onNodeWithContentDescription("Activity, 3 steps, expanded")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithContentDescription("Show thinking").assertIsDisplayed()
        compose.onNodeWithContentDescription("2 actions, completed, collapsed").assertIsDisplayed()
    }

    @Test fun answerReasoningOnlyUsesSingularWording() {
        compose.setContent {
            HermesAndroidTheme {
                TurnActivityGroup(FoldedEntry.TurnActivity(emptyList(), "own reasoning", 4), false, {}, "session")
            }
        }
        compose.onNodeWithText("Activity · 1 step").assertIsDisplayed()
        compose.onNodeWithContentDescription("Activity, 1 step, collapsed").assertIsDisplayed()
    }

    @Test fun expandedActivityPreservesInterleavedOrderAndRestoresInnerState() {
        val steps = listOf(
            IndexedChatMessage(7, ChatMessage(ChatMessageRole.Tool, "first_tool · first")),
            IndexedChatMessage(9, ChatMessage(ChatMessageRole.Assistant, "", reasoningText = "middle thinking")),
            IndexedChatMessage(12, ChatMessage(ChatMessageRole.Tool, "last_tool · last")),
            IndexedChatMessage(15, ChatMessage(ChatMessageRole.Assistant, "intermediate prose")),
        )
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            HermesAndroidTheme {
                TurnActivityGroup(FoldedEntry.TurnActivity(steps, "answer thinking", 16), true, {}, "session")
            }
        }
        val thinkingNodes = compose.onAllNodesWithContentDescription("Show thinking").fetchSemanticsNodes()
        val tools = compose.onAllNodesWithContentDescription("1 action, completed, collapsed").fetchSemanticsNodes()
        assertEquals(2, thinkingNodes.size)
        assertEquals(2, tools.size)
        org.junit.Assert.assertTrue(thinkingNodes[0].boundsInRoot.top < tools[0].boundsInRoot.top)
        org.junit.Assert.assertTrue(tools[0].boundsInRoot.top < thinkingNodes[1].boundsInRoot.top)
        org.junit.Assert.assertTrue(thinkingNodes[1].boundsInRoot.top < tools[1].boundsInRoot.top)
        compose.onNodeWithText("intermediate prose").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Show thinking")[0].performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Hide thinking").assertIsDisplayed()
    }

    @Test fun bridgePreservesSourceIndicesAndObjects() {
        val entries = foldTranscriptTurns(messages, false)
        val answer = entries[1] as FoldedEntry.Single
        val activity = entries[2] as FoldedEntry.TurnActivity
        assertEquals(4, answer.index)
        assertSame(messages[4], answer.message)
        assertEquals(listOf(1, 2, 3), activity.steps.map { it.index })
        assertSame(messages[1], activity.steps[0].message)
        assertEquals(4, activity.answerIndex)
    }

    @Test fun keysPreferRetainedRowIdentityAndOtherwiseUseIndices() {
        val entries = foldTranscriptTurns(messages, false)
        val chat = ChatSessionSnapshot(messages = messages)
        assertEquals("message:index:0", foldedEntryKey(entries[0], chat))
        assertEquals("turn-activity:index:1", foldedEntryKey(entries[2], chat))
        val rows = messages.mapIndexed { i, m -> TranscriptRow(100L + i, m.role.name.lowercase(), m.text, true) }
        val retained = chat.copy(transcriptPresentation = TranscriptPresentation(TranscriptSnapshot(rows = rows), messages))
        assertEquals("message:row:100", foldedEntryKey(entries[0], retained))
        assertEquals("turn-activity:row:101", foldedEntryKey(entries[2], retained))
        val reasoning = FoldedEntry.TurnActivity(emptyList(), "own", 4)
        assertEquals("turn-activity:answer:4", foldedEntryKey(reasoning, chat))
        assertEquals("turn-activity:answer:row:104", foldedEntryKey(reasoning, retained))
        val movedMessages = messages.drop(1)
        val moved = retained.copy(messages = movedMessages,
            transcriptPresentation = TranscriptPresentation(TranscriptSnapshot(rows = rows.drop(1)), movedMessages))
        val movedEntry = (entries[2] as FoldedEntry.TurnActivity).copy(steps = listOf(IndexedChatMessage(0, movedMessages[0])))
        assertEquals(foldedEntryKey(entries[2], retained), foldedEntryKey(movedEntry, moved))
        assertEquals("turn-activity:index:1", foldedEntryKey(entries[2], retained.copy(messages = messages.toMutableList())))
    }
}
