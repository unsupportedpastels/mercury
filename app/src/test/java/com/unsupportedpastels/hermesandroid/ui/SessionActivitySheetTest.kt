package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SessionActivitySheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toolsStatusAndReadOnlyRefreshAreAvailable() {
        var updates = 0
        var reconnects = 0
        val refreshing = mutableStateOf(false)
        compose.setContent { MaterialTheme {
            SessionActivitySheet(
                summary = SessionProgressPolicy.summarize(RunEventState(), true),
                connectionLost = true, lastObservedAt = 0, now = 12000, onDismiss = {},
                onGetUpdate = { updates++ }, onRetryConnection = { reconnects++ },
                refreshing = refreshing.value, status = "Gathering context", isSending = true,
                tools = listOf(
                    RunToolRow("1", "read_file", context = "Main.kt", state = RunToolState.Running),
                    RunToolRow("2", "terminal", summary = "Tests passed", state = RunToolState.Completed),
                ),
            )
        } }
        compose.onNodeWithTag("Session activity sheet").assertIsDisplayed()
        compose.onNodeWithText("Activity").assertIsDisplayed()
        compose.onNodeWithText("Gathering context").assertIsDisplayed()
        compose.onNodeWithText("Tools").assertIsDisplayed()
        compose.onNodeWithContentDescription("Running tool read_file: Main.kt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Completed tool terminal: Tests passed").assertIsDisplayed()
        compose.onNodeWithContentDescription("Get progress update (read-only)").performClick()
        assertEquals(1, updates)
        assertEquals(0, reconnects)
        compose.runOnIdle { refreshing.value = true }
        compose.onNodeWithContentDescription("Get progress update (read-only)").assertIsNotEnabled()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(1, reconnects)
    }

    @Test fun expandedReportsAndMilestonesSurviveRefreshFailureAndRetry() {
        val refreshing = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var updates = 0
        var reconnects = 0
        compose.setContent { MaterialTheme {
            SessionActivitySheet(
                summary = SessionProgressPolicy.summarize(RunEventState(todos = listOf(
                    RunTodoItem("pending", "Review report", RunTodoStatus.Pending),
                )), false),
                connectionLost = true, lastObservedAt = 0, now = 12_000,
                onDismiss = {}, restored = true,
                onGetUpdate = { updates++; refreshing.value = true; error.value = null },
                onRetryConnection = { reconnects++ },
                refreshing = refreshing.value, refreshError = error.value,
                evidence = listOf(SessionProgressItem("Build report", "Host reported exit 0")),
            )
        } }
        val sheet = compose.onNodeWithTag("Session activity sheet")
        fun reveal(text: String) {
            sheet.performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        fun assertRetainedReport() {
            reveal("Review report")
            reveal("Tool reports")
            compose.onNodeWithText("Tool reports").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
            reveal("Host reported exit 0")
            // A successful tool report must not promote a pending milestone to Done.
            compose.onAllNodesWithText("Done").assertCountEquals(0)
        }
        fun refreshButton() = compose.onNodeWithContentDescription("Get progress update (read-only)")
        fun revealRefresh() {
            sheet.performScrollToNode(hasContentDescription("Get progress update (read-only)"))
        }

        reveal("Tool reports")
        compose.onNodeWithText("Tool reports").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onAllNodesWithText("Host reported exit 0").assertCountEquals(0)
        compose.onNodeWithText("Tool reports").performClick()
        assertRetainedReport()
        revealRefresh()
        refreshButton().performClick()
        assertEquals(1, updates)
        assertEquals(0, reconnects)
        refreshButton().assertIsNotEnabled().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Refreshing"))
        refreshButton().performClick()
        assertEquals(1, updates)
        assertRetainedReport()

        compose.runOnIdle {
            refreshing.value = false
            error.value = "Update unavailable; showing saved progress"
        }
        reveal("Couldn’t refresh")
        revealRefresh()
        refreshButton().assertIsEnabled()
        assertRetainedReport()
        revealRefresh()
        refreshButton().performClick()
        assertEquals(2, updates)
        assertEquals(0, reconnects)
        refreshButton().assertIsNotEnabled()
        compose.onAllNodesWithText("Couldn’t refresh").assertCountEquals(0)
        assertRetainedReport()

        compose.runOnIdle { refreshing.value = false }
        revealRefresh()
        refreshButton().assertIsEnabled()
        reveal("Reconnect")
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(1, reconnects)
        assertEquals(2, updates)
        assertRetainedReport()
        reveal("Tool reports")
        compose.onNodeWithText("Tool reports").performClick()
        compose.onNodeWithText("Tool reports").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onAllNodesWithText("Host reported exit 0").assertCountEquals(0)
    }

    @Test fun idleStatusIsHiddenAndMilestoneSectionsRemain() {
        compose.setContent { MaterialTheme {
            SessionActivitySheet(
                summary = SessionProgressPolicy.summarize(RunEventState(todos = listOf(
                    RunTodoItem("1", "Current", RunTodoStatus.InProgress),
                    RunTodoItem("2", "Finished", RunTodoStatus.Completed),
                )), false), connectionLost = false, lastObservedAt = null, now = 0, onDismiss = {},
                status = "Old status", isSending = false,
            )
        } }
        compose.onNodeWithText("Activity").assertIsDisplayed()
        compose.onAllNodesWithText("Old status").assertCountEquals(0)
        compose.onAllNodesWithText("Tools").assertCountEquals(0)
        compose.onNodeWithText("In progress").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
    }
}
