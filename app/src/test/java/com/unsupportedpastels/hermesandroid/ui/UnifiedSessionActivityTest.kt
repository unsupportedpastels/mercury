package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.*
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import org.junit.Assert.assertEquals
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

    @Test fun savedProgressAgesInMountedDetailsWithoutRequestingRefresh() {
        val chat = mutableStateOf(ChatSessionSnapshot(progress = DurableProgress(
            hasMilestoneSnapshot = true, restored = true,
            milestones = listOf(RunTodoItem("done", "Saved review", RunTodoStatus.Completed)),
        )))
        var updates = 0
        var reconnects = 0
        compose.setContent { MaterialTheme {
            HermesApp(snapshot = snapshot(chat.value), initialRoute = SessionDetailRoute(id),
                onGetSessionProgress = { updates++ }, onRetrySessionConnection = { reconnects++ })
        } }
        openSavedActivity()
        compose.onNodeWithText("Saved").assertIsDisplayed()
        // Install a receipt after navigation, so startup cost cannot consume the
        // boundary. From here on the snapshot stays fixed: only the screen's
        // production LaunchedEffect clock can update the mounted sheet's age.
        compose.runOnIdle {
            chat.value = chat.value.copy(progress = chat.value.progress.copy(
                lastObservedAtEpochMillis = System.currentTimeMillis() - 58_000L))
        }
        compose.mainClock.advanceTimeBy(1_032)
        compose.onNode(hasText("Saved · 58s ago") or hasText("Saved · 59s ago")).assertIsDisplayed()
        val receipt = chat.value.progress.lastObservedAtEpochMillis
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeBy(1_032)
            compose.onAllNodesWithText("Saved · 1m ago").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("Session activity sheet").assertIsDisplayed()
        compose.onNodeWithText("Saved review").assertIsDisplayed()
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        assertEquals(receipt, chat.value.progress.lastObservedAtEpochMillis)
        assertEquals(0, updates)
        assertEquals(0, reconnects)
    }

    @Test fun completedChildDismissalPersistsButNewEvidenceReappearsInCurrentSheet() {
        val chat = mutableStateOf(ChatSessionSnapshot(backgroundTasks = BackgroundTasks(listOf(finished))))
        compose.setContent { MaterialTheme {
            HermesApp(snapshot = snapshot(chat.value), initialRoute = SessionDetailRoute(id))
        } }
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        openSavedActivity()
        compose.onNodeWithText("Review complete").assertIsDisplayed()
        compose.onNodeWithTag("Session activity sheet").performScrollToNode(hasText("Dismiss completed"))
        compose.onNodeWithText("Dismiss completed").performClick()
        compose.onAllNodesWithText("Review complete").assertCountEquals(0)
        compose.onAllNodesWithText("Dismiss completed").assertCountEquals(0)
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        dismissActivity()
        // Unrelated snapshot changes and reopening must not clear dismissal.
        compose.runOnIdle { chat.value = chat.value.copy(notice = "Snapshot refreshed") }
        openSavedActivity()
        compose.onAllNodesWithText("Review complete").assertCountEquals(0)
        dismissActivity()
        compose.runOnIdle {
            chat.value = chat.value.copy(backgroundTasks = BackgroundTasks(listOf(finished.copy(
                status = BackgroundTaskStatus.Active, available = true,
                observedAtMillis = System.currentTimeMillis(), action = "Checking follow-up evidence",
            ))))
        }
        compose.onNodeWithContentDescription("Activity: 1 background task").assertIsDisplayed().performClick()
        compose.onNodeWithText("Review complete").assertIsDisplayed()
        compose.onNodeWithText("Active · observed activity").assertIsDisplayed()
        compose.onNodeWithText("Checking follow-up evidence").assertIsDisplayed()
        compose.onAllNodesWithText("Dismiss completed").assertCountEquals(0)
        assertNoLegacySurfaces()
    }

    @Test fun freshChildOutranksCompletedPlanThenAgesToQuietHistory() {
        val child = finished.copy(status = BackgroundTaskStatus.Active, available = true,
            observedAtMillis = System.currentTimeMillis())
        val chat = mutableStateOf(ChatSessionSnapshot(
            progress = DurableProgress(hasMilestoneSnapshot = true, restored = true,
                milestones = listOf(RunTodoItem("done", "Completed plan", RunTodoStatus.Completed))),
            backgroundTasks = BackgroundTasks(listOf(child)),
        ))
        var updates = 0
        compose.setContent { MaterialTheme {
            HermesApp(snapshot = snapshot(chat.value), initialRoute = SessionDetailRoute(id),
                onGetSessionProgress = { updates++ })
        } }
        compose.onNodeWithContentDescription("Activity: 1 background task").assertIsDisplayed().performClick()
        compose.onNodeWithText("Completed plan").assertIsDisplayed()
        compose.onNodeWithText("Active · observed activity").assertIsDisplayed()
        // Place this same child just inside its freshness window after mounting.
        // Do not send a stale replacement snapshot: expiry must come from the
        // production screen clock while both the plan and child remain supplied.
        compose.runOnIdle {
            chat.value = chat.value.copy(backgroundTasks = BackgroundTasks(listOf(child.copy(
                observedAtMillis = System.currentTimeMillis() - 118_000L,
            ))))
        }
        compose.onNodeWithText("Active · observed activity").assertIsDisplayed()
        val suppliedTasks = chat.value.backgroundTasks
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.mainClock.advanceTimeBy(1_032)
            compose.onAllNodesWithText("Last known active · no recent activity").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Completed plan").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onAllNodesWithText("Active · observed activity").assertCountEquals(0)
        dismissActivity()
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        compose.onAllNodesWithTag("Session progress strip").assertCountEquals(0)
        openSavedActivity()
        compose.onNodeWithText("Completed plan").assertIsDisplayed()
        compose.onNodeWithText("Last known active · no recent activity").assertIsDisplayed()
        assertEquals(suppliedTasks, chat.value.backgroundTasks)
        assertEquals(0, updates)
        assertNoLegacySurfaces()
    }

    private fun snapshot(chat: ChatSessionSnapshot) = HermesGatewaySnapshot(
        authenticationState = AuthenticationState.Authenticated,
        durableSessions = listOf(SessionSummary(id, "Regression")),
        chatSessions = mapOf(id to chat),
    )

    private fun openSavedActivity() {
        compose.onNodeWithContentDescription("Open session details").performClick()
        compose.onNodeWithContentDescription("Open activity details").performClick()
        compose.onNodeWithTag("Session activity sheet").assertIsDisplayed()
    }

    private fun dismissActivity() {
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
        compose.onAllNodesWithTag("Session activity sheet").assertCountEquals(0)
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
