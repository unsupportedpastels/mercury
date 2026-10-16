package com.unsupportedpastels.hermesandroid.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Streams mono int16 PCM from `/api/audio/speak-stream` through an [AudioTrack]
 * in streaming mode. Created lazily on the server's start frame (which carries
 * the sample rate); writes are blocking on [ioDispatcher]; an odd trailing byte
 * is carried into the next frame so 16-bit samples never split across writes.
 */
class AudioTrackSpeechSink(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val trackFactory: (sampleRateHz: Int) -> AudioTrack = ::defaultTrack,
) : PcmSpeechSink {
    @Volatile private var track: AudioTrack? = null
    private var carriedByte: Byte? = null
    private var writtenBytes = 0L

    @Synchronized
    override fun start(sampleRateHz: Int, channels: Int): Boolean {
        if (channels != 1) return false
        stop()
        return try {
            val created = trackFactory(sampleRateHz)
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                return false
            }
            if (Build.VERSION.SDK_INT >= 31) created.setStartThresholdInFrames(1)
            created.play()
            track = created
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun write(pcm: ByteArray) {
        val activeTrack = track ?: return
        // Re-attach a carried odd byte so samples stay 16-bit aligned.
        val carried = carriedByte
        val aligned: ByteArray
        if (carried != null) {
            aligned = ByteArray(pcm.size + 1)
            aligned[0] = carried
            pcm.copyInto(aligned, 1)
            carriedByte = null
        } else {
            aligned = pcm
        }
        val writable = aligned.size and 1.inv()
        if (writable < aligned.size) carriedByte = aligned[aligned.size - 1]
        if (writable == 0) return
        withContext(ioDispatcher) {
            var offset = 0
            while (offset < writable && track === activeTrack) {
                val written = activeTrack.write(aligned, offset, writable - offset)
                if (written <= 0) break
                synchronized(this@AudioTrackSpeechSink) {
                    if (track === activeTrack) writtenBytes += written
                }
                offset += written
            }
        }
    }

    override suspend fun finish() {
        val activeTrack = track ?: return
        val targetFrames = writtenBytes / 2
        try {
            withContext(ioDispatcher) {
                val remaining = (targetFrames - (activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL))
                    .coerceAtLeast(0)
                val drained = withTimeoutOrNull(remaining * 1_000 / activeTrack.sampleRate + 2_000) {
                    // Older Android cannot lower the startup threshold. Fill the
                    // buffer locally so even a short final clip starts playing.
                    if (Build.VERSION.SDK_INT < 31 && remaining > 0) {
                        val silence = ByteArray(activeTrack.bufferSizeInFrames * 2)
                        var offset = 0
                        while (track === activeTrack && offset < silence.size) {
                            val written = activeTrack.write(silence, offset, silence.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                            check(written >= 0) { "Could not start audio playback" }
                            if (written == 0) delay(20) else offset += written
                        }
                    }
                    // A successful write queues PCM; it does not mean it played.
                    while (track === activeTrack &&
                        (activeTrack.playbackHeadPosition.toLong() and 0xffff_ffffL) < targetFrames
                    ) {
                        delay(20)
                    }
                    true
                }
                check(drained == true) { "Audio playback did not drain" }
            }
        } finally {
            synchronized(this) {
                if (track === activeTrack) stop()
            }
        }
    }

    @Synchronized
    override fun stop() {
        val activeTrack = track
        track = null
        carriedByte = null
        writtenBytes = 0
        activeTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.stop() }
            it.release()
        }
    }

    private companion object {
        fun defaultTrack(sampleRateHz: Int): AudioTrack {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRateHz / 2)
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }
    }
}
