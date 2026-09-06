package com.unsupportedpastels.hermesandroid.connection

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsupportedpastels.hermesandroid.MainActivity
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real decoder test. Run only on a disposable emulator with fake-hermes --video fixture.
 * Build APKs, install explicitly with adb -s SERIAL, then am instrument -e class this class.
 * No fake player, downloader, credentials store, or production dependency injection.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ManagedVideoEndToEndTest {
    companion object {
        @JvmStatic @BeforeClass fun permissions() {
            val i = InstrumentationRegistry.getInstrumentation()
            if (android.os.Build.VERSION.SDK_INT >= 33) i.uiAutomation.grantRuntimePermission(
                i.targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun text(value: String) = rule.onAllNodesWithText(value, substring = true)
    private fun waitText(value: String) = rule.waitUntil(30_000) {
        text(value).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitPlay() {
        try {
            rule.waitUntil(30_000) {
                rule.onAllNodesWithContentDescription("Play video").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            throw AssertionError(rule.onRoot().printToString(maxDepth = 16), failure)
        }
    }
    private fun openVideoSurface() {
        if (InstrumentationRegistry.getArguments().getString("entry") == "artifact") {
            rule.onNodeWithContentDescription("Open session details").performClick()
            waitText("View all artifacts")
            rule.onNodeWithText("View all artifacts").performScrollTo().performClick()
        }
        waitPlay()
    }
    private fun player(): Player {
        var result: Player? = null
        onView(isAssignableFrom(PlayerView::class.java)).inRoot(object : org.hamcrest.TypeSafeMatcher<androidx.test.espresso.Root>() {
            override fun describeTo(description: org.hamcrest.Description) { description.appendText("focused window") }
            override fun matchesSafely(root: androidx.test.espresso.Root) = root.decorView.hasWindowFocus()
        }).check { view, error ->
            if (error != null) throw error
            result = (view as PlayerView).player
        }
        return requireNotNull(result)
    }
    private fun state(player: Player): Pair<Long, Boolean> {
        var value = 0L to false
        instrumentation.runOnMainSync { value = player.currentPosition to player.isPlaying }
        return value
    }
    private fun advances(player: Player, label: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!state(player).second && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val start = state(player)
        assertTrue("$label must be playing: $start", start.second)
        Thread.sleep(2_300)
        val end = state(player)
        Log.i("ManagedVideoQA", "$label position=${start.first}->${end.first} isPlaying=${end.second}")
        assertTrue("$label clock did not advance >=2s: $start -> $end", end.first - start.first >= 2_000)
        assertTrue("$label stopped unexpectedly", end.second)
    }
    private fun screenshot(label: String) {
        val metric = InstrumentationRegistry.getArguments().getString("metric") ?: "compact"
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "video-$metric-$label.png")
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("ManagedVideoQA", "screenshot=${file.name}")
    }
    @Test fun managedVideoRealPlaybackFullscreenPauseAndCachedReplay() {
        rule.waitUntil(30_000) {
            text("Connect to Hermes").fetchSemanticsNodes().isNotEmpty() ||
                text("E2E Session").fetchSemanticsNodes().isNotEmpty()
        }
        if (text("Connect to Hermes").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Self-hosted").performClick()
            rule.onNodeWithContentDescription("Server origin input").performTextInput("10.0.2.2:8787")
            rule.onNodeWithContentDescription("Use HTTPS checkbox").performClick()
            rule.onNodeWithText("Continue").performClick()
            waitText("Sign in with username and password")
            rule.onNodeWithText("Sign in with username and password").performClick()
            rule.onNodeWithText("Password").performTextInput("e2epass")
            rule.onNodeWithText("Sign in").performClick()
        }
        waitText("E2E Session")
        // Let startup authentication/catalog refresh finish before opening cached rows.
        Thread.sleep(5_000)
        text("E2E Session")[0].performClick()
        openVideoSurface()
        rule.onNodeWithContentDescription("Play video").performClick()
        try {
            rule.waitUntil(30_000) {
                rule.onAllNodesWithContentDescription("Open fullscreen video").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            screenshot("failure")
            throw AssertionError(rule.onAllNodes(isRoot()).printToString(maxDepth = 18), failure)
        }
        val inline = player()
        advances(inline, "inline")
        screenshot("playing")
        rule.onNodeWithContentDescription("Open fullscreen video").performClick()
        rule.onNodeWithContentDescription("Close fullscreen video").assertExists()
        advances(inline, "fullscreen")
        screenshot("fullscreen")
        rule.onNodeWithContentDescription("Close fullscreen video").performClick()
        assertSame("fullscreen should return same player", inline, player())
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        Thread.sleep(500)
        val stopped = state(inline)
        Thread.sleep(1_000)
        val later = state(inline)
        Log.i("ManagedVideoQA", "background position=${stopped.first}->${later.first} isPlaying=${later.second}")
        assertFalse(later.second)
        assertTrue("background clock advanced", later.first - stopped.first < 100)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.activityRule.scenario.recreate()
        openVideoSurface()
        screenshot("cached-poster")
        val cache = File(instrumentation.targetContext.cacheDir, "managed-video")
        val before = cache.walkTopDown().filter { it.isFile }.associate { it.absolutePath to (it.length() to it.lastModified()) }
        Log.i("ManagedVideoQA", "cache files=${before.size}")
        rule.onNodeWithContentDescription("Play video").performClick()
        try {
            rule.waitUntil(30_000) {
                rule.onAllNodesWithContentDescription("Open fullscreen video").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            screenshot("failure")
            throw AssertionError(rule.onAllNodes(isRoot()).printToString(maxDepth = 18), failure)
        }
        advances(player(), "cached-replay")
        screenshot("cached-replay")
        val after = cache.walkTopDown().filter { it.isFile }.associate { it.absolutePath to (it.length() to it.lastModified()) }
        assertTrue("expected persisted cache", before.isNotEmpty())
        assertEquals("cached replay must not rewrite download", before, after)
    }
}
