package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageMarkdownImageUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediaAndExplicitMarkdownImagesReachTheSameManagedLoaderAndRender() {
        val requested = ConcurrentLinkedQueue<String>()
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
                Canvas(this).drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.YELLOW })
            }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val mediaPath = "/tmp/ui-media-11.png"
        val markdownPath = "/tmp/ui-markdown-11.png"

        composeRule.setContent {
            CompositionLocalProvider(LocalManagedImageScope provides "test:inline-markdown-images") {
                HermesAndroidTheme {
                    MarkdownMessage(
                        text = "Before\n\nMEDIA:$mediaPath\n\nMiddle ![chart]($markdownPath) after",
                        loadManagedImage = { path ->
                            requested += path
                            bytes
                        },
                    )
                }
            }
        }

        composeRule.waitUntil(5_000) { requested.size == 2 }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasContentDescription("Generated image; tap to enlarge"))
                .fetchSemanticsNodes().size == 2
        }
        assertEquals(setOf(mediaPath, markdownPath), requested.toSet())
        composeRule.onAllNodes(hasContentDescription("Generated image; tap to enlarge"))
            .assertCountEquals(2)
        composeRule.onNodeWithText("Before").assertIsDisplayed()
        composeRule.onNodeWithText("Middle").assertIsDisplayed()
        composeRule.onAllNodesWithText("after", substring = true).assertCountEquals(1)
    }
}
