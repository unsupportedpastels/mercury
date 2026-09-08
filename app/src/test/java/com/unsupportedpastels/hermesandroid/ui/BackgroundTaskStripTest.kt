package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.gateway.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackgroundTaskStripTest {
    @get:Rule val compose = createComposeRule()
    @Test fun terminalReasoningIsNotLabelledThinkingAndUserMarkerTextIsPreserved() {
        val id = com.unsupportedpastels.hermesandroid.app.DurableSessionId("reasoning-fixture")
        compose.setContent { MaterialTheme {
            HermesApp(snapshot = HermesGatewaySnapshot(
                authenticationState = AuthenticationState.Authenticated,
                durableSessions = listOf(com.unsupportedpastels.hermesandroid.app.SessionSummary(id, "Reasoning fixture")),
                chatSessions = mapOf(id to ChatSessionSnapshot(messages = listOf(
                    ChatMessage(ChatMessageRole.User, "[response interrupted]"),
                    ChatMessage(ChatMessageRole.Assistant, "Partial answer preserved", reasoningText = "[response interrupted]"),
                ))),
            ), initialRoute = com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute(id))
        } }
        compose.onAllNodesWithText("Thinking").assertCountEquals(0)
        compose.onNodeWithText("Reasoning").assertIsDisplayed()
        compose.onNodeWithText("Partial answer preserved").assertIsDisplayed()
        compose.onAllNodesWithText("[response interrupted]").assertCountEquals(2)
    }
    @Test fun detailsAndTerminalDismissDoNotHideUnresolvedChildren() {
        val rows = BackgroundTasks(listOf(
            BackgroundTaskRow(RuntimeSessionId("runtime"), "child", "Review tests", "Reading test results", BackgroundTaskStatus.Active, 1000),
            BackgroundTaskRow(RuntimeSessionId("runtime"), "done", "Build", "Build verified", BackgroundTaskStatus.Finished, 1000),
        ))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(rows, nowOverride = 2000) } }
        compose.onNodeWithText("Background tasks · 1 active").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Review tests").assertIsDisplayed()
        compose.onNodeWithText("Dismiss completed").performScrollTo().performClick()
        compose.onAllNodesWithText("Build verified").assertCountEquals(0)
        compose.onNodeWithText("Review tests").assertIsDisplayed()
    }
    @Test fun recoveredTerminalWithoutTimestampDoesNotInventAnAge() {
        val rows = BackgroundTasks(listOf(BackgroundTaskRow(RuntimeSessionId("runtime"), "done", "Build", null, BackgroundTaskStatus.Finished, 0)))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(rows, nowOverride = 8000) } }
        compose.onNodeWithText("Finished · time unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Finished 8s ago").assertCountEquals(0)
    }

    @Test fun terminalOnlyUsesFinishedAgeAndStartsCollapsed() {
        val rows = BackgroundTasks(listOf(BackgroundTaskRow(RuntimeSessionId("runtime"), "done", "Build", "Verified", BackgroundTaskStatus.Finished, 1000)))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(rows, nowOverride = 8000) } }
        compose.onNodeWithText("Background tasks · completed details").assertIsDisplayed()
        compose.onNodeWithText("Finished 7s ago").assertIsDisplayed()
        compose.onAllNodesWithText("Verified").assertCountEquals(0)
        compose.onAllNodesWithText("Last observed activity 7s ago").assertCountEquals(0)
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Verified").assertIsDisplayed()
    }

    @Test fun terminalEventDoesNotRefreshActiveActivityAge() {
        val rows = BackgroundTasks(listOf(
            BackgroundTaskRow(RuntimeSessionId("runtime"), "child", "Review", null, BackgroundTaskStatus.Active, 1000),
            BackgroundTaskRow(RuntimeSessionId("runtime"), "done", "Build", null, BackgroundTaskStatus.Finished, 7000),
        ))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(rows, nowOverride = 8000) } }
        compose.onNodeWithText("Last observed activity 7s ago").assertIsDisplayed()
    }

    @Test fun staleAndFreshChildrenExposePartialKnowledge() {
        val rows = BackgroundTasks(listOf(
            BackgroundTaskRow(RuntimeSessionId("runtime"), "child", "Review", null, BackgroundTaskStatus.Active, 150000),
            BackgroundTaskRow(RuntimeSessionId("runtime"), "stale", "Build", null, BackgroundTaskStatus.Active, 1000),
        ))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(rows, nowOverride = 160000) } }
        compose.onNodeWithText("Background tasks · 1 active · other status unavailable").assertIsDisplayed()
    }

    @Test fun finishingLastChildCollapsesOpenDetailsAndDismissHidesStrip() {
        val task = BackgroundTaskRow(RuntimeSessionId("runtime"), "child", "Review", "Reading results", BackgroundTaskStatus.Active, 1000)
        val tasks = androidx.compose.runtime.mutableStateOf(BackgroundTasks(listOf(task)))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(tasks.value, nowOverride = 8000) } }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Reading results").assertIsDisplayed()
        compose.runOnIdle { tasks.value = BackgroundTasks(listOf(task.copy(status = BackgroundTaskStatus.Finished, observedAtMillis = 7000))) }
        compose.onNodeWithText("Details").assertIsDisplayed()
        compose.onAllNodesWithText("Reading results").assertCountEquals(0)
        compose.onNodeWithText("Finished 1s ago").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Dismiss completed").performScrollTo().performClick()
        compose.onAllNodesWithTag("Background task strip").assertCountEquals(0)
    }

    @Test
    fun unavailableHasNoRunningClaim() {
        val rows = BackgroundTasks(listOf(
            BackgroundTaskRow(
                RuntimeSessionId("runtime"),
                "child",
                "Review tests",
                null,
                BackgroundTaskStatus.Active,
                1000,
                false,
            ),
        ))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(rows, nowOverride = 2000) } }
        compose.onNodeWithText("Background tasks · status unavailable").assertIsDisplayed()
        compose.onAllNodesWithText("Background tasks · 0 active").assertCountEquals(0)
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Last known · updates unavailable").assertIsDisplayed()
    }

    @Test
    fun historicalUnknownRowsAreDismissibleWithoutInventingCompletion() {
        val rows = BackgroundTasks(listOf(
            BackgroundTaskRow(
                runtimeId = RuntimeSessionId("runtime"),
                id = "identity-unavailable",
                goal = "Historical child",
                action = null,
                status = BackgroundTaskStatus.Unknown,
                observedAtMillis = 0,
                available = false,
                identityKnown = false,
            ),
        ))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(rows, nowOverride = 2_000) } }
        compose.onNodeWithText("Background tasks · status unavailable").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Historical · status unavailable").assertIsDisplayed()
        compose.onNodeWithText("Dismiss unavailable").performScrollTo().performClick()
        compose.onAllNodesWithTag("Background task strip").assertCountEquals(0)
    }

    @Test
    fun newUnknownEvidenceReappearsAfterAnOlderRowWasDismissed() {
        val old = BackgroundTaskRow(
            runtimeId = RuntimeSessionId("runtime"),
            id = "identity-unavailable",
            goal = "Historical child",
            action = null,
            status = BackgroundTaskStatus.Unknown,
            observedAtMillis = 0,
            available = false,
            identityKnown = false,
        )
        val tasks = androidx.compose.runtime.mutableStateOf(BackgroundTasks(listOf(old)))
        compose.setContent { MaterialTheme { BackgroundTaskStrip(tasks.value, nowOverride = 2_000) } }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Dismiss unavailable").performScrollTo().performClick()
        compose.onAllNodesWithTag("Background task strip").assertCountEquals(0)

        compose.runOnIdle {
            tasks.value = BackgroundTasks(listOf(old.copy(
                action = "New observed evidence",
                observedAtMillis = 1_000,
                available = true,
            )))
        }
        compose.onNodeWithTag("Background task strip").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Status unavailable").assertIsDisplayed()
    }
}