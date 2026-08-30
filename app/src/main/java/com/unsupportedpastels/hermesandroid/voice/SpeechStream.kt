package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.hermesandroid.gateway.allowsCredentialRecovery
import com.unsupportedpastels.hermesandroid.gateway.classifySocketClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One inbound frame from the `/api/audio/speak-stream` socket. */
sealed interface SpeechSocketFrame {
    data class Text(val text: String) : SpeechSocketFrame

    class Binary(val bytes: ByteArray) : SpeechSocketFrame
}

/**
 * Minimal socket seam for the streaming-speech WebSocket. Mirrors the chat
 * socket seam but adds binary frames (server PCM). Implementations are
 * Ktor-backed in production and fakes in tests.
 */
interface SpeechStreamSocket {
    suspend fun sendText(text: String)

    /** Next frame, or null when the peer closed the socket. */
    suspend fun receiveFrame(): SpeechSocketFrame?

    /**
     * The application close code the peer sent, read once [receiveFrame] has
     * returned null. Null when the socket dropped without a close frame. Shares
     * the chat socket's taxonomy so both transports treat `4401` alike.
     */
    suspend fun closeCode(): Int? = null

    suspend fun close()
}

interface SpeechWebSocketFactory {
    suspend fun connect(url: String): SpeechStreamSocket
}

/** Where PCM from the speech stream goes. [AudioTrackSpeechSink] on device. */
interface PcmSpeechSink {
    /** Prepare output for the announced format. False = unplayable format. */
    fun start(sampleRateHz: Int, channels: Int): Boolean

    /** Blocking write of int16 little-endian PCM bytes. */
    suspend fun write(pcm: ByteArray)

    /** No more PCM will arrive; let buffered audio drain, then release. */
    suspend fun finish()

    /** Immediate stop and release (barge-in, session switch, lifecycle). */
    fun stop()
}

/** How a streaming speech session ended. */
enum class SpeechStreamOutcome {
    /** Server sent `end` and all PCM was played. */
    Completed,

    /** The stream dropped after audio had started — never replay via REST. */
    CompletedPartial,

    /**
     * The server declared `fallback` (or dropped/failed before any PCM), so
     * the caller may synthesize the final text once via `POST /api/audio/speak`.
     */
    Fallback,

    /**
     * The socket closed with `4401`: the credential was rejected. REST
     * synthesis is billable and would present the same refused credential, so
     * this outcome grants no fallback licence — the caller stops and lets
     * credential recovery run.
     */
    CredentialRejected,

    /** The caller stopped the stream deliberately. */
    Stopped,
}

/**
 * Verified server protocol (hermes_cli/web_server.py `speak_stream_ws`):
 * client sends `{"text": …}` / `{"done": true}` / `{"stop": true}`; server sends
 * `{"type":"start","sample_rate":N,"channels":1}`, binary int16 PCM frames,
 * then `{"type":"end"}` — or `{"type":"fallback"}` when the configured TTS
 * provider has no chunked API.
 */
object SpeechStreamProtocol {
    /** Server PCM frames are small sentence chunks; anything huge is a bug/attack. */
    const val MAX_PCM_FRAME_BYTES: Int = 1 shl 20

    const val MAX_TEXT_FRAME_BYTES: Int = 64 * 1024

    /** Sample-rate sanity bounds for `AudioTrack` creation. */
    val SAMPLE_RATE_RANGE: IntRange = 8_000..48_000

    /**
     * No start/PCM for this long *before any audio* = treat as dropped and fall
     * back. Once audio has played the socket may stay quiet for minutes (tool
     * execution between spoken sentences), so no timeout applies after that —
     * drops are detected by the socket closing.
     */
    const val PROGRESS_TIMEOUT_MILLIS: Long = 15_000L

    fun textFrame(text: String): String =
        buildJsonObject { put("text", text) }.toString()

    const val DONE_FRAME: String = """{"done":true}"""
    const val STOP_FRAME: String = """{"stop":true}"""
}

/** Parsed server control frame. */
internal sealed interface SpeechServerFrame {
    data class Start(val sampleRate: Int, val channels: Int) : SpeechServerFrame

    data object End : SpeechServerFrame

    data object Fallback : SpeechServerFrame

    data object Unknown : SpeechServerFrame
}

internal fun parseSpeechServerFrame(text: String): SpeechServerFrame {
    if (text.length > SpeechStreamProtocol.MAX_TEXT_FRAME_BYTES) return SpeechServerFrame.Unknown
    val root = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return SpeechServerFrame.Unknown
    return when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
        "start" -> SpeechServerFrame.Start(
            sampleRate = (root["sample_rate"] as? JsonPrimitive)?.intOrNull ?: 0,
            channels = (root["channels"] as? JsonPrimitive)?.intOrNull ?: 1,
        )
        "end" -> SpeechServerFrame.End
        "fallback" -> SpeechServerFrame.Fallback
        else -> SpeechServerFrame.Unknown
    }
}

/**
 * Drives one speech-stream session: pumps server frames into [sink] while the
 * caller feeds text via [feedText]/[finishText] (typically from live assistant
 * deltas) and can [stop] at any moment (barge-in). The whole-text-replay rule
 * is enforced here: a drop or failure only maps to [SpeechStreamOutcome.Fallback]
 * while no PCM has been played yet.
 */
class SpeechStreamRun(
    private val socket: SpeechStreamSocket,
    private val sink: PcmSpeechSink,
    private val progressTimeoutMillis: Long = SpeechStreamProtocol.PROGRESS_TIMEOUT_MILLIS,
    /** Bounded diagnostic sink (frame kinds/sizes only, never content). */
    private val log: (String) -> Unit = {},
) {
    @Volatile
    private var stopped = false

    @Volatile
    var audioStarted = false
        private set

    /** Feed incremental reply text. Safe to call while the pump is running. */
    suspend fun feedText(text: String) {
        if (stopped || text.isEmpty()) return
        sendIgnoringSocketTeardown(SpeechStreamProtocol.textFrame(text))
    }

    /** The reply is complete — no more text will be fed. */
    suspend fun finishText() {
        if (stopped) return
        sendIgnoringSocketTeardown(SpeechStreamProtocol.DONE_FRAME)
    }

    /**
     * A send suspended on the socket resumes with CancellationException when the
     * *socket session* is torn down (e.g. the server answered `fallback` and the
     * pump closed it) — that is the socket's cancellation, not the caller's, and
     * rethrowing it would silently kill the whole voice loop. Only propagate
     * cancellation when this coroutine itself was cancelled; every other send
     * failure is resolved by the pump's outcome.
     */
    private suspend fun sendIgnoringSocketTeardown(frame: String) {
        try {
            socket.sendText(frame)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
        } catch (_: Exception) {
        }
    }

    /** Deliberate stop (barge-in, session switch). Best-effort stop frame, then close. */
    suspend fun stop() {
        stopped = true
        sink.stop()
        try {
            socket.sendText(SpeechStreamProtocol.STOP_FRAME)
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Receive-side pump. Suspends until the session resolves and returns how it
     * ended. Runs concurrently with [feedText]/[finishText].
     */
    suspend fun pump(): SpeechStreamOutcome {
        var started = false
        log("pump:begin")
        try {
            while (true) {
                // Long.MAX_VALUE disables the watchdog entirely (no timer is
                // scheduled — virtual-time tests would otherwise fire it).
                val frame = if (audioStarted || progressTimeoutMillis == Long.MAX_VALUE) {
                    socket.receiveFrame()
                } else {
                    withTimeoutOrNull(progressTimeoutMillis) { socket.receiveFrame() }
                }
                if (stopped) return SpeechStreamOutcome.Stopped
                when (frame) {
                    is SpeechSocketFrame.Text -> log("pump:text len=${frame.text.length}")
                    is SpeechSocketFrame.Binary -> log("pump:pcm len=${frame.bytes.size}")
                    null -> log("pump:closed-or-timeout")
                }
                when (frame) {
                    null -> return closedOutcome()
                    is SpeechSocketFrame.Text -> when (val parsed = parseSpeechServerFrame(frame.text)) {
                        is SpeechServerFrame.Start -> {
                            if (started) return dropOutcome()
                            if (parsed.channels != 1 ||
                                parsed.sampleRate !in SpeechStreamProtocol.SAMPLE_RATE_RANGE
                            ) {
                                return dropOutcome()
                            }
                            if (!sink.start(parsed.sampleRate, parsed.channels)) return dropOutcome()
                            started = true
                        }
                        SpeechServerFrame.End -> {
                            if (!audioStarted) return SpeechStreamOutcome.Fallback
                            sink.finish()
                            return SpeechStreamOutcome.Completed
                        }
                        SpeechServerFrame.Fallback -> return dropOutcome()
                        SpeechServerFrame.Unknown -> Unit
                    }
                    is SpeechSocketFrame.Binary -> {
                        if (!started || frame.bytes.size > SpeechStreamProtocol.MAX_PCM_FRAME_BYTES) {
                            return dropOutcome()
                        }
                        if (frame.bytes.isNotEmpty()) {
                            audioStarted = true
                            sink.write(frame.bytes)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            sink.stop()
            throw cancelled
        } catch (_: Exception) {
            return dropOutcome()
        } finally {
            stopped = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The socket closed (or the pre-audio watchdog fired). Classify the close
     * code: only `4401` withdraws the fallback licence, because REST synthesis
     * would bill for a retry with a credential the server just refused. Every
     * other class keeps the existing one-shot fallback behaviour.
     */
    private suspend fun closedOutcome(): SpeechStreamOutcome {
        if (audioStarted) return dropOutcome()
        val code = runCatching { socket.closeCode() }.getOrNull()
        if (!classifySocketClose(code).allowsCredentialRecovery) return dropOutcome()
        sink.stop()
        return SpeechStreamOutcome.CredentialRejected
    }

    /** A drop after audio must never replay whole text; before audio it may fall back. */
    private fun dropOutcome(): SpeechStreamOutcome {
        sink.stop()
        return if (audioStarted) SpeechStreamOutcome.CompletedPartial else SpeechStreamOutcome.Fallback
    }
}
