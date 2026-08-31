package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.connection.CREDENTIAL_REJECTED_USER_MESSAGE
import com.unsupportedpastels.hermesandroid.connection.DEFAULT_TUNNEL_ORIGIN
import com.unsupportedpastels.hermesandroid.connection.LOOPBACK_SECURITY_WARNING
import com.unsupportedpastels.hermesandroid.connection.LOCALHOST_TUNNEL_MESSAGE
import com.unsupportedpastels.hermesandroid.connection.MINIMUM_HERMES_VERSION
import com.unsupportedpastels.hermesandroid.connection.PROTOCOL_INCOMPATIBLE_MESSAGE
import com.unsupportedpastels.hermesandroid.connection.ServerCatalog
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ServerConnectionMode
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.TunnelTestResult
import com.unsupportedpastels.hermesandroid.gateway.CacheSource
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ExternalSshTunnelSetupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(sdk = [35], qualifiers = "w400dp-h900dp")
    fun compactSetupShowsInstructionsAndRejectsLocalhost() {
        assertSetupInstructionsAndLocalhostRejection()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w610dp-h900dp")
    fun mediumSetupShowsInstructionsAndRejectsLocalhost() {
        assertSetupInstructionsAndLocalhostRejection()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w900dp-h675dp")
    fun expandedSetupShowsInstructionsAndRejectsLocalhost() {
        assertSetupInstructionsAndLocalhostRejection()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w400dp-h900dp")
    fun largeTextSetupShowsInstructionsAndRejectsLocalhost() {
        assertSetupInstructionsAndLocalhostRejection(fontScale = 1.5f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w400dp-h900dp")
    fun compactTunnelSetupShowsWarningTestAndSaveWithoutScrolling() {
        val origin = ServerOrigin.parse(DEFAULT_TUNNEL_ORIGIN)
        composeRule.setContent {
            HermesAndroidTheme {
                ServerSettingsScreen(
                    serverOrigin = origin,
                    serverCatalog = ServerCatalog.single(
                        ServerCatalogEntry(
                            origin = origin,
                            connectionMode = ServerConnectionMode.ExternalSshTunnel,
                        ),
                    ),
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Connection type External SSH tunnel")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Server origin input").assertIsDisplayed()
        composeRule.onNodeWithText("Experimental").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Shared loopback warning").assertIsDisplayed()
        composeRule.onNodeWithText(LOOPBACK_SECURITY_WARNING, substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Test tunnel").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsDisplayed()

        val warningTop = composeRule.onNodeWithContentDescription("Shared loopback warning")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val testTop = composeRule.onNodeWithContentDescription("Test tunnel")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val saveTop = composeRule.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot.top
        assertTrue(warningTop < saveTop)
        assertTrue(testTop < saveTop)
        assertTrue(warningTop < testTop)
    }

    @Test
    fun testTunnelSuccessIsNonMutating() {
        var tested: ServerOrigin? = null
        var saved: ServerCatalogEntry? = null
        composeRule.setContent {
            HermesAndroidTheme {
                ServerSettingsScreen(
                    serverOrigin = null,
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                    onSaveEntry = {
                        saved = it
                        Result.success(Unit)
                    },
                    onTestTunnel = { origin ->
                        tested = origin
                        TunnelTestResult.Success
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Connection type External SSH tunnel")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Server origin input")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Test tunnel").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { tested != null }
        composeRule.onNodeWithText("Tunnel handshake succeeded", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(DEFAULT_TUNNEL_ORIGIN, tested?.value)
        assertNull(saved)
    }

    @Test
    fun tunnelUnavailableShowsRetryThatDoesNotClaimControlOfSsh() {
        var retries = 0
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Recovering,
                        tunnelConnectionFailure = TunnelConnectionFailure.TunnelUnavailable,
                        connectionError = "SSH tunnel unavailable",
                    ),
                    serverSettingsState = com.unsupportedpastels.hermesandroid.connection.ServerSettingsState.Ready(
                        ServerOrigin.parse(DEFAULT_TUNNEL_ORIGIN),
                    ),
                    onRetryConnection = { retries += 1 },
                )
            }
        }

        composeRule.onNodeWithText("SSH tunnel unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("cannot start the tunnel", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Retry reconnects this app only", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun credentialRejectedBannerShowsRetrySetupAndCancelAndFiresCallbacks() {
        var retries = 0
        var setups = 0
        var cancels = 0
        composeRule.setContent {
            HermesAndroidTheme {
                ConnectionRecoveryBanner(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        tunnelConnectionFailure = TunnelConnectionFailure.CredentialRejected,
                        connectionError = CREDENTIAL_REJECTED_USER_MESSAGE,
                    ),
                    onRetry = { retries += 1 },
                    onConnectionSetup = { setups += 1 },
                    onCancel = { cancels += 1 },
                    onAcceptNewServer = {},
                )
            }
        }

        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Connection setup").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(1, retries)
        assertEquals(1, setups)
        assertEquals(1, cancels)
    }

    @Test
    fun wrongServiceAndBootstrapIncompatibleAreDistinct() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        tunnelConnectionFailure = TunnelConnectionFailure.NotHermesEndpoint,
                        connectionError = "wrong",
                    ),
                    serverSettingsState = com.unsupportedpastels.hermesandroid.connection.ServerSettingsState.Ready(
                        ServerOrigin.parse(DEFAULT_TUNNEL_ORIGIN),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Wrong service on this port").assertIsDisplayed()
        composeRule.onNodeWithText("another local port", substring = true).assertIsDisplayed()
    }

    @Test
    fun protocolIncompatibleBannerShowsDistinctCopy() {
        composeRule.setContent {
            HermesAndroidTheme {
                ConnectionRecoveryBanner(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        tunnelConnectionFailure = TunnelConnectionFailure.ProtocolIncompatible,
                        connectionError = PROTOCOL_INCOMPATIBLE_MESSAGE,
                    ),
                    onRetry = {},
                    onConnectionSetup = {},
                    onCancel = {},
                    onAcceptNewServer = {},
                )
            }
        }

        composeRule.onNodeWithText("Protocol incompatible").assertIsDisplayed()
        composeRule.onNodeWithText(PROTOCOL_INCOMPATIBLE_MESSAGE).assertIsDisplayed()
        composeRule.onNodeWithText(MINIMUM_HERMES_VERSION, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Wrong service on this port").assertDoesNotExist()
        composeRule.onNodeWithText("Bootstrap unavailable").assertDoesNotExist()
        composeRule.onNodeWithText("Gated Hermes server").assertDoesNotExist()
    }

    @Test
    fun bootstrapUnavailableIsDistinctFromGatedServer() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        tunnelConnectionFailure = TunnelConnectionFailure.BootstrapRejected,
                        connectionError = "Hermes tunnel authorization bootstrap was rejected",
                    ),
                    serverSettingsState = com.unsupportedpastels.hermesandroid.connection.ServerSettingsState.Ready(
                        ServerOrigin.parse(DEFAULT_TUNNEL_ORIGIN),
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Bootstrap unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Gated Hermes server").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w900dp-h675dp")
    fun expandedTunnelSetupShowsWarningTestAndSaveWithoutScrolling() {
        val origin = ServerOrigin.parse(DEFAULT_TUNNEL_ORIGIN)
        composeRule.setContent {
            HermesAndroidTheme {
                ServerSettingsScreen(
                    serverOrigin = origin,
                    serverCatalog = ServerCatalog.single(
                        ServerCatalogEntry(
                            origin = origin,
                            connectionMode = ServerConnectionMode.ExternalSshTunnel,
                        ),
                    ),
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Shared loopback warning")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Test tunnel")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Save")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel")
            .performScrollTo()
            .assertIsDisplayed()

        val warningTop = composeRule.onNodeWithContentDescription("Shared loopback warning")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val testTop = composeRule.onNodeWithContentDescription("Test tunnel")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val saveTop = composeRule.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot.top
        assertTrue(warningTop < saveTop)
        assertTrue(testTop < saveTop)
        composeRule.onNodeWithText("Setup checklist", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue(saveTop < composeRule.onNodeWithText("Setup checklist", substring = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .top)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w610dp-h900dp")
    fun mediumInstallationChangedShowsSingleRecoveryBanner() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Recovering,
                        tunnelConnectionFailure = TunnelConnectionFailure.InstallationChanged,
                        connectionError = "This local port now appears to lead to a different Hermes installation.",
                    ),
                    serverSettingsState = com.unsupportedpastels.hermesandroid.connection.ServerSettingsState.Ready(
                        ServerOrigin.parse(DEFAULT_TUNNEL_ORIGIN),
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Connection recovery: Hermes installation changed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Accept new server").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onAllNodesWithText("different Hermes installation", substring = true)
            .assertCountEquals(1)
    }

    @Test
    fun recoveringInstallationChangedShowsAcceptAndCancelNotGenericReconnect() {
        var accepted = 0
        var cancelled = 0
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Recovering,
                        tunnelConnectionFailure = TunnelConnectionFailure.InstallationChanged,
                        connectionError = "This local port now appears to lead to a different Hermes installation.",
                        sessionMetadataSource = CacheSource.Cached,
                    ),
                    serverSettingsState = com.unsupportedpastels.hermesandroid.connection.ServerSettingsState.Ready(
                        ServerOrigin.parse(DEFAULT_TUNNEL_ORIGIN),
                    ),
                    onAcceptNewInstallation = { accepted += 1 },
                    onCancelRecovery = { cancelled += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Connection recovery: Hermes installation changed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cached offline data", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Accept new server").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, accepted)
        assertEquals(1, cancelled)
    }

    @Test
    fun switchingModesDoesNotLeakThePreviousOrigin() {
        var saved: ServerCatalogEntry? = null
        composeRule.setContent {
            HermesAndroidTheme {
                ServerSettingsScreen(
                    serverOrigin = null,
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                    onSaveEntry = {
                        saved = it
                        Result.success(Unit)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput("https://hermes.example")
        composeRule.onNodeWithContentDescription("Connection type External SSH tunnel")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Server origin input")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Shared loopback warning")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(LOOPBACK_SECURITY_WARNING, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Save").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(DEFAULT_TUNNEL_ORIGIN, saved?.origin?.value)
        assertEquals(ServerConnectionMode.ExternalSshTunnel, saved?.connectionMode)

        composeRule.onNodeWithContentDescription("Connection type Server URL")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Server origin input")
            .assertIsDisplayed()
        composeRule.onNodeWithText("https://hermes.example").assertIsDisplayed()
        composeRule.onNodeWithText(DEFAULT_TUNNEL_ORIGIN).assertDoesNotExist()
    }

    @Test
    fun selectedTunnelModeAndOriginSurviveProcessRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            HermesAndroidTheme {
                ServerSettingsScreen(
                    serverOrigin = null,
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Connection type External SSH tunnel")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithContentDescription("Server origin input").performTextClearance()
        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput("http://127.0.0.1:19119")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("http://127.0.0.1:19119").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Test tunnel").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("Experimental").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun rejectedMutationShowsExplicitRetryAction() {
        val sessionId = com.unsupportedpastels.hermesandroid.app.DurableSessionId("session-1")
        var retried: String? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        authenticationState = com.unsupportedpastels.hermesandroid.gateway.AuthenticationState.Authenticated,
                        durableSessions = listOf(
                            com.unsupportedpastels.hermesandroid.app.SessionSummary(
                                id = sessionId,
                                title = "Chat",
                            ),
                        ),
                        chatSessions = mapOf(
                            sessionId to com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot(
                                error = "Could not send message",
                            ),
                        ),
                    ),
                    initialRoute = com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute(sessionId),
                    onSendMessage = { _, text -> retried = text },
                )
            }
        }

        composeRule.onNodeWithText("Could not send message").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Retry action").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("retry me")
        composeRule.onNodeWithContentDescription("Retry action").performClick()
        assertEquals("retry me", retried)
    }

    private fun assertSetupInstructionsAndLocalhostRejection(fontScale: Float = 1f) {
        var saved: ServerCatalogEntry? = null
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                HermesAndroidTheme {
                    ServerSettingsScreen(
                        serverOrigin = null,
                        showBack = true,
                        onBack = {},
                        onSave = { Result.success(Unit) },
                        onSaveEntry = {
                            saved = it
                            Result.success(Unit)
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Connection type External SSH tunnel")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText("Configure the local port forward outside this app", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Termius", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Experimental").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Shared loopback warning")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Server origin input").performScrollTo().performTextClearance()
        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput("http://localhost:9119")
        composeRule.onNodeWithText(LOCALHOST_TUNNEL_MESSAGE, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Save").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertNull(saved)
        composeRule.onNodeWithText("paste a token", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
