package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import com.unsupportedpastels.hermesandroid.voice.VoiceServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Released Hermes returns `401 {"detail":"Unauthorized"}` for a missing or stale
 * dashboard session token, while many successfully authorized operations return
 * `403` through resource/policy branches (unreadable files, paths outside managed
 * roots, sensitive paths, unwritable directories, Host/Origin restrictions).
 *
 * Only `401` may therefore invalidate a credential or start a rebootstrap. These
 * table-driven cases pin that boundary for every credential-bearing REST family.
 */
class HermesRestRejectionTaxonomyTest {
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val sessionId = DurableSessionId("stored-1")
    private val credential = "opaque-access".toHermesCredential()
    private val modelOptions = ModelOptions(
        current = ModelSelection("nous", "hermes-4"),
        providers = listOf(ModelProviderOption("nous", "Nous", listOf("hermes-4"))),
        profile = "default",
    )

    private fun protectedCalls(): List<Pair<String, suspend (HermesConnectionClient) -> Unit>> = listOf(
        "sessionListing" to { client ->
            client.loadSessionsForProfile(origin, credential, "default")
        },
        "sessionPageListing" to { client ->
            client.loadSessionsPageForProfile(origin, credential, "default")
        },
        "authorizationProof" to { client -> client.authenticate(origin, credential) },
        "transcript" to { client -> client.loadTranscript(origin, credential, sessionId) },
        "sessionSearch" to { client -> client.searchSessions(origin, credential, "audit") },
        "profileListing" to { client -> client.loadProfiles(origin, credential) },
        "modelOptions" to { client -> client.loadDefaultModelOptions(origin, credential, "default") },
        // `/api/model/info` is on the released server's public allowlist, so it
        // cannot actually answer 401. The classifier is defensive only; it is
        // covered here so a future server that does protect the route inherits
        // the same taxonomy instead of a silent gap.
        "currentModelInfo" to { client -> client.loadCurrentModelInfo(origin, credential, "default") },
        "reasoningEffort" to { client ->
            client.loadProfileReasoningEffort(origin, credential, "default", "nous", "hermes-4")
        },
        "reasoningDefault" to { client ->
            client.loadProfileReasoningDefault(origin, credential, "default")
        },
        "reasoningOverrides" to { client ->
            client.loadProfileReasoningOverrides(origin, credential, "default", modelOptions)
        },
        "cronRuns" to { client -> client.loadCronJobRuns(origin, credential, "default", "job-1") },
        "hostFileListing" to { client -> client.loadHostFiles(origin, credential, "/workspace") },
        "hostDirectoryListing" to { client ->
            client.loadHostDirectories(origin, credential, "/workspace")
        },
        "hostFileRead" to { client ->
            client.readManagedFile(origin, credential, "/workspace/notes.txt")
        },
        "hostFileDownload" to { client ->
            client.downloadManagedFile(origin, credential, "/workspace/notes.txt")
        },
        "hostFileStream" to { client ->
            client.streamManagedFile(origin, credential, "/workspace/notes.txt")
        },
        "hostImageDownload" to { client ->
            client.downloadManagedImage(origin, credential, "/workspace/shot.png")
        },
        "hostDirectoryCreate" to { client ->
            client.createHostDirectory(origin, credential, "/workspace", "new")
        },
        "sessionUpdate" to { client ->
            client.updateSession(origin, credential, sessionId, title = "Renamed")
        },
        "sessionDelete" to { client -> client.deleteSession(origin, credential, sessionId) },
        "sessionBulkDelete" to { client ->
            client.bulkDeleteSessions(origin, credential, listOf(sessionId))
        },
        "configWrite" to { client ->
            client.updateServerConfig(origin, credential, "default", buildJsonObject { put("a", 1) })
        },
        "reasoningDefaultWrite" to { client ->
            client.setProfileReasoningEffort(origin, credential, "default", "high")
        },
        "reasoningOverrideWrite" to { client ->
            client.setProfileModelReasoningOverride(
                origin,
                credential,
                "default",
                ModelSelection("nous", "hermes-4"),
                "high",
            )
        },
        "defaultModelWrite" to { client ->
            client.setDefaultModel(origin, credential, "default", ModelSelection("nous", "hermes-4"))
        },
        "cronTrigger" to { client -> client.triggerCronJob(origin, credential, "default", "job-1") },
        "transcription" to { client ->
            client.transcribeAudio(origin, credential, "default", "data:audio/webm;base64,AA==", "audio/webm")
        },
        "speechSynthesis" to { client -> client.speakText(origin, credential, "default", "hello") },
    )

    private fun clientResponding(status: HttpStatusCode): HermesConnectionClient =
        HttpHermesConnectionClient(
            HttpClient(
                MockEngine {
                    respond(
                        content = """{"detail":"Unauthorized"}""",
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

    @Test
    fun everyProtectedRestFamilyClassifies401AsCredentialRejection() = runTest {
        val client = clientResponding(HttpStatusCode.Unauthorized)
        val misclassified = mutableListOf<String>()

        protectedCalls().forEach { (name, call) ->
            val failure = runCatching { call(client) }.exceptionOrNull()
            if (failure !is HermesAuthenticationRejectedException) {
                misclassified += "$name -> ${failure?.javaClass?.simpleName}"
            }
        }

        assertEquals(emptyList<String>(), misclassified)
    }

    @Test
    fun noProtectedRestFamilyTreats403AsCredentialRejection() = runTest {
        val client = clientResponding(HttpStatusCode.Forbidden)
        val misclassified = mutableListOf<String>()

        protectedCalls().forEach { (name, call) ->
            val failure = runCatching { call(client) }.exceptionOrNull()
            if (failure is HermesAuthenticationRejectedException) {
                misclassified += name
            }
        }

        assertEquals(emptyList<String>(), misclassified)
    }

    @Test
    fun elevenLabsVoiceListingPropagatesRejectionButNot403() = runTest {
        val rejected = runCatching {
            clientResponding(HttpStatusCode.Unauthorized)
                .listElevenLabsVoices(origin, credential, "default")
        }.exceptionOrNull()
        val forbidden = clientResponding(HttpStatusCode.Forbidden)
            .listElevenLabsVoices(origin, credential, "default")

        assertTrue(rejected is HermesAuthenticationRejectedException)
        assertEquals(emptyList<Any>(), forbidden)
    }

    @Test
    fun voiceCapabilityAndConfigReadsPropagateRejectionButFailClosedOn403() = runTest {
        val unauthorized = clientResponding(HttpStatusCode.Unauthorized)
        val forbidden = clientResponding(HttpStatusCode.Forbidden)

        val capabilityRejection = runCatching {
            unauthorized.probeVoiceCapabilities(origin, credential, "default")
        }.exceptionOrNull()
        val configRejection = runCatching {
            unauthorized.loadVoiceServerConfig(origin, credential, "default")
        }.exceptionOrNull()

        assertTrue(capabilityRejection is HermesAuthenticationRejectedException)
        assertTrue(configRejection is HermesAuthenticationRejectedException)
        assertEquals(VoiceCapabilities.NONE, forbidden.probeVoiceCapabilities(origin, credential, "default"))
        assertEquals(VoiceServerConfig.DEFAULT, forbidden.loadVoiceServerConfig(origin, credential, "default"))
    }

    @Test
    fun authFreeRequestsNeverClassifyRejectionOnPublicDiscovery() = runTest {
        val requested = mutableListOf<String>()
        val client = HttpHermesConnectionClient(
            HttpClient(
                MockEngine { request ->
                    requested += request.url.encodedPath
                    assertEquals(null, request.headers[HERMES_SESSION_TOKEN_HEADER])
                    assertEquals(null, request.headers[HttpHeaders.Authorization])
                    respond(
                        content = """{"detail":"Unauthorized"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

        val probeFailure = runCatching { client.probe(origin) }.exceptionOrNull()
        val tunnelFailure = runCatching { client.probeExternalTunnel(origin) }.exceptionOrNull()
        val statusFailure = runCatching { client.loadOperationalStatus(origin, "default") }.exceptionOrNull()

        assertFalse(probeFailure is HermesAuthenticationRejectedException)
        assertFalse(tunnelFailure is HermesAuthenticationRejectedException)
        assertFalse(statusFailure is HermesAuthenticationRejectedException)
        assertTrue(probeFailure is HermesConnectionException)
        assertTrue(requested.all { it == "/api/status" })
    }

    @Test
    fun authFreeDirectSessionListingUsesNoCredentialType() = runTest {
        var sawAuthorization = false
        var sawSessionHeader = false
        val client = HttpHermesConnectionClient(
            HttpClient(
                MockEngine { request ->
                    sawAuthorization = sawAuthorization || request.headers[HttpHeaders.Authorization] != null
                    sawSessionHeader =
                        sawSessionHeader || request.headers[HERMES_SESSION_TOKEN_HEADER] != null
                    respond(
                        content = when (request.url.encodedPath) {
                            "/api/status" -> """{"version":"0.20.4","auth_required":false}"""
                            else -> """{"sessions":[]}"""
                        },
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

        val info = client.probe(origin)

        assertFalse(info.authRequired)
        assertFalse(sawAuthorization)
        assertFalse(sawSessionHeader)
    }
}
