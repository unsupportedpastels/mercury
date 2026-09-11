package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.RecentSessionsState
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme

@PreviewTest
@Preview(name = "All sessions compact", widthDp = 400, heightDp = 500, uiMode = 32)
@Preview(name = "All sessions medium", widthDp = 610, heightDp = 500, uiMode = 32)
@Preview(name = "All sessions expanded", widthDp = 900, heightDp = 500, uiMode = 32)
@Composable
fun RecentSessionsInboxScreenshot() {
    val project = ProjectSummary(ProjectId("sample"), "Mercury", "/workspace/mercury", 2, emptyList())
    val working = SessionSummary(
        DurableSessionId("working"), "Restore inbox rows", projectId = project.id,
        workspacePath = project.primaryPath, preview = "Reuse the existing project inbox presentation",
        lastActiveEpochSeconds = (System.currentTimeMillis() - 300_000) / 1_000.0,
        messageCount = 12, model = "Example model", profile = "default",
    )
    val idle = working.copy(id = DurableSessionId("idle"), title = "Review compact and expanded layouts",
        preview = "Ready for review", messageCount = 1,
        lastActiveEpochSeconds = (System.currentTimeMillis() - 7_200_000) / 1_000.0)
    HermesAndroidTheme {
        RecentSessionsScreen(
            snapshot = HermesGatewaySnapshot(
                recentSessions = RecentSessionsState(listOf(working, idle,
                    SessionSummary(DurableSessionId("sparse"), "New session without metadata"))),
                activeWorkingSessionIds = setOf(working.id),
                activeRuntimes = listOf(ActiveRuntimeSession(RuntimeSessionId("controller"), idle.id,
                    idle.title, RuntimeAccess.Controller)),
            ),
            projects = listOf(project), showBack = true, onBack = {}, onLoad = {}, onLoadMore = {},
            onSessionSelected = {},
        )
    }
}
