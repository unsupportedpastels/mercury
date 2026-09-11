package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic device fixture proving both accepted syntaxes reach the managed loader and an Image node. */
@RunWith(AndroidJUnit4::class)
class MarkdownManagedImageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediaAndExplicitMarkdownSyntaxRenderThroughManagedImageLoader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requested = ConcurrentLinkedQueue<String>()
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                Canvas(this).drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.YELLOW })
            }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val mediaPath = "/tmp/instrumented-media-29.png"
        val markdownPath = "/tmp/instrumented-markdown-29.png"

        composeRule.setContent {
            CompositionLocalProvider(LocalManagedImageScope provides "instrumented:inline-markdown-images") {
                HermesAndroidTheme {
                    MarkdownMessage(
                        text = "MEDIA:$mediaPath\n\nInline prose ![chart]($markdownPath) remains",
                        loadManagedImage = { path ->
                            requested += path
                            bytes
                        },
                    )
                }
            }
        }

        composeRule.waitUntil(10_000) { requested.size == 2 }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Generated image; tap to enlarge"))
                .fetchSemanticsNodes().size == 2
        }
        assertEquals(setOf(mediaPath, markdownPath), requested.toSet())
        composeRule.onAllNodes(hasContentDescription("Generated image; tap to enlarge"))
            .assertCountEquals(2)

        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(750)
        val screenshot = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "inline-markdown-images.png",
        )
        screenshot.outputStream().use { output ->
            instrumentation.uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        Log.i("InlineMarkdownImageQA", "screenshot=${screenshot.absolutePath}")
    }
}
