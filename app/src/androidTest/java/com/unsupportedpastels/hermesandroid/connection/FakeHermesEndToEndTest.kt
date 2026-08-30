package com.unsupportedpastels.hermesandroid.connection

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * True end-to-end drive of the release UI against the scripted fake Hermes
 * backend (tools/fake-hermes/fake_hermes.py running on the host): configure a
 * bare-host cleartext server via the Use HTTPS checkbox, password sign-in,
 * open a session, send a prompt over the real WebSocket, and verify the
 * interrupt-sentinel completion keeps the streamed partial and never renders
 * "Operation interrupted" prose.
 *
 * Skips (does not fail) when the fake server is not reachable, so the
 * ordinary connected suite stays hermetic.
 */
@RunWith(AndroidJUnit4::class)
class FakeHermesEndToEndTest {

    companion object {
        // Grant notifications before the activity launches so the first-run
        // permission dialog never covers the UI.
        @JvmStatic
        @org.junit.BeforeClass
        fun grantNotificationPermission() {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val hostAddress = "10.0.2.2"
    private val hostPort = 8787

    private fun fakeServerReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(hostAddress, hostPort), 2_000) }
        true
    }.getOrDefault(false)

    /** waitUntil whose condition tolerates the hierarchy not being composed yet. */
    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        try {
            composeRule.waitUntil(timeoutMillis) {
                runCatching {
                    composeRule.onAllNodesWithText(text, substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val tree: String = runCatching {
                composeRule.onRoot().printToString(maxDepth = 12)
            }.getOrElse { failure -> "unavailable: $failure" }
            throw AssertionError("timed out waiting for \"$text\"; on screen:\n$tree", timeout)
        }
    }

    private fun hasText(text: String): Boolean = runCatching {
        composeRule.onAllNodesWithText(text, substring = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    @Test
    fun bareHostCleartextConnectSendAndSentinelSuppression() {
        assumeTrue("fake hermes server not running on host", fakeServerReachable())

        // Configure the server through the real dialog: bare host, HTTPS off.
        waitForText("Configure server")
        composeRule.onNodeWithText("Configure server").performClick()
        composeRule.onNodeWithContentDescription("Open Servers settings").performClick()
        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput("$hostAddress:$hostPort")
        composeRule.onNodeWithContentDescription("Use HTTPS checkbox").performClick()
        composeRule.onNodeWithText("Save").assertIsEnabled().performScrollTo().performClick()
        composeRule.waitForIdle()

        // Password sign-in against the fake backend.
        waitForText("Sign in with username and password")
        composeRule.onNodeWithText("Sign in with username and password").performClick()
        composeRule.onNodeWithText("Password").performTextInput("e2epass")
        composeRule.onNodeWithText("Sign in").performClick()

        // Authenticated home shows the fake's session; open it.
        waitForText("E2E Session")
        composeRule.onAllNodesWithText("E2E Session", substring = true)[0].performClick()

        // Send a prompt through the real composer and WebSocket.
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onAllNodes(
                    androidx.compose.ui.test.hasTestTag("Message composer"),
                ).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        try {
            composeRule.waitUntil(20_000) {
                runCatching {
                    composeRule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())
                        .fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val tree: String = runCatching {
                composeRule.onRoot().printToString(maxDepth = 12)
            }.getOrElse { failure -> "unavailable: $failure" }
            throw AssertionError("composer never became editable; on screen:\n$tree", timeout)
        }
        composeRule.onNode(androidx.compose.ui.test.hasSetTextAction())
            .performTextInput("stream then interrupt")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        // The fake streams "The answer is 42" then completes with the
        // interrupt sentinel. The partial must render; the sentinel must not.
        waitForText("The answer is 42", timeoutMillis = 30_000)
        // Give the sentinel completion time to arrive, then assert suppression.
        Thread.sleep(10_000)
        composeRule.waitForIdle()
        assert(!hasText("Operation interrupted")) {
            "interrupt sentinel was rendered as assistant prose"
        }
        assert(hasText("The answer is 42")) {
            "streamed partial was lost after sentinel completion"
        }
    }
}
