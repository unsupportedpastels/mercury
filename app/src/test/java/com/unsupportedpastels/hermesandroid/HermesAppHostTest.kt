package com.unsupportedpastels.hermesandroid

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ApprovalInteraction
import com.unsupportedpastels.hermesandroid.app.ClarificationInteraction
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsRepository
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsViewModel
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.relay.RelayUiState
import com.unsupportedpastels.mercury.core.relay.RelayPlatformCrypto
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesAppHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savedRelayTargetConnectsFromHomeWithoutOpeningSettings() {
        val repository = FakeServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        val target = relayTarget()
        var connectedTarget: RelayPairedTarget? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesAppHost(
                    viewModel = viewModel,
                    snapshot = HermesGatewaySnapshot(),
                    relayState = RelayUiState(targets = listOf(target)),
                    onRelayConnect = { connectedTarget = it },
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(target, connectedTarget) }
    }

    @Test
    fun savedOriginFlowsBackIntoTheApp() {
        val repository = FakeServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        composeRule.setContent {
            HermesAndroidTheme {
                HermesAppHost(
                    viewModel = viewModel,
                    snapshot = HermesGatewaySnapshot(),
                    relayState = RelayUiState(isLoaded = true),
                )
            }
        }

        composeRule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        composeRule.onNodeWithText("Self-hosted").assertIsDisplayed()
        composeRule.onNodeWithText("Hermes Cloud").assertIsDisplayed()
        composeRule.onNodeWithText("Relay").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Open Servers settings").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput("https://hermes.example/")
        composeRule.onNodeWithText("Continue").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Server configured").assertIsDisplayed()
        composeRule.onNodeWithText("https://hermes.example").assertIsDisplayed()
    }

    @Test
    fun projectCreationFlowsThroughTheHostCallbackAndOpensReturnedDraft() {
        val repository = FakeServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        val projectId = ProjectId("project-1")
        val draftId = DurableSessionId("draft-project-1")
        val project = ProjectSummary(projectId, "Hermes Android", "/workspace/app", 1, emptyList())
        val draft = SessionSummary(
            id = draftId,
            title = "Returned draft",
            projectId = projectId,
            workspacePath = "/workspace/app",
            isLocalDraft = true,
        )
        var createdForProject: ProjectId? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesAppHost(
                    viewModel = viewModel,
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        projects = listOf(project),
                        projectState = ProjectLoadState.Loaded(listOf(project)),
                        durableSessions = listOf(draft),
                        projectSessions = mapOf(projectId to listOf(draft)),
                        projectSessionStates = mapOf(
                            projectId to ProjectSessionLoadState.Loaded(listOf(draft)),
                        ),
                    ),
                    onCreateProjectSession = {
                        createdForProject = it
                        draftId
                    },
                )
            }
        }

        composeRule.onNodeWithText("Hermes Android").performClick()
        composeRule.onNodeWithText("New task").performClick()
        composeRule.onNodeWithText("Returned draft").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(projectId, createdForProject) }
    }

    @Test
    fun projectSelectionFlowsThroughTheHostCallback() {
        val repository = FakeServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "Hermes Android", "/workspace/app", 0, emptyList())
        var openedProject: ProjectId? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesAppHost(
                    viewModel = viewModel,
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        projects = listOf(project),
                        projectState = ProjectLoadState.Loaded(listOf(project)),
                    ),
                    onOpenProject = { openedProject = it },
                )
            }
        }

        composeRule.onNodeWithText("Hermes Android").performClick()

        composeRule.runOnIdle { assertEquals(projectId, openedProject) }
    }

    @Test
    fun requestedNotificationSessionOpensExactDurableSession() {
        val repository = FakeServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        val requestedId = DurableSessionId("notification-session")
        var openedSession: DurableSessionId? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesAppHost(
                    viewModel = viewModel,
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        durableSessions = listOf(SessionSummary(requestedId, "Notification session")),
                    ),
                    requestedSessionId = requestedId,
                    onOpenSession = { openedSession = it },
                )
            }
        }

        composeRule.onNodeWithText("Notification session").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(requestedId, openedSession) }
    }

    @Test
    fun controllerInteractionsFlowThroughHostCallbacksWithExactDurableIdentity() {
        val repository = FakeServerSettingsRepository()
        val viewModel = ServerSettingsViewModel(repository)
        val sessionId = DurableSessionId("stored-1")
        val runtimeId = RuntimeSessionId("runtime-1")
        var clarification: Triple<DurableSessionId, String, String>? = null
        var approval: Triple<DurableSessionId, String, Boolean>? = null
        var stopped: DurableSessionId? = null
        val runState = RunEventState(
            clarification = ClarificationInteraction(
                runtimeSessionId = runtimeId,
                requestId = "clarify-1",
                question = "Proceed?",
                choices = listOf("Yes", "No"),
                multiSelect = false,
            ),
            approval = ApprovalInteraction(
                runtimeSessionId = runtimeId,
                requestId = null,
                commandPreview = "redacted",
                descriptionPreview = "Allow action?",
                choices = listOf("once", "deny"),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesAppHost(
                    viewModel = viewModel,
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        durableSessions = listOf(SessionSummary(sessionId, "Controller session")),
                        activeRuntimes = listOf(
                            ActiveRuntimeSession(runtimeId, sessionId, "Controller session", RuntimeAccess.Controller),
                        ),
                        chatSessions = mapOf(
                            sessionId to ChatSessionSnapshot(isSending = true, runState = runState),
                        ),
                    ),
                    onClarificationResponse = { id, request, answer ->
                        clarification = Triple(id, request, answer)
                    },
                    onApprovalResponse = { id, choice, all ->
                        approval = Triple(id, choice, all)
                    },
                    onStopSession = { stopped = it },
                )
            }
        }

        composeRule.onNodeWithText("Controller session").performClick()
        composeRule.onNodeWithText("deny").performScrollTo().performClick()
        composeRule.onNodeWithText("Yes").performScrollTo().performClick()
        composeRule.onNodeWithText("Continue").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Stop Hermes response").performClick()

        composeRule.runOnIdle {
            assertEquals(Triple(sessionId, "clarify-1", "Yes"), clarification)
            assertEquals(Triple(sessionId, "deny", false), approval)
            assertEquals(sessionId, stopped)
        }
    }
}

private fun relayTarget() = RelayPairedTarget(
    id = "00000000-0000-4000-8000-000000000001",
    label = "Saved relay",
    relayOrigin = "https://relay.example.com",
    installationId = ByteArray(32) { (it + 0x80).toByte() },
    hostPublicKey = RelayPlatformCrypto.x25519PublicKey(ByteArray(32) { (it + 0x20).toByte() }),
    deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() }),
    deviceStaticPrivateKey = ByteArray(32) { (it + 0x40).toByte() },
    fingerprint = "0123456789abcdef",
    status = RelayTargetStatus.Approved,
    createdAtEpochSeconds = 1,
    lastUsedEpochSeconds = 2,
)

private class FakeServerSettingsRepository : ServerSettingsRepository {
    private val mutableOrigin = MutableStateFlow<ServerOrigin?>(null)
    override val states: Flow<ServerSettingsState> = mutableOrigin
        .map { ServerSettingsState.Ready(it) }

    override suspend fun save(serverOrigin: ServerOrigin) {
        mutableOrigin.value = serverOrigin
    }
}
