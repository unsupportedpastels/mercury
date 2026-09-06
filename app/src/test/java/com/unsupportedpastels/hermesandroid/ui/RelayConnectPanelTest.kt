package com.unsupportedpastels.hermesandroid.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.unsupportedpastels.hermesandroid.relay.RelayPairingPhase
import com.unsupportedpastels.hermesandroid.relay.RelayUiState
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayConnectPanelTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scanAndPasteBothDeliverThePairingCodeWithoutRenderingIt() {
        var scanCalls = 0
        var paired: String? = null
        compose.setContent {
            MaterialTheme {
                RelayConnectPanel(
                    state = RelayUiState(),
                    onScan = { scanCalls += 1 },
                    onPair = { paired = it },
                    onConnect = {},
                    onRemove = {},
                )
            }
        }

        compose.onNodeWithText("Pair with QR code").performClick()
        compose.onNodeWithText("Paste pairing code instead").performClick()
        compose.onNodeWithContentDescription("Relay pairing code input").performTextInput("secret-qr-payload")
        compose.onNodeWithText("Pair").performClick()

        assertEquals(1, scanCalls)
        assertEquals("secret-qr-payload", paired)
        compose.onAllNodesWithText("secret-qr-payload").assertCountEquals(0)
    }

    @Test
    fun approvedTargetConnectsAndPendingFingerprintIsExplained() {
        var connected: String? = null
        val approved = target(RelayTargetStatus.Approved)
        compose.setContent {
            MaterialTheme {
                RelayConnectPanel(
                    state = RelayUiState(
                        targets = listOf(approved),
                        phase = RelayPairingPhase.AwaitingApproval("pending", "0123456789abcdef"),
                    ),
                    onScan = {},
                    onPair = {},
                    onConnect = { connected = it.id },
                    onRemove = {},
                )
            }
        }

        compose.onNodeWithText("Study host").assertIsDisplayed().performClick()
        assertEquals(approved.id, connected)
        compose.onNodeWithText("0123 4567 89ab cdef").assertIsDisplayed()
        compose.onNodeWithText("Waiting for host approval").assertIsDisplayed()
    }

    private fun target(status: RelayTargetStatus) = RelayPairedTarget(
        id = "00000000-0000-4000-8000-000000000001",
        label = "Study host",
        relayOrigin = "https://relay.example.com",
        installationId = ByteArray(32),
        hostPublicKey = ByteArray(32) { 1 },
        deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { 2 }),
        deviceStaticPrivateKey = ByteArray(32) { 3 },
        fingerprint = "0123456789abcdef",
        status = status,
        createdAtEpochSeconds = 1,
        lastUsedEpochSeconds = null,
    )
}
