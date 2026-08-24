package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Encrypted, origin-scoped cookie storage for Hermes dashboard sessions.
 *
 * Basic auth is deliberately cookie-backed: passwords never enter this class,
 * and HttpOnly session cookies are the only durable credential material kept.
 */
class EncryptedHermesCookieStorage(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) : CookiesStorage {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createAead(context.applicationContext, preferencesName)
    }
    private val mutex = Mutex()
    private val loaded = mutableSetOf<String>()
    private val cookies = mutableMapOf<String, MutableMap<String, String>>()

    override suspend fun get(url: Url): List<Cookie> = mutex.withLock {
        val origin = originKey(url)
        loadLocked(origin)
        // Replay stored values verbatim. Hermes session cookies are base64
        // (RFC 4648) tokens containing '=', '/', and '+'; the server sets them
        // as an already well-formed cookie value, so re-encoding on the way out
        // corrupts the token and the server rejects the session. Ktor's default
        // Cookie encoding is URI_ENCODING, which would percent-encode those
        // bytes — pin RAW so the rendered request header matches what the
        // server sent (this mirrors the iOS client's verbatim HTTPCookie replay).
        cookies[origin].orEmpty().map { (name, value) ->
            Cookie(name, value, encoding = CookieEncoding.RAW)
        }
    }

    override suspend fun addCookie(url: Url, cookie: Cookie) = mutex.withLock {
        val origin = originKey(url)
        loadLocked(origin)
        val bucket = cookies.getOrPut(origin) { mutableMapOf() }
        if (cookie.value.isEmpty()) {
            bucket.remove(cookie.name)
        } else {
            bucket[cookie.name] = cookie.value
        }
        persistLocked(origin, bucket)
    }

    override fun close() = Unit

    private fun loadLocked(origin: String) {
        if (!loaded.add(origin)) return
        val encoded = preferences.getString(preferenceKey(origin), null) ?: return
        val decoded = runCatching {
            val ciphertext = Base64.decode(encoded, Base64.DEFAULT)
            if (ciphertext.size > MAX_CIPHERTEXT_BYTES) return@runCatching emptyMap<String, String>()
            val plaintext = aead.decrypt(ciphertext, origin.toByteArray(StandardCharsets.UTF_8))
            if (plaintext.size > MAX_PLAINTEXT_BYTES) return@runCatching emptyMap<String, String>()
            json.decodeFromString<List<CookieRecord>>(plaintext.toString(StandardCharsets.UTF_8))
                .associate { it.name to it.value }
        }.getOrDefault(emptyMap())
        cookies[origin] = decoded.toMutableMap()
    }

    @Suppress("UseKtx")
    private fun persistLocked(origin: String, values: Map<String, String>) {
        val plaintext = json.encodeToString(
            values.entries.map { CookieRecord(it.key, it.value) },
        ).toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Hermes cookie record is too large" }
        val ciphertext = aead.encrypt(plaintext, origin.toByteArray(StandardCharsets.UTF_8))
        require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "Hermes cookie record is too large" }
        check(
            preferences.edit()
                .putString(preferenceKey(origin), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist Hermes session cookies" }
    }

    private fun preferenceKey(origin: String): String = "cookie_" + sha256(origin).toHex()

    private fun originKey(url: Url): String =
        "${url.protocol.name}://${url.host}:${url.port}"

    @Serializable
    private data class CookieRecord(val name: String, val value: String)

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "hermes_cookie_store"
        const val KEYSET_NAME = "hermes_cookie_store_keyset"
        const val KEYSET_PREFERENCES_SUFFIX = ".keyset"
        const val MASTER_KEY_URI = "android-keystore://hermes_cookie_store_master"
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + 1024

        fun createAead(context: Context, preferencesName: String): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(
                    context,
                    "$KEYSET_NAME:$preferencesName",
                    "$preferencesName$KEYSET_PREFERENCES_SUFFIX",
                )
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        }

        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

        fun ByteArray.toHex(): String = joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
