package com.unsupportedpastels.hermesandroid.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AudioTrackSpeechSinkTest {
    @Test
    fun finishWaitsForTheLastFrame() = runTest {
        val track = TestAudioTrack()
        val sink = AudioTrackSpeechSink(StandardTestDispatcher(testScheduler)) { track }
        assertTrue(sink.start(22_050, 1))
        sink.write(ByteArray(19_536)) // 443 ms, accepted in partial writes.
        track.playedFrames = 9_767

        val finishing = launch { sink.finish() }
        runCurrent()
        assertFalse(finishing.isCompleted)
        assertEquals(0, track.releases)

        track.playedFrames++
        testScheduler.advanceTimeBy(20)
        runCurrent()
        assertTrue(finishing.isCompleted)
        assertEquals(1, track.releases)
    }

    @Test
    fun stopDuringDrainDoesNotReleaseReplacementPlayback() = runTest {
        val old = TestAudioTrack()
        val replacement = TestAudioTrack()
        var next = old
        val sink = AudioTrackSpeechSink(StandardTestDispatcher(testScheduler)) { next }
        sink.start(22_050, 1)
        sink.write(byteArrayOf(1, 2))
        val finishing = launch { sink.finish() }
        runCurrent()

        assertFalse(finishing.isCompleted)
        sink.stop()
        assertEquals(1, old.releases)
        next = replacement
        sink.start(22_050, 1)
        finishing.cancel()
        finishing.join()
        assertEquals(1, old.releases)
        assertEquals(0, replacement.releases)
        sink.stop()
    }

    private class TestAudioTrack : AudioTrack(
        AudioAttributes.Builder().build(),
        AudioFormat.Builder().setSampleRate(22_050)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
        65_536, MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
    ) {
        var playedFrames = 0
        var releases = 0
        override fun getPlaybackHeadPosition(): Int = playedFrames
        override fun write(pcm: ByteArray, offset: Int, size: Int): Int = minOf(size, 1_024)
        override fun release() { releases++; super.release() }
    }
}
