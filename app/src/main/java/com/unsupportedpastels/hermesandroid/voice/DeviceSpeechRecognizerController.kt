package com.unsupportedpastels.hermesandroid.voice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Visible lifecycle of the app-owned native recognizer. */
enum class DeviceSpeechRecognizerState {
    Idle,
    Listening,
    Restarting,
}

internal interface DeviceSpeechRecognizer {
    val state: StateFlow<DeviceSpeechRecognizerState>
    val isActive: Boolean

    fun start(
        currentDraft: String,
        onDraftChanged: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean

    fun finish()
}

/**
 * App-owned wrapper around the installed Android recognition service. Unlike
 * ACTION_RECOGNIZE_SPEECH, this keeps control of the recognition session and
 * restarts it after provider endpointing, preserving the accumulated draft.
 */
class DeviceSpeechRecognizerController(
    private val context: Context,
) : DeviceSpeechRecognizer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(DeviceSpeechRecognizerState.Idle)
    override val state: StateFlow<DeviceSpeechRecognizerState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var generation = 0L
    private var baseDraft = ""
    private var committedDraft = ""
    private var onDraftChanged: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    override val isActive: Boolean
        get() = _state.value != DeviceSpeechRecognizerState.Idle

    override fun start(
        currentDraft: String,
        onDraftChanged: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        stop(keepDraft = false)
        if (!isAvailable(context)) return false

        baseDraft = currentDraft
        committedDraft = currentDraft
        this.onDraftChanged = onDraftChanged
        this.onError = onError
        generation++
        val token = generation
        recognizer = runCatching {
            SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(Listener(token))
            }
        }.getOrNull()
        if (recognizer == null) {
            clearCallbacks()
            return false
        }
        _state.value = DeviceSpeechRecognizerState.Listening
        startListening(token)
        return true
    }

    /** Finish and keep all recognized text currently in the draft. */
    override fun finish() {
        stop(keepDraft = true)
    }

    /** Cancel and restore the draft as it was before recognition started. */
    fun cancel() {
        stop(keepDraft = false)
    }

    private fun startListening(token: Long) {
        if (!isCurrent(token)) return
        try {
            recognizer?.startListening(recognitionIntent())
            _state.value = DeviceSpeechRecognizerState.Listening
        } catch (_: Exception) {
            scheduleRestart(token)
        }
    }

    private fun scheduleRestart(token: Long) {
        if (!isCurrent(token)) return
        _state.value = DeviceSpeechRecognizerState.Restarting
        mainHandler.postDelayed(
            { startListening(token) },
            RESTART_DELAY_MILLIS,
        )
    }

    private fun handlePartial(token: Long, results: BundleText?) {
        if (!isCurrent(token)) return
        val text = results?.text ?: return
        onDraftChanged?.invoke(VoiceInputPolicy.mergeDraft(committedDraft, text))
    }

    private fun handleFinal(token: Long, results: BundleText?) {
        if (!isCurrent(token)) return
        val text = results?.text
        if (!text.isNullOrBlank()) {
            committedDraft = VoiceInputPolicy.mergeDraft(committedDraft, text)
            onDraftChanged?.invoke(committedDraft)
        }
        // Google endpointing can close a recognition segment after a short
        // pause. Restart while preserving committedDraft instead of ending the
        // user’s conversational input.
        scheduleRestart(token)
    }

    private fun handleError(token: Long, error: Int) {
        if (!isCurrent(token)) return
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
        ) {
            onError?.invoke("Voice input is unavailable")
            stop(keepDraft = true)
        } else {
            // No-match, speech-timeout, client, and provider endpoint errors
            // are recoverable while the user keeps the voice-input surface open.
            scheduleRestart(token)
        }
    }

    private fun stop(keepDraft: Boolean) {
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.let {
            runCatching { it.cancel() }
            it.destroy()
        }
        recognizer = null
        if (!keepDraft) onDraftChanged?.invoke(baseDraft)
        clearCallbacks()
        _state.value = DeviceSpeechRecognizerState.Idle
    }

    private fun clearCallbacks() {
        onDraftChanged = null
        onError = null
        baseDraft = ""
        committedDraft = ""
    }

    private fun isCurrent(token: Long): Boolean = token == generation && recognizer != null

    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    private inner class Listener(private val token: Long) : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            if (isCurrent(token)) _state.value = DeviceSpeechRecognizerState.Listening
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            handleError(token, error)
        }

        override fun onResults(results: android.os.Bundle?) {
            handleFinal(token, results?.let(::BundleText))
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            handlePartial(token, partialResults?.let(::BundleText))
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private data class BundleText(val text: String?) {
        constructor(bundle: android.os.Bundle) : this(
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf(String::isNotEmpty),
        )
    }

    companion object {
        private const val RESTART_DELAY_MILLIS = 150L

        fun isAvailable(context: Context): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    }
}
