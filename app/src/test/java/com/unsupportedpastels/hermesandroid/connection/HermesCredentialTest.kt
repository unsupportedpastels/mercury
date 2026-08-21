package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesCredentialTest {
    private val origin = ServerOrigin.parse("http://127.0.0.1:19119")

    @Test
    fun credentialsNeverRevealTheirSecretInDebugText() {
        val oauthSecret = "oauth-secret-value"
        val sessionSecret = "session-secret-value"

        val oauth = HermesCredential.NativeBearer.create(oauthSecret)
        val session = HermesCredential.LoopbackSession.create(origin, sessionSecret)

        assertFalse(oauth.toString().contains(oauthSecret))
        assertFalse(session.toString().contains(sessionSecret))
        assertEquals("HermesCredential.NativeBearer(***)", oauth.toString())
        assertEquals("HermesCredential.LoopbackSession(${origin.value}, ***)", session.toString())
    }

    @Test
    fun loopbackSessionConstructionRejectsNonLoopbackAndInvalidTokens() {
        assertTrue(
            runCatching {
                HermesCredential.LoopbackSession.create(
                    ServerOrigin.parse("https://hermes.example"),
                    "session-secret",
                )
            }.isFailure,
        )
        listOf("", "   ", "contains\ncontrol", "x".repeat(MAX_HERMES_CREDENTIAL_CHARS + 1)).forEach { token ->
            assertTrue(runCatching { HermesCredential.LoopbackSession.create(origin, token) }.isFailure)
        }
    }

    @Test
    fun optionalTokenBoundaryMapsNullAndBlankToNoCredential() {
        assertEquals(HermesCredential.None, null.toHermesCredential())
        assertEquals(HermesCredential.None, "".toHermesCredential())
        assertEquals(HermesCredential.None, "   ".toHermesCredential())
        assertTrue("opaque".toHermesCredential() is HermesCredential.NativeBearer)
    }

    @Test
    fun protectedRestRequestsApplyCredentialSpecificHeaders() = runTest {
        val observed = mutableListOf<Pair<String?, String?>>()
        val engine = MockEngine { request ->
            observed += request.headers[HttpHeaders.Authorization] to
                request.headers[HERMES_SESSION_TOKEN_HEADER]
            respond(
                """{"sessions":[],"total":0,"limit":20,"offset":0}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        client.loadSessionsPageForProfile(origin, HermesCredential.None, "default")
        client.loadSessionsPageForProfile(
            origin,
            HermesCredential.NativeBearer.create("oauth-secret"),
            "default",
        )
        client.loadSessionsPageForProfile(
            origin,
            HermesCredential.LoopbackSession.create(origin, "session-secret"),
            "default",
        )

        assertEquals(
            listOf(
                null to null,
                "Bearer oauth-secret" to null,
                null to "session-secret",
            ),
            observed,
        )
    }

    @Test
    fun loopbackSessionAuthorizesVoiceAndConfigRestEndpointsWithoutBearer() = runTest {
        val paths = mutableListOf<String>()
        val client = loopbackCredentialClient(paths) { path ->
            when (path) {
                "/api/audio/elevenlabs/voices" -> """{"available":false}"""
                "/api/config" -> """{"ok":true}"""
                else -> error("Unexpected path $path")
            }
        }
        val credential = HermesCredential.LoopbackSession.create(origin, "session-secret")

        client.probeVoiceCapabilities(origin, credential, "default")
        client.updateServerConfig(
            origin,
            credential,
            "default",
            kotlinx.serialization.json.buildJsonObject {},
        )

        assertEquals(listOf("/api/audio/elevenlabs/voices", "/api/config"), paths)
    }

    @Test
    fun loopbackSessionAuthorizesSessionMutationSearchAndProfilesWithoutBearer() = runTest {
        val paths = mutableListOf<String>()
        val client = loopbackCredentialClient(paths) { path ->
            when {
                path.startsWith("/api/sessions/stored-1") -> """{"ok":true,"title":"Renamed"}"""
                path == "/api/sessions/search" -> """{"results":[]}"""
                path == "/api/profiles" -> """{"profiles":[{"name":"default"}]}"""
                else -> error("Unexpected path $path")
            }
        }
        val credential = HermesCredential.LoopbackSession.create(origin, "session-secret")

        client.updateSession(origin, credential, DurableSessionId("stored-1"), title = "Renamed")
        client.searchSessions(origin, credential, "needle")
        client.loadProfiles(origin, credential)

        assertEquals(
            listOf("/api/sessions/stored-1", "/api/sessions/search", "/api/profiles"),
            paths,
        )
    }

    @Test
    fun loopbackSessionAuthorizesModelConfigAndCronRestEndpointsWithoutBearer() = runTest {
        val paths = mutableListOf<String>()
        val client = loopbackCredentialClient(paths) { path ->
            when (path) {
                "/api/model/options" -> """{"providers":[]}"""
                "/api/config" -> """{"agent":{"reasoning_effort":"high"}}"""
                "/api/cron/jobs/job-1/runs" -> """{"runs":[],"limit":20}"""
                else -> error("Unexpected path $path")
            }
        }
        val credential = HermesCredential.LoopbackSession.create(origin, "session-secret")

        client.loadDefaultModelOptions(origin, credential, "default")
        client.loadProfileReasoningDefault(origin, credential, "default")
        client.loadCronJobRuns(origin, credential, "default", "job-1")

        assertEquals(
            listOf("/api/model/options", "/api/config", "/api/cron/jobs/job-1/runs"),
            paths,
        )
    }

    @Test
    fun loopbackSessionAuthorizesTranscriptAndFilesRestEndpointsWithoutBearer() = runTest {
        val paths = mutableListOf<String>()
        val client = loopbackCredentialClient(paths) { path ->
            when (path) {
                "/api/sessions/stored-1/messages" -> """{"messages":[]}"""
                "/api/files" -> """{"path":"/srv","entries":[]}"""
                else -> error("Unexpected path $path")
            }
        }
        val credential = HermesCredential.LoopbackSession.create(origin, "session-secret")

        client.loadTranscript(origin, credential, DurableSessionId("stored-1"))
        client.loadHostFiles(origin, credential, "/srv")

        assertEquals(listOf("/api/sessions/stored-1/messages", "/api/files"), paths)
    }

    @Test
    fun publicStatusAndProviderDiscoveryStayUnauthenticated() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HERMES_SESSION_TOKEN_HEADER])
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    """{"version":"0.20.4","auth_required":true,"auth_flows":["native_pkce"]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/auth/providers" -> respond(
                    """{"providers":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected ${request.url}")
            }
        }

        HttpHermesConnectionClient(HttpClient(engine)).probe(origin)

        assertEquals(listOf("/api/status", "/api/auth/providers"), paths)
    }

    private fun loopbackCredentialClient(
        paths: MutableList<String>,
        responseBody: (String) -> String,
    ): HttpHermesConnectionClient = HttpHermesConnectionClient(
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                assertNull(request.headers[HttpHeaders.Authorization])
                assertEquals("session-secret", request.headers[HERMES_SESSION_TOKEN_HEADER])
                respond(
                    responseBody(path),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ),
    )
}
