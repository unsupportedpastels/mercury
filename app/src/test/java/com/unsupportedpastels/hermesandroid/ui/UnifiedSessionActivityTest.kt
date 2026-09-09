package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
class UnifiedSessionActivityTest {
    @get:Rule val compose = createComposeRule()
    private val id = DurableSessionId("activity-regression")
    private val finished = BackgroundTaskRow(RuntimeSessionId("runtime"), "child", "Review complete", null,
        BackgroundTaskStatus.Finished, 0, false)

    @Test fun completedChildExitedProcessAndEmptyTodoSnapshotHaveOneQuietPill() {
        compose.setContent {
            MaterialTheme {
                HermesApp(snapshot = HermesGatewaySnapshot(
                    authenticationState = AuthenticationState.Authenticated,
                    durableSessions = listOf(SessionSummary(id, "Regression")),
                    chatSessions = mapOf(id to ChatSessionSnapshot(
                        messages = listOf(ChatMessage(ChatMessageRole.Assistant, "Parent answer complete")),
                        progress = DurableProgress(hasMilestoneSnapshot = true, restored = true),
                        runState = RunEventState(todos = listOf(RunTodoItem("old", "Old milestone", RunTodoStatus.InProgress))),
                        backgroundTasks = BackgroundTasks(listOf(finished)),
                        processRows = listOf(ProcessRow("build", "Synthetic build", "exited", exitCode = 0)),
                    )),
                ), initialRoute = SessionDetailRoute(id))
            }
        }
        compose.onAllNodesWithTag("Session progress strip").assertCountEquals(0)
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        assertNoLegacySurfaces()
        compose.onAllNodesWithText("No progress yet").assertCountEquals(0)
        compose.onNodeWithContentDescription("Open session details").performClick()
        compose.onNodeWithContentDescription("Open activity details").performClick()
        compose.onNodeWithText("Review complete").assertIsDisplayed()
        compose.onNodeWithText("Finished").assertIsDisplayed()
        compose.onAllNodesWithText("Old milestone").assertCountEquals(0)
        compose.onNodeWithTag("Session activity sheet").performScrollToNode(hasText("Synthetic build"))
        compose.onNodeWithText("Synthetic build").assertIsDisplayed()
        compose.onNodeWithText("exited (0)").assertIsDisplayed()
        compose.onAllNodesWithText("time unavailable", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Only observed child events", substring = true).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo) and
            (hasAnyAncestor(hasTestTag("Session activity sheet")) or hasAnyAncestor(hasTestTag("Session progress strip")))).assertCountEquals(0)
    }

    @Test fun emptyScreenHasNoActivitySurfaceEvenDuringInitialHistoryRefresh() {
        compose.setContent { MaterialTheme {
            HermesApp(snapshot = HermesGatewaySnapshot(
                authenticationState = AuthenticationState.Authenticated,
                durableSessions = listOf(SessionSummary(id, "Empty")),
                chatSessions = mapOf(id to ChatSessionSnapshot(isLoading = true)),
            ), initialRoute = SessionDetailRoute(id))
        } }
        compose.onAllNodesWithTag("Session progress strip").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Get progress update (read-only)").assertCountEquals(0)
        assertNoLegacySurfaces()
    }

    private fun assertNoLegacySurfaces() {
        compose.onAllNodesWithTag("Background task strip").assertCountEquals(0)
        compose.onAllNodesWithTag("Unified activity stack").assertCountEquals(0)
        compose.onAllNodesWithTag("Active work indicator").assertCountEquals(0)
    }
}
