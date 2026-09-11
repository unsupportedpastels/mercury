package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h900dp")
class RecentSessionsTest {
    @get:Rule val composeRule = createComposeRule()
    private val project = ProjectSummary(ProjectId("mercury"), "Mercury", "/work/mercury", 2, emptyList())
    private val session = SessionSummary(
        DurableSessionId("working"), "Repair inbox", projectId = project.id,
        workspacePath = "/work/mercury", preview = "Reuse the project row",
        messageCount = 12, model = "test-model", profile = "default",
        lastActiveEpochSeconds = (System.currentTimeMillis() - 300_000) / 1_000.0,
    )

    private fun render(snapshot: HermesGatewaySnapshot, select: (DurableSessionId) -> Unit = {},
                       load: () -> Unit = {}, more: () -> Unit = {}) {
        composeRule.setContent {
            HermesAndroidTheme {
                RecentSessionsScreen(snapshot, listOf(project), true, {}, load, more,
                    onSessionSelected = select)
            }
        }
    }

    @Test fun globalRowsShowInboxMetadataProjectAndSelection() {
        var selected: DurableSessionId? = null
        render(HermesGatewaySnapshot(recentSessions = RecentSessionsState(listOf(session))), select = { selected = it })
        composeRule.onNodeWithText("test-model · 12 messages", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("5 min. ago", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Mercury", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Reuse the project row", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Repair inbox").performClick()
        assertEquals(session.id, selected)
    }

    @Test fun authoritativeWorkingRowPulsesButIdleControllerDoesNot() {
        composeRule.mainClock.autoAdvance = false
        val idle = session.copy(id = DurableSessionId("idle"), title = "Idle controller")
        render(HermesGatewaySnapshot(
            recentSessions = RecentSessionsState(listOf(session, idle)),
            activeWorkingSessionIds = setOf(session.id),
            activeRuntimes = listOf(ActiveRuntimeSession(RuntimeSessionId("runtime"), idle.id,
                idle.title, RuntimeAccess.Controller)),
        ))
        composeRule.mainClock.advanceTimeByFrame()
        val pulse = composeRule.onNodeWithContentDescription("Repair inbox is running", useUnmergedTree = true)
        val start = pulse.fetchSemanticsNode().config[SessionStatusPulseAlpha]
        composeRule.mainClock.advanceTimeBy(450)
        val faded = pulse.fetchSemanticsNode().config[SessionStatusPulseAlpha]
        assertTrue("Running indicator must animate, not just show a static dot", faded < start)
        composeRule.onNodeWithText("Idle controller").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Current controller session"))
        composeRule.onNodeWithText("Repair inbox").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SessionStatusPulseAlpha), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Idle controller is running", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun sparseMetadataDoesNotInventCountTimeOrPulse() {
        val sparse = SessionSummary(DurableSessionId("sparse"), "Sparse session")
        render(HermesGatewaySnapshot(recentSessions = RecentSessionsState(listOf(sparse))))
        composeRule.onNodeWithText("No project", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("No workspace", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Last active time available", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("messages", substring = true).assertCountEquals(0)
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SessionStatusPulseAlpha), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun emptyErrorRetainsRetryAndInitialLoadingHasProgress() {
        var loadCalls = 0
        var snapshot by androidx.compose.runtime.mutableStateOf(HermesGatewaySnapshot(
            recentSessions = RecentSessionsState(isLoading = true)))
        composeRule.setContent {
            HermesAndroidTheme {
                RecentSessionsScreen(snapshot, listOf(project), true, {}, { loadCalls++ }, {},
                    onSessionSelected = {})
            }
        }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        composeRule.onNodeWithText("No recent sessions").assertDoesNotExist()
        composeRule.runOnIdle {
            snapshot = snapshot.copy(recentSessions = RecentSessionsState(error = "Offline"))
        }
        composeRule.onNodeWithText("Offline").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, loadCalls)
    }

    @Test fun sharedProjectRowDefaultsRetainProfileAndUnreadMarker() {
        composeRule.setContent {
            HermesAndroidTheme {
                val colors = com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors.current
                SessionInboxRow(session, project.label, false, true, colors.active, colors.completed, {})
            }
        }
        composeRule.onNodeWithText("default", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Repair inbox completed; unread", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SessionStatusPulseAlpha), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun pagingErrorKeepsRowsAndRetryCallback() {
        var moreCalls = 0
        render(HermesGatewaySnapshot(recentSessions = RecentSessionsState(
            listOf(session.copy(messageCount = 1)), hasMore = true, error = "Offline")), more = { moreCalls++ })
        composeRule.onNodeWithText("test-model · 1 message", useUnmergedTree = true).assertIsDisplayed()
        val beforeRetry = moreCalls
        composeRule.onNodeWithText("Could not load more · Retry").performClick()
        assertEquals(beforeRetry + 1, moreCalls)
    }
}
