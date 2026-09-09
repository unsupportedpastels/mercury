package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
