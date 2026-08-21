package com.unsupportedpastels.hermesandroid.voice

import com.unsupportedpastels.hermesandroid.connection.HermesConnectionException
import com.unsupportedpastels.hermesandroid.connection.HttpHermesConnectionClient
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.toHermesCredential
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSynthesisTest {
    @Test
    fun decodeRoundTripsEncode() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val url = encodeAudioDataUrl(bytes, "audio/mpeg")
        val decoded = decodeAudioDataUrl(url)!!
        assertArrayEquals(bytes, decoded.bytes)
        assertEquals("audio/mpeg", decoded.mimeType)
    }

    @Test
    fun decodeRejectsNonDataAndNonBase64Urls() {
        assertNull(decodeAudioDataUrl("https://example.com/a.mp3"))
        assertNull(decodeAudioDataUrl("data:audio/mpeg,notbase64"))
        assertNull(decodeAudioDataUrl("data:audio/mpeg;base64,%%%invalid%%%"))
    }

    @Test
    fun decodeDefaultsMimeWhenHeaderBlank() {
        val decoded = decodeAudioDataUrl("data:;base64,AAAA")!!
        assertEquals("audio/mpeg", decoded.mimeType)
    }

    @Test
    fun speakPostsTextAndDecodesAudio() = runTest {
        val payload = encodeAudioDataUrl(byteArrayOf(9, 8, 7), "audio/wav")
        val engine = MockEngine { request ->
            assertEquals("/api/audio/speak", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            assertTrue((request.body as TextContent).text.contains("\"text\""))
            respond(
                """{"ok": true, "data_url": "$payload", "mime_type": "audio/wav", "provider": "eleven"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val audio = HttpHermesConnectionClient(HttpClient(engine) { install(HttpTimeout) })
            .speakText(
                ServerOrigin.parse("https://hermes.example"),
                credential = "t".toHermesCredential(),
                profile = "work",
                text = "Hello there",
            )

        assertArrayEquals(byteArrayOf(9, 8, 7), audio.bytes)
        assertEquals("audio/wav", audio.mimeType)
    }

    @Test
    fun speakSurfacesHttpErrors() = runTest {
        val engine = MockEngine { respond("no", status = HttpStatusCode.ServiceUnavailable) }
        try {
            HttpHermesConnectionClient(HttpClient(engine) { install(HttpTimeout) })
                .speakText(
                    ServerOrigin.parse("https://hermes.example"),
                    credential = "t".toHermesCredential(),
                    profile = "default",
                    text = "hi",
                )
            throw AssertionError("Expected HermesConnectionException")
        } catch (expected: HermesConnectionException) {
            assertTrue(expected.message!!.contains("503"))
        }
    }
}
