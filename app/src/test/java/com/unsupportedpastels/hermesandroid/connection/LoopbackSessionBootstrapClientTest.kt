package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackSessionBootstrapClientTest {
    private val origin = ServerOrigin.parse("http://127.0.0.1:19119")

    @Test
    fun bootstrapFetchesCanonicalRootWithoutAuthorizationAndBypassesCaches() = runTest {
        val engine = MockEngine { request ->
            assertEquals(origin.value + "/", request.url.toString())
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HERMES_SESSION_TOKEN_HEADER])
            assertTrue(request.headers[HttpHeaders.CacheControl].orEmpty().contains("no-cache"))
            assertEquals("no-cache", request.headers[HttpHeaders.Pragma])
            respond(
                """<html><head><script>window.__HERMES_SESSION_TOKEN__="served-token";window.__HERMES_DASHBOARD_EMBEDDED_CHAT__=true;</script></head></html>""",
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
            )
        }

        val result = HttpLoopbackSessionBootstrapClient(
            HttpClient(engine) { configureHermesHttpClient() },
        ).bootstrap(origin)

        assertTrue(result is LoopbackSessionBootstrapResult.Success)
        val credential = (result as LoopbackSessionBootstrapResult.Success).credential
        assertEquals(origin, credential.origin)
        assertFalse(credential.toString().contains("served-token"))
    }

    @Test
    fun bootstrapDecodesTheExactJsonStringAssignment() = runTest {
        val result = bootstrapWith(
            """<script>before();window.__HERMES_SESSION_TOKEN__="served\\token\"quoted";after();</script>""",
        )

        assertTrue(result is LoopbackSessionBootstrapResult.Success)
        assertFalse(result.toString().contains("served"))
    }

    @Test
    fun bootstrapRejectsMissingDuplicateEmptyAndMalformedAssignments() = runTest {
        val cases = listOf(
            "<html></html>" to LoopbackSessionBootstrapFailure.TokenAbsent,
            """<script>window.__HERMES_SESSION_TOKEN__="one";window.__HERMES_SESSION_TOKEN__="two";</script>""" to
                LoopbackSessionBootstrapFailure.TokenMalformed,
            """<script>window.__HERMES_SESSION_TOKEN__="";</script>""" to
                LoopbackSessionBootstrapFailure.TokenMalformed,
            """<script>window.__HERMES_SESSION_TOKEN__={bad};</script>""" to
                LoopbackSessionBootstrapFailure.TokenMalformed,
            """<script>window.__HERMES_SESSION_TOKEN__ = "spaced";</script>""" to
                LoopbackSessionBootstrapFailure.TokenAbsent,
        )

        cases.forEach { (html, expected) ->
            val result = bootstrapWith(html)
            assertEquals(expected, (result as LoopbackSessionBootstrapResult.Failure).reason)
            assertFalse(result.toString().contains(html))
        }
    }

    @Test
    fun bootstrapRejectsOversizedShellWithoutReturningItsBody() = runTest {
        val marker = "window.__HERMES_SESSION_TOKEN__=\"secret-in-oversized-body\";"
        val result = bootstrapWith(marker + "x".repeat(MAX_LOOPBACK_BOOTSTRAP_BODY_BYTES))

        assertEquals(
            LoopbackSessionBootstrapFailure.BodyTooLarge,
            (result as LoopbackSessionBootstrapResult.Failure).reason,
        )
        assertFalse(result.toString().contains("secret-in-oversized-body"))
    }

    @Test
    fun bootstrapRejectsRedirectBeforeDispatchingToTargetWithDefaultClient() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            respond(
                content = "redirect body must not escape",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://attacker.example/"),
            )
        }

        val result = HttpLoopbackSessionBootstrapClient(HttpClient(engine)).bootstrap(origin)

        assertEquals(
            LoopbackSessionBootstrapFailure.RedirectRejected,
            (result as LoopbackSessionBootstrapResult.Failure).reason,
        )
        assertEquals(1, requests)
        assertFalse(result.toString().contains("attacker"))
        assertFalse(result.toString().contains("redirect body"))
    }

    @Test
    fun bootstrapRejectsNonLoopbackOriginsBeforeNetworking() = runTest {
        var requested = false
        val client = HttpLoopbackSessionBootstrapClient(HttpClient(MockEngine {
            requested = true
            error("must not request")
        }))

        val result = client.bootstrap(ServerOrigin.parse("https://hermes.example"))

        assertEquals(
            LoopbackSessionBootstrapFailure.NonLoopbackOrigin,
            (result as LoopbackSessionBootstrapResult.Failure).reason,
        )
        assertFalse(requested)
    }

    private suspend fun bootstrapWith(html: String): LoopbackSessionBootstrapResult {
        val engine = MockEngine {
            respond(html, headers = headersOf(HttpHeaders.ContentType, "text/html"))
        }
        return HttpLoopbackSessionBootstrapClient(HttpClient(engine)).bootstrap(origin)
    }
}
