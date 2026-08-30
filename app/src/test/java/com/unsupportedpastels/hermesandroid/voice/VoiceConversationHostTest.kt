package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun view(
    turnRunning: Boolean = false,
    streamingText: String? = null,
    finalText: String? = null,
    finalIndex: Int = -1,
    messageCount: Int = 0,
    streamingIndex: Int = if (streamingText != null) messageCount - 1 else -1,
) = VoiceReplyView(turnRunning, streamingText, streamingIndex, finalText, finalIndex, messageCount)

/** Test harness: scripted listen results, recorded submissions, fake speech. */
private class Harness(
    stopPhrases: List<String> = listOf("stop"),
    val streamingAvailable: Boolean = false,
) {
    val controller = VoiceConversationController { stopPhrases }
    val replies = MutableStateFlow(view())
    val submissions = mutableListOf<String>()
    val interruptedFlags = mutableListOf<Boolean>()
    val restSpoken = mutableListOf<String>()
    var transcribeResult: Result<TranscriptionResult> =
        Result.success(TranscriptionResult("hello there", null))
    var listenGate = CompletableDeferred<DictationRecording?>()

    /** Mirrors the real ViewModel: sendMessage flips sending state synchronously. */
    var onSubmit: (String) -> Unit = {}

    var bargeEnabled = false

    /** One element = one barge; a consumed trigger never re-fires. */
    val bargeTrigger = Channel<Unit>(Channel.UNLIMITED)
    var bargeCapture: DictationRecording? =
        DictationRecording("data:audio/mp4;base64,QQ==", "audio/mp4")
    var stopTurnCalls = 0
    var listenCalls = 0
    var listenCancellationCount = 0
    var bargeCancellationCount = 0
    var playbackStops = 0
    var holdPlayback = false
    var playbackGate = CompletableDeferred<Unit>()
    val streamSocket = FakeHostSpeechSocket()

    fun engines(turnStartTimeoutMillis: Long = 10_000L) = VoiceConversationEngines(
        listen = { _ ->
            listenCalls++
            try {
                listenGate.await()
            } catch (cancelled: CancellationException) {
                listenCancellationCount++
                throw cancelled
            }
        },
        transcribe = { _, _ -> transcribeResult },
        submit = { text, interrupted ->
            submissions += text
            interruptedFlags += interrupted
            onSubmit(text)
        },
        replies = replies,
        openStream = { if (streamingAvailable) streamSocket else null },
        sinkFactory = { HostRecordingSink() },
        restSpeak = { text ->
            restSpoken += text
            Result.success(SpeechAudio(byteArrayOf(1), "audio/mpeg"))
        },
        playAudio = { _, onStarted, onFinished, _ ->
            onStarted()
            if (holdPlayback) playbackGate.await()
            onFinished()
        },
        stopPlayback = { playbackStops++ },
        stopTurn = {
            stopTurnCalls++
            // Mirrors the real seam: stopping the turn settles busy state.
            replies.value = replies.value.copy(turnRunning = false)
        },
        monitorBargeIn = if (bargeEnabled) {
            { onTrigger ->
                try {
                    bargeTrigger.receive()
                    onTrigger()
                    bargeCapture
                } catch (cancelled: CancellationException) {
                    bargeCancellationCount++
                    throw cancelled
                }
            }
        } else {
            null
        },
        turnStartTimeoutMillis = turnStartTimeoutMillis,
        // advanceUntilIdle skips virtual time, so the pre-audio watchdog must
        // not fire between scripted frames.
        streamProgressTimeoutMillis = Long.MAX_VALUE,
    )

    fun utterance(dataUrl: String = "data:audio/mp4;base64,QQ==") {
        val gate = listenGate
        listenGate = CompletableDeferred()
        gate.complete(DictationRecording(dataUrl, "audio/mp4"))
    }

    fun releasePlayback() {
        if (!playbackGate.isCompleted) playbackGate.complete(Unit)
    }
}

private class HostRecordingSink : PcmSpeechSink {
    override fun start(sampleRateHz: Int, channels: Int): Boolean = true

    override suspend fun write(pcm: ByteArray) = Unit

    override suspend fun finish() = Unit

    override fun stop() = Unit
}

private class FakeHostSpeechSocket : SpeechStreamSocket {
    val sent = mutableListOf<String>()
    private val inbound = kotlinx.coroutines.channels.Channel<SpeechSocketFrame?>(
        kotlinx.coroutines.channels.Channel.UNLIMITED,
    )

    fun serverSends(frame: SpeechSocketFrame) {
        inbound.trySend(frame)
    }

    fun serverCloses(code: Int? = null) {
        peerCloseCode = code
        inbound.trySend(null)
    }

    private var peerCloseCode: Int? = null

    override suspend fun sendText(text: String) {
        sent += text
    }

    override suspend fun receiveFrame(): SpeechSocketFrame? = inbound.receive()

    override suspend fun closeCode(): Int? = peerCloseCode

    override suspend fun close() {
        inbound.trySend(null)
    }
}

class VoiceConversationHostTest {
    private fun Harness.startHost(
        scope: kotlinx.coroutines.test.TestScope,
    ): VoiceConversationHost {
        // Prime the listen gate so the first turn waits for a scripted utterance.
        listenGate = CompletableDeferred()
        val host = VoiceConversationHost(controller, scope, engines())
        host.start()
        // Let the loop reach its await on the current gate before scripting.
        scope.advanceUntilIdle()
        return host
    }

    @Test
    fun substantiveTranscriptSubmitsAndSpeaksViaRest() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()
        assertEquals(listOf("hello there"), h.submissions)
        h.replies.value = view(
            turnRunning = false,
            finalText = "Here is the answer.",
            finalIndex = 1,
            messageCount = 2,
        )
        advanceUntilIdle()

        assertEquals(listOf("Here is the answer."), h.restSpoken)
        // Playback finished → re-armed for the next utterance.
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        assertTrue(h.listenCalls >= 2)
        host.end()
    }

    @Test
    fun stopPhraseEndsLoopWithoutSubmitting() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        h.transcribeResult = Result.success(TranscriptionResult("Stop.", null))
        h.utterance()
        advanceUntilIdle()
        assertTrue(h.submissions.isEmpty())
        assertEquals(VoiceConversationState.Idle, h.controller.state.value)
        assertFalse(host.isActive)
    }

    @Test
    fun emptyTranscriptRearmsWithoutSubmit() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        h.transcribeResult = Result.success(TranscriptionResult("", null))
        h.utterance()
        advanceUntilIdle()
        assertTrue(h.submissions.isEmpty())
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        assertTrue(h.listenCalls >= 2)
        host.end()
    }

    @Test
    fun transcriptionFailureShowsNoticeAndRearms() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        h.transcribeResult = Result.failure(RuntimeException("boom"))
        h.utterance()
        advanceUntilIdle()
        assertEquals(VoiceConversationNotice.TranscriptionFailed, h.controller.notice.value)
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        host.end()
    }

    @Test
    fun toolOnlyTurnRearmsWithoutSpeaking() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()
        // Turn completes with no new assistant text.
        h.replies.value = view(turnRunning = false, messageCount = 2)
        advanceUntilIdle()
        assertTrue(h.restSpoken.isEmpty())
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        host.end()
    }

    @Test
    fun historicalReplyBeforeBaselineIsNeverSpoken() = runTest {
        val h = Harness()
        // Simulate an old assistant reply already in the transcript.
        h.replies.value = view(finalText = "Old reply.", finalIndex = 0, messageCount = 1)
        val host = h.startHost(this)
        h.onSubmit = {
            h.replies.value = view(
                turnRunning = true,
                finalText = "Old reply.",
                finalIndex = 0,
                messageCount = 3,
            )
        }
        h.utterance()
        advanceUntilIdle()
        // Turn ends with no new final reply (e.g. errored) — old reply stays silent.
        h.replies.value = view(
            turnRunning = false,
            finalText = "Old reply.",
            finalIndex = 0,
            messageCount = 3,
        )
        advanceUntilIdle()
        assertTrue(h.restSpoken.isEmpty())
        host.end()
    }

    @Test
    fun turnThatNeverStartsRearms() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        h.utterance()
        advanceUntilIdle()
        // No turn activity ever appears; the start timeout re-arms the loop.
        assertEquals(listOf("hello there"), h.submissions)
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        host.end()
    }

    @Test
    fun finalizedReplyStreamsAsOneShotAfterTurnSettles() = runTest {
        val h = Harness(streamingAvailable = true)
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        // Nothing touches the speech socket while the turn is generating —
        // an open speak-stream can starve the server producing the reply.
        h.replies.value = view(turnRunning = true, streamingText = "Hello", messageCount = 2)
        advanceUntilIdle()
        assertTrue(h.streamSocket.sent.isEmpty())

        h.replies.value = view(
            turnRunning = false,
            finalText = "Hello world.",
            finalIndex = 1,
            messageCount = 2,
        )
        // Server plays the audio and ends the stream.
        h.streamSocket.serverSends(
            SpeechSocketFrame.Text("""{"type":"start","sample_rate":24000,"channels":1}"""),
        )
        h.streamSocket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(1, 2)))
        h.streamSocket.serverSends(SpeechSocketFrame.Text("""{"type":"end"}"""))
        advanceUntilIdle()

        assertEquals(
            listOf("""{"text":"Hello world."}""", """{"done":true}"""),
            h.streamSocket.sent,
        )
        // Streamed audio played; REST fallback never used.
        assertTrue(h.restSpoken.isEmpty())
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        host.end()
    }

    @Test
    fun streamFallbackBeforeAudioSpeaksOnceViaRest() = runTest {
        val h = Harness(streamingAvailable = true)
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        h.streamSocket.serverSends(SpeechSocketFrame.Text("""{"type":"fallback"}"""))
        h.replies.value = view(
            turnRunning = false,
            finalText = "Hello world.",
            finalIndex = 1,
            messageCount = 2,
        )
        advanceUntilIdle()

        assertEquals(listOf("Hello world."), h.restSpoken)
        host.end()
    }

    /**
     * A `4401` before any audio must not be laundered into the REST fallback:
     * REST TTS is billable and the credential has just been refused, so the
     * only correct outcome is to stop and let credential recovery run.
     */
    @Test
    fun aRejectedSpeechStreamNeverSynthesizesOverBillableRest() = runTest {
        val h = Harness(streamingAvailable = true)
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        h.streamSocket.serverCloses(4401)
        h.replies.value = view(
            turnRunning = false,
            finalText = "Hello world.",
            finalIndex = 1,
            messageCount = 2,
        )
        advanceUntilIdle()

        assertTrue(h.restSpoken.isEmpty())
        host.end()
    }

    @Test
    fun streamDropAfterAudioNeverReplaysViaRest() = runTest {
        val h = Harness(streamingAvailable = true)
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        h.streamSocket.serverSends(
            SpeechSocketFrame.Text("""{"type":"start","sample_rate":24000,"channels":1}"""),
        )
        h.streamSocket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(1, 2)))
        h.streamSocket.serverCloses()
        h.replies.value = view(
            turnRunning = false,
            finalText = "Hello world.",
            finalIndex = 1,
            messageCount = 2,
        )
        advanceUntilIdle()

        assertTrue(h.restSpoken.isEmpty())
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        host.end()
    }

    @Test
    fun endDuringListeningStopsEverything() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        advanceUntilIdle()
        host.end()
        advanceUntilIdle()
        assertEquals(VoiceConversationState.Idle, h.controller.state.value)
        assertTrue(h.playbackStops >= 1)
        // A late utterance from a torn-down recorder never submits.
        h.utterance()
        advanceUntilIdle()
        assertTrue(h.submissions.isEmpty())
    }

    @Test
    fun mutedLoopHoldsWithoutCapturing() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        advanceUntilIdle()
        val callsWhileListening = h.listenCalls
        // Complete the pending listen while muted: the next turn must hold.
        h.controller.setMuted(true)
        h.transcribeResult = Result.success(TranscriptionResult("", null))
        h.utterance()
        advanceUntilIdle()
        assertEquals(callsWhileListening, h.listenCalls)
        h.controller.setMuted(false)
        advanceUntilIdle()
        assertTrue(h.listenCalls > callsWhileListening)
        host.end()
    }

    @Test
    fun mutingCancelsActiveListeningCapture() = runTest {
        val h = Harness()
        val host = h.startHost(this)

        h.controller.setMuted(true)
        advanceUntilIdle()

        assertEquals(1, h.listenCancellationCount)
        host.end()
    }

    @Test
    fun mutingCancelsActiveBargeMonitor() = runTest {
        val h = Harness()
        h.bargeEnabled = true
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        h.controller.setMuted(true)
        advanceUntilIdle()

        assertEquals(1, h.bargeCancellationCount)
        host.end()
    }

    @Test
    fun historicalReplyResurfacedBySessionResumeIsNeverSpoken() = runTest {
        val h = Harness(streamingAvailable = true)
        // Two messages of history exist before the voice turn.
        h.replies.value = view(finalText = "Old reply.", finalIndex = 1, messageCount = 2)
        val host = h.startHost(this)
        h.onSubmit = {
            // Session resume re-marks the OLD reply as streaming while the new
            // turn runs — its index (1) sits below the baseline (2).
            h.replies.value = view(
                turnRunning = true,
                streamingText = "Old reply.",
                streamingIndex = 1,
                messageCount = 4,
            )
        }
        h.utterance()
        advanceUntilIdle()
        // The old text must never reach the speech socket during the turn.
        assertTrue(h.streamSocket.sent.isEmpty())

        // Turn ends with no NEW finalized reply — the resurfaced old reply
        // (index 1 < baseline 2) stays silent and the loop re-arms.
        h.replies.value = view(
            turnRunning = false,
            finalText = "Old reply.",
            finalIndex = 1,
            messageCount = 4,
        )
        advanceUntilIdle()
        assertTrue(h.streamSocket.sent.isEmpty())
        assertTrue(h.restSpoken.isEmpty())
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)

        host.end()
    }

    @Test
    fun transientNotRunningFlapDoesNotEndTheTurnEarly() = runTest {
        val h = Harness()
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        // Transcript reconciliation transiently replaces the optimistic
        // messages: "no turn, no reply" for a moment (observed on device).
        h.replies.value = view(turnRunning = false, messageCount = 1)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        // The turn resumes within the confirmation window…
        h.replies.value = view(turnRunning = true, messageCount = 2)
        advanceUntilIdle()
        // …and finally settles with the real reply, which is spoken.
        h.replies.value = view(
            turnRunning = false,
            finalText = "The real answer.",
            finalIndex = 1,
            messageCount = 2,
        )
        advanceUntilIdle()

        assertEquals(listOf("The real answer."), h.restSpoken)
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        host.end()
    }

    @Test
    fun bargeCaptureMatchingSpokenReplyIsDiscardedAsEcho() = runTest {
        val h = Harness()
        h.bargeEnabled = true
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()
        // Turn 1 settles and its reply is spoken.
        h.replies.value = view(
            turnRunning = false,
            finalText = "The build passed and all tests are green.",
            finalIndex = 1,
            messageCount = 2,
        )
        advanceUntilIdle()
        assertEquals(listOf("The build passed and all tests are green."), h.restSpoken)

        // Turn 2 submits normally…
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 4) }
        h.transcribeResult = Result.success(TranscriptionResult("run it again", null))
        h.holdPlayback = true
        h.playbackGate = CompletableDeferred()
        h.utterance()
        advanceUntilIdle()
        assertEquals(listOf("hello there", "run it again"), h.submissions)
        h.replies.value = view(
            turnRunning = false,
            finalText = "The second build passed and all tests are green.",
            finalIndex = 3,
            messageCount = 4,
        )
        advanceUntilIdle()

        // …then speaker bleed trips the barge and transcribes as the reply
        // Hermes itself spoke. It must be discarded, never submitted.
        h.transcribeResult = Result.success(
            TranscriptionResult("the build passed and all tests are green", null),
        )
        h.bargeTrigger.trySend(Unit)
        advanceUntilIdle()
        h.releasePlayback()
        advanceUntilIdle()

        assertEquals(2, h.submissions.size)
        // Echo classification must happen before any destructive cancellation:
        // speaker bleed is not a user interruption.
        assertEquals(0, h.stopTurnCalls)
        // Local playback may already have been cut to let a real user take the
        // floor, but the Hermes response itself must not be cancelled.
        assertEquals(1, h.playbackStops)
        host.end()
    }

    @Test
    fun bargeDuringTurnStopsItAndSubmitsCaptureInterrupted() = runTest {
        val h = Harness()
        h.bargeEnabled = true
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()
        assertEquals(listOf("hello there"), h.submissions)

        // The user talks over the reply; the captured utterance is substantive.
        h.transcribeResult = Result.success(TranscriptionResult("actually use pytest", null))
        h.bargeTrigger.trySend(Unit)
        advanceUntilIdle()

        assertTrue(h.stopTurnCalls >= 1)
        assertEquals(listOf("hello there", "actually use pytest"), h.submissions)
        assertEquals(listOf(false, true), h.interruptedFlags)
        host.end()
    }

    @Test
    fun bargeStopPhraseEndsLoopWithoutSubmitting() = runTest {
        val h = Harness()
        h.bargeEnabled = true
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        h.transcribeResult = Result.success(TranscriptionResult("stop", null))
        h.bargeTrigger.trySend(Unit)
        advanceUntilIdle()

        assertEquals(listOf("hello there"), h.submissions)
        assertEquals(VoiceConversationState.Idle, h.controller.state.value)
        assertFalse(host.isActive)
    }

    @Test
    fun bargeWithEmptyTranscriptRearmsWithoutInterruptedSubmit() = runTest {
        val h = Harness()
        h.bargeEnabled = true
        val host = h.startHost(this)
        h.onSubmit = { h.replies.value = view(turnRunning = true, messageCount = 2) }
        h.utterance()
        advanceUntilIdle()

        h.transcribeResult = Result.success(TranscriptionResult("", null))
        h.bargeTrigger.trySend(Unit)
        advanceUntilIdle()

        assertEquals(listOf("hello there"), h.submissions)
        assertEquals(listOf(false), h.interruptedFlags)
        assertTrue(h.controller.state.value is VoiceConversationState.Listening)
        host.end()
    }

    @Test
    fun snapshotMappingExtractsTurnAndReplyState() {
        val snapshot = ChatSessionSnapshot(
            messages = listOf(
                ChatMessage(ChatMessageRole.User, "hi"),
                ChatMessage(ChatMessageRole.Assistant, "old reply"),
                ChatMessage(ChatMessageRole.User, "again"),
                ChatMessage(ChatMessageRole.Assistant, "streaming…", isStreaming = true),
            ),
            isSending = true,
        )
        val mapped = snapshot.toVoiceReplyView()
        assertTrue(mapped.turnRunning)
        assertEquals("streaming…", mapped.streamingText)
        assertEquals("old reply", mapped.finalAssistantText)
        assertEquals(1, mapped.finalAssistantIndex)
        assertEquals(4, mapped.messageCount)

        val idle = ChatSessionSnapshot(messages = emptyList()).toVoiceReplyView()
        assertFalse(idle.turnRunning)
        assertNull(idle.streamingText)
    }
}
