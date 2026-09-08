package com.unsupportedpastels.hermesandroid.connection

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsupportedpastels.hermesandroid.MainActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly selected fake-network contract test, NOT real Hermes host success.
 * Start a fresh fake process for EACH metric:
 * FAKE_HERMES_SCENARIO=android-progress FAKE_HERMES_TEST_KEY=<test-key> python3 tools/fake-hermes/fake_hermes.py 8787
 * On a disposable, signed-out emulator, select this class with instrumentation arguments:
 * -e FAKE_HERMES_TEST_KEY <test-key> -e FAKE_HERMES_ORIGIN http://10.0.2.2:8787 -e metric compact
 * Repeat with externally configured medium/expanded metrics; this test never changes device metrics.
 * Pull external files/session-progress-<metric>-*.png BEFORE clearing/uninstalling the test app.
 * Missing/wrong fake server or a preauthenticated app fails, never silently skips.
 * The harness alone calls /__test__/progress. The production app uses only official APIs.
 */
@RunWith(AndroidJUnit4::class)
class SessionProgressEndToEndTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val metric get() = arguments.getString("metric") ?: "compact"

    @Test fun coldHistoryRefreshAndRecreateNeverSubmitWork() {
        require(metric in setOf("compact", "medium", "expanded"))
        try {
            val initialState = control()
            assertEquals("Use a fresh fake process for each run", 1, initialState.getInt("revision"))
            assertObserverOnly(initialState)
            // Require the real signed-out journey rather than injecting credentials or a ViewModel.
            waitText("Connect to Hermes")
            rule.onNodeWithText("Self-hosted").performClick()
            val uri = URI(origin())
            rule.onNodeWithContentDescription("Server origin input")
                .performTextInput("${uri.host}:${uri.port}")
            rule.onNodeWithContentDescription("Use HTTPS checkbox").performClick()
            rule.onNodeWithText("Continue").performClick()
            waitText("Sign in with username and password")
            rule.onNodeWithText("Sign in with username and password").performClick()
            rule.onNodeWithText("Password").performTextInput("e2epass")
            rule.onNodeWithText("Sign in").assertIsEnabled().performClick()
            waitText("E2E Session")
            rule.onAllNodesWithText("E2E Session")[0].performClick()
            // Empty initial loads emit no strip. Wait for authoritative history,
            // not the old unconditional refresh surface, before opening details.
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("Turn activity").fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
            capture("cold-history")
            openProgress()
            waitText("Verify progress recovery")
            sheetText("In progress").assertIsDisplayed()
            scrollSheetTo("Verify progress recovery")
            sheetText("Verify progress recovery").assertIsDisplayed()
            scrollSheetTo("Inspect progress contract")
            sheetText("Inspect progress contract").assertIsDisplayed()
            scrollSheetTo("Install test build")
            sheetText("Install test build").assertIsDisplayed()
            // Opening this idle fake session performs an asynchronous initial
            // resume after history loads. Fence that before measuring refresh.
            rule.waitUntil(30_000) { control().getJSONObject("rpc_counts").optInt("session.resume", 0) == 1 }
            val before = control()
            assertTrue("Cold startup must read official saved history", before.getInt("history_reads") > 0)
            assertObserverOnly(before)
            capture("initial-milestones")

            // Simulate a separate host writer, not an app Send/resume action.
            assertEquals(2, control("POST").getInt("revision"))
            assertEquals(2, control().getInt("revision"))
            rule.onNodeWithTag("Session activity sheet").performScrollToNode(
                hasContentDescription("Get progress update (read-only)"))
            rule.onNode(hasContentDescription("Get progress update (read-only)") and inSheet())
                .assertIsEnabled().performClick()
            rule.waitUntil(30_000) {
                control().getInt("history_reads") > before.getInt("history_reads")
            }
            // Historical milestones never pin a live line after the turn ends.
            rule.waitUntil(30_000) { rule.onAllNodes(hasText("In progress") and inSheet()).fetchSemanticsNodes().isEmpty() }
            pressBack()
            rule.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
            assertObserverOnly(control())
            assertEquals("Get update must not resume or take over a runtime",
                before.getJSONObject("rpc_counts").optInt("session.resume", 0),
                control().getJSONObject("rpc_counts").optInt("session.resume", 0))
            capture("updated-milestones")
            openProgress()
            scrollSheetTo("Done")
            sheetText("Done").assertIsDisplayed()
            scrollSheetTo("Tool reports")
            // Evidence is explicitly expanded, not promoted into milestone completion.
            rule.onAllNodes(hasText("Synthetic checks passed", substring = true) and inSheet())
                .assertCountEquals(0)
            sheetText("Tool reports").performClick()
            rule.onAllNodesWithText("independent verification", substring = true).assertCountEquals(0)
            scrollSheetTo("Synthetic checks passed", substring = true)
            rule.onNode(hasText("Synthetic checks passed", substring = true) and inSheet()).assertIsDisplayed()
            capture("synthetic-evidence")
            pressBack()

            rule.activityRule.scenario.recreate()
            rule.waitUntil(30_000) { rule.onAllNodesWithTag("Turn activity").fetchSemanticsNodes().isNotEmpty() }
            // Recreated MainActivity opens a new controller asynchronously after
            // restoring history. Fence its resume before checking settled chrome.
            rule.waitUntil(30_000) {
                control().getJSONObject("rpc_counts").optInt("session.resume", 0) >=
                    before.getJSONObject("rpc_counts").optInt("session.resume", 0) + 1 &&
                    rule.onAllNodesWithTag("Composer activity line").fetchSemanticsNodes().isEmpty()
            }
            rule.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
            openProgress()
            scrollSheetTo("Verify progress recovery")
            sheetText("Verify progress recovery").assertIsDisplayed()
            scrollSheetTo("Install test build")
            sheetText("Install test build").assertIsDisplayed()
            capture("recreated")
            pressBack()
            rule.onNode(hasSetTextAction()).performClick().performTextInput("Synthetic unsent draft")
            rule.waitUntil(10_000) {
                var imeVisible = false
                instrumentation.runOnMainSync {
                    imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                }
                imeVisible
            }
            rule.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
            rule.onNodeWithTag("Message composer").assertIsDisplayed()
            capture("keyboard")
            val finalState = control()
            assertObserverOnly(finalState)
            File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
                "session-progress-$metric-counters.json").writeText(finalState.toString(2))
        } catch (failure: Throwable) {
            runCatching { capture("failure") }
            throw AssertionError("Synthetic progress journey failed: ${failure.message}", failure)
        }
    }

    private fun inSheet() = hasAnyAncestor(hasTestTag("Session activity sheet"))
    private fun sheetText(text: String) = rule.onNode(hasText(text) and inSheet())
    private fun scrollSheetTo(text: String, substring: Boolean = false) {
        rule.onNodeWithTag("Session activity sheet").performScrollToNode(hasText(text, substring = substring))
    }
    private fun openProgress() {
        waitDescription("Open session details")
        rule.onNodeWithContentDescription("Open session details").performClick()
        rule.onNodeWithContentDescription("Open activity details").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("Session activity sheet").fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun waitText(text: String) = rule.waitUntil(30_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitDescription(text: String) = rule.waitUntil(30_000) {
        rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun assertObserverOnly(state: JSONObject) {
        val counts = state.getJSONObject("rpc_counts")
        // Opening an idle session uses the existing resume/read path. Only the
        // refresh action is required to leave that count unchanged above.
        for (method in listOf("prompt.submit", "session.activate", "session.create",
            "session.close", "session.interrupt", "session.steer", "config.set")) {
            assertEquals("Observer journey must not call $method", 0, counts.optInt(method, 0))
        }
    }
    private fun capture(label: String) {
        rule.waitForIdle()
        val committed = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            rule.activity.window.decorView.apply {
                viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                invalidate()
            }
        }
        check(committed.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Screenshot frame was not committed" }
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(directory, "session-progress-$metric-$label.png").outputStream().use { output ->
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            finally { bitmap.recycle() }
        }
    }

    companion object {
        private fun origin(): String {
            val value = InstrumentationRegistry.getArguments().getString("FAKE_HERMES_ORIGIN")
                ?: "http://10.0.2.2:8787"
            val uri = URI(value)
            require(uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost") &&
                uri.port in 1..65535 && uri.rawPath.isNullOrEmpty() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null) { "Use a bare emulator-local fake origin" }
            return value
        }
        private fun control(method: String = "GET"): JSONObject {
            val key = requireNotNull(InstrumentationRegistry.getArguments().getString("FAKE_HERMES_TEST_KEY")) {
                "Select this test with FAKE_HERMES_TEST_KEY and a fresh android-progress fake server"
            }
            require(key.isNotBlank())
            val connection = (URL(origin() + "/__test__/progress").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5_000
                readTimeout = 5_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("X-Fake-Hermes-Test-Key", key)
                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            return try {
                if (method == "POST") connection.outputStream.use { it.write("{}".toByteArray()) }
                check(connection.responseCode == 200) { "Missing/wrong progress fake: HTTP ${connection.responseCode}" }
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 16_384) { "Fake control response exceeded its bound" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                JSONObject(bytes.toString(Charsets.UTF_8)).also {
                    check(it.getString("scenario") == "android-progress" && it.getBoolean("synthetic"))
                }
            } finally { connection.disconnect() }
        }
        @JvmStatic @BeforeClass fun preflight() {
            // Runs before MainActivity starts: fail explicitly rather than skipping an absent server.
            assertEquals("Restart the fake server before this selected test", 1, control().getInt("revision"))
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
