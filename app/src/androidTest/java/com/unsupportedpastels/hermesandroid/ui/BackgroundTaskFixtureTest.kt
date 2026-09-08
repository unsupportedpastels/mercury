package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundTaskFixtureTest {
    @get:Rule val compose = createAndroidComposeRule<BackgroundTaskFixtureActivity>()
    @Test fun pendingBackgroundResponseHasReadableStatusInsteadOfBlankReply() {
        compose.activityRule.scenario.onActivity { it.intent.putExtra("waiting-for-final", true) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Background work continues. The final response is not available yet.").assertIsDisplayed()
        compose.onNodeWithTag("Message composer").assertIsDisplayed()
        capture("pending-final-response.png")
    }

    @Test fun connectionLossExposesRetryWithoutRestartingTheApp() {
        compose.activityRule.scenario.onActivity { it.intent.putExtra("connection-error", true) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Connection lost while receiving response").assertIsDisplayed()
        compose.onNodeWithContentDescription("Retry session connection").assertIsDisplayed()
        capture("session-retry-action.png")
        compose.onNodeWithContentDescription("Retry session connection").performClick()
        compose.onNodeWithText("Synthetic connection retry requested").assertIsDisplayed()
        compose.onAllNodesWithText("Connection lost while receiving response").assertCountEquals(0)
    }

    @Test fun processHistoryDoesNotAnimateAndUnavailableHistoryCanBeDismissed() {
        compose.activityRule.scenario.onActivity { it.intent.putExtra("unavailable-history", true) }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("Unified activity stack").assertIsDisplayed()
        compose.onAllNodesWithTag("Active work indicator").assertCountEquals(0)
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Historical task with unavailable status").assertIsDisplayed()
        capture("unavailable-activity-history.png")
        compose.onNodeWithText("Dismiss unavailable").performScrollTo().performClick()
        compose.onAllNodesWithTag("Background task strip").assertCountEquals(0)
        compose.onNodeWithTag("Message composer").assertIsDisplayed()
        capture("dismissed-activity-history.png")
    }
    @Test fun capturesPersistentStripAndDetails() {
        compose.onNodeWithText("Background tasks · 1 active · other status unavailable").assertIsDisplayed()
        capture("background-strip.png")
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Review lifecycle regressions").assertIsDisplayed()
        capture("background-details.png")
        compose.onNodeWithText("Hide details").performClick()
        compose.onNodeWithTag("Message composer").assertIsDisplayed()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
