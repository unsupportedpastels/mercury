package com.unsupportedpastels.hermesandroid.connection

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsupportedpastels.hermesandroid.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.io.File
import java.util.UUID

/**
 * Opt-in UI journey against the scripted startup fake. It verifies the actual
 * project-creation surface against the server's managed-folder endpoints:
 * browse an existing folder, create a child, then register a project there.
 *
 * Pass the origin as an instrumentation argument, for example:
 *
 *   -Pandroid.testInstrumentationRunnerArguments.FAKE_HERMES_ORIGIN=http://127.0.0.1:8787
 */
@RunWith(AndroidJUnit4::class)
class FakeHermesFolderEndToEndTest {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun grantNotificationPermission() {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val origin: String?
        get() = InstrumentationRegistry.getArguments()
            .getString("FAKE_HERMES_ORIGIN")
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun fakeServerReachable(origin: String): Boolean = runCatching {
        val uri = URI(origin)
        val host = uri.host ?: return@runCatching false
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        Socket().use { it.connect(InetSocketAddress(host, port), 2_000) }
        true
    }.getOrDefault(false)

    private fun waitForText(text: String, timeoutMillis: Long = 20_000) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                composeRule.onAllNodesWithText(text, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun waitForFolder(path: String, timeoutMillis: Long = 20_000) {
        try {
            composeRule.waitUntil(timeoutMillis) {
                runCatching {
                    composeRule.onNodeWithTag("Toggle create host folder").assertIsEnabled()
                    composeRule.onNodeWithTag("Host folder input").fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text == path
                }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            capture("failure")
            throw AssertionError(composeRule.onAllNodes(isRoot()).printToString(), failure)
        }
    }

    private fun capture(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metric = InstrumentationRegistry.getArguments().getString("metric") ?: "compact"
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "folders-$metric-$label.png")
        file.outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun browseExistingFolderCreateChildAndRegisterProject() {
        val suffix = UUID.randomUUID().toString().take(8)
        val folderName = "android-$suffix"
        val projectName = "Folder Project $suffix"
        val fakeOrigin = origin
        assumeTrue("FAKE_HERMES_ORIGIN was not supplied", fakeOrigin != null)
        assumeTrue("fake Hermes server is not reachable", fakeServerReachable(fakeOrigin!!))

        val uri = URI(fakeOrigin)
        val hostAndPort = buildString {
            append(uri.host)
            if (uri.port > 0) append(':').append(uri.port)
        }

        waitForText("Connect to Hermes")
        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput(hostAndPort)
        if (uri.scheme.equals("http", ignoreCase = true)) {
            composeRule.onNodeWithContentDescription("Use HTTPS checkbox").performScrollTo().performClick()
        }
        composeRule.onNodeWithText("Continue").performScrollTo().assertIsEnabled().performClick()

        waitForText("Sign in with username and password")
        composeRule.onNodeWithText("Sign in with username and password").performClick()
        composeRule.onNodeWithText("Password").performTextInput("e2epass")
        composeRule.onNodeWithText("Sign in").performClick()

        // Expanded navigation may collapse project titles into a dock. Wait
        // for the actual connected action rather than a particular list row.
        composeRule.waitUntil(20_000) {
            runCatching {
                composeRule.onNodeWithContentDescription("Create project").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithContentDescription("Create project").performClick()
        waitForFolder("/srv/mercury-e2e")
        // A locked managed root disables arbitrary path entry, not browsing
        // a server-returned child. Exercise the actual directory row.
        waitForText("workspace")
        composeRule.onNodeWithText("workspace").performClick()
        waitForFolder("/srv/mercury-e2e/workspace")
        capture("existing")
        composeRule.onNodeWithTag("Toggle create host folder").performScrollTo().performClick()
        composeRule.onNodeWithTag("New folder name input").performScrollTo().performTextInput(folderName)
        composeRule.waitForIdle()
        capture("new-folder-form")
        composeRule.onNodeWithTag("Confirm create host folder")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        waitForFolder("/srv/mercury-e2e/workspace/$folderName")
        capture("created")

        composeRule.onNodeWithTag("Project name input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("Project name input").performTextInput(projectName)
        composeRule.onNodeWithTag("Confirm create project")
            .assertIsEnabled()
            .performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("Create project sheet").fetchSemanticsNodes().isEmpty()
        }
        waitForText(projectName)
        capture("registered")
    }
}
