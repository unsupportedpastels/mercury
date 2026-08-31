package com.unsupportedpastels.hermesandroid.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.android.tools.screenshot.PreviewTest
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerCatalog
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ServerConnectionMode
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobRun
import com.unsupportedpastels.hermesandroid.gateway.CronJobRunsState
import com.unsupportedpastels.hermesandroid.gateway.CronJobScope
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState
import com.unsupportedpastels.hermesandroid.gateway.CronRestCapability
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme

private val screenshotAlphaProjectId = ProjectId("project-alpha")
private val screenshotBetaProjectId = ProjectId("project-beta")
private const val screenshotAlphaWorkspace = "/workspaces/hermes-android"
private const val screenshotBetaWorkspace = "/workspaces/hermes-docs"
private val screenshotServerSettings = ServerSettingsState.Ready(
    ServerOrigin.parse("https://ham.sdhost.cc"),
)
private val screenshotHostListing = HostDirectoryListing(
    path = "/srv/projects",
    directories = listOf(
        HostDirectoryEntry("android", "/srv/projects/android"),
        HostDirectoryEntry("documentation", "/srv/projects/documentation"),
        HostDirectoryEntry("experiments", "/srv/projects/experiments"),
    ),
    parentPath = "/srv",
)

private val screenshotAlphaWorkspaceSession = SessionSummary(
    id = DurableSessionId("session-alpha-workspace"),
    title = "Workspace review",
    projectId = screenshotAlphaProjectId,
    workspacePath = screenshotAlphaWorkspace,
    preview = "Review the adaptive workspace and session behavior",
    messageCount = 42,
    model = "Fable 5",
    profile = "hermes-agent",
)
private val screenshotAlphaReleaseSession = SessionSummary(
    id = DurableSessionId("session-alpha-release"),
    title = "Release checklist",
    projectId = screenshotAlphaProjectId,
    workspacePath = screenshotAlphaWorkspace,
    preview = "Verify the release gates and package the Android build",
    messageCount = 18,
    model = "Fable 5",
    profile = "hermes-agent",
)
private val screenshotBetaSession = SessionSummary(
    id = DurableSessionId("session-beta-docs"),
    title = "Documentation update",
    projectId = screenshotBetaProjectId,
    workspacePath = screenshotBetaWorkspace,
)
private val screenshotRecentSession = SessionSummary(
    id = DurableSessionId("session-recent-notes"),
    title = "Recent notes",
)

private val screenshotAlphaProject = ProjectSummary(
    id = screenshotAlphaProjectId,
    label = "Hermes Android",
    primaryPath = screenshotAlphaWorkspace,
    sessionCount = 2,
    previewSessions = listOf(screenshotAlphaWorkspaceSession, screenshotAlphaReleaseSession),
)
private val screenshotBetaProject = ProjectSummary(
    id = screenshotBetaProjectId,
    label = "Hermes documentation",
    primaryPath = screenshotBetaWorkspace,
    sessionCount = 1,
    previewSessions = listOf(screenshotBetaSession),
)
private val screenshotProjects = listOf(screenshotAlphaProject, screenshotBetaProject)
private val screenshotProjectSessions = mapOf(
    screenshotAlphaProjectId to listOf(screenshotAlphaWorkspaceSession, screenshotAlphaReleaseSession),
    screenshotBetaProjectId to listOf(screenshotBetaSession),
)
private val screenshotScopedSessionIds = setOf(
    screenshotAlphaWorkspaceSession.id,
    screenshotAlphaReleaseSession.id,
    screenshotBetaSession.id,
)
private val screenshotTranscript = listOf(
    ChatMessage(ChatMessageRole.User, "- **Status:** open `Settings`."),
    ChatMessage(
        ChatMessageRole.Assistant,
        """
        - **Container:** removed; no `service` remains.
        ```text
        example/image:latest
        ```
        """.trimIndent(),
    ),
)

private fun screenshotProjectSnapshot(): HermesGatewaySnapshot = HermesGatewaySnapshot(
    connectionState = ConnectionState.Connected,
    authenticationState = AuthenticationState.Authenticated,
    durableSessions = listOf(
        screenshotAlphaWorkspaceSession,
        screenshotAlphaReleaseSession,
        screenshotBetaSession,
        screenshotRecentSession,
    ),
    projects = screenshotProjects,
    projectState = ProjectLoadState.Loaded(
        projects = screenshotProjects,
        activeProjectId = screenshotAlphaProjectId,
        scopedSessionIds = screenshotScopedSessionIds,
    ),
    activeProjectId = screenshotAlphaProjectId,
    scopedSessionIds = screenshotScopedSessionIds,
    projectSessions = screenshotProjectSessions,
    projectSessionStates = screenshotProjectSessions.mapValues { (_, sessions) ->
        ProjectSessionLoadState.Loaded(sessions)
    },
)

private fun screenshotWorkspaceSnapshot(): HermesGatewaySnapshot = screenshotProjectSnapshot().copy(
    chatSessions = mapOf(
        screenshotAlphaWorkspaceSession.id to ChatSessionSnapshot(messages = screenshotTranscript),
    ),
)

private fun screenshotActiveSessionSnapshot(): HermesGatewaySnapshot = screenshotProjectSnapshot().copy(
    activeRuntimes = listOf(
        ActiveRuntimeSession(
            runtimeSessionId = RuntimeSessionId("runtime-alpha-workspace"),
            durableSessionId = screenshotAlphaWorkspaceSession.id,
            title = screenshotAlphaWorkspaceSession.title,
            access = RuntimeAccess.Controller,
        ),
    ),
    chatSessions = mapOf(
        screenshotAlphaWorkspaceSession.id to ChatSessionSnapshot(
            messages = screenshotTranscript,
            isSending = true,
            model = "openai/gpt-5.6-sol",
            provider = "openai-codex",
            modelCapabilities = ModelCapabilities(fast = true, reasoning = true),
            fastMode = "fast",
            reasoningEffort = "medium",
            runState = RunEventState(
                status = RunStatus(kind = "working", text = "Reviewing workspace"),
                tools = listOf(
                    RunToolRow(
                        toolId = "tool-workspace-review",
                        name = "read_file",
                        context = "HermesApp.kt",
                        summary = "Read adaptive shell",
                        state = RunToolState.Completed,
                    ),
                ),
            ),
        ),
    ),
)

private fun screenshotObservedReasoningSnapshot(): HermesGatewaySnapshot = screenshotProjectSnapshot().copy(
    chatSessions = mapOf(
        screenshotAlphaWorkspaceSession.id to ChatSessionSnapshot(
            model = "openai/gpt-5.6-terra",
            provider = "openai-codex",
            reasoningEffort = "high",
        ),
    ),
)

private fun screenshotTableSnapshot(): HermesGatewaySnapshot = screenshotProjectSnapshot().copy(
    chatSessions = mapOf(
        screenshotAlphaWorkspaceSession.id to ChatSessionSnapshot(
            messages = listOf(
                ChatMessage(
                    ChatMessageRole.Assistant,
                    """
                    Preferred data sources:

                    | Category | Preferred source |
                    |---|---|
                    | Body Battery, stress, Garmin recovery | CIRQA |
                    | Golf UX and round activity | Apple Watch + 18Birdies |
                    | Apple activity and workout details | HealthKit |
                    | Sleep timing/stages | Compare Apple Watch and CIRQA |
                    | Unified coaching/history | Foundry |
                    """.trimIndent(),
                ),
            ),
        ),
    ),
)

private val screenshotNewDraft = SessionSummary(
    id = DurableSessionId("draft-model-preview"),
    title = "New chat",
    isLocalDraft = true,
)

private fun screenshotNewDraftSnapshot(): HermesGatewaySnapshot = screenshotProjectSnapshot().copy(
    durableSessions = listOf(screenshotNewDraft) + screenshotProjectSnapshot().durableSessions,
    chatSessions = mapOf(
        screenshotNewDraft.id to ChatSessionSnapshot(
            model = "openai/gpt-5.6-sol",
            provider = "openai-codex",
            modelCapabilities = ModelCapabilities(fast = true, reasoning = true),
            reasoningEffort = "high",
            draftDefaultsLoaded = true,
        ),
    ),
)

private val screenshotCronJobs = listOf(
    CronJob(
        jobId = "nightly-brief",
        name = "Nightly brief",
        schedule = "0 2 * * *",
        enabled = true,
        state = "scheduled",
        nextRunAt = "2026-08-17T02:00:00Z",
        lastRunAt = "2026-08-16T02:00:00Z",
        lastStatus = "success",
    ),
    CronJob(
        jobId = "price-watch",
        name = "Price watch",
        schedule = "every 2h",
        enabled = false,
        state = "paused",
        lastStatus = "skipped",
    ),
)
private val screenshotCronScope = CronJobScope("https://hermes.example", "default", "nightly-brief")
private val screenshotCronRuns = listOf(
    CronJobRun(
        id = "cron_nightly-brief_1",
        title = "Nightly brief execution",
        preview = "Returned execution session details",
        source = "cron",
        startedAt = 1_700_000_000.0,
        endedAt = 1_700_000_120.0,
        status = "completed",
        messageCount = 12,
        toolCallCount = 4,
        inputTokens = 600,
        outputTokens = 220,
    ),
)

@Composable
private fun ScreenshotCronJobsPanel() {
    HermesAndroidTheme(darkTheme = false) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CronJobsPanel(
                state = CronJobsState.Ready(screenshotCronJobs),
                onRefresh = {},
                cronServerOrigin = screenshotCronScope.serverOrigin,
                cronProfile = screenshotCronScope.profile,
                triggerCapability = CronRestCapability.Supported,
                historyCapability = CronRestCapability.Supported,
                runsByScope = mapOf(screenshotCronScope to CronJobRunsState.Ready(screenshotCronRuns)),
                onRunNow = {},
                onToggleRuns = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Compact cron jobs", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesCronJobsCompactScreenshot() {
    ScreenshotCronJobsPanel()
}

@PreviewTest
@Preview(name = "Medium cron jobs", widthDp = 610, heightDp = 900, showBackground = true)
@Composable
fun HermesCronJobsMediumScreenshot() {
    ScreenshotCronJobsPanel()
}

@PreviewTest
@Preview(name = "Expanded cron jobs", widthDp = 900, heightDp = 675, showBackground = true)
@Composable
fun HermesCronJobsExpandedScreenshot() {
    ScreenshotCronJobsPanel()
}

@PreviewTest
@Preview(name = "Compact project home light", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesProjectHomeScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = screenshotProjectSnapshot(),
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Expanded create project host folder",
    widthDp = 1200,
    heightDp = 900,
    showBackground = true,
)
@Composable
fun HermesExpandedCreateProjectScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = screenshotProjectSnapshot(),
                initialProjectCreatorOpen = true,
                initialProjectCreatorListing = screenshotHostListing,
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Compact dark open project search",
    widthDp = 400,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun HermesOpenProjectSearchScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = true) {
            HermesApp(
                snapshot = screenshotProjectSnapshot(),
                initialHomeSearchOpen = true,
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Expanded dark new chat model preview",
    widthDp = 900,
    heightDp = 675,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun HermesExpandedNewChatModelPreviewScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = true) {
            HermesApp(
                snapshot = screenshotNewDraftSnapshot(),
                initialRoute = SessionDetailRoute(screenshotNewDraft.id),
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Compact active session workspace", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesActiveSessionScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = screenshotActiveSessionSnapshot(),
                initialRoute = SessionDetailRoute(screenshotAlphaWorkspaceSession.id),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Compact observed reasoning effort", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesObservedReasoningScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = screenshotObservedReasoningSnapshot(),
                initialRoute = SessionDetailRoute(screenshotAlphaWorkspaceSession.id),
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Compact dark markdown table",
    widthDp = 400,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun HermesMarkdownTableScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = true) {
            HermesApp(
                snapshot = screenshotTableSnapshot(),
                initialRoute = SessionDetailRoute(screenshotAlphaWorkspaceSession.id),
            )
        }
    }
}

@PreviewTest
@Preview(name = "Medium list detail", widthDp = 610, heightDp = 900, showBackground = true)
@Composable
fun HermesMediumListDetailScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = screenshotWorkspaceSnapshot(),
                initialRoute = SessionDetailRoute(screenshotAlphaWorkspaceSession.id),
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Expanded project session workspace", widthDp = 900, heightDp = 675, showBackground = true)
@Composable
fun HermesExpandedProjectSessionScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = screenshotWorkspaceSnapshot(),
                initialRoute = SessionDetailRoute(screenshotAlphaWorkspaceSession.id),
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Expanded collapsed project dock", widthDp = 900, heightDp = 675, showBackground = true)
@Composable
fun HermesExpandedCollapsedProjectDockScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = screenshotWorkspaceSnapshot(),
                initialRoute = SessionDetailRoute(screenshotAlphaWorkspaceSession.id),
                serverSettingsState = screenshotServerSettings,
                initialProjectDockCollapsed = true,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Expanded dark home placeholder",
    widthDp = 900,
    heightDp = 675,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun HermesExpandedDarkHomePlaceholderScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = true) {
            HermesApp(
                snapshot = screenshotProjectSnapshot(),
                initialHomeSearchOpen = true,
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(
    name = "Compact dark large text project home",
    widthDp = 400,
    heightDp = 500,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun HermesCompactDarkLargeTextScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = true) {
            HermesApp(
                snapshot = screenshotProjectSnapshot(),
                serverSettingsState = screenshotServerSettings,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Compact server setup", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesServerSetupScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(snapshot = HermesGatewaySnapshot())
        }
    }
}

@PreviewTest
@Preview(name = "Compact server settings", widthDp = 400, heightDp = 900, showBackground = true)
@Composable
fun HermesServerDialogScreenshot() {
    HermesAndroidTheme(darkTheme = false) {
        ServerSettingsScreen(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            showBack = true,
            onBack = {},
            onSave = { Result.success(Unit) },
        )
    }
}

private val screenshotTunnelOrigin = ServerOrigin.parse("http://127.0.0.1:9119")
private val screenshotTunnelCatalog = ServerCatalog.single(
    ServerCatalogEntry(
        origin = screenshotTunnelOrigin,
        connectionMode = ServerConnectionMode.ExternalSshTunnel,
    ),
)

@PreviewTest
@Preview(name = "Compact external SSH tunnel setup", widthDp = 400, heightDp = 900, showBackground = true)
@Preview(name = "Medium external SSH tunnel setup", widthDp = 610, heightDp = 900, showBackground = true)
@Preview(name = "Expanded external SSH tunnel setup", widthDp = 900, heightDp = 675, showBackground = true)
@Composable
fun HermesExternalSshTunnelSetupScreenshot() {
    HermesAndroidTheme(darkTheme = false) {
        ServerSettingsScreen(
            serverOrigin = screenshotTunnelOrigin,
            serverCatalog = screenshotTunnelCatalog,
            showBack = true,
            onBack = {},
            onSave = { Result.success(Unit) },
        )
    }
}

@PreviewTest
@Preview(name = "Compact tunnel unavailable", widthDp = 400, heightDp = 900, showBackground = true)
@Preview(name = "Medium tunnel unavailable", widthDp = 610, heightDp = 900, showBackground = true)
@Preview(name = "Expanded tunnel unavailable", widthDp = 900, heightDp = 675, showBackground = true)
@Composable
fun HermesTunnelUnavailableScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Recovering,
                    tunnelConnectionFailure = TunnelConnectionFailure.TunnelUnavailable,
                    connectionError = "SSH tunnel unavailable",
                ),
                serverSettingsState = ServerSettingsState.Ready(screenshotTunnelOrigin),
                serverCatalog = screenshotTunnelCatalog,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Compact installation changed", widthDp = 400, heightDp = 900, showBackground = true)
@Preview(name = "Medium installation changed", widthDp = 610, heightDp = 900, showBackground = true)
@Preview(name = "Expanded installation changed", widthDp = 900, heightDp = 675, showBackground = true)
@Composable
fun HermesInstallationChangedScreenshot() {
    ScreenshotNavigationHost {
        HermesAndroidTheme(darkTheme = false) {
            HermesApp(
                snapshot = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Recovering,
                    tunnelConnectionFailure = TunnelConnectionFailure.InstallationChanged,
                    connectionError = "This local port now appears to lead to a different Hermes installation.",
                ),
                serverSettingsState = ServerSettingsState.Ready(screenshotTunnelOrigin),
                serverCatalog = screenshotTunnelCatalog,
            )
        }
    }
}

@PreviewTest
@Preview(name = "Slash completion picker", widthDp = 400, heightDp = 500, showBackground = true)
@Composable
fun SlashCompletionMenuScreenshot() {
    HermesAndroidTheme(darkTheme = false) {
        SlashCompletionMenu(
            completion = SlashCompletionState(
                composerText = "/go",
                items = listOf(
                    SlashCompletionItem("goal", "/goal", "Set a standing goal for this session"),
                    SlashCompletionItem("gol", "/gol"),
                    SlashCompletionItem("goodbye", "/goodbye", "End the conversation"),
                ),
                replaceFrom = 1,
            ),
            onItemSelected = {},
        )
    }
}

@Composable
private fun ScreenshotNavigationHost(content: @Composable () -> Unit) {
    val owner = rememberNavigationEventDispatcherOwner(parent = null)
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides owner,
        content = content,
    )
}
