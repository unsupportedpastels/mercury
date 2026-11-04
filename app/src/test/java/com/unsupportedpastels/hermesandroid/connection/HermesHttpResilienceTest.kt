package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException

/**
 * On a bad mobile link the transport drops mid-request; before this the whole screen failed and the
 * user had to retry by hand. Retrying is only safe for reads — a replayed POST would send a chat
 * message twice — so both halves are pinned here.
 */
class HermesHttpResilienceTest {

    @Test
    fun aDroppedGetIsRetriedAndThenSucceeds() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            if (attempts == 1) throw IOException("link dropped")
            respond("""{"ok":true}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { configureHermesHttpClient() }
        try {
            val body = client.get("http://127.0.0.1:1/api/sessions").bodyAsText()
            assertEquals("""{"ok":true}""", body)
            assertEquals("a single transport drop should cost one retry, not the screen", 2, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun aDroppedPostIsNotRetried() = runTest {
        var attempts = 0
        val engine = MockEngine {
            attempts++
            throw IOException("link dropped")
        }
        val client = HttpClient(engine) { configureHermesHttpClient() }
        var failure: Throwable? = null
        try {
            client.post("http://127.0.0.1:1/api/chat") { setBody("{}") }
        } catch (error: Throwable) {
            failure = error
        } finally {
            client.close()
        }
        assertNotNull("the failure must surface, not vanish", failure)
        assertEquals("replaying a POST would send the message twice", 1, attempts)
    }
}