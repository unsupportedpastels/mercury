package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The server answers 200 `{"already_absent": true}` when the store it was asked about does not hold the
 * id. Treating that as "deleted" removed the row from the device only, and the next refresh brought it
 * back — the reported "delete, refresh, it is there again".
 */
class SessionDeleteIdempotencyTest {

    private fun clientReturning(body: String): HttpClient {
        val engine = MockEngine {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine)
    }

    private suspend fun deleteWith(body: String) {
        HttpHermesConnectionClient(clientReturning(body))
            .deleteSession(
                ServerOrigin.parse("127.0.0.1:45601"),
                accessToken = "token",
                durableSessionId = DurableSessionId("session-1"),
                profile = null,
            )
    }

    @Test
    fun aRealDeleteIsAccepted() = runTest {
        deleteWith("""{"ok":true,"deleted_ids":["session-1"]}""")
    }

    @Test
    fun anAlreadyAbsentAnswerIsNotADelete() = runTest {
        try {
            deleteWith("""{"ok":true,"already_absent":true}""")
            fail("an already_absent answer must not count as a delete")
        } catch (expected: HermesSessionAlreadyAbsentException) {
            assertTrue(expected.message?.contains("session-1") == true)
        }
    }
}
