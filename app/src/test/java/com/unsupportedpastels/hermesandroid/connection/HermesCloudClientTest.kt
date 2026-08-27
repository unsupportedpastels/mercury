package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class HermesCloudClientTest {
    private val portal = ServerOrigin.parse("https://portal.nousresearch.com")

    private fun jsonEngine(
        handler: (path: String, method: HttpMethod, query: String) -> Pair<HttpStatusCode, String>,
    ) = MockEngine { request ->
        val (status, body) = handler(
            request.url.encodedPath,
            request.method,
            request.url.encodedQuery,
        )
        respond(
            content = body,
            status = status,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    @Test
    fun discoverAgentsParsesTrimmedRowsAndResolvedOrg() = runTest {
        val engine = jsonEngine { path, method, _ ->
            assertEquals("/api/agents", path)
            assertEquals(HttpMethod.Get, method)
            HttpStatusCode.OK to """
                {
                  "agents": [
                    {"id":"a1","name":"small","status":"RUNNING",
                     "dashboardUrl":"https://small-9000.agents.nousresearch.com",
                     "dashboardGatewayState":"unknown","extra":"ignored"},
                    {"id":"a2","name":"Zulu Beta","status":"RUNNING","dashboardUrl":null,
                     "dashboardGatewayState":"active"},
                    {"name":"no-id-dropped","status":"RUNNING"}
                  ],
                  "org": {"id":"org1","slug":"672c1d1f","name":"Personal","isPersonal":true,"role":"OWNER"}
                }
            """.trimIndent()
        }
        val client = HttpHermesCloudClient(HttpClient(engine))

        val result = client.discoverAgents(portal, "bearer-token")

        assertTrue(result is CloudDiscoverResult.Agents)
        val agents = (result as CloudDiscoverResult.Agents)
        assertEquals(2, agents.agents.size)
        assertEquals("a1", agents.agents[0].id)
        assertEquals("small", agents.agents[0].name)
        assertTrue(agents.agents[0].isConnectable)
        // Missing dashboardUrl → not connectable, shows provisioning.
        assertNull(agents.agents[1].dashboardUrl)
        assertFalse(agents.agents[1].isConnectable)
        assertEquals("org1", agents.org?.id)
        assertEquals("672c1d1f", agents.org?.slug)
        assertTrue(agents.org?.isPersonal == true)
    }

    @Test
    fun discoverAgentsToleratesWrongTypedOptionalFields() = runTest {
        val engine = jsonEngine { _, _, _ ->
            // A preview-era schema mismatch: wrong-typed "name" (object) and
            // "dashboardGatewayState" (array). This must NOT crash discovery or
            // lose the sibling valid row. A row with a valid id survives with
            // wrong-typed optionals falling back to their defaults.
            HttpStatusCode.OK to """
                {"agents":[
                  {"id":"bad","name":{},"status":"RUNNING",
                   "dashboardUrl":"https://bad.agents.nousresearch.com","dashboardGatewayState":[]},
                  {"id":"good","name":"good","status":"RUNNING",
                   "dashboardUrl":"https://good.agents.nousresearch.com","dashboardGatewayState":"active"}
                ]}
            """.trimIndent()
        }
        val client = HttpHermesCloudClient(HttpClient(engine))

        val result = client.discoverAgents(portal, "bearer-token")

        assertTrue(result is CloudDiscoverResult.Agents)
        val agents = (result as CloudDiscoverResult.Agents).agents
        // Both rows present; the wrong-typed fields fall back (name → id,
        // dashboardGatewayState → "unknown") instead of failing the discovery.
        assertEquals(listOf("bad", "good"), agents.map { it.id })
        val bad = agents.first { it.id == "bad" }
        assertEquals("bad", bad.name)
        assertEquals("unknown", bad.dashboardGatewayState)
    }

    @Test
    fun discoverAgentsScopesToOrgQueryWhenProvided() = runTest {
        var seenQuery = ""
        val engine = jsonEngine { _, _, query ->
            seenQuery = query
            HttpStatusCode.OK to """{"agents":[]}"""
        }
        val client = HttpHermesCloudClient(HttpClient(engine))

        client.discoverAgents(portal, "bearer-token", org = "672c1d1f")

        assertEquals("org=672c1d1f", seenQuery)
    }

    @Test
    fun discoverAgents409ReturnsOrgSelection() = runTest {
        val engine = jsonEngine { _, _, _ ->
            HttpStatusCode.Conflict to """
                {"error":"org_selection_required","orgs":[
                  {"id":"o1","slug":"acme","name":"Acme","isPersonal":false,"role":"MEMBER"},
                  {"id":"o2","slug":null,"name":"Personal","isPersonal":true,"role":"OWNER"}
                ]}
            """.trimIndent()
        }
        val client = HttpHermesCloudClient(HttpClient(engine))

        val result = client.discoverAgents(portal, "bearer-token")

        assertTrue(result is CloudDiscoverResult.NeedsOrgSelection)
        val orgs = (result as CloudDiscoverResult.NeedsOrgSelection).orgs
        assertEquals(listOf("o1", "o2"), orgs.map { it.id })
        assertNull(orgs[1].slug)
    }

    @Test
    fun discoverAgents401SurfacesSignInRequired() = runTest {
        val engine = jsonEngine { _, _, _ ->
            HttpStatusCode.Unauthorized to """{"error":"unauthorized"}"""
        }
        val client = HttpHermesCloudClient(HttpClient(engine))

        try {
            client.discoverAgents(portal, "stale-token")
            fail("expected sign-in-required")
        } catch (expected: HermesCloudSignInRequiredException) {
            // expected
        }
    }

    @Test
    fun requestDeviceCodeParsesVerificationDetails() = runTest {
        val engine = jsonEngine { path, method, _ ->
            assertEquals("/api/oauth/device/code", path)
            assertEquals(HttpMethod.Post, method)
            HttpStatusCode.OK to """
                {"device_code":"dc_secret","user_code":"TF96-5E58",
                 "verification_uri":"https://portal.nousresearch.com/manage-subscription",
                 "verification_uri_complete":"https://portal.nousresearch.com/manage-subscription?user_code=TF96-5E58",
                 "expires_in":600,"interval":5}
            """.trimIndent()
        }
        val client = HttpHermesCloudClient(HttpClient(engine))

        val code = client.requestDeviceCode(portal)

        assertEquals("dc_secret", code.deviceCode)
        assertEquals("TF96-5E58", code.userCode)
        assertEquals(600L, code.expiresInSeconds)
        assertEquals(5L, code.intervalSeconds)
        assertTrue(code.verificationUriComplete.contains("user_code=TF96-5E58"))
    }

    @Test
    fun awaitDeviceTokenPollsPastPendingThenReturnsToken() = runTest {
        var call = 0
        val engine = jsonEngine { path, _, _ ->
            assertEquals("/api/oauth/token", path)
            call += 1
            if (call == 1) {
                HttpStatusCode.BadRequest to """{"error":"authorization_pending"}"""
            } else {
                HttpStatusCode.OK to """
                    {"access_token":"at","refresh_token":"rt","token_type":"Bearer",
                     "scope":"inference:invoke","expires_in":3600}
                """.trimIndent()
            }
        }
        var now = 1000L
        val client = HttpHermesCloudClient(
            client = HttpClient(engine),
            delayMillis = { /* no real sleep */ },
            nowSeconds = { now },
        )
        val device = PortalDeviceCode(
            deviceCode = "dc",
            userCode = "u",
            verificationUri = "https://portal.nousresearch.com",
            verificationUriComplete = "https://portal.nousresearch.com",
            expiresInSeconds = 600L,
            intervalSeconds = 1L,
        )

        val token = client.awaitDeviceToken(portal, device)

        assertEquals(2, call)
        assertEquals("at", token.accessToken)
        assertEquals("rt", token.refreshToken)
        assertEquals(now + 3600L, token.expiresAt)
    }

    @Test
    fun refreshTokenSendsRefreshHeaderAndCarriesRotatedToken() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/oauth/token", request.url.encodedPath)
            assertEquals("old-rt", request.headers["x-nous-refresh-token"])
            respond(
                content = """{"access_token":"new-at","refresh_token":"new-rt","expires_in":3600}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesCloudClient(HttpClient(engine), nowSeconds = { 0L })

        val token = client.refreshToken(portal, "old-rt")

        assertEquals("new-at", token.accessToken)
        assertEquals("new-rt", token.refreshToken)
    }

    @Test
    fun refreshTokenReuseIsTerminalSignIn() = runTest {
        val engine = jsonEngine { _, _, _ ->
            HttpStatusCode.BadRequest to """{"error":"refresh_token_reused"}"""
        }
        val client = HttpHermesCloudClient(HttpClient(engine))

        try {
            client.refreshToken(portal, "old-rt")
            fail("expected sign-in-required")
        } catch (expected: HermesCloudSignInRequiredException) {
            // expected
        }
    }

    @Test
    fun discoveryRetriesTransientDnsFailureThenSucceeds() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls += 1
            if (calls == 1) {
                // CIO surfaces a bare UnresolvedAddressException on the first
                // request after backgrounding; the client must retry, not abort.
                throw java.nio.channels.UnresolvedAddressException()
            }
            respond(
                content = """{"agents":[{"id":"a1","name":"small","status":"RUNNING",
                    "dashboardUrl":"https://small-9000.agents.nousresearch.com",
                    "dashboardGatewayState":"unknown"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesCloudClient(HttpClient(engine), delayMillis = {})

        val result = client.discoverAgents(portal, "bearer-token")

        assertEquals(2, calls)
        assertTrue(result is CloudDiscoverResult.Agents)
        assertEquals("a1", (result as CloudDiscoverResult.Agents).agents.single().id)
    }

    @Test
    fun awaitDeviceTokenKeepsPollingThroughBackgroundNetworkDrop() = runTest {
        // The app is backgrounded while the user approves in the browser, so the
        // token poll's first several attempts hit a torn-down network. Those must
        // not abort the sign-in; polling continues until approval lands.
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            when {
                calls <= 4 -> throw java.nio.channels.UnresolvedAddressException()
                calls == 5 -> respond(
                    content = """{"error":"authorization_pending"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        var now = 0L
        val client = HttpHermesCloudClient(
            client = HttpClient(engine),
            delayMillis = { now += 1 }, // advance clock a little each wait
            nowSeconds = { now },
        )
        val device = PortalDeviceCode(
            deviceCode = "dc",
            userCode = "u",
            verificationUri = "https://portal.nousresearch.com",
            verificationUriComplete = "https://portal.nousresearch.com",
            expiresInSeconds = 600L,
            intervalSeconds = 1L,
        )

        val token = client.awaitDeviceToken(portal, device)

        assertEquals("at", token.accessToken)
        assertTrue(calls >= 6)
    }

    @Test
    fun awaitDeviceTokenKeepsPollingThroughClosedResponseStream() = runTest {
        // CIO can establish the request while the app is backgrounded, then
        // lose the response stream as Android freezes/unfreezes the process.
        // Both observed shapes are retryable for RFC 8628 device polling.
        var calls = 0
        val engine = MockEngine { _ ->
            calls += 1
            when (calls) {
                1 -> throw EOFException("response ended early")
                2 -> throw IOException("connection closed")
                3 -> respond(
                    content = """{"error":"authorization_pending"}""",
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        var now = 0L
        val client = HttpHermesCloudClient(
            client = HttpClient(engine),
            delayMillis = { now += 1 },
            nowSeconds = { now },
        )
        val device = PortalDeviceCode(
            deviceCode = "dc",
            userCode = "u",
            verificationUri = "https://portal.nousresearch.com",
            verificationUriComplete = "https://portal.nousresearch.com",
            expiresInSeconds = 600L,
            intervalSeconds = 1L,
        )

        val token = client.awaitDeviceToken(portal, device)

        assertEquals("at", token.accessToken)
        assertEquals(4, calls)
    }
}
