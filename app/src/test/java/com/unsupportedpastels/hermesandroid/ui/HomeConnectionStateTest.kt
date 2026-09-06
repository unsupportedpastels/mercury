package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeConnectionStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun unconfiguredDirectStillOffersHttpsSetup() {
        renderDisconnected(ServerSettingsState.Ready(null))
        composeRule.onNodeWithContentDescription("Sessions. Connection: Not configured").assertIsDisplayed()
        composeRule.onNodeWithText("No server configured").assertIsDisplayed()
        composeRule.onNodeWithText("Add the HTTPS origin of your Hermes server.").assertIsDisplayed()
        composeRule.onNodeWithText("Configure server").performClick()
        composeRule.onAllNodesWithText("Retry").assertCountEquals(0)
    }

    @Test
    fun configuredDirectStillOffersRetryAndEditServer() {
        var retries = 0
        renderDisconnected(
            ServerSettingsState.Ready(com.unsupportedpastels.hermesandroid.connection.ServerOrigin.parse("https://hermes.example")),
            onRetry = { retries++ },
        )
        composeRule.onNodeWithContentDescription("Sessions. Connection: Offline").assertIsDisplayed()
        composeRule.onNodeWithText("Server configured").assertIsDisplayed()
        composeRule.onNodeWithText("https://hermes.example").assertIsDisplayed()
        composeRule.onNodeWithText("Edit server").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun selectedRelayTakesPrecedenceOverSavedDirectOriginEvenWithoutLabel() {
        renderDisconnected(
            ServerSettingsState.Ready(com.unsupportedpastels.hermesandroid.connection.ServerOrigin.parse("https://hermes.example")),
            relayId = "saved-relay",
        )
        composeRule.onNodeWithText("Relay configured").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onAllNodesWithText("Server configured").assertCountEquals(0)
        composeRule.onAllNodesWithText("Edit server").assertCountEquals(0)
    }

    @Test
    fun selectedRelayRemainsRetryableWhileDirectSettingsLoad() {
        renderDisconnected(ServerSettingsState.Loading, relayId = "saved-relay")
        composeRule.onNodeWithText("Relay configured").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onAllNodesWithText("Loading server settings").assertCountEquals(0)
    }

    private fun renderDisconnected(
        settings: ServerSettingsState,
        relayId: String? = null,
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        relayTargetId = relayId,
                    ),
                    serverSettingsState = settings,
                    onRetryConnection = onRetry,
                )
            }
        }
    }

    @Test
    fun configuredRelayOpeningFailureShowsOfflineAndRetriesSavedConnection() {
        var retries = 0
        val error = "The relay or host is unreachable. Check that the host is online, then retry."
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        relayTargetId = "saved-relay",
                        relayTargetLabel = "Office relay",
                        connectionError = error,
                    ),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onRetryConnection = { retries++ },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Sessions. Connection: Offline").assertIsDisplayed()
        composeRule.onNodeWithText("Office relay").assertIsDisplayed()
        composeRule.onNodeWithText("Relay configured").assertIsDisplayed()
        composeRule.onNodeWithText(error).assertIsDisplayed()
        listOf("Not configured", "No server configured", "Add the HTTPS origin of your Hermes server.", "Configure server", "Edit server").forEach {
            composeRule.onAllNodesWithText(it).assertCountEquals(0)
        }
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
