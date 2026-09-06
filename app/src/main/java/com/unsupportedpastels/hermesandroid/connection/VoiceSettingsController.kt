package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.voice.ElevenLabsVoice
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import com.unsupportedpastels.hermesandroid.voice.VoiceServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native owner for server voice settings; no audio or transport lifecycle moves into KMP. */
internal class VoiceSettingsController(
    private val guard: ConnectionOperationGuard,
    private val probe: suspend (ConnectionOperationScope) -> Pair<VoiceCapabilities, VoiceServerConfig>,
    private val write: suspend (ConnectionOperationScope, JsonObject) -> Boolean,
    private val voices: suspend (ConnectionOperationScope) -> List<ElevenLabsVoice>,
) {
    private val mutableCapabilities = MutableStateFlow(VoiceCapabilities.NONE)
    val capabilities = mutableCapabilities.asStateFlow()
    private val mutableConfig = MutableStateFlow(VoiceServerConfig.DEFAULT)
    val config = mutableConfig.asStateFlow()
    private var publishedScope: ConnectionOperationScope? = null
    // Reads that replace the whole config and optimistic writes have one ordering.
    private val settingsMutex = Mutex()

    fun onScopeChanged() {
        val current = guard.currentScope()
        if (publishedScope == current) return
        publishedScope = current
        mutableCapabilities.value = VoiceCapabilities.NONE
        mutableConfig.value = VoiceServerConfig.DEFAULT
    }

    private fun directScope(): ConnectionOperationScope? {
        onScopeChanged()
        return publishedScope?.takeIf { it.relayTargetId == null }
    }

    suspend fun refresh() {
        val scope = directScope() ?: return
        settingsMutex.withLock {
            guard.ensureCurrent(scope)
            try {
                val result = guard.run(scope) { probe(scope) }
                mutableCapabilities.value = result.first
                mutableConfig.value = result.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (guard.isCurrent(scope)) {
                    mutableCapabilities.value = VoiceCapabilities.NONE
                    mutableConfig.value = VoiceServerConfig.DEFAULT
                }
            }
        }
    }

    suspend fun setAutoTts(enabled: Boolean): Boolean = update(
        transform = { it.copy(autoTts = enabled) },
        payload = buildJsonObject { put("voice", buildJsonObject { put("auto_tts", enabled) }) },
    )

    suspend fun setElevenLabsVoice(voiceId: String): Boolean {
        val value = voiceId.trim().take(128)
        if (value.isEmpty()) return false
        return update(
            transform = { it.copy(elevenLabsVoiceId = value) },
            payload = buildJsonObject {
                put("tts", buildJsonObject { put("elevenlabs", buildJsonObject { put("voice_id", value) }) })
            },
        )
    }

    private suspend fun update(transform: (VoiceServerConfig) -> VoiceServerConfig, payload: JsonObject): Boolean {
        val scope = directScope() ?: return false
        return settingsMutex.withLock {
            guard.ensureCurrent(scope)
            val previous = mutableConfig.value
            mutableConfig.value = transform(previous)
            try {
                val accepted = guard.run(scope) { write(scope, payload) }
                if (!accepted) mutableConfig.value = previous
                accepted
            } catch (cancelled: CancellationException) {
                if (guard.isCurrent(scope)) mutableConfig.value = previous
                throw cancelled
            } catch (_: Exception) {
                if (guard.isCurrent(scope)) mutableConfig.value = previous
                false
            }
        }
    }

    suspend fun loadVoices(): List<ElevenLabsVoice> {
        val scope = directScope() ?: return emptyList()
        return try {
            guard.run(scope) { voices(scope) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
}
