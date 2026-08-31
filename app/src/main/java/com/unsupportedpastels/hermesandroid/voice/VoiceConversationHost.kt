package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select

/**
 * The slice of a chat snapshot the voice loop watches: whether the turn is
 * still running, the live streaming text, and the last finalized assistant
 * reply with its position (so a baseline taken at submit time can exclude
 * history — the loop must never speak an old reply).
 */
data class VoiceReplyView(
    val turnRunning: Boolean,
    val streamingText: String?,
    /** Position of the streaming assistant message, or -1. Both the streaming
     * and final speech paths are baseline-gated: session resume/reconcile can
     * re-mark a *historical* assistant message as streaming, and it must never
     * be fed to TTS as if it were this turn's reply. */
    val streamingIndex: Int,
    val finalAssistantText: String?,
    val finalAssistantIndex: Int,
    val messageCount: Int,
)

fun ChatSessionSnapshot.toVoiceReplyView(): VoiceReplyView {
    var finalIndex = -1
    var finalText: String? = null
    var streamingIndex = -1
    var streamingText: String? = null
    messages.forEachIndexed { index, message ->
        if (message.role != ChatMessageRole.Assistant) return@forEachIndexed
        if (message.isStreaming) {
            streamingIndex = index
            streamingText = message.text.takeIf { it.isNotEmpty() }
        } else if (message.text.isNotBlank()) {
            finalIndex = index
            finalText = message.text
        }
    }
    return VoiceReplyView(
        turnRunning = isSending || messages.any { it.isStreaming },
        streamingText = streamingText,
        streamingIndex = if (streamingText != null) streamingIndex else -1,
        finalAssistantText = finalText,
        finalAssistantIndex = finalIndex,
        messageCount = messages.size,
    )
}

/**
 * Everything the voice loop needs from the outside world, as injectable seams:
 * Android capture, server transcription, prompt submission, chat progress, the
 * streaming speech socket, and the REST/playback fallback. The host contains
 * the loop *logic* and is tested against fakes; the composable wrapper supplies
 * real engines.
 */
class VoiceConversationEngines(
    /** Record one utterance (level callbacks in 0..1) until silence/cap; null = nothing captured. */
    val listen: suspend (onLevel: (Float) -> Unit) -> DictationRecording?,
    val transcribe: suspend (dataUrl: String, mimeType: String?) -> Result<TranscriptionResult>,
    /**
     * Submit a voice prompt through the exact-session controller seam.
     * [interrupted] is true only for the utterance captured by a barge-in — the
     * flag is scoped to that one call, so a stale barge can never annotate a
     * later typed message or another session's submit.
     */
    val submit: (text: String, interrupted: Boolean) -> Unit,
    /** Live view of the open session's chat progress. */
    val replies: Flow<VoiceReplyView>,
    /** Fresh-ticket streaming socket, or null when streaming is unavailable. */
    val openStream: suspend () -> SpeechStreamSocket?,
    val sinkFactory: () -> PcmSpeechSink,
    /** REST synthesis fallback (sanitized text). */
    val restSpeak: suspend (text: String) -> Result<SpeechAudio>,
    /** Play a REST-synthesized clip; suspends until playback finishes or fails. */
    val playAudio: suspend (
        audio: SpeechAudio,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        onError: () -> Unit,
    ) -> Unit,
    val stopPlayback: () -> Unit,
    /** Stop the running turn (the same seam as the composer Stop button). */
    val stopTurn: () -> Unit = {},
    /** Acquire/release Android voice-communication routing for the full loop. */
    val startAudioSession: () -> Unit = {},
    val stopAudioSession: () -> Unit = {},
    /**
     * Full-duplex barge-in monitor, or null when barge-in is disabled. Runs
     * while the agent thinks/speaks; calls its trigger callback the moment
     * sustained user speech is detected, keeps capturing until the utterance
     * ends, and returns the captured recording (null if capture failed).
     */
    val monitorBargeIn: (suspend (onTrigger: () -> Unit) -> DictationRecording?)? = null,
    /** How long to wait for the submitted turn to start before re-arming. */
    val turnStartTimeoutMillis: Long = 10_000L,
    /** After a barge stops the turn, how long to wait for busy state to settle. */
    val bargeSettleTimeoutMillis: Long = 5_000L,
    /** Bound on the speech-socket connect; past it, REST speaks instead. */
    val streamOpenTimeoutMillis: Long = 10_000L,
    /** A "turn finished" report must hold this long before the reply speaks. */
    val settleConfirmMillis: Long = 2_000L,
    /** Pre-audio watchdog for the speech stream (see [SpeechStreamProtocol]). */
    val streamProgressTimeoutMillis: Long = SpeechStreamProtocol.PROGRESS_TIMEOUT_MILLIS,
    /**
     * Diagnostic sink for loop-phase events. Receives bounded category strings
     * only — never transcript, audio, or config content. No-op by default.
     */
    val log: (String) -> Unit = {},
)

/**
 * Drives one voice-conversation loop: listen → transcribe → submit → speak →
 * re-arm, per [VoiceConversationController] rules. Runs as a single coroutine
 * so session switch/lifecycle teardown is one [end] call; every engine callback
 * is inherently generation-safe because ending cancels the loop job.
 */
private const val EMPTY_CAPTURE_BACKOFF_MILLIS = 500L
/** Let the barge detector observe Speaking before TTS emits audio. */
private const val BARGE_PLAYBACK_ARM_MILLIS = 600L
/** Let the speaker/audio route drain before reopening the microphone. */
private const val POST_PLAYBACK_MIC_COOLDOWN_MILLIS = 1_500L

class VoiceConversationHost(
    val controller: VoiceConversationController,
    private val scope: CoroutineScope,
    private val engines: VoiceConversationEngines,
) {
    private var loopJob: Job? = null
    private var activeRun: SpeechStreamRun? = null

    @Volatile
    private var barged = false

    @Volatile
    private var bargedCapture: DictationRecording? = null

    @Volatile
    private var bargeStopIssued = false

    @Volatile
    private var bargePlaybackStopIssued = false

    private var bargeMonitorJob: Job? = null

    /** The reply text most recently sent to TTS, for echo self-capture checks. */
    @Volatile
    private var lastSpokenText: String? = null

    val isActive: Boolean get() = controller.isActive

    /**
     * Run one microphone operation until it completes or mute is enabled. The
     * child job is deliberately separate from the loop job so muting cancels
     * recorder polling without ending the conversation itself.
     */
    private suspend fun captureUntilMuted(
        operation: suspend () -> DictationRecording?,
    ): DictationRecording? = coroutineScope {
        val capture = async(start = CoroutineStart.UNDISPATCHED) { operation() }
        val muted = async(start = CoroutineStart.UNDISPATCHED) {
            controller.muted.first { it }
        }
        try {
            select {
                capture.onAwait { it }
                muted.onAwait {
                    capture.cancel()
                    null
                }
            }
        } finally {
            muted.cancel()
            capture.cancel()
        }
    }

    fun start() {
        if (!controller.start()) return
        engines.startAudioSession()
        engines.log("host:start gen=${controller.generation}")
        loopJob = scope.launch {
            try {
                while (isActive && controller.isActive) {
                    runTurn()
                }
            } finally {
                engines.stopPlayback()
                engines.stopAudioSession()
            }
        }
    }

    /** Hard end from UI, stop phrase, session switch, or lifecycle. */
    fun end() {
        engines.log("host:end gen=${controller.generation}")
        controller.end()
        val job = loopJob
        loopJob = null
        job?.cancel()
        bargeMonitorJob?.cancel()
        bargeMonitorJob = null
        val run = activeRun
        activeRun = null
        if (run != null) {
            scope.launch { run.stop() }
        }
        engines.stopPlayback()
        engines.stopAudioSession()
    }

    private suspend fun runTurn() {
        // Muted: hold without capturing until unmuted or ended.
        controller.muted.first { !it }

        engines.log("listen:start")
        val recording = captureUntilMuted {
            engines.listen { level -> controller.onListeningLevel(level) }
        }
        engines.log("listen:done captured=${recording != null}")
        if (!controller.onUtteranceCaptured()) return
        if (recording == null) {
            controller.onCaptureEmpty()
            // A failing recorder must not spin the loop.
            kotlinx.coroutines.delay(EMPTY_CAPTURE_BACKOFF_MILLIS)
            return
        }

        val transcript = try {
            engines.transcribe(recording.dataUrl, recording.mimeType)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val text = transcript.getOrNull()
        engines.log("transcribe:done chars=${text?.transcript?.length ?: -1}")
        if (text == null) {
            controller.onTranscriptionFailed()
            return
        }
        when (controller.onTranscript(text.transcript)) {
            TranscriptDisposition.Submit -> submitAndSpeakLoop(text.transcript)
            TranscriptDisposition.EndConversation -> end()
            TranscriptDisposition.Rearm -> Unit
        }
    }

    /**
     * Submit → speak, then chain barge-in follow-ups: a barge stops the reply
     * mid-flight, captures the interruption, and (for substantive text) submits
     * it as the next prompt with `interrupted=true`.
     */
    private suspend fun submitAndSpeakLoop(firstText: String) {
        var pending: String? = firstText
        var interrupted = false
        while (pending != null && controller.isActive) {
            val text = pending
            pending = null
            if (interrupted) {
                // Give the stopped turn a bounded window to settle before resubmitting.
                withTimeoutOrNull(engines.bargeSettleTimeoutMillis) {
                    engines.replies.first { !it.turnRunning }
                }
                if (!controller.isActive) return
            }
            barged = false
            bargedCapture = null
            bargeStopIssued = false
            bargePlaybackStopIssued = false
            val monitor = startBargeMonitor()
            try {
                speakTurn(text, interrupted)
            } finally {
                // A triggered monitor is mid-capture; let it finish its utterance.
                if (!barged) monitor?.cancel()
            }
            if (barged) {
                monitor?.join()
                pending = processBargeCapture(bargedCapture)
                bargedCapture = null
                interrupted = true
            } else {
                // Do not reopen the normal microphone listener on the same
                // scheduler turn as TTS completion. The speaker/audio route can
                // still contain the tail of the reply, which otherwise becomes
                // the next user utterance (even though barge monitoring has
                // already been canceled).
                kotlinx.coroutines.delay(POST_PLAYBACK_MIC_COOLDOWN_MILLIS)
            }
        }
    }

    private fun startBargeMonitor(): Job? {
        val monitor = engines.monitorBargeIn ?: return null
        val job = scope.launch {
            val recording = captureUntilMuted {
                monitor {
                    barged = true
                    engines.log("barge:candidate")
                    if (controller.state.value !is VoiceConversationState.Speaking) {
                        // There is no audible reply to classify while Thinking,
                        // so preserve the responsive interruption path.
                        stopForBarge()
                        controller.onBargeIn()
                    } else {
                        // Stop only local audio immediately so a real user can
                        // take the floor. Do not cancel Hermes until STT and echo
                        // comparison complete; otherwise TTS cancels itself.
                        stopPlaybackForBarge()
                        engines.log("barge:playback-candidate")
                    }
                }
            }
            if (barged && !controller.muted.value) {
                bargedCapture = recording
            } else if (controller.muted.value) {
                // Mute can race with the detector after it has stopped local
                // playback. Discard that partial barge and re-arm once unmuted.
                val playbackWasStopped = bargePlaybackStopIssued
                barged = false
                bargedCapture = null
                bargeStopIssued = false
                bargePlaybackStopIssued = false
                if (playbackWasStopped) controller.onBargeIn()
            }
        }
        bargeMonitorJob = job
        return job
    }

    /** Transcribe the barged utterance; returns the next prompt text or null. */
    private suspend fun processBargeCapture(capture: DictationRecording?): String? {
        if (!controller.isActive) return null
        if (capture == null) return null
        if (!controller.onUtteranceCaptured()) return null
        val result = try {
            engines.transcribe(capture.dataUrl, capture.mimeType)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
        val text = result.getOrNull()
        if (text == null) {
            controller.onTranscriptionFailed()
            return null
        }
        // Speaker bleed: a playback-phase capture that matches the reply Hermes
        // just spoke is a self-capture, not the user — discard it or the loop
        // feeds its own voice back into itself (TTS → STT → TTS).
        val echoSimilarity = VoiceEchoFilter.bestSimilarity(text.transcript, lastSpokenText)
        engines.log(
            "barge:echo-check sim=${"%.2f".format(echoSimilarity)} " +
                "aChars=${text.transcript.length} bChars=${lastSpokenText?.length ?: 0}",
        )
        if (echoSimilarity >= VoiceEchoFilter.DEFAULT_SIMILARITY_THRESHOLD) {
            engines.log("barge:echo-discarded")
            controller.onCaptureEmpty()
            return null
        }
        // The capture is now classified as user speech. Keep the stop seam
        // for a genuine interruption, but never invoke it for speaker bleed.
        stopForBarge()
        controller.onBargeIn()
        return when (controller.onTranscript(text.transcript)) {
            TranscriptDisposition.Submit -> text.transcript
            TranscriptDisposition.EndConversation -> {
                end()
                null
            }
            TranscriptDisposition.Rearm -> null
        }
    }

    private fun stopForBarge() {
        if (bargeStopIssued) return
        bargeStopIssued = true
        activeRun?.let { run -> scope.launch { run.stop() } }
        if (!bargePlaybackStopIssued) engines.stopPlayback()
        engines.stopTurn()
    }

    private fun stopPlaybackForBarge() {
        if (bargePlaybackStopIssued) return
        bargePlaybackStopIssued = true
        activeRun?.let { run -> scope.launch { run.stop() } }
        engines.stopPlayback()
    }

    /** Mute only the microphone capture; the loop remains explicitly active. */
    fun setMuted(muted: Boolean) {
        controller.setMuted(muted)
    }

    private suspend fun speakTurn(promptText: String, interrupted: Boolean = false) {
        val baseline = engines.replies.first().messageCount
        engines.log("submit interrupted=$interrupted baseline=$baseline")
        engines.submit(promptText, interrupted)

        val started = withTimeoutOrNull(engines.turnStartTimeoutMillis) {
            engines.replies.first { it.turnRunning || it.messageCount > baseline }
        }
        if (started == null) {
            engines.log("turn:never-started")
            controller.onTurnCompleteWithoutSpeech()
            return
        }
        engines.log("turn:started")

        // Wait for the turn to settle WITHOUT touching the speech socket. An
        // open `/api/audio/speak-stream` session during generation starves the
        // same single-process server that is producing the reply (observed on a
        // released host: chat deltas froze the moment the socket opened and
        // resumed the moment it closed), deadlocking the loop. Speech starts
        // only after the reply finalizes; the streaming socket is then used as
        // a one-shot transport so audio still begins on the first synthesized
        // sentence rather than after whole-reply synthesis.
        var lastViewLog = ""
        suspend fun awaitNotRunning(): VoiceReplyView = engines.replies.first { view ->
            val viewLog = "turn:view running=${view.turnRunning} " +
                "streamIdx=${view.streamingIndex} finalIdx=${view.finalAssistantIndex} " +
                "count=${view.messageCount}"
            if (viewLog != lastViewLog) {
                lastViewLog = viewLog
                engines.log(viewLog)
            }
            !view.turnRunning
        }

        // The settle must be STABLE: transcript reconciliation can transiently
        // replace the optimistic messages mid-generation, briefly reporting "no
        // turn running" with the reply missing. A flap back to running within
        // the confirmation window means the turn is still going.
        var settled = awaitNotRunning()
        while (true) {
            val resumed = withTimeoutOrNull(engines.settleConfirmMillis) {
                engines.replies.first { it.turnRunning }
            } ?: break
            engines.log("turn:resumed-after-flap")
            settled = awaitNotRunning()
        }
        settled = engines.replies.first()
        engines.log("turn:settled finalIdx=${settled.finalAssistantIndex} baseline=$baseline")
        if (!controller.isActive || barged) return

        // Only a reply created after this turn's baseline is speakable — a
        // resumed session can resurface historical replies, and they must
        // stay silent.
        val replyText = settled.finalAssistantText
            ?.takeIf { settled.finalAssistantIndex >= baseline }
        if (replyText == null) {
            controller.onTurnCompleteWithoutSpeech()
            return
        }
        speakFinalizedReply(replyText)
    }

    /** Speak a finalized reply: one-shot streaming first, REST fallback. */
    private suspend fun speakFinalizedReply(replyText: String) {
        lastSpokenText = replyText
        engines.log("stream:opening")
        // The connect itself must be bounded: a host that accepts the route in
        // capability probes can still leave the WS upgrade hanging, and an
        // unbounded connect would freeze the loop in Thinking forever.
        val socket = withTimeoutOrNull(engines.streamOpenTimeoutMillis) {
            try {
                engines.openStream()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        if (socket == null) {
            engines.log("stream:unavailable")
            speakViaRest(replyText)
            return
        }
        engines.log("stream:opened")
        val sink = FirstAudioNotifyingSink(engines.sinkFactory()) {
            controller.onSpeechStarted()
        }
        val run = SpeechStreamRun(socket, sink, engines.streamProgressTimeoutMillis, engines.log)
        activeRun = run
        val pump = scope.async(start = CoroutineStart.DEFAULT) { run.pump() }
        engines.log("stream:feeding chars=${replyText.length}")
        run.feedText(replyText)
        engines.log("stream:fed")
        run.finishText()
        engines.log("stream:done-sent")
        val outcome = pump.await()
        activeRun = null
        engines.log("stream:outcome=$outcome audio=${run.audioStarted}")
        if (!controller.isActive || barged) return
        when (outcome) {
            SpeechStreamOutcome.Completed,
            SpeechStreamOutcome.CompletedPartial,
            -> if (run.audioStarted) {
                controller.onSpeechFinished()
            } else {
                speakViaRest(replyText)
            }
            SpeechStreamOutcome.Fallback -> speakViaRest(replyText)
            // The server refused the credential. REST synthesis is billable and
            // would present the same refused credential, so this turn stays
            // silent and credential recovery is left to the connection layer.
            SpeechStreamOutcome.CredentialRejected -> controller.onSpeechFailed()
            SpeechStreamOutcome.Stopped -> Unit
        }
    }

    /** One-shot REST synthesis + playback of the finalized reply, or re-arm. */
    private suspend fun speakViaRest(replyText: String?) {
        // A barge already cut this turn; its reply must stay silent.
        if (barged) return
        val speechText = replyText?.let { sanitizeTextForSpeech(it) }.orEmpty()
        engines.log("rest:speak chars=${speechText.length}")
        if (speechText.isBlank()) {
            controller.onTurnCompleteWithoutSpeech()
            return
        }
        val audio = engines.restSpeak(speechText).getOrNull()
        if (audio == null) {
            engines.log("rest:synthesis-failed")
            controller.onSpeechFailed()
            return
        }
        if (!controller.isActive || barged) return
        engines.log("rest:playing bytes=${audio.bytes.size}")
        // Arm playback protection before handing the clip to MediaPlayer. The
        // Android engine invokes onStarted immediately after player.start(),
        // which leaves a race where the monitor can sample speaker bleed while
        // controller is still Thinking and fire a false barge.
        controller.onSpeechStarted()
        // The barge monitor samples independently every 100 ms. Give it a
        // chance to observe Speaking and enter BargeInDetector's playback
        // grace window before MediaPlayer can emit the first samples.
        kotlinx.coroutines.delay(BARGE_PLAYBACK_ARM_MILLIS)
        engines.playAudio(
            audio,
            { controller.onSpeechStarted() },
            { controller.onSpeechFinished() },
            { controller.onSpeechFailed() },
        )
        engines.log("rest:playback-done")
    }
}

/** Wraps a sink to arm playback protection before the first PCM write. */
internal class FirstAudioNotifyingSink(
    private val delegate: PcmSpeechSink,
    private val onFirstAudio: () -> Unit,
) : PcmSpeechSink {
    private var notified = false

    override fun start(sampleRateHz: Int, channels: Int): Boolean {
        if (!notified) {
            // The barge monitor runs concurrently with the stream pump. Arm
            // playback protection before AudioTrack setup and before any PCM
            // can be written, so its next sample enters playback grace rather
            // than using the normal speech threshold.
            notified = true
            onFirstAudio()
        }
        return delegate.start(sampleRateHz, channels)
    }

    override suspend fun write(pcm: ByteArray) {
        delegate.write(pcm)
    }

    override suspend fun finish() = delegate.finish()

    override fun stop() = delegate.stop()
}
