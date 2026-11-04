package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeTokenStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferencesName = "native_token_store_tests"
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val otherOrigin = ServerOrigin.parse("https://other.example")

    @Before
    fun clearTokenPreferences() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun savedTokensCanBeLoadedForTheSameOrigin() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val tokens = tokenSet("access-token", "refresh-token")

        store.save(origin, tokens)

        assertEquals(tokens, store.load(origin))
    }

    @Test
    fun tokenOperationsUseTheConfiguredIoDispatcher() = runTest {
        val dispatcher = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val store = EncryptedNativeTokenStore(
            context = context,
            preferencesName = preferencesName,
            ioDispatcher = dispatcher,
        )
        val tokens = tokenSet("access-token", "refresh-token")

        store.save(origin, tokens)
        val afterSave = dispatcher.dispatchCount
        store.load(origin)
        val afterLoad = dispatcher.dispatchCount
        store.clear(origin)

        assertTrue(afterSave > 0)
        assertTrue(afterLoad > afterSave)
        assertTrue(dispatcher.dispatchCount > afterLoad)
    }

    @Test
    fun encryptionPrimitiveInitializationIsDeferredUntilAnIoOperation() = runTest {
        var factoryCalls = 0
        val store = EncryptedNativeTokenStore(
            context = context,
            preferencesName = preferencesName,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            aeadFactory = {
                factoryCalls += 1
                PassthroughAead
            },
        )

        assertEquals(0, factoryCalls)

        store.save(origin, tokenSet("access-token", "refresh-token"))
        assertEquals(1, factoryCalls)

        store.load(origin)
        assertEquals(1, factoryCalls)
    }

    @Test
    fun tokensAreOriginScopedAndClearOnlyRemovesThatOrigin() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val first = tokenSet("first-access", "first-refresh")
        val second = tokenSet("second-access", "second-refresh")
        store.save(origin, first)
        store.save(otherOrigin, second)

        store.clear(origin)

        assertNull(store.load(origin))
        assertEquals(second, store.load(otherOrigin))
    }

    @Test
    fun preferencesDoNotContainPlaintextTokenValues() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val tokens = tokenSet("access-token-that-must-stay-encrypted", "refresh-token-that-must-stay-encrypted")

        store.save(origin, tokens)

        val persistedText = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all
            .values
            .joinToString("|")
        assertFalse(persistedText.contains(tokens.accessToken))
        assertFalse(persistedText.contains(tokens.refreshToken))
        assertTrue(persistedText.isNotBlank())
    }

    @Test
    fun ciphertextCopiedToAnotherOriginCannotBeDecrypted() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val tokens = tokenSet("access-token", "refresh-token")
        store.save(origin, tokens)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val ciphertext = preferences.getString(tokenPreferenceKey(origin), null)
        requireNotNull(ciphertext)
        preferences.edit().putString(tokenPreferenceKey(otherOrigin), ciphertext).commit()

        assertNull(store.load(otherOrigin))
    }

    @Test
    fun corruptedCiphertextFailsClosed() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().putString(tokenPreferenceKey(origin), "not-ciphertext").commit()

        assertNull(store.load(origin))
    }

    @Test
    fun oversizedTokenFieldsAreRejectedBeforePersistence() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val oversized = tokenSet("a".repeat(16 * 1024 + 1), "refresh-token")

        val failure = runCatching { store.save(origin, oversized) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertNull(store.load(origin))
    }

    

    @Test
    fun anSshTunnelTokenIsRecognisedSoItIsPresentedWithoutAnAuthChallenge() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val tunnelTokens = tokenSet("access-token", "refresh-token")
            .copy(provider = SSH_SESSION_TOKEN_PROVIDER)

        store.save(origin, tunnelTokens)

        assertTrue(store.hasSshSessionToken(origin))
    }

    @Test
    fun otherProviderTokensAreNotMistakenForTunnelTokens() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)

        store.save(origin, tokenSet("access-token", "refresh-token"))

        assertFalse(store.hasSshSessionToken(origin))
        assertFalse(store.hasSshSessionToken(otherOrigin))
    }

    @Test
    fun aTunnelTokenSurvivesReParsingItsOwnOriginValue() = runTest {
        // The SSH lifecycle saves the token under the origin it computed
        // (`127.0.0.1:<port>`, useTls = false), while the connection layer later looks it
        // up from the stored server entry, which re-parses the origin's canonical
        // `value`. If those two ever disagree the token is invisible — the client then
        // authenticates with nothing and every API call answers 401.
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val saved = ServerOrigin.parse("127.0.0.1:37835", useTls = false)
        val lookedUpFromCatalog = ServerOrigin.parse(saved.value)

        store.save(saved, tokenSet("access-token", "refresh-token").copy(provider = SSH_SESSION_TOKEN_PROVIDER))

        assertTrue(store.hasSshSessionToken(lookedUpFromCatalog))
        assertEquals("access-token", store.load(lookedUpFromCatalog)?.accessToken)
    }

    @Test
    fun aTunnelTokenSavedInItsOwnSlotIsFoundFromAnyOriginForm() = runTest {
        // The bug this closes: the lifecycle saved the token under the origin it computed,
        // the connection layer looked it up under the origin the catalog handed back, the
        // lookup missed, and the client declared "authentication not required" — sending no
        // credential at all and turning every API call into a 401.
        val store = EncryptedNativeTokenStore(context, preferencesName)
        val originTheLifecycleComputed = ServerOrigin.parse("127.0.0.1:37835", useTls = false)
        val originTheCatalogHandedBack = ServerOrigin.parse("https://127.0.0.1:37835")

        store.saveSshSessionToken(
            tokenSet("tunnel-token", "").copy(provider = SSH_SESSION_TOKEN_PROVIDER),
        )

        assertEquals("tunnel-token", store.load(originTheCatalogHandedBack)?.accessToken)
        assertTrue(store.hasSshSessionToken(originTheCatalogHandedBack))
        assertTrue(originTheLifecycleComputed.value != originTheCatalogHandedBack.value)
    }

    @Test
    fun theTunnelSlotIsIgnoredWhenItDoesNotHoldATunnelToken() = runTest {
        val store = EncryptedNativeTokenStore(context, preferencesName)

        // Same slot, ordinary OAuth provider: it must not be served as a tunnel token.
        store.saveSshSessionToken(tokenSet("access-token", "refresh-token"))

        assertNull(store.load(origin))
        assertFalse(store.hasSshSessionToken(origin))
    }

    private fun tokenSet(accessToken: String, refreshToken: String) = NativeTokenSet(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = 2_000_000_000,
        provider = "nous",
        userId = "user-1",
    )

    private fun tokenPreferenceKey(serverOrigin: ServerOrigin): String =
        "token_" + MessageDigest.getInstance("SHA-256")
            .digest(serverOrigin.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatchCount: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount += 1
            delegate.dispatch(context, block)
        }
    }

    private object PassthroughAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = ciphertext
    }
}
