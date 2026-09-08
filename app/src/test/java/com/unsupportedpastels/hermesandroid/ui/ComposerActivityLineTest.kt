package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.mercury.core.activity.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComposerActivityLineTest {
    @get:Rule val compose = createComposeRule()
    private val working = ActivityLineState(ActivityLineKind.Working, "Running · tests", true, true)

    @Test fun lineHasButtonSemanticsTimerAndClickActionWithoutMotion() {
        var opens = 0
        compose.setContent { MaterialTheme {
            ComposerActivityLine(working, 1000, { opens++ }, nowOverride = 66000, motionAllowedOverride = false)
        } }
        compose.onNodeWithTag("Composer activity line")
            .assertHeightIsEqualTo(32.dp)
            .assertContentDescriptionEquals("Activity: Running · tests")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .performClick()
        compose.onNodeWithText("1:05", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, opens)
    }

    @Test fun hiddenEmitsNoNodeAndTimerRequiresValidStartAndFlag() {
        val state = mutableStateOf(working)
        val start = mutableStateOf<Long?>(null)
        compose.setContent { MaterialTheme {
            ComposerActivityLine(state.value, start.value, {}, nowOverride = 1000, motionAllowedOverride = true)
        } }
        compose.onAllNodesWithText("0s", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { start.value = 2000 }
        compose.onAllNodesWithText("0s", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { start.value = 1000 }
        compose.onNodeWithText("0s", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { state.value = ActivityLineState(ActivityLineKind.NeedsYou, "Needs you", false, false) }
        compose.onNodeWithContentDescription("Activity: Needs you").assertIsDisplayed()
        compose.onAllNodesWithText("0s", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { state.value = ActivityLineState(ActivityLineKind.Hidden, "", false, false) }
        compose.onAllNodesWithTag("Composer activity line").assertCountEquals(0)
    }

    @Test fun heldLabelsWaitButKindsDoNot() {
        val candidate = mutableStateOf(working)
        val now = mutableLongStateOf(0)
        compose.setContent { MaterialTheme {
            ComposerActivityLine(rememberHeldActivityLine(candidate.value, now.longValue), null, {}, motionAllowedOverride = false)
        } }
        compose.runOnIdle { candidate.value = working.copy(label = "Editing") }
        compose.onNodeWithContentDescription("Activity: Running · tests").assertIsDisplayed()
        compose.runOnIdle { now.longValue = 1200 }
        compose.onNodeWithContentDescription("Activity: Editing").assertIsDisplayed()
        compose.runOnIdle { candidate.value = ActivityLineState(ActivityLineKind.ConnectionLost, "Connection lost", false, false) }
        compose.onNodeWithContentDescription("Activity: Connection lost").assertIsDisplayed()
    }

    @Test fun compactLargeTextKeepsSingleRowAndTimerTicksWithoutMotion() {
        val startedAt = System.currentTimeMillis() - 59000
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, 1.5f),
            ) {
                MaterialTheme {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.requiredWidth(280.dp)
                    ) {
                        ComposerActivityLine(working.copy(label = "Long activity ".repeat(20)),
                            startedAt, {}, motionAllowedOverride = false)
                    }
                }
            }
        }
        compose.onNodeWithTag("Composer activity line").assertHeightIsEqualTo(32.dp)
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("1:00", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun disclosureHugsLabelAndElapsedStaysRightForLongLargeText() {
        val label = mutableStateOf("Thinking")
        val now = mutableLongStateOf(10_000)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, 1.5f),
            ) { MaterialTheme {
                ComposerActivityLine(working.copy(label = label.value), 1000, {},
                    modifier = androidx.compose.ui.Modifier.requiredWidth(280.dp),
                    nowOverride = now.longValue, motionAllowedOverride = false)
            } }
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val timer = bounds("Activity elapsed")
        val disclosure = bounds("Activity disclosure")
        org.junit.Assert.assertTrue(disclosure.left >= bounds("Activity label").right)
        org.junit.Assert.assertTrue(timer.left > disclosure.right)
        compose.runOnIdle { label.value = "Checking a very long activity label ".repeat(12); now.longValue = 66_000 }
        val longLabel = bounds("Activity label")
        org.junit.Assert.assertTrue(longLabel.right <= bounds("Activity disclosure").left)
        org.junit.Assert.assertTrue(bounds("Activity disclosure").right < bounds("Activity elapsed").left)
        assertEquals(timer, bounds("Activity elapsed"))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("Activity label", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertEquals(androidx.compose.ui.text.style.TextOverflow.Ellipsis, layouts.single().layoutInput.overflow)
        org.junit.Assert.assertTrue(longLabel.width < timer.left - bounds("Composer activity line").left)
        compose.onNodeWithTag("Composer activity line").assertHeightIsEqualTo(32.dp)
    }

    @Test fun heldLabelAdoptsOnClockWithoutFurtherInput() {
        val candidate = mutableStateOf(working)
        compose.setContent { MaterialTheme {
            ComposerActivityLine(rememberHeldActivityLine(candidate.value), null, {}, motionAllowedOverride = false)
        } }
        compose.runOnIdle { candidate.value = working.copy(label = "Editing") }
        compose.waitUntil(5000) {
            compose.onAllNodesWithContentDescription("Activity: Editing").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
