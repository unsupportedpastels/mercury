package com.unsupportedpastels.hermesandroid.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-contract guard for the sign-in callback wiring.
 *
 * Regression: `HermesAppHost` declared and received `onPasswordSignIn` but did
 * not forward it to `HermesApp`, so the composite fell back to the no-op
 * default. The username/password dialog closed and nothing happened — basic
 * auth appeared to "just sit on the sign-in screen".
 *
 * A pure Compose test on `HermesApp` cannot catch this because the drop happens
 * one layer up, in the host's call to `HermesApp`. This test asserts the
 * forwarding line exists in the host so the two-hop wiring can't silently
 * regress. Every other `on...SignIn` callback is forwarded next to it; this
 * keeps `onPasswordSignIn` in that set.
 */
class HermesAppHostSignInWiringTest {
    private val mainActivitySource: String by lazy {
        val candidates = listOf(
            "app/src/main/java/com/unsupportedpastels/hermesandroid/MainActivity.kt",
            "src/main/java/com/unsupportedpastels/hermesandroid/MainActivity.kt",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("MainActivity.kt not found; checked: $candidates (cwd=${File(".").absolutePath})")
        file.readText()
    }

    @Test
    fun hostForwardsPasswordSignInCallbackToHermesApp() {
        // The host must pass its onPasswordSignIn through to HermesApp rather
        // than letting HermesApp use its no-op default.
        assertTrue(
            "HermesAppHost must forward onPasswordSignIn to HermesApp " +
                "(regression: dialog closed without invoking sign-in).",
            mainActivitySource.contains("onPasswordSignIn = onPasswordSignIn"),
        )
    }
}
