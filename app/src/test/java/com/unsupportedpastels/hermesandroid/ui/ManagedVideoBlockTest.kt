package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ManagedVideoBlockTest {
    @get:Rule val rule = createComposeRule()

    @Test fun unavailableTransportDoesNotOfferPlayback() {
        rule.setContent { MaterialTheme { ManagedVideoBlock("/a/clip.mp4") } }
        rule.onAllNodesWithText("Video playback is unavailable").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Play video").assertCountEquals(0)
    }

    @Test fun downloadIsExplicitAndErrorsAreSafe() {
        var requested: String? = null
        rule.setContent {
            MaterialTheme {
                MarkdownMessage("MEDIA:/a/clip.mp4", loadManagedVideo = { path ->
                    requested = path
                    Result.failure(IllegalStateException("private transport detail"))
                })
            }
        }
        rule.runOnIdle { assertEquals(null, requested) }
        rule.onNodeWithContentDescription("Play video").performClick()
        rule.runOnIdle { assertEquals("/a/clip.mp4", requested) }
        rule.onAllNodesWithText("Could not load video").assertCountEquals(1)
        rule.onAllNodesWithText("private transport detail").assertCountEquals(0)
    }
}
