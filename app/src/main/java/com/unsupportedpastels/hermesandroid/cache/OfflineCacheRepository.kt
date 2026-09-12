package com.unsupportedpastels.hermesandroid.cache

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The bounded, encrypted local cache contract. Transcript bodies are opt-in. */
object OfflineCachePolicy {
    const val MAX_SESSION_COUNT = 100
    const val MAX_MESSAGES_PER_SESSION = 200
    const val MAX_BODY_BYTES = 128 * 1024
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
    const val RETENTION_SECONDS = 30L * 24L * 60L * 60L
    const val MAX_TEXT_BYTES = 4 * 1024
    const val MAX_ID_BYTES = 256
}

data class CacheScope(
    val origin: ServerOrigin,
    val profile: String,
) {
    init {
        require(profile.trim().isNotEmpty()) { "Cache profile must not be blank" }
        require(profile.toByteArray(StandardCharsets.UTF_8).size <= 64) {
            "Cache profile is too large"
        }
    }
}

data class CachedSession(
    val summary: SessionSummary,
    val messages: List<ChatMessage> = emptyList(),
    val updatedAtEpochSeconds: Long,
)

data class OfflineCacheSnapshot(val sessions: List<CachedSession> = emptyList())

interface OfflineCacheRepository {
    val transcriptCachingEnabled: StateFlow<Boolean>

    suspend fun read(scope: CacheScope, nowEpochSeconds: Long): OfflineCacheSnapshot
    suspend fun writeMetadata(scope: CacheScope, sessions: List<SessionSummary>, nowEpochSeconds: Long)
    suspend fun writeTranscript(
        scope: CacheScope,
        summary: SessionSummary,
        messages: List<ChatMessage>,
        nowEpochSeconds: Long,
    )
    suspend fun deleteSession(scope: CacheScope, durableSessionId: DurableSessionId)
    suspend fun clearTranscriptTails(scope: CacheScope? = null)
    suspend fun clearTranscriptTailsForOrigin(origin: ServerOrigin) = Unit
    suspend fun clear(scope: CacheScope? = null)
    suspend fun setTranscriptCachingEnabled(enabled: Boolean)
}

class EncryptedOfflineCacheRepository(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    aeadFactory: () -> Aead = { createAead(context.applicationContext, preferencesName) },
    private val clock: () -> Long = { System.currentTimeMillis() / 1_000L },
) : OfflineCacheRepository {
    private val preferences = context.applicationContext
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val aead by lazy(LazyThreadSafetyMode.SYNCHRONIZED, aeadFactory)
    private val mutableTranscriptCachingEnabled = MutableStateFlow(
        preferences.getBoolean(TRANSCRIPT_CACHING_ENABLED_KEY, false),
    )
    private val operationMutex = Mutex()

    override val transcriptCachingEnabled: StateFlow<Boolean> =
        mutableTranscriptCachingEnabled.asStateFlow()

    override suspend fun read(scope: CacheScope, nowEpochSeconds: Long): OfflineCacheSnapshot =
        withOperationLock {
            val rows = readRows(nowEpochSeconds)
            OfflineCacheSnapshot(
                rows.filter { row ->
                    row.originFingerprint == originFingerprint(scope.origin) &&
                        row.profile == scope.profile
                }.map(::toCachedSession)
                    .sortedWith(compareByDescending<CachedSession> { it.updatedAtEpochSeconds }
                        .thenBy { it.summary.id.value }),
            )
        }

    override suspend fun writeMetadata(
        scope: CacheScope,
        sessions: List<SessionSummary>,
        nowEpochSeconds: Long,
    ) = withOperationLock {
        val existing = readRows(nowEpochSeconds).associateBy { it.rowKey }
        val currentIds = sessions.asSequence()
            .filterNot { it.isLocalDraft }
            .map { it.id.value }
            .toSet()
        val editor = preferences.edit()
        existing.values
            .filter { it.originFingerprint == originFingerprint(scope.origin) && it.profile == scope.profile }
            .filterNot { it.sessionId in currentIds }
            .forEach { editor.remove(it.rowKey) }
        sessions.asSequence()
            .filterNot { it.isLocalDraft }
            .take(OfflineCachePolicy.MAX_SESSION_COUNT)
            .forEach { summary ->
                val key = rowKey(scope, summary.id)
                val old = existing[key]
                val row = storedRow(
                    scope = scope,
                    summary = summary,
                    messages = if (mutableTranscriptCachingEnabled.value) old?.messages.orEmpty() else emptyList(),
                    updatedAtEpochSeconds = nowEpochSeconds,
                )
                editor.putString(key, encode(row))
            }
        commit(editor)
        prune(nowEpochSeconds)
    }

    override suspend fun writeTranscript(
        scope: CacheScope,
        summary: SessionSummary,
        messages: List<ChatMessage>,
        nowEpochSeconds: Long,
    ) = withOperationLock {
        if (!mutableTranscriptCachingEnabled.value || summary.isLocalDraft) return@withOperationLock
        readRows(nowEpochSeconds)
        val row = storedRow(
            scope = scope,
            summary = summary,
            messages = boundMessages(messages),
            updatedAtEpochSeconds = nowEpochSeconds,
        )
        val editor = preferences.edit().putString(row.rowKey, encode(row))
        commit(editor)
        prune(nowEpochSeconds)
    }

    override suspend fun deleteSession(scope: CacheScope, durableSessionId: DurableSessionId) =
        withOperationLock {
            commit(preferences.edit().remove(rowKey(scope, durableSessionId)))
        }

    override suspend fun clearTranscriptTails(scope: CacheScope?) = withOperationLock {
        clearTranscriptTailsLocked(scope, currentEpochSeconds())
    }

    override suspend fun clearTranscriptTailsForOrigin(origin: ServerOrigin) = withOperationLock {
        val fingerprint = originFingerprint(origin)
        val rows = readRows(currentEpochSeconds())
        val editor = preferences.edit()
        rows.filter { row ->
            row.originFingerprint == fingerprint && row.messages.isNotEmpty()
        }.forEach { row ->
            editor.putString(row.rowKey, encode(row.copy(messages = emptyList())))
        }
        commit(editor)
    }

    override suspend fun clear(scope: CacheScope?) = withOperationLock {
        val rows = readRows(currentEpochSeconds())
        val editor = preferences.edit()
        rows.filter { scope == null || matches(it, scope) }.forEach { row -> editor.remove(row.rowKey) }
        commit(editor)
    }

    override suspend fun setTranscriptCachingEnabled(enabled: Boolean) = withOperationLock {
        commit(preferences.edit().putBoolean(TRANSCRIPT_CACHING_ENABLED_KEY, enabled))
        mutableTranscriptCachingEnabled.value = enabled
        if (!enabled) clearTranscriptTailsLocked(null, currentEpochSeconds())
    }

    private fun clearTranscriptTailsLocked(scope: CacheScope?, nowEpochSeconds: Long) {
        val rows = readRows(nowEpochSeconds)
        val editor = preferences.edit()
        rows.filter { scope == null || matches(it, scope) }.forEach { row ->
            if (row.messages.isNotEmpty()) {
                editor.putString(row.rowKey, encode(row.copy(messages = emptyList())))
            }
        }
        commit(editor)
    }

    private suspend fun <T> withOperationLock(block: () -> T): T =
        withContext(ioDispatcher) {
            operationMutex.withLock {
                block()
            }
        }

    private fun readRows(nowEpochSeconds: Long): List<StoredCacheRow> {
        val decoded = mutableListOf<StoredCacheRow>()
        val corruptKeys = mutableListOf<String>()
        preferences.all.keys.filter { it.startsWith(ROW_PREFIX) }.forEach { key ->
            val fingerprint = key.removePrefix(ROW_PREFIX)
            val encoded = preferences.getString(key, null) ?: run {
                corruptKeys += key
                return@forEach
            }
            val row = runCatching {
                val ciphertext = Base64.decode(encoded, Base64.DEFAULT)
                val plaintext = aead.decrypt(
                    ciphertext,
                    fingerprint.toByteArray(StandardCharsets.UTF_8),
                )
                json.decodeFromString<StoredCacheRow>(
                    plaintext.toString(StandardCharsets.UTF_8),
                )
            }.getOrNull()
            if (row == null || row.rowKey != key || row.originFingerprint.isBlank() ||
                row.profile.isBlank() || row.sessionId.isBlank() || row.updatedAtEpochSeconds < 0L
            ) {
                corruptKeys += key
            } else if (row.updatedAtEpochSeconds + OfflineCachePolicy.RETENTION_SECONDS >= nowEpochSeconds) {
                decoded += row
            } else {
                corruptKeys += key
            }
        }
        if (corruptKeys.isNotEmpty()) {
            val editor = preferences.edit()
            corruptKeys.forEach(editor::remove)
            commit(editor)
        }
        return decoded
    }

    private fun prune(nowEpochSeconds: Long) {
        val rows = readRows(nowEpochSeconds)
            .sortedWith(compareByDescending<StoredCacheRow> { it.updatedAtEpochSeconds }.thenBy { it.rowKey })
        val keep = rows.take(OfflineCachePolicy.MAX_SESSION_COUNT).toMutableList()
        var bytes = keep.sumOf { encode(it).toByteArray(StandardCharsets.UTF_8).size }
        while (keep.isNotEmpty() && bytes > OfflineCachePolicy.MAX_TOTAL_BYTES) {
            val removed = keep.removeAt(keep.lastIndex)
            bytes -= encode(removed).toByteArray(StandardCharsets.UTF_8).size
        }
        val keepKeys = keep.mapTo(mutableSetOf(), StoredCacheRow::rowKey)
        val editor = preferences.edit()
        rows.filterNot { it.rowKey in keepKeys }.forEach { editor.remove(it.rowKey) }
        commit(editor)
    }

    private fun storedRow(
        scope: CacheScope,
        summary: SessionSummary,
        messages: List<StoredMessage>,
        updatedAtEpochSeconds: Long,
    ): StoredCacheRow {
        val id = boundText(summary.id.value, OfflineCachePolicy.MAX_ID_BYTES)
        val safeSummary = summary.copy(
            id = DurableSessionId(id),
            title = boundText(summary.title, OfflineCachePolicy.MAX_TEXT_BYTES),
            projectId = summary.projectId?.let { ProjectId(boundText(it.value, OfflineCachePolicy.MAX_TEXT_BYTES)) },
            workspacePath = summary.workspacePath?.let { boundText(it, OfflineCachePolicy.MAX_TEXT_BYTES) },
            preview = summary.preview?.let { boundText(it, OfflineCachePolicy.MAX_TEXT_BYTES) },
            model = summary.model?.let { boundText(it, OfflineCachePolicy.MAX_TEXT_BYTES) },
            provider = summary.provider?.let { boundText(it, OfflineCachePolicy.MAX_TEXT_BYTES) },
            profile = scope.profile,
            isLocalDraft = false,
        )
        return StoredCacheRow(
            rowKey = rowKey(scope, safeSummary.id),
            originFingerprint = originFingerprint(scope.origin),
            profile = scope.profile,
            sessionId = safeSummary.id.value,
            updatedAtEpochSeconds = updatedAtEpochSeconds.coerceAtLeast(0L),
            summary = StoredSummary.from(safeSummary),
            messages = if (mutableTranscriptCachingEnabled.value) messages else emptyList(),
        )
    }

    private fun boundMessage(message: ChatMessage): StoredMessage = StoredMessage(
        role = message.role.name,
        text = boundText(message.text, OfflineCachePolicy.MAX_BODY_BYTES),
        reasoningText = boundText(message.reasoningText, OfflineCachePolicy.MAX_BODY_BYTES),
        displayKind = message.displayKind?.let { boundText(it, 256) },
    )

    private fun boundMessages(messages: List<ChatMessage>): List<StoredMessage> {
        val selected = ArrayDeque<StoredMessage>()
        var retainedBytes = 0
        val transcriptBudget = OfflineCachePolicy.MAX_TOTAL_BYTES / 2
        messages.asReversed().asSequence()
            .take(OfflineCachePolicy.MAX_MESSAGES_PER_SESSION)
            .forEach { message ->
                val stored = boundMessage(message)
                val bytes = stored.text.toByteArray(StandardCharsets.UTF_8).size +
                    stored.reasoningText.toByteArray(StandardCharsets.UTF_8).size +
                    stored.displayKind.orEmpty().toByteArray(StandardCharsets.UTF_8).size
                if (selected.isEmpty() || retainedBytes + bytes <= transcriptBudget) {
                    selected.addFirst(stored)
                    retainedBytes += bytes
                }
            }
        return selected.toList()
    }

    private fun toCachedSession(row: StoredCacheRow): CachedSession = CachedSession(
        summary = row.summary.toSession(row.sessionId, row.profile),
        messages = if (mutableTranscriptCachingEnabled.value) row.messages.mapNotNull { it.toMessage() } else emptyList(),
        updatedAtEpochSeconds = row.updatedAtEpochSeconds,
    )

    private fun matches(row: StoredCacheRow, scope: CacheScope): Boolean =
        row.originFingerprint == originFingerprint(scope.origin) && row.profile == scope.profile

    private fun encode(row: StoredCacheRow): String = Base64.encodeToString(
        aead.encrypt(
            json.encodeToString(row).toByteArray(StandardCharsets.UTF_8),
            row.rowKey.removePrefix(ROW_PREFIX).toByteArray(StandardCharsets.UTF_8),
        ),
        Base64.NO_WRAP,
    )

    @Suppress("UseKtx")
    private fun commit(editor: android.content.SharedPreferences.Editor) {
        check(editor.commit()) { "Could not persist offline cache" }
    }

    private fun currentEpochSeconds(): Long = clock()

    private companion object {
        const val DEFAULT_PREFERENCES_NAME = "offline_session_cache"
        const val KEYSET_PREFERENCES_SUFFIX = ".keyset"
        const val KEYSET_NAME = "offline_session_cache_keyset"
        const val MASTER_KEY_URI = "android-keystore://offline_session_cache_master"
        const val ROW_PREFIX = "row-"
        const val TRANSCRIPT_CACHING_ENABLED_KEY = "transcript_caching_enabled"

        fun rowKey(scope: CacheScope, id: DurableSessionId): String =
            ROW_PREFIX + sha256(
                "${scope.origin.value}\u0000${scope.profile}\u0000${id.value}",
            ).toHex()

        fun originFingerprint(origin: ServerOrigin): String = sha256(origin.value).toHex()

        fun createAead(context: Context, preferencesName: String): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, "$KEYSET_NAME:$preferencesName", "$preferencesName$KEYSET_PREFERENCES_SUFFIX")
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build().keysetHandle.getPrimitive(Aead::class.java)
        }

        fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun boundText(value: String, maxBytes: Int): String {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            return if (bytes.size <= maxBytes) value else String(
                bytes.copyOf(maxBytes),
                StandardCharsets.UTF_8,
            )
        }
    }
}

@Serializable
private data class StoredCacheRow(
    val rowKey: String,
    val originFingerprint: String,
    val profile: String,
    val sessionId: String,
    val updatedAtEpochSeconds: Long,
    val summary: StoredSummary,
    val messages: List<StoredMessage> = emptyList(),
)

@Serializable
private data class StoredSummary(
    val title: String,
    val projectId: String? = null,
    val workspacePath: String? = null,
    val preview: String? = null,
    val lastActiveEpochSeconds: Double? = null,
    val messageCount: Int? = null,
    val model: String? = null,
    val provider: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
) {
    fun toSession(id: String, profile: String): SessionSummary = SessionSummary(
        id = DurableSessionId(id),
        title = title,
        projectId = projectId?.let(::ProjectId),
        workspacePath = workspacePath,
        preview = preview,
        lastActiveEpochSeconds = lastActiveEpochSeconds,
        messageCount = messageCount,
        model = model,
        provider = provider,
        profile = profile,
        pinned = pinned,
        archived = archived,
    )

    companion object {
        fun from(summary: SessionSummary) = StoredSummary(
            title = summary.title,
            projectId = summary.projectId?.value,
            workspacePath = summary.workspacePath,
            preview = summary.preview,
            lastActiveEpochSeconds = summary.lastActiveEpochSeconds,
            messageCount = summary.messageCount,
            model = summary.model,
            provider = summary.provider,
            pinned = summary.pinned,
            archived = summary.archived,
        )
    }
}

@Serializable
private data class StoredMessage(
    val role: String,
    val text: String,
    val reasoningText: String = "",
    val displayKind: String? = null,
) {
    fun toMessage(): ChatMessage? = runCatching {
        ChatMessage(
            role = ChatMessageRole.valueOf(role),
            text = text,
            reasoningText = reasoningText,
            displayKind = displayKind,
        )
    }.getOrNull()
}
