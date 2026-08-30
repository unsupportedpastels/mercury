package com.unsupportedpastels.hermesandroid.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSpeechSocket : SpeechStreamSocket {
    val sent = mutableListOf<String>()
    var closed = false
    private val inbound = Channel<SpeechSocketFrame?>(Channel.UNLIMITED)

    fun serverSends(frame: SpeechSocketFrame) {
        inbound.trySend(frame)
    }

    fun serverCloses(code: Int? = null) {
        peerCloseCode = code
        inbound.trySend(null)
    }

    private var peerCloseCode: Int? = null

    override suspend fun sendText(text: String) {
        if (closed) throw IllegalStateException("closed")
        sent += text
    }

    override suspend fun receiveFrame(): SpeechSocketFrame? = inbound.receive()

    override suspend fun closeCode(): Int? = peerCloseCode

    override suspend fun close() {
        closed = true
        inbound.trySend(null)
    }
}

private class RecordingSink : PcmSpeechSink {
    var startedRate: Int? = null
    var startResult = true
    var finished = false
    var stopped = false
    val written = mutableListOf<ByteArray>()

    override fun start(sampleRateHz: Int, channels: Int): Boolean {
        startedRate = sampleRateHz
        return startResult
    }

    override suspend fun write(pcm: ByteArray) {
        written += pcm
    }

    override suspend fun finish() {
        finished = true
    }

    override fun stop() {
        stopped = true
    }
}

private fun startFrame(rate: Int = 24_000, channels: Int = 1) =
    SpeechSocketFrame.Text("""{"type":"start","sample_rate":$rate,"channels":$channels}""")

class SpeechStreamRunTest {
    @Test
    fun playbackNotificationIsArmedWhenSinkStartsBeforePcmWrite() {
        var notified = false
        val sink = FirstAudioNotifyingSink(RecordingSink()) { notified = true }

        assertFalse(notified)
        assertTrue(sink.start(24_000, 1))
        assertTrue(notified)

        // The callback is one-shot; writing PCM must not change its ordering or
        // invoke it a second time.
        kotlinx.coroutines.test.runTest {
            sink.write(byteArrayOf(1, 2))
        }
        assertTrue(notified)
    }

    @Test
    fun completesAfterStartPcmEnd() = runTest {
        val socket = FakeSpeechSocket()
        val sink = RecordingSink()
        val run = SpeechStreamRun(socket, sink)

        socket.serverSends(startFrame())
        socket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(1, 2, 3, 4)))
        socket.serverSends(SpeechSocketFrame.Text("""{"type":"end"}"""))

        assertEquals(SpeechStreamOutcome.Completed, run.pump())
        assertEquals(24_000, sink.startedRate)
        assertEquals(1, sink.written.size)
        assertTrue(sink.finished)
        assertTrue(socket.closed)
    }

    @Test
    fun feedsTextAndDoneFrames() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())

        run.feedText("Hello ")
        run.feedText("world.")
        run.finishText()

        assertEquals(
            listOf("""{"text":"Hello "}""", """{"text":"world."}""", """{"done":true}"""),
            socket.sent,
        )
    }

    /**
     * §21: a `4401` close is the one class that may drive credential recovery.
     * It must never resolve to [SpeechStreamOutcome.Fallback], because that is
     * the caller's licence to synthesize the same text over billable REST TTS
     * with a credential the server has just refused.
     */
    @Test
    fun aCredentialRejectionCloseIsNotAFallbackLicence() = runTest {
        val socket = FakeSpeechSocket()
        val sink = RecordingSink()
        val run = SpeechStreamRun(socket, sink)

        socket.serverCloses(4401)

        assertEquals(SpeechStreamOutcome.CredentialRejected, run.pump())
        assertFalse(sink.finished)
    }

    /** Every other close class keeps the existing one-shot REST fallback. */
    @Test
    fun policyFeatureAndServerErrorClosesStayFallbacks() = runTest {
        listOf(4403, 4404, 4408, 1011, 1006, null).forEach { code ->
            val socket = FakeSpeechSocket()
            val run = SpeechStreamRun(socket, RecordingSink())

            socket.serverCloses(code)

            assertEquals("close code $code", SpeechStreamOutcome.Fallback, run.pump())
        }
    }

    /** After audio, no close code may re-open the whole-text replay door. */
    @Test
    fun aCredentialRejectionAfterAudioStillNeverReplays() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())

        socket.serverSends(startFrame())
        socket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(1, 2, 3, 4)))
        socket.serverCloses(4401)

        assertEquals(SpeechStreamOutcome.CompletedPartial, run.pump())
    }

    @Test
    fun fallbackFrameBeforeAudioIsFallback() = runTest {
        val socket = FakeSpeechSocket()
        val sink = RecordingSink()
        val run = SpeechStreamRun(socket, sink)

        socket.serverSends(SpeechSocketFrame.Text("""{"type":"fallback"}"""))
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
        assertFalse(sink.finished)
    }

    @Test
    fun dropBeforeAudioIsFallback() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())

        socket.serverCloses()
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
    }

    @Test
    fun dropAfterAudioIsCompletedPartialNeverReplay() = runTest {
        val socket = FakeSpeechSocket()
        val sink = RecordingSink()
        val run = SpeechStreamRun(socket, sink)

        socket.serverSends(startFrame())
        socket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(1, 2)))
        socket.serverCloses()

        assertEquals(SpeechStreamOutcome.CompletedPartial, run.pump())
        assertTrue(sink.stopped)
    }

    @Test
    fun stopResolvesAsStoppedAndSendsStopFrame() = runTest {
        val socket = FakeSpeechSocket()
        val sink = RecordingSink()
        val run = SpeechStreamRun(socket, sink)

        val outcome = launch { assertEquals(SpeechStreamOutcome.Stopped, run.pump()) }
        socket.serverSends(startFrame())
        run.stop()
        outcome.join()
        assertTrue(sink.stopped)
        assertTrue(socket.sent.contains("""{"stop":true}"""))
    }

    @Test
    fun rejectsStereoStart() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())
        socket.serverSends(startFrame(channels = 2))
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
    }

    @Test
    fun rejectsAbsurdSampleRate() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())
        socket.serverSends(startFrame(rate = 1_000_000))
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
    }

    @Test
    fun unplayableSinkFallsBackBeforeAudio() = runTest {
        val socket = FakeSpeechSocket()
        val sink = RecordingSink().apply { startResult = false }
        val run = SpeechStreamRun(socket, sink)
        socket.serverSends(startFrame())
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
    }

    @Test
    fun oversizedPcmFrameAbortsWithoutReplayAfterAudio() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())
        socket.serverSends(startFrame())
        socket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(5, 6)))
        socket.serverSends(
            SpeechSocketFrame.Binary(ByteArray(SpeechStreamProtocol.MAX_PCM_FRAME_BYTES + 1)),
        )
        assertEquals(SpeechStreamOutcome.CompletedPartial, run.pump())
    }

    @Test
    fun pcmBeforeStartIsProtocolViolation() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())
        socket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(1, 2)))
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
    }

    @Test
    fun endBeforeAnyAudioFallsBack() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())
        socket.serverSends(SpeechSocketFrame.Text("""{"type":"end"}"""))
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
    }

    @Test
    fun unknownFramesAreIgnored() = runTest {
        val socket = FakeSpeechSocket()
        val sink = RecordingSink()
        val run = SpeechStreamRun(socket, sink)
        socket.serverSends(SpeechSocketFrame.Text("""{"type":"telemetry","x":1}"""))
        socket.serverSends(startFrame())
        socket.serverSends(SpeechSocketFrame.Binary(byteArrayOf(9, 9)))
        socket.serverSends(SpeechSocketFrame.Text("""{"type":"end"}"""))
        assertEquals(SpeechStreamOutcome.Completed, run.pump())
    }

    @Test
    fun progressTimeoutBeforeAudioFallsBack() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink(), progressTimeoutMillis = 1_000L)
        // Nothing ever arrives; virtual time advances past the watchdog.
        assertEquals(SpeechStreamOutcome.Fallback, run.pump())
    }

    @Test
    fun socketTeardownDuringSendDoesNotCancelTheCaller() = runTest {
        // Regression: the server answered `fallback` and the pump closed the
        // socket while feedText was still suspended in send — the resulting
        // CancellationException belongs to the socket and must not propagate
        // into (and silently kill) the calling voice loop.
        val socket = object : SpeechStreamSocket {
            override suspend fun sendText(text: String) {
                throw kotlinx.coroutines.CancellationException("socket session cancelled")
            }

            override suspend fun receiveFrame(): SpeechSocketFrame? = null

            override suspend fun close() = Unit
        }
        val run = SpeechStreamRun(socket, RecordingSink())
        run.feedText("hello")
        run.finishText()
        // Reaching here without CancellationException is the assertion.
        assertTrue(true)
    }

    @Test
    fun feedAfterStopIsIgnored() = runTest {
        val socket = FakeSpeechSocket()
        val run = SpeechStreamRun(socket, RecordingSink())
        run.stop()
        run.feedText("late text")
        assertEquals(listOf("""{"stop":true}"""), socket.sent)
    }
}
