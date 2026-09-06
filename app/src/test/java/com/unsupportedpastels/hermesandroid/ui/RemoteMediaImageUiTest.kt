package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class RemoteMediaImageUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun managedCacheNeverCrossesOriginOrRelayScope() {
        val scope = androidx.compose.runtime.mutableStateOf("direct:https://a.example")
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val bytes = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalManagedImageScope provides scope.value) {
                RemoteMediaImage("/tmp/scoped-ui.png", loadManagedImage = {
                    calls.incrementAndGet()
                    if (scope.value.startsWith("relay:")) throw com.unsupportedpastels.hermesandroid.relay.RelayImageUnsupportedException()
                    bytes
                })
            }
        }
        composeRule.waitUntil(5000) { calls.get() > 0 }
        composeRule.waitForIdle()
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Generated image; tap to enlarge")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { scope.value = "relay:another-host" }
        composeRule.waitForIdle()
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("This Relay host does not support image reads")).fetchSemanticsNodes().isNotEmpty()
        }
        org.junit.Assert.assertEquals(2, calls.get())
        composeRule.onNodeWithContentDescription("Generated image; tap to enlarge").assertDoesNotExist()
        composeRule.onNodeWithText("This Relay host does not support image reads").assertIsDisplayed()
    }

    @Test
    fun staleNonCooperativeImageCannotPublishIntoReplacementScope() {
        val scope = androidx.compose.runtime.mutableStateOf("relay:old")
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val bytes = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        composeRule.setContent {
            val captured = scope.value
            androidx.compose.runtime.CompositionLocalProvider(LocalManagedImageScope provides captured) {
                RemoteMediaImage("/tmp/stale-ui.png", loadManagedImage = {
                    if (captured == "relay:old") {
                        started.set(true)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                        bytes
                    } else throw com.unsupportedpastels.hermesandroid.relay.RelayImageUnsupportedException()
                })
            }
        }
        composeRule.waitUntil(5000) { started.get() }
        composeRule.runOnIdle { scope.value = "direct:https://replacement.example" }
        composeRule.waitUntil(5000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("This Relay host does not support image reads")).fetchSemanticsNodes().isNotEmpty()
        }
        release.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Generated image; tap to enlarge").assertDoesNotExist()
        composeRule.onNodeWithText("This Relay host does not support image reads").assertIsDisplayed()
    }

    @Test
    fun tappingGeneratedImageOpensEnlargedViewerAndCloseDismissesIt() {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).asImageBitmap()
        composeRule.setContent {
            HermesAndroidTheme {
                LoadedRemoteMediaImage(bitmap = bitmap)
            }
        }

        composeRule.onNodeWithContentDescription("Generated image; tap to enlarge")
            .performClick()
        composeRule.onNodeWithContentDescription("Enlarged generated image")
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Close enlarged image")
            .performClick()
        composeRule.onNodeWithContentDescription("Enlarged generated image")
            .assertDoesNotExist()
    }

    @Test
    fun pinchingEnlargedImageChangesItsZoomLevel() {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).asImageBitmap()
        composeRule.setContent {
            HermesAndroidTheme {
                LoadedRemoteMediaImage(bitmap = bitmap)
            }
        }
        composeRule.onNodeWithContentDescription("Generated image; tap to enlarge")
            .performClick()

        composeRule.onNodeWithContentDescription("Enlarged generated image")
            .performTouchInput {
                pinch(
                    start0 = center + Offset(-40f, 0f),
                    start1 = center + Offset(40f, 0f),
                    end0 = center + Offset(-140f, 0f),
                    end1 = center + Offset(140f, 0f),
                    durationMillis = 300L,
                )
            }
            .assert(
                SemanticsMatcher("image is zoomed") { node ->
                    node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
                        ?.startsWith("Zoom 1") == false
                },
            )
    }
}