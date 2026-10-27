package com.unsupportedpastels.hermesandroid.ssh

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A private key — and its passphrase, when it has one — remembered for one SSH target. */
@Serializable
data class StoredSshKey(
    val privateKey: String,
    val passphrase: String = "",
)

/**
 * Private keys for SSH targets, encrypted at rest.
 *
 * Nothing is written to `~/.ssh`, to app-external storage, or to any file: the
 * key exists only as ciphertext in this app's private preferences, sealed with
 * an Android-Keystore-backed Tink AES256-GCM keyset — the same scheme the
 * cookie and native-token stores use, so the master key never leaves the
 * Keystore and is not extractable.
 */
class EncryptedSshKeyStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    aeadFactory: () -> Aead = { createAead(context.applicationContext, preferencesName) },
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED, aeadFactory)

    suspend fun load(target: String): StoredSshKey? = withContext(ioDispatcher) {
        val encoded = preferences.getString(preferenceKey(target), null) ?: return@withContext null
        val ciphertext = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return@withContext null
        val plaintext = runCatching { aead.decrypt(ciphertext, associatedData(target)) }.getOrNull()
            ?: return@withContext null
        runCatching { json.decodeFromString<StoredSshKey>(plaintext.decodeToString()) }.getOrNull()
    }

    suspend fun save(target: String, key: StoredSshKey) = withContext(ioDispatcher) {
        val plaintext = json.encodeToString(key).toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "SSH key record is too large" }
        val ciphertext = aead.encrypt(plaintext, associatedData(target))
        check(
            preferences.edit()
                .putString(preferenceKey(target), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist the SSH key" }
    }

    suspend fun clear(target: String) = withContext(ioDispatcher) {
        check(preferences.edit().remove(preferenceKey(target)).commit()) { "Could not clear the SSH key" }
    }

    /**
     * Remember the last target (and which credential kind it used) so the tunnel can be
     * re-established after the app was frozen or killed. Not secret: the host the user
     * typed, and whether it authenticated with a key or a password.
     */
    suspend fun rememberLast(target: String, mode: String, localPort: Int = 0) = withContext(ioDispatcher) {
        preferences.edit()
            .putString(LAST_TARGET_KEY, target)
            .putString(LAST_MODE_KEY, mode)
            .putInt(LAST_PORT_KEY, localPort)
            .apply()
    }

    /** The local forward port of the last successful tunnel, so a rebuild can reuse it. */
    suspend fun lastLocalPort(): Int = withContext(ioDispatcher) {
        preferences.getInt(LAST_PORT_KEY, 0)
    }

    suspend fun lastTarget(): Pair<String, String>? = withContext(ioDispatcher) {
        val target = preferences.getString(LAST_TARGET_KEY, null) ?: return@withContext null
        target to preferences.getString(LAST_MODE_KEY, "").orEmpty()
    }

    private fun associatedData(target: String): ByteArray = target.toByteArray(StandardCharsets.UTF_8)

    private fun preferenceKey(target: String): String =
        "sshkey_" + sha256Hex(target)

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(associatedData(value))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "hermes_ssh_keys"
        const val KEYSET_NAME = "hermes_ssh_keys_keyset"
        const val KEYSET_PREFERENCES_SUFFIX = ".keyset"
        const val MASTER_KEY_URI = "android-keystore://hermes_ssh_keys_master"
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        const val LAST_TARGET_KEY = "last_ssh_target"
        const val LAST_MODE_KEY = "last_ssh_mode"
        const val LAST_PORT_KEY = "last_ssh_local_port"

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
    }
}