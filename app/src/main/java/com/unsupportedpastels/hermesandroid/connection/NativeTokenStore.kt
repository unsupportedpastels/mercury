package com.unsupportedpastels.hermesandroid.connection

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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface NativeTokenStore {
    suspend fun load(serverOrigin: ServerOrigin): NativeTokenSet?

    suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet)

    suspend fun clear(serverOrigin: ServerOrigin)
}

class EncryptedNativeTokenStore(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    aeadFactory: () -> Aead = {
        createAead(context.applicationContext, preferencesName)
    },
) : NativeTokenStore {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED, aeadFactory)

    override suspend fun load(serverOrigin: ServerOrigin): NativeTokenSet? = withContext(ioDispatcher) {
        val encoded = preferences.getString(preferenceKey(serverOrigin), null)
            ?: return@withContext null
        val ciphertext = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?: return@withContext null
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) return@withContext null

        runCatching {
            val plaintext = aead.decrypt(ciphertext, associatedData(serverOrigin))
            if (plaintext.size > MAX_SERIALIZED_TOKEN_BYTES) return@runCatching null
            val tokens = json.decodeFromString<NativeTokenSet>(
                plaintext.toString(StandardCharsets.UTF_8),
            )
            validate(tokens)
            tokens
        }.getOrNull()
    }

    @Suppress("UseKtx") // Preserve commit() result so credential persistence fails closed.
    override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) = withContext(ioDispatcher) {
        validate(tokens)
        val plaintext = json.encodeToString(tokens).toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_SERIALIZED_TOKEN_BYTES) {
            "Native token record is too large"
        }
        val ciphertext = aead.encrypt(plaintext, associatedData(serverOrigin))
        require(ciphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "Native token record is too large"
        }
        check(
            preferences.edit()
                .putString(
                    preferenceKey(serverOrigin),
                    Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                )
                .commit(),
        ) { "Could not persist native tokens" }
    }

    @Suppress("UseKtx") // Preserve commit() result so credential removal fails closed.
    override suspend fun clear(serverOrigin: ServerOrigin) = withContext(ioDispatcher) {
        check(
            preferences.edit()
                .remove(preferenceKey(serverOrigin))
                .commit(),
        ) { "Could not clear native tokens" }
    }

    private fun associatedData(serverOrigin: ServerOrigin): ByteArray =
        serverOrigin.value.toByteArray(StandardCharsets.UTF_8)

    private fun preferenceKey(serverOrigin: ServerOrigin): String =
        "token_" + sha256(serverOrigin.value).toHex()

    private fun validate(tokens: NativeTokenSet) {
        require(tokens.provider.isNotBlank()) { "Native token response was incomplete" }
        require(tokens.userId.isNotBlank()) { "Native token response was incomplete" }
        if (tokens.provider != "basic") {
            require(tokens.accessToken.isNotBlank()) { "Native token response was incomplete" }
            require(tokens.expiresAt > 0) { "Native token response was incomplete" }
        }
        requireTokenSize(tokens.accessToken)
        requireTokenSize(tokens.refreshToken)
        requireTokenSize(tokens.provider)
        requireTokenSize(tokens.userId)
    }

    private fun requireTokenSize(value: String) {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_TOKEN_FIELD_BYTES) {
            "Native token field is too large"
        }
    }

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "native_token_store"
        const val KEYSET_PREFERENCES_SUFFIX = ".keyset"
        const val KEYSET_NAME = "native_token_store_keyset"
        const val MASTER_KEY_URI = "android-keystore://native_token_store_master"
        const val MAX_TOKEN_FIELD_BYTES = 16 * 1024
        const val MAX_SERIALIZED_TOKEN_BYTES = 64 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_SERIALIZED_TOKEN_BYTES + 1024

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

typealias AndroidNativeTokenStore = EncryptedNativeTokenStore
