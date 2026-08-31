package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.hermesandroid.connection.HttpHermesConnectionClient
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.toHermesCredential
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapabilityProbeTest {
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun probeHitsVoicesRouteWithProfileAndReportsAvailability() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/audio/elevenlabs/voices", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            respond("""{"available": true, "voices": []}""", headers = jsonHeaders)
        }

        val caps = HttpHermesConnectionClient(HttpClient(engine))
            .probeVoiceCapabilities(origin, credential = "t".toHermesCredential(), profile = "work")

        assertTrue(caps.audioRoutesPresent)
        assertTrue(caps.canPickElevenLabsVoice)
    }

    @Test
    fun probeWithoutKeyKeepsAudioRoutesButHidesPicker() = runTest {
        val engine = MockEngine {
            respond("""{"available": false, "voices": []}""", headers = jsonHeaders)
        }

        val caps = HttpHermesConnectionClient(HttpClient(engine))
            .probeVoiceCapabilities(origin, credential = "t".toHermesCredential(), profile = "default")

        assertTrue(caps.audioRoutesPresent)
        assertFalse(caps.canPickElevenLabsVoice)
    }

    @Test
    fun probeOnOlderServerFailsClosed() = runTest {
        val engine = MockEngine {
            respond("Not Found", status = HttpStatusCode.NotFound)
        }

        val caps = HttpHermesConnectionClient(HttpClient(engine))
            .probeVoiceCapabilities(origin, credential = "t".toHermesCredential(), profile = "default")

        assertEquals(VoiceCapabilities.NONE, caps)
    }

    @Test
    fun voiceConfigLoadParsesServerSection() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/config", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            respond(
                """{"voice": {"submit_mode": "draft", "auto_tts": true, "stop_phrases": ["stop", "cancel"]}}""",
                headers = jsonHeaders,
            )
        }

        val config = HttpHermesConnectionClient(HttpClient(engine))
            .loadVoiceServerConfig(origin, credential = "t".toHermesCredential(), profile = "work")

        assertEquals(VoiceSubmitMode.Draft, config.submitMode)
        assertTrue(config.autoTts)
        assertEquals(listOf("stop", "cancel"), config.stopPhrases)
    }

    @Test
    fun voiceConfigLoadFailsSafeToDefaults() = runTest {
        val engine = MockEngine {
            respond("boom", status = HttpStatusCode.InternalServerError)
        }

        val config = HttpHermesConnectionClient(HttpClient(engine))
            .loadVoiceServerConfig(origin, credential = "t".toHermesCredential(), profile = "default")

        assertEquals(VoiceServerConfig.DEFAULT, config)
    }
}
