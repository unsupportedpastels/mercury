package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Whole-screen synthetic acceptance; never real host/task execution evidence. */
@RunWith(AndroidJUnit4::class)
class BackgroundTaskFixtureTest {
    @get:Rule val compose = createAndroidComposeRule<BackgroundTaskFixtureActivity>()

    @Test fun emptyScreenHasZeroActivityPills() {
        fixture("empty-activity")
        noLine()
        compose.onAllNodesWithTag("Turn activity").assertCountEquals(0)
        capture("activity-empty.png")
    }
    @Test fun combinedHistoryIsQuietButAvailableInDetails() {
        fixture("combined-history")
        noLine()
        capture("activity-combined.png")
        openDetails()
        sheetText("Synthetic review complete").assertIsDisplayed()
        sheetText("Finished").assertIsDisplayed()
        scrollTo("Synthetic build")
        sheetText("exited (0)").assertIsDisplayed()
        compose.onAllNodesWithText("time unavailable", substring = true).assertCountEquals(0)
        noSpinner()
        capture("activity-combined-details.png")
        pressBack()
        keyboard()
        noLine()
        capture("activity-combined-keyboard.png")
    }
    @Test fun pendingBackgroundResponseUsesOnlyTheActivityLine() {
        fixture("waiting-for-final")
        line("1 background task")
        compose.onAllNodesWithText("Background work continues. The final response is not available yet.").assertCountEquals(0)
        capture("pending-final-response.png")
        openDetails()
        sheetText("Review lifecycle regressions").assertIsDisplayed()
    }
    @Test fun connectionLossExposesRetryWithoutRestartingTheApp() {
        fixture("connection-error")
        compose.onNodeWithText("Connection lost while receiving response").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry session connection").assertIsDisplayed()
        capture("session-retry-action.png")
        compose.onNodeWithContentDescription("Retry session connection").performClick()
        compose.onNodeWithText("Synthetic connection retry requested").assertIsDisplayed()
        compose.onAllNodesWithText("Connection lost while receiving response").assertCountEquals(0)
    }
    @Test fun processHistoryDoesNotAnimateAndUnavailableHistoryCanBeDismissed() {
        fixture("unavailable-history")
        noLine()
        openDetails()
        sheetText("Historical task with unavailable status").assertIsDisplayed()
        noSpinner()
        capture("unavailable-activity-history.png")
        scrollTo("Dismiss unavailable")
        sheetText("Dismiss unavailable").performClick()
        compose.onAllNodesWithText("Historical task with unavailable status").assertCountEquals(0)
        scrollTo("Synthetic supporting process")
        sheetText("Synthetic supporting process").assertIsDisplayed()
        pressBack()
        noLine()
        capture("dismissed-activity-history.png")
    }
    @Test fun capturesChildAfterParentAndDetails() {
        line("1 background task")
        capture("background-strip.png")
        openDetails()
        sheetText("Review lifecycle regressions").assertIsDisplayed()
        capture("background-details.png")
        pressBack()
        keyboard()
        line("1 background task")
        capture("background-keyboard.png")
    }
    @Test fun sheetRefreshAndReconnectInvokeDistinctScopedCallbacks() {
        fixture("connection-error")
        openDetails()
        compose.onNode(hasContentDescription("Get progress update (read-only)") and inSheet()).performClick()
        sheetText("Reconnect").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("Synthetic read-only update requested").assertIsDisplayed()
        compose.onAllNodesWithText("Synthetic connection retry requested").assertCountEquals(0)
        openDetails()
        sheetText("Reconnect").performClick()
        pressBack()
        compose.onNodeWithText("Synthetic connection retry requested").assertIsDisplayed()
        compose.onAllNodesWithText("Connection lost while receiving response").assertCountEquals(0)
    }
    @Test fun workingHasOneLineStopAndReachableKeyboard() {
        mode("working")
        line("Running · Checking tests")
        compose.onNodeWithContentDescription("Stop Hermes response").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Show thinking").assertCountEquals(0)
        capture("activity-working.png")
        openDetails()
        scrollTo("Tools")
        compose.onNodeWithContentDescription("Running tool terminal: Checking tests").assertIsDisplayed()
        capture("activity-working-details.png")
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
        keyboard()
        line("Running · Checking tests")
        capture("activity-working-keyboard.png")
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNodeWithContentDescription("Stop Hermes response").assertIsDisplayed().assertIsEnabled()
    }
    @Test fun reasoningStreamsBehindThinkingLine() {
        mode("thinking")
        line("Thinking")
        compose.onAllNodesWithContentDescription("Show thinking").assertCountEquals(0)
        capture("activity-thinking.png")
        keyboard()
        capture("activity-thinking-keyboard.png")
    }
    @Test fun longActivityKeepsTimerAfterDisclosureWithKeyboard() {
        compose.activityRule.scenario.onActivity { it.intent.putExtra("long-activity-label", true) }
        mode("working")
        keyboard()
        val label = compose.onNodeWithTag("Activity label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val disclosure = compose.onNodeWithTag("Activity disclosure", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val elapsed = compose.onNodeWithTag("Activity elapsed", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        check(label.right <= disclosure.left && disclosure.right < elapsed.left)
        compose.onNodeWithTag("Composer activity line").assertHeightIsEqualTo(32.dp)
        capture("activity-long-keyboard.png")
    }
    @Test fun streamingAnswerRemainsVisible() {
        mode("streaming")
        line("Writing")
        compose.onNodeWithText("The synthetic review is complete. The final answer stays readable.").assertIsDisplayed()
        capture("activity-streaming.png")
    }
    @Test fun finishedTurnFoldsStepsWithoutPinnedChrome() {
        mode("finished")
        noLine()
        compose.onNodeWithText("The synthetic review is complete. The final answer stays readable.").assertIsDisplayed()
        compose.onNodeWithTag("Turn activity").assertIsDisplayed()
        capture("activity-finished.png")
        compose.onNodeWithTag("Turn activity").performClick()
        compose.onNodeWithText("Inspecting the synthetic change").assertIsDisplayed()
        capture("activity-finished-expanded.png")
    }
    @Test fun needsYouRetainsActionableQuestion() {
        mode("needs-you")
        line("Needs you")
        compose.onNodeWithText("Which environment?").assertIsDisplayed()
        compose.onNodeWithText("Staging").assertIsDisplayed().assertIsEnabled()
        capture("activity-needs-you.png")
        compose.onNodeWithText("Staging").performClick()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        compose.onNodeWithText("Synthetic Staging answer received").assertIsDisplayed()
        compose.onAllNodesWithText("Which environment?").assertCountEquals(0)
        capture("activity-question-answered.png")
    }
    @Test fun reconnectRetainsStaticToolEvidence() {
        mode("reconnecting")
        line("Reconnecting")
        capture("activity-reconnecting.png")
        openDetails()
        scrollTo("Tools")
        noSpinner()
        capture("activity-reconnecting-details.png")
    }

    private fun fixture(extra: String) {
        compose.activityRule.scenario.onActivity { it.intent.putExtra(extra, true) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }
    private fun mode(value: String) {
        compose.activityRule.scenario.onActivity { it.intent.putExtra("activity-mode", value) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }
    private fun line(label: String) {
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(1)
        compose.onNodeWithTag("Composer activity line").assertIsDisplayed().assertHeightIsEqualTo(32.dp)
        compose.onNodeWithContentDescription("Activity: $label").assertIsDisplayed()
        noLegacy()
    }
    private fun noLine() {
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
        noLegacy()
    }
    private fun noLegacy() {
        for (tag in listOf("Session progress strip", "Background task strip", "Unified activity stack", "Active work indicator"))
            compose.onAllNodesWithTag(tag).assertCountEquals(0)
        compose.onAllNodesWithText("Hermes is responding…").assertCountEquals(0)
        compose.onAllNodesWithText("No progress yet").assertCountEquals(0)
        compose.onNodeWithTag("Message composer").assertIsDisplayed()
    }
    private fun noSpinner() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo) and inSheet()).assertCountEquals(0)
    }
    private fun keyboard() {
        // Wait for the modal's exit transition before focusing the input below it.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("Session activity sheet").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Synthetic unsent draft")
        compose.activityRule.scenario.onActivity {
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(10_000) {
            var visible = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                if (!visible && compose.activity.window.decorView.hasWindowFocus()) {
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                        .show(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
            }
            visible
        }
        compose.onNodeWithTag("Message composer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Change session model").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open session details").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Composer options").assertCountEquals(0)
    }
    private fun inSheet() = hasAnyAncestor(hasTestTag("Session activity sheet"))
    private fun sheetText(text: String) = compose.onNode(hasText(text) and inSheet())
    private fun scrollTo(text: String) = compose.onNodeWithTag("Session activity sheet").performScrollToNode(hasText(text))
    private fun openDetails() {
        // A previous fixture can leave the IME visible across Activity recreation.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("Composer activity line").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("Composer activity line").performClick()
        else {
            compose.onNodeWithContentDescription("Open session details").performClick()
            compose.onNodeWithContentDescription("Open activity details").performClick()
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Semantics can publish before RenderThread commits the matching pixels.
        val committed = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            compose.activity.window.decorView.apply {
                viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                invalidate()
            }
        }
        check(committed.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Screenshot frame was not committed" }
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            finally { bitmap.recycle() }
        }
    }
}
