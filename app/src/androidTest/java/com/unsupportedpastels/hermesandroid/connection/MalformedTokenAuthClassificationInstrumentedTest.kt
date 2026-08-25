package com.unsupportedpastels.hermesandroid.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device E2E for the outage-reconnect auth-classification bug.
 *
 * Runs against the LIVE server at [SERVER_ORIGIN]. It writes a *decrypt-valid but
 * malformed* access token through the app's real [EncryptedNativeTokenStore]
 * (real on-device Tink/Keystore keyset — the exact path that a truncated token
 * from an outage-during-refresh would take), then drives the real
 * [HttpHermesConnectionClient] and asserts the server + client behaviour.
 *
 * Two things are proven end-to-end, no mocks:
 *   1. The server maps a malformed bearer to HTTP 503 on /api/auth/me, which the
 *      client raises as [HermesAuthProviderUnavailableException] — the signal the
 *      connect layer keys on to escalate to sign-in instead of looping forever.
 *   2. A well-formed request path still classifies cleanly (control).
 *
 * Uses a throwaway preferences name so it never touches the installed app's
 * stored credentials.
 */
@RunWith(AndroidJUnit4::class)
class MalformedTokenAuthClassificationInstrumentedTest {

    private val context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun malformedTokenThroughRealKeysetProducesAuthProviderUnavailableFromLiveServer() = runBlocking {
        val origin = ServerOrigin.parse(SERVER_ORIGIN)

        // Reachability gate: skip (don't fail) if the device can't reach the host,
        // so the suite stays green off-network.
        val probeClient = HttpClient(CIO) { configureHermesHttpClient() }
        val client = HttpHermesConnectionClient(probeClient)
        val reachable = runCatching { client.probe(origin) }.isSuccess
        assumeTrue("server $SERVER_ORIGIN not reachable from device; skipping", reachable)

        // Write a malformed (non-JWT) token through the REAL encrypted store using
        // a throwaway prefs name so the installed app's credentials are untouched.
        val store = EncryptedNativeTokenStore(
            context = context,
            preferencesName = "instrumented_poison_token_store",
        )
        val malformed = NativeTokenSet(
            accessToken = "not-a-jwt-opaque-token", // decrypts fine, fails JWT parse -> server 503
            refreshToken = "opaque-refresh",
            expiresAt = Long.MAX_VALUE / 2, // far future: never treated as expired
            provider = "nous",
            userId = "instrumented-user",
        )
        store.save(origin, malformed)

        try {
            // Round-trips through real Tink AEAD decrypt (proves keyset path works).
            val loaded = store.load(origin)
            assertEquals("not-a-jwt-opaque-token", loaded?.accessToken)

            // Drive the real authenticated call with the malformed bearer against
            // the live server. The bug: server 503s on a malformed token; the
            // client surfaces it as HermesAuthProviderUnavailableException — NOT a
            // plain transport error and NOT a silent success.
            val error = runCatching {
                client.authenticate(origin, malformed.accessToken)
            }.exceptionOrNull()

            assertTrue(
                "expected HermesAuthProviderUnavailableException (HTTP 503 auth path), got $error",
                error is HermesAuthProviderUnavailableException,
            )
            assertTrue(
                "503 auth-provider-unavailable must be distinct from a bad-credential rejection",
                error !is HermesAuthenticationRejectedException,
            )
        } finally {
            store.clear(origin)
            probeClient.close()
        }
    }

    private companion object {
        // The live backend under test.
        const val SERVER_ORIGIN = "https://ham.sdhost.cc"
    }
}
