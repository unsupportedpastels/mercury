package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackgroundTaskFixtureTest {
    @get:Rule val compose = createAndroidComposeRule<BackgroundTaskFixtureActivity>()
    @Test fun capturesPersistentStripAndDetails() {
        compose.onNodeWithText("Background tasks · 1 active").assertIsDisplayed()
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
