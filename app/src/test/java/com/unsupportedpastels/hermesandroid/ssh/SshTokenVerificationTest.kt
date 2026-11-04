package com.unsupportedpastels.hermesandroid.ssh

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The verification that keeps an unauthenticatable origin from being published.
 *
 * A local stand-in for a spawned backend: it accepts one bearer token and 401s
 * everything else, which is what `hermes serve --isolated` does (measured: no
 * credential → 401, correct token → 200).
 */
class SshTokenVerificationTest {
    private lateinit var server: HttpServer
    private var port: Int = 0
    private val servedToken = "served-session-token"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/me") { exchange ->
            val accepted = exchange.requestHeaders.getFirst("Authorization") == "Bearer $servedToken"
            val body = (
                if (accepted) """{"user_id":"local"}""" else """{"detail":"Unauthorized"}"""
                ).toByteArray()
            exchange.sendResponseHeaders(if (accepted) 200 else 401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun theTokenTheBackendServesIsAccepted() = runTest {
        assertTrue(backendAcceptsSessionToken(port, servedToken))
    }

    @Test
    fun aTokenFromAnOlderSessionIsRejected() = runTest {
        // The exact failure this guards: the app held a token, sent it, and the backend
        // answered "Hermes session listing returned HTTP 401".
        assertFalse(backendAcceptsSessionToken(port, "token-minted-while-adoption-was-failing"))
        assertFalse(backendAcceptsSessionToken(port, ""))
    }

    @Test
    fun anUnreachablePortIsReportedAsRejectedRatherThanThrowing() = runTest {
        // A dead forward must not crash the connect flow; it is just "not verified".
        assertFalse(backendAcceptsSessionToken(port + 1, servedToken, timeoutMillis = 400))
    }
}