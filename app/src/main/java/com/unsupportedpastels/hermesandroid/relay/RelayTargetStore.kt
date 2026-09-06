package com.unsupportedpastels.hermesandroid.relay

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetCodec
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RelayTargetStoreFailure {
    PersistenceFailed,
    CorruptState,
    TargetLimitReached,
    UnknownTarget,
    InvalidLabel,
}

class RelayTargetStoreException(
    val failure: RelayTargetStoreFailure,
) : Exception("Mercury Relay target storage failed")

interface RelayTargetRepository {
    suspend fun load(): List<RelayPairedTarget>
    suspend fun add(target: RelayPairedTarget): RelayPairedTarget
    suspend fun markApproved(id: String, nowEpochSeconds: Long)
    suspend fun touch(id: String, nowEpochSeconds: Long)
    suspend fun updateLabel(id: String, label: String)
    /** Stores a router token renewed by the host over the authenticated channel. */
    suspend fun updateRoutingToken(id: String, token: String) {}
    suspend fun remove(id: String)
}

class EncryptedRelayTargetStore(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    aeadFactory: () -> Aead = { createAead(context.applicationContext, preferencesName) },
) : RelayTargetRepository {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED, aeadFactory)
    private val mutex = Mutex()
    private var loaded: List<RelayPairedTarget>? = null

    override suspend fun load(): List<RelayPairedTarget> = withContext(ioDispatcher) {
        mutex.withLock { loadLocked().map(::copyTarget) }
    }

    override suspend fun add(target: RelayPairedTarget): RelayPairedTarget = withContext(ioDispatcher) {
        mutex.withLock {
            val targets = loadLocked().toMutableList()
            if (targets.size >= RelayTargetCodec.maxTargets) fail(RelayTargetStoreFailure.TargetLimitReached)
            if (targets.any { it.id.equals(target.id, ignoreCase = true) }) fail(RelayTargetStoreFailure.PersistenceFailed)
            targets += copyTarget(target)
            persistLocked(targets)
            copyTarget(target)
        }
    }

    override suspend fun markApproved(id: String, nowEpochSeconds: Long) = mutate(id) { target ->
        target.copy(status = RelayTargetStatus.Approved, lastUsedEpochSeconds = maxOf(0, nowEpochSeconds))
    }

    override suspend fun touch(id: String, nowEpochSeconds: Long) = mutate(id) { target ->
        target.copy(lastUsedEpochSeconds = maxOf(0, nowEpochSeconds))
    }

    override suspend fun updateLabel(id: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.length > RelayTargetCodec.maxLabelCharacters || trimmed.any { it.isISOControl() }) {
            fail(RelayTargetStoreFailure.InvalidLabel)
        }
        mutate(id) { target -> target.copy(label = trimmed) }
    }

    override suspend fun updateRoutingToken(id: String, token: String) {
        mutate(id) { target -> target.copy(relayRoutingToken = token) }
    }

    override suspend fun remove(id: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val targets = loadLocked().toMutableList()
            if (targets.none { it.id.equals(id, ignoreCase = true) }) fail(RelayTargetStoreFailure.UnknownTarget)
            targets.removeAll { it.id.equals(id, ignoreCase = true) }
            persistLocked(targets)
        }
    }

    suspend fun removeAll() = withContext(ioDispatcher) {
        mutex.withLock { persistLocked(emptyList()) }
    }

    private suspend fun mutate(
        id: String,
        transform: (RelayPairedTarget) -> RelayPairedTarget,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            val targets = loadLocked().toMutableList()
            val index = targets.indexOfFirst { it.id.equals(id, ignoreCase = true) }
            if (index < 0) fail(RelayTargetStoreFailure.UnknownTarget)
            targets[index] = transform(targets[index])
            persistLocked(targets)
        }
    }

    private fun loadLocked(): List<RelayPairedTarget> {
        loaded?.let { return it }
        val encoded = preferences.getString(RECORD_KEY, null)
        if (encoded == null) return emptyList<RelayPairedTarget>().also { loaded = it }
        val ciphertext = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.size <= MAX_CIPHERTEXT_BYTES }
            ?: fail(RelayTargetStoreFailure.CorruptState)
        val decoded = try {
            val plaintext = aead.decrypt(ciphertext, ASSOCIATED_DATA)
            if (plaintext.size > RelayTargetCodec.maxPersistedBytes) fail(RelayTargetStoreFailure.CorruptState)
            RelayTargetCodec.decode(plaintext)
        } catch (error: RelayTargetStoreException) {
            throw error
        } catch (_: Exception) {
            fail(RelayTargetStoreFailure.CorruptState)
        }
        loaded = decoded.map(::copyTarget)
        return loaded!!
    }

    @Suppress("UseKtx")
    private fun persistLocked(targets: List<RelayPairedTarget>) {
        val plaintext = try {
            RelayTargetCodec.encode(targets)
        } catch (_: Exception) {
            fail(RelayTargetStoreFailure.PersistenceFailed)
        }
        val ciphertext = try {
            aead.encrypt(plaintext, ASSOCIATED_DATA)
        } catch (_: Exception) {
            fail(RelayTargetStoreFailure.PersistenceFailed)
        }
        if (ciphertext.size > MAX_CIPHERTEXT_BYTES) fail(RelayTargetStoreFailure.PersistenceFailed)
        val persisted = preferences.edit()
            .putString(RECORD_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        if (!persisted) fail(RelayTargetStoreFailure.PersistenceFailed)
        loaded = targets.map(::copyTarget)
    }

    private fun copyTarget(target: RelayPairedTarget): RelayPairedTarget = RelayPairedTarget(
        id = target.id,
        label = target.label,
        relayOrigin = target.relayOrigin,
        installationId = target.installationId.copyOf(),
        hostPublicKey = target.hostPublicKey.copyOf(),
        deviceId = target.deviceId,
        deviceStaticPrivateKey = target.deviceStaticPrivateKey.copyOf(),
        fingerprint = target.fingerprint,
        status = target.status,
        createdAtEpochSeconds = target.createdAtEpochSeconds,
        lastUsedEpochSeconds = target.lastUsedEpochSeconds,
        relayRoutingToken = target.relayRoutingToken,
    )

    private fun fail(failure: RelayTargetStoreFailure): Nothing = throw RelayTargetStoreException(failure)

    companion object {
        const val RECORD_KEY = "relay_targets_v1"
        private const val DEFAULT_PREFERENCES_NAME = "relay_target_store"
        private const val KEYSET_NAME = "relay_target_store_keyset"
        private const val KEYSET_SUFFIX = ".keyset"
        private const val MASTER_KEY_URI = "android-keystore://relay_target_store_master"
        private const val MAX_CIPHERTEXT_BYTES = 65 * 1024
        private val ASSOCIATED_DATA = "mercury-relay-targets-v1".encodeToByteArray()

        private fun createAead(context: Context, preferencesName: String): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, "$KEYSET_NAME:$preferencesName", "$preferencesName$KEYSET_SUFFIX")
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        }
    }
}
