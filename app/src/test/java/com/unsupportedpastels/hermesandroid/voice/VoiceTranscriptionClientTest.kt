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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptionClientTest {
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(engine: MockEngine) =
        HttpHermesConnectionClient(HttpClient(engine) { install(HttpTimeout) })

    @Test
    fun transcribePostsDataUrlAndParsesTranscript() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/audio/transcribe", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            val body = (request.body as TextContent).text
            assertTrue(body.contains("\"data_url\""))
            assertTrue(body.contains("data:audio/mp4;base64,AAAA"))
            assertTrue(body.contains("\"mime_type\":\"audio/mp4\""))
            respond(
                """{"ok": true, "transcript": "  hello there  ", "provider": "whisper"}""",
                headers = jsonHeaders,
            )
        }

        val result = client(engine).transcribeAudio(
            origin,
            credential = "t".toHermesCredential(),
            profile = "work",
            dataUrl = "data:audio/mp4;base64,AAAA",
            mimeType = "audio/mp4",
        )

        assertEquals("hello there", result.transcript)
        assertEquals("whisper", result.provider)
        assertEquals(false, result.isEmpty)
    }

    @Test
    fun emptyTranscriptIsSilenceNotError() = runTest {
        val engine = MockEngine {
            respond("""{"ok": true, "transcript": "", "provider": "whisper"}""", headers = jsonHeaders)
        }

        val result = client(engine).transcribeAudio(
            origin,
            credential = "t".toHermesCredential(),
            profile = "default",
            dataUrl = "data:audio/mp4;base64,AAAA",
            mimeType = null,
        )

        assertTrue(result.isEmpty)
    }

    @Test
    fun httpErrorSurfacesAsConnectionException() = runTest {
        val engine = MockEngine {
            respond("nope", status = HttpStatusCode.BadRequest)
        }

        try {
            client(engine).transcribeAudio(
                origin,
                credential = "t".toHermesCredential(),
                profile = "default",
                dataUrl = "data:audio/mp4;base64,AAAA",
                mimeType = null,
            )
            throw AssertionError("Expected HermesConnectionException")
        } catch (expected: HermesConnectionException) {
            assertTrue(expected.message!!.contains("400"))
        }
    }
}
