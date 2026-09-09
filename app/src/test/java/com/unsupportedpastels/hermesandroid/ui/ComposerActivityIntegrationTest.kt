package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.*
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComposerActivityIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private fun show(chat: ChatSessionSnapshot) {
        val id = DurableSessionId("composer-activity")
        compose.setContent { MaterialTheme { HermesApp(
            snapshot = HermesGatewaySnapshot(authenticationState = AuthenticationState.Authenticated,
                durableSessions = listOf(SessionSummary(id, "Activity test")), chatSessions = mapOf(id to chat)),
            initialRoute = SessionDetailRoute(id)) } }
    }
    @Test fun streamingAnswerUsesOneComposerLineAndDetailsRetainReasoning() {
        show(ChatSessionSnapshot(isSending = true, messages = listOf(
            ChatMessage(ChatMessageRole.User, "Question"),
            ChatMessage(ChatMessageRole.Assistant, "Visible answer", isStreaming = true, reasoningText = "Distinct reasoning"))))
        compose.onNodeWithContentDescription("Activity: Writing").assertIsDisplayed()
        compose.onNode(hasTestTag("Composer activity line") and hasAnyAncestor(hasTestTag("Message composer"))).assertIsDisplayed()
        compose.onAllNodesWithText("Hermes is responding…").assertCountEquals(0)
        compose.onAllNodesWithTag("Session progress strip").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Show thinking").assertCountEquals(0)
        compose.onNodeWithTag("Composer activity line").performClick()
        compose.onNodeWithTag("Session activity sheet").performScrollToNode(hasContentDescription("Show thinking"))
        compose.onNodeWithContentDescription("Show thinking").performClick()
        compose.onNodeWithText("Distinct reasoning").assertIsDisplayed()
    }
    @Test fun reconnectIsStaticAndNeverShowsStaleToolsInTranscript() {
        show(ChatSessionSnapshot(isSending = true, connectionPhase = ChatConnectionPhase.Reconnecting,
            runState = RunEventState(tools = listOf(RunToolRow("run", "terminal", state = RunToolState.Running)))))
        compose.onNodeWithContentDescription("Activity: Reconnecting").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Running tool terminal").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
            and hasAnyAncestor(hasTestTag("Session activity sheet"))).assertCountEquals(0)
    }
    @Test fun respondingInteractionKeepsNeedsYouAndQuestionInTranscript() {
        show(ChatSessionSnapshot(isSending = true, runState = RunEventState(clarification = ClarificationInteraction(
            RuntimeSessionId("runtime"), "request", "Which environment?", listOf("Staging"), false,
            lifecycle = RunInteractionLifecycle.Responding))))
        compose.onNodeWithContentDescription("Activity: Needs you").assertIsDisplayed()
        compose.onNodeWithText("Which environment?").assertIsDisplayed()
        compose.onAllNodesWithTag("Session progress strip").assertCountEquals(0)
    }
    @Test fun freshChildKeepsLineAfterParentAnswerButUnstampedProcessDoesNot() {
        show(ChatSessionSnapshot(messages = listOf(ChatMessage(ChatMessageRole.Assistant, "Done")),
            backgroundTasks = BackgroundTasks(listOf(BackgroundTaskRow(RuntimeSessionId("runtime"), "child", "Review",
                null, BackgroundTaskStatus.Active, System.currentTimeMillis())))))
        compose.onNodeWithContentDescription("Activity: 1 background task").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
    @Test fun untimestampedRunningProcessIsOnlyHistoryNotLiveWork() {
        show(ChatSessionSnapshot(processRows = listOf(ProcessRow("build", "Build history", "running"))))
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open session details").performClick()
        compose.onNodeWithContentDescription("Open activity details").performClick()
        compose.onNodeWithText("Processes · last reported").assertIsDisplayed()
        compose.onNodeWithText("Build history").assertIsDisplayed()
    }
    @Test fun olderTurnDisclosureNeverBorrowsLatestRunState() {
        show(ChatSessionSnapshot(isSending = true, messages = listOf(
            ChatMessage(ChatMessageRole.User, "Old question"),
            ChatMessage(ChatMessageRole.Assistant, "Old intermediate"),
            ChatMessage(ChatMessageRole.Assistant, "Old answer"),
            ChatMessage(ChatMessageRole.User, "New question")),
            runState = RunEventState(tools = listOf(RunToolRow("new", "latest_tool", state = RunToolState.Running)))))
        compose.onNodeWithTag("Session timeline").performScrollToNode(hasTestTag("Turn activity"))
        compose.onNodeWithTag("Turn activity").performClick()
        compose.onNodeWithText("Old intermediate").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Running tool latest_tool").assertCountEquals(0)
    }
    @Test fun restoredHistoryNeverHidesAnAuthoritativelyRunningTurn() {
        // Reopen / foreground recovery: resume reported running=true and the read-only
        // history refresh labeled the progress restored. The line must stay live.
        show(ChatSessionSnapshot(isSending = true,
            progress = DurableProgress(hasMilestoneSnapshot = true, restored = true,
                evidence = listOf(ProgressToolEvidence("old", "terminal", "exit 0", true, 1_000L))),
            messages = listOf(ChatMessage(ChatMessageRole.User, "Question")),
            runState = RunEventState(tools = listOf(RunToolRow("run", "terminal", state = RunToolState.Running)))))
        compose.onNodeWithContentDescription("Activity: Running").assertIsDisplayed()
        compose.onNodeWithTag("Composer activity line").performClick()
        compose.onNodeWithContentDescription("Running tool terminal").assertIsDisplayed()
        // The live running tool row animates; restored history only labels saved evidence.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
            and hasAnyAncestor(hasTestTag("Session activity sheet"))).assertCountEquals(1)
    }
    @Test fun restoredHistoryNeverHidesHostConfirmedBackgroundChildren() {
        // Cold reopen mid-delegation: the parent turn already finished (resume running=false),
        // the history refresh labeled progress restored, and the only child evidence is a
        // recovered snapshot the gateway registry just confirmed as running.
        show(ChatSessionSnapshot(
            progress = DurableProgress(hasMilestoneSnapshot = true, restored = true),
            messages = listOf(ChatMessage(ChatMessageRole.User, "Write 4 poems. Use subagents."),
                ChatMessage(ChatMessageRole.Assistant, "Four subagents are each writing a poem.")),
            backgroundTasks = BackgroundTasks(listOf(BackgroundTaskRow(RuntimeSessionId("runtime"), "sa-0", "Write a poem",
                null, BackgroundTaskStatus.Active, observedAtMillis = 0L, registryConfirmedAtMillis = System.currentTimeMillis())))))
        compose.onNodeWithContentDescription("Activity: 1 background task").assertIsDisplayed()
        compose.onNodeWithTag("Composer activity line").performClick()
        compose.onNodeWithText("Active · host reports running").assertIsDisplayed()
    }
    @Test fun completedTurnHasAnswerAndDisclosureWithoutLiveLine() {
        show(ChatSessionSnapshot(messages = listOf(ChatMessage(ChatMessageRole.User, "Question"),
            ChatMessage(ChatMessageRole.Assistant, "Intermediate"), ChatMessage(ChatMessageRole.Tool, "Tool output"),
            ChatMessage(ChatMessageRole.Assistant, "Final answer", reasoningText = "Distinct reasoning"))))
        compose.onNodeWithText("Final answer").assertIsDisplayed()
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        compose.onNodeWithTag("Turn activity").performClick()
        compose.onNodeWithText("Intermediate").assertIsDisplayed()
    }
}
