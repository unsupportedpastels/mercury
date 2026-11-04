package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat that cannot open reports one sentence, and that sentence is all the user has. When the cause
 * was swallowed the report read "Could not mint Hermes chat ticket" for a dead tunnel, which names the
 * stage and hides the reason — the user's own screenshot showed exactly that.
 */
class WsTicketFailureMessageTest {

    private fun clientThatFails(failure: Throwable): HttpClient {
        val engine = MockEngine { throw failure }
        return HttpClient(engine)
    }

    @Test
    fun aBrokenRequestKeepsTheCauseInTheMessage() = runTest {
        val client = KtorWsTicketClient(clientThatFails(IOException("Connection refused")), Json)

        val error = runCatching {
            client.mintTicket(ServerOrigin.parse("127.0.0.1:45601"), "token")
        }.exceptionOrNull()

        assertTrue(error is HermesChatTransportException)
        assertTrue(
            "message should name the cause, was: ${error?.message}",
            error?.message?.contains("Connection refused") == true,
        )
    }

    @Test
    fun aServerErrorStillNamesTheStage() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
        val client = KtorWsTicketClient(HttpClient(engine), Json)

        val error = runCatching {
            client.mintTicket(ServerOrigin.parse("127.0.0.1:45601"), "token")
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("HTTP 500") == true)
    }
}
