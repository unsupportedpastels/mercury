package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.CronJobScope

class HermesConnectionClientTest {
    @Test
    fun renamePinAndDeleteUseProfileScopedOfficialSessionContracts() = runTest {
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}?${request.url.encodedQuery}"
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            if (request.method == HttpMethod.Patch) {
                val body = (request.body as TextContent).text
                assertEquals(
                    mapOf(
                        "title" to "Renamed",
                        "pinned" to "true",
                        "profile" to "work profile",
                    ),
                    Json.parseToJsonElement(body).jsonObject
                        .filterValues { it.toString() != "null" }
                        .mapValues { it.value.jsonPrimitive.content },
                )
            }
            respond(
                content = if (request.method == HttpMethod.Patch) {
                    """{"ok":true,"title":"Renamed","pinned":true}"""
                } else {
                    """{"ok":true}"""
                },
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))
        val origin = ServerOrigin.parse("https://hermes.example")
        val id = DurableSessionId("stored/1")

        val updated = client.updateSession(
            origin,
            "opaque-access",
            id,
            profile = "work profile",
            title = "Renamed",
            pinned = true,
        )
        client.deleteSession(origin, "opaque-access", id, profile = "work profile")

        assertEquals("Renamed", updated.title)
        assertEquals(true, updated.pinned)
        assertEquals(
            listOf(
                "PATCH /api/sessions/stored%2F1?",
                "DELETE /api/sessions/stored%2F1?profile=work+profile",
            ),
            requests,
        )
    }

    @Test
    fun transcriptSearchUsesBoundedProfileScopedServerFtsContract() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/sessions/search", request.url.encodedPath)
            assertEquals("lifecycle race", request.url.parameters["q"])
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("work", request.url.parameters["profile"])
            respond(
                content = """{"results":[{"session_id":"stored-1","title":"Race audit","snippet":"matched lifecycle race excerpt","role":"assistant"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val results = HttpHermesConnectionClient(HttpClient(engine)).searchSessions(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "lifecycle race",
            profile = "work",
        )

        assertEquals("stored-1", results.single().sessionId.value)
        assertEquals("Race audit", results.single().title)
        assertEquals("matched lifecycle race excerpt", results.single().snippet)
    }

    @Test
    fun settingsLoadsProfilesAndProfileDefaultAndRequiresExplicitExpensiveConfirmation() = runTest {
        val requested = mutableListOf<String>()
        val engine = MockEngine { request ->
            requested += "${request.method.value} ${request.url.encodedPath}?${request.url.encodedQuery}"
            when (request.url.encodedPath) {
                "/api/profiles" -> respond(
                    """{"profiles":[{"name":"default"},{"name":"work"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/model/options" -> respond(
                    """{"provider":"nous","model":"current-default","providers":[{"slug":"nous","name":"Nous","authenticated":true,"models":["current-default","expensive"],"capabilities":{"current-default":{"fast":false,"reasoning":true},"expensive":{"fast":true,"reasoning":"bad"}}}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/model/set" -> respond(
                    """{"ok":false,"confirm_required":true,"confirm_message":"This model is expensive","provider":"nous","model":"expensive"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))
        val origin = ServerOrigin.parse("https://hermes.example")

        val profiles = client.loadProfiles(origin, "opaque-access")
        val options = client.loadDefaultModelOptions(origin, "opaque-access", "work")
        val result = client.setDefaultModel(
            origin,
            "opaque-access",
            "work",
            ModelSelection("nous", "expensive"),
        )

        assertEquals(listOf("default", "work"), profiles)
        assertEquals(ModelSelection("nous", "current-default"), options.current)
        assertEquals("work", options.profile)
        assertEquals(false, options.providers.single().capabilities["current-default"]?.fast)
        assertEquals(true, options.providers.single().capabilities["current-default"]?.reasoning)
        assertEquals(true, options.providers.single().capabilities["expensive"]?.fast)
        assertEquals(null, options.providers.single().capabilities["expensive"]?.reasoning)
        assertTrue(result.confirmationRequired)
        assertEquals("This model is expensive", result.confirmationMessage)
        assertEquals(
            listOf(
                "GET /api/profiles?",
                "GET /api/model/options?profile=work&explicit_only=1",
                "POST /api/model/set?profile=work",
            ),
            requested,
        )
    }

    @Test
    fun modelOptionsAcceptsBoundedCatalogLargerThanGeneralResponseLimit() = runTest {
        val models = List(5_000) { index -> "model-${index.toString().padStart(8, '0')}" }
        val response = """{"provider":"nous","model":"${models.first()}","providers":[{"slug":"nous","name":"Nous","models":[${models.joinToString(",") { "\"$it\"" }}]}]}"""
        assertTrue(response.toByteArray().size > 64 * 1024)
        val engine = MockEngine { request ->
            assertEquals("/api/model/options", request.url.encodedPath)
            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val options = HttpHermesConnectionClient(HttpClient(engine)).loadDefaultModelOptions(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "default",
        )

        assertEquals(ModelSelection("nous", models.first()), options.current)
        assertEquals(200, options.providers.single().models.size)
    }

    @Test
    fun currentModelInfoIsProfileScopedAndToleratesMalformedOptionalFields() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/model/info", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            respond(
                """{"profile":"work","provider":"nous","model":"Hermes-4-405B","effective_context_length":131072,"capabilities":{"fast":true,"reasoning":"true"},"future":{"ignored":true}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val info = HttpHermesConnectionClient(HttpClient(engine)).loadCurrentModelInfo(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
        )

        assertEquals("work", info.profile)
        assertEquals("nous", info.provider)
        assertEquals("Hermes-4-405B", info.model)
        assertEquals(131072, info.effectiveContextLength)
        assertEquals(true, info.capabilities.fast)
        assertEquals(null, info.capabilities.reasoning)
    }

    @Test
    fun profileReasoningDefaultUsesNarrowFutureChatConfigPut() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/config", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            val body = (request.body as TextContent).text
            assertEquals(
                """{"config":{"agent":{"reasoning_effort":"high"}}}""",
                body,
            )
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpHermesConnectionClient(HttpClient(engine)).setProfileReasoningEffort(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            "high",
        )
    }

    @Test
    fun perModelReasoningOverrideWritesModelKeyedByItsOwnPrefixViaDeepMergedConfigPut() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/config", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            val body = (request.body as TextContent).text
            // Keyed off the model string itself (which already carries its own
            // provider prefix). The portal slug ("nous") is NOT prepended.
            assertEquals(
                """{"config":{"agent":{"reasoning_overrides":{"anthropic/claude-opus-5":"high"}}}}""",
                body,
            )
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpHermesConnectionClient(HttpClient(engine)).setProfileModelReasoningOverride(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            ModelSelection("nous", "anthropic/claude-opus-5"),
            "high",
        )
    }

    @Test
    fun loadProfileReasoningOverridesMatchesModelPrefixedKeys() = runTest {
        val engine = MockEngine {
            respond(
                """{"agent":{"reasoning_overrides":{"anthropic/claude-opus-5":"high"}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val options = ModelOptions(
            current = null,
            providers = listOf(
                ModelProviderOption(
                    slug = "nous",
                    name = "Nous Portal",
                    models = listOf("anthropic/claude-opus-5", "anthropic/claude-sonnet-5"),
                    capabilities = emptyMap(),
                ),
            ),
            profile = "work",
        )
        val overrides = HttpHermesConnectionClient(HttpClient(engine)).loadProfileReasoningOverrides(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            options,
        )
        assertEquals(
            mapOf(ModelSelection("nous", "anthropic/claude-opus-5") to "high"),
            overrides,
        )
    }

    @Test
    fun loadProfileReasoningOverridesMatchesProviderQualifiedBareModelIds() = runTest {
        val engine = MockEngine {
            respond(
                """{"agent":{"reasoning_overrides":{"openai/shared-model":"low"}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val options = ModelOptions(
            current = null,
            providers = listOf(
                ModelProviderOption(
                    slug = "openai",
                    name = "OpenAI",
                    models = listOf("shared-model"),
                ),
            ),
            profile = "work",
        )
        val overrides = HttpHermesConnectionClient(HttpClient(engine)).loadProfileReasoningOverrides(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            options,
        )
        assertEquals(mapOf(ModelSelection("openai", "shared-model") to "low"), overrides)
    }

    @Test
    fun loadProfileReasoningDefaultIgnoresCurrentModelOverride() = runTest {
        val engine = MockEngine {
            respond(
                """{"agent":{"reasoning_effort":"medium","reasoning_overrides":{"anthropic/claude-opus-5":"high"}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val effort = HttpHermesConnectionClient(HttpClient(engine)).loadProfileReasoningDefault(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
        )
        assertEquals("medium", effort)
    }

    @Test
    fun profileReasoningEffortUsesModelOverrideBeforeGlobalDefault() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/config", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            respond(
                """{"agent":{"reasoning_effort":"medium","reasoning_overrides":{"openai/gpt-5.6-sol":"high"}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val effort = HttpHermesConnectionClient(HttpClient(engine)).loadProfileReasoningEffort(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            "openai",
            "gpt-5.6-sol",
        )

        assertEquals("high", effort)
    }

    @Test
    fun profileReasoningEffortPrefersSelectedProviderWhenModelNamesOverlap() = runTest {
        val engine = MockEngine {
            respond(
                """{"agent":{"reasoning_effort":"medium","reasoning_overrides":{"anthropic/shared-model":"low","openai/shared-model":"high","shared-model":"minimal"}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val effort = HttpHermesConnectionClient(HttpClient(engine)).loadProfileReasoningEffort(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            "openai",
            "shared-model",
        )

        assertEquals("high", effort)
    }

    @Test
    fun managedImageDownloadUsesAuthenticatedOfficialFileEndpoint() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val engine = MockEngine { request ->
            assertEquals("/api/files/download", request.url.encodedPath)
            assertEquals("/workspace/project/generated.jpg", request.url.parameters["path"])
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            respond(
                content = bytes,
                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val downloaded = client.downloadManagedImage(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "/workspace/project/generated.jpg",
        )

        assertTrue(downloaded.contentEquals(bytes))
    }

    @Test
    fun networkCancellationIsPreservedAcrossConnectionOperations() = runTest {
        fun cancellingClient() = HttpHermesConnectionClient(
            HttpClient(MockEngine { throw CancellationException("cancelled") }),
        )
        val origin = ServerOrigin.parse("https://hermes.example")

        val probeFailure = runCatching { cancellingClient().probe(origin) }.exceptionOrNull()
        val authFailure = runCatching {
            cancellingClient().authenticate(origin, "opaque-access")
        }.exceptionOrNull()
        val transcriptFailure = runCatching {
            cancellingClient().loadTranscript(
                origin,
                "opaque-access",
                DurableSessionId("durable-cancel"),
            )
        }.exceptionOrNull()

        assertTrue(probeFailure is CancellationException)
        assertTrue(authFailure is CancellationException)
        assertTrue(transcriptFailure is CancellationException)
    }

    @Test
    fun transcriptKeepsToolRowsUsingNameAndContextPreview() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/sessions/durable-1/messages", request.url.encodedPath)
            respond(
                content = """
                    {"session_id":"durable-1","messages":[
                      {"role":"user","text":"Hi"},
                      {"role":"assistant","text":"Hello"},
                      {"role":"assistant","text":"","reasoning_content":"Private chain summary"},
                      {"role":"tool","name":"terminal","context":"pwd","args":{"command":"pwd"}},
                      {"role":"tool","name":"web_search","context":"query: gpus"}
                    ],"pagination":{"limit":100,"offset":0,"returned":5}}
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val messages = client.loadTranscript(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = null,
            DurableSessionId("durable-1"),
        )

        assertEquals(5, messages.size)
        assertEquals(ChatMessageRole.User, messages[0].role)
        assertEquals(ChatMessageRole.Assistant, messages[1].role)
        assertEquals("Private chain summary", messages[2].reasoningText)
        assertEquals("", messages[2].text)
        // Server tool rows carry {role, name, context, args} with no text/content.
        assertEquals(ChatMessageRole.Tool, messages[3].role)
        assertEquals("terminal · pwd", messages[3].text)
        assertEquals("web_search · query: gpus", messages[4].text)
    }

    @Test
    fun oversizedTranscriptRetriesWithSmallerLatestPage() = runTest {
        val requestedLimits = mutableListOf<String?>()
        val engine = MockEngine { request ->
            assertEquals("/api/sessions/durable-large/messages", request.url.encodedPath)
            assertEquals("latest", request.url.parameters["order"])
            requestedLimits += request.url.parameters["limit"]
            if (request.url.parameters["limit"] == "100") {
                respond(
                    content = "x".repeat(1024 * 1024 + 1),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = """{"messages":[{"role":"assistant","content":"Recovered tail"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }

        val messages = HttpHermesConnectionClient(HttpClient(engine)).loadTranscript(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = null,
            DurableSessionId("durable-large"),
        )

        assertEquals(listOf("100", "50"), requestedLimits)
        assertEquals("Recovered tail", messages.single().text)
    }

    @Test
    fun productionHttpConfigurationRejectsRedirects() = runTest {
        var requestCount = 0
        val engine = MockEngine {
            requestCount += 1
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://other.example/api/status"),
            )
        }
        val client = HttpClient(engine) { configureHermesHttpClient() }

        val response = client.get("https://hermes.example/api/status")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(1, requestCount)
        client.close()
    }

    @Test
    fun probeRejectsStatusMissingRequiredAuthRequiredField() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"version":"0.20.0"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun probeRejectsProvidersResponseMissingEnvelope() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{"version":"0.20.0","auth_required":true}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/auth/providers" -> respond(
                    content = "{}",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun probeRejectsOversizedResponseBody() = runTest {
        val oversizedBody = """{"auth_required":false,"padding":"${"x".repeat(70_000)}"}"""
        val engine = MockEngine {
            respond(
                content = oversizedBody,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun probeLoadsDurableSessionsWhenAuthenticationIsNotRequired() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{"version":"0.20.0","auth_required":false}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[{"session_key":"stored-1","title":"First session"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val result = client.probe(ServerOrigin.parse("https://hermes.example"))

        assertEquals(listOf("/api/status", "/api/profiles/sessions"), requestedPaths)
        assertFalse(result.authRequired)
        assertEquals(listOf("First session"), result.sessions.map { it.title })
    }

    @Test
    fun sessionListingKeepsInboxMetadataFromHermesServe() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{"version":"0.20.0","auth_required":false}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{
                        "sessions":[{
                            "id":"stored-rich",
                            "title":"Inbox session",
                            "preview":"First user prompt preview",
                            "last_active":1700000000.5,
                            "message_count":42,
                            "model":"Fable 5",
                            "billing_provider":"nous",
                            "profile":"hermes-agent",
                            "cwd":"/workspaces/hermes-agent"
                        }]
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }

        val session = HttpHermesConnectionClient(HttpClient(engine))
            .probe(ServerOrigin.parse("https://hermes.example"))
            .sessions.single()

        assertEquals("First user prompt preview", session.preview)
        assertEquals(1700000000.5, session.lastActiveEpochSeconds ?: error("missing last active"), 0.0)
        assertEquals(42, session.messageCount)
        assertEquals("Fable 5", session.model)
        assertEquals("nous", session.provider)
        assertEquals("hermes-agent", session.profile)
        assertEquals("/workspaces/hermes-agent", session.workspacePath)
    }

    @Test
    fun loadSessionsForProfileArchivedOnlyUsesOfficialArchivedOnlyQuery() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/profiles/sessions", request.url.encodedPath)
            assertEquals("only", request.url.parameters["archived"])
            assertEquals("work", request.url.parameters["profile"])
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{
                    "sessions":[{
                        "id":"stored-archived",
                        "title":"Archived task",
                        "archived":true
                    }]
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val sessions = HttpHermesConnectionClient(HttpClient(engine)).loadSessionsForProfile(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            profile = "work",
            archivedOnly = true,
        )

        assertEquals(listOf("stored-archived"), sessions.map { it.id.value })
        assertTrue(sessions.single().archived)
    }

    @Test
    fun loadSessionsPageUsesOfficialLimitOffsetAndReadsPaginationMetadata() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/profiles/sessions", request.url.encodedPath)
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("20", request.url.parameters["offset"])
            assertEquals("recent", request.url.parameters["order"])
            assertEquals("exclude", request.url.parameters["archived"])
            assertEquals("default", request.url.parameters["profile"])
            respond(
                content = """{
                    "sessions":[{"id":"stored-21","title":"Session 21"}],
                    "total":21,
                    "limit":20,
                    "offset":20
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val page = HttpHermesConnectionClient(HttpClient(engine)).loadSessionsPageForProfile(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            profile = "default",
            limit = 20,
            offset = 20,
        )

        assertEquals(listOf("stored-21"), page.sessions.map { it.id.value })
        assertEquals(21, page.total)
        assertEquals(20, page.limit)
        assertEquals(20, page.offset)
    }

    @Test
    fun probeDiscoversReachableGatedServerAndNativeNousLogin() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            assertFalse(request.headers.contains(HttpHeaders.Authorization))
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{
                        "version":"0.20.0",
                        "auth_required":true,
                        "auth_flows":["native_pkce"],
                        "future_field":{"ignored":true}
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/auth/providers" -> respond(
                    content = """{
                        "providers":[{
                            "name":"nous",
                            "display_name":"Nous Research",
                            "supports_password":false,
                            "future_field":"ignored"
                        }]
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val result = client.probe(ServerOrigin.parse("https://hermes.example"))

        assertEquals(listOf("/api/status", "/api/auth/providers"), requestedPaths)
        assertEquals("0.20.0", result.version)
        assertTrue(result.authRequired)
        assertTrue(result.nativeOAuthSupported)
        assertEquals(listOf("nous"), result.providers.map { it.name })
    }

    @Test
    fun probeDoesNotPresentFailedProviderDiscoveryAsConnected() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/status" -> respond(
                    content = """{"version":"0.20.0","auth_required":true}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/auth/providers" -> respondError(HttpStatusCode.ServiceUnavailable)
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.probe(ServerOrigin.parse("https://hermes.example"))
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun authenticationLoadsIdentityAndSessionsConcurrently() = runTest {
        val identityStarted = CompletableDeferred<Unit>()
        val sessionsStarted = CompletableDeferred<Unit>()

        val authentication = async {
            authenticatedConnectionConcurrently(
                loadUser = {
                    identityStarted.complete(Unit)
                    sessionsStarted.await()
                    "user"
                },
                loadSessions = {
                    sessionsStarted.complete(Unit)
                    identityStarted.await()
                    emptyList()
                },
            )
        }
        withTimeout(5_000) {
            identityStarted.await()
            sessionsStarted.await()
        }

        assertTrue(identityStarted.isCompleted)
        assertTrue(sessionsStarted.isCompleted)
        assertEquals("user", authentication.await().userId)
    }

    @Test
    fun authenticatedConnectionVerifiesBearerAndLoadsDurableSessions() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath == "/api/profiles/sessions") {
                assertEquals("20", request.url.parameters["limit"])
                assertEquals("default", request.url.parameters["profile"])
            }
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user","provider":"nous"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{
                        "sessions":[
                            {"session_key":"stored-1","title":"First session"},
                            {"session_key":"stored-2","title":""},
                            {"session_key":"stored-1","title":"Duplicate aggregate row"}
                        ],
                        "future_field":"ignored"
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val authenticated = client.authenticate(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals(listOf("/api/auth/me", "/api/profiles/sessions"), requestedPaths)
        assertEquals("user", authenticated.userId)
        assertEquals(
            listOf(
                DurableSessionId("stored-1") to "First session",
                DurableSessionId("stored-2") to "Untitled session",
            ),
            authenticated.sessions.map { it.id to it.title },
        )
    }

    @Test
    fun authenticatedConnectionUsesRestSessionIdInsteadOfTransportSessionKey() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{
                        "sessions":[{
                            "id":"database-session-id",
                            "session_key":"transport-session-key",
                            "title":"Session"
                        }]
                    }""".trimIndent(),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val authenticated = client.authenticate(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals(
            DurableSessionId("database-session-id"),
            authenticated.sessions.single().id,
        )
    }

    @Test
    fun authenticatedConnectionReadsMultiChunkSessionResponseWithinLimit() = runTest {
        val largeTitle = "t".repeat(40_000)
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[{"session_key":"stored-1","title":"$largeTitle"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val authenticated = client.authenticate(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals(largeTitle, authenticated.sessions.single().title)
    }

    @Test
    fun authenticatedConnectionEnforcesTwentySessionLimitOnServerResponse() = runTest {
        val rows = (1..25).joinToString(",") { index ->
            "{\"session_key\":\"stored-$index\",\"title\":\"Session $index\"}"
        }
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[$rows]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val authenticated = client.authenticate(
            ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
        )

        assertEquals(20, authenticated.sessions.size)
        assertEquals("stored-20", authenticated.sessions.last().id.value)
    }

    @Test
    fun authenticatedConnectionClassifiesRejectedTokenAtIdentityEndpoint() = runTest {
        val client = HttpHermesConnectionClient(
            HttpClient(
                MockEngine {
                    respond(
                        content = """{"error":"invalid session"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
        )

        val failure = runCatching {
            client.authenticate(
                ServerOrigin.parse("https://hermes.example"),
                accessToken = "opaque-access",
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesAuthenticationRejectedException)
    }

    @Test
    fun authenticatedConnectionClassifiesRejectedTokenDuringSessionListing() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"error":"invalid session"}""",
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.authenticate(
                ServerOrigin.parse("https://hermes.example"),
                accessToken = "opaque-access",
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesAuthenticationRejectedException)
    }

    @Test
    fun authenticatedConnectionRejectsBlankUserId() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"   "}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = """{"sessions":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.authenticate(
                ServerOrigin.parse("https://hermes.example"),
                accessToken = "opaque-access",
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun authenticatedConnectionRejectsSessionsResponseMissingEnvelope() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    content = """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    content = "{}",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val failure = runCatching {
            client.authenticate(
                ServerOrigin.parse("https://hermes.example"),
                accessToken = "opaque-access",
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesConnectionException)
    }

    @Test
    fun transcriptAllowsBoundedPayloadAboveGenericResponseLimit() = runTest {
        val largeMessage = "x".repeat(70_000)
        val engine = MockEngine { request ->
            assertEquals("/api/sessions/stored-1/messages", request.url.encodedPath)
            assertEquals("default", request.url.parameters["profile"])
            assertEquals("100", request.url.parameters["limit"])
            assertEquals("latest", request.url.parameters["order"])
            respond(
                content = """{"messages":[{"role":"assistant","content":"$largeMessage"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val messages = client.loadTranscript(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            durableSessionId = DurableSessionId("stored-1"),
        )

        assertEquals(1, messages.size)
        assertEquals(largeMessage.length, messages.single().text.length)
    }

    @Test
    fun hostDirectoryListingUsesManagedFilesContract() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/files", request.url.encodedPath)
            assertEquals("/srv/projects", request.url.parameters["path"])
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            respond(
                content = """{
                    "path":"/srv/projects",
                    "parent":"/srv",
                    "root":null,
                    "locked_root":null,
                    "can_change_path":true,
                    "entries":[
                        {"name":"android","path":"/srv/projects/android","is_directory":true,"future":"ignored"},
                        {"name":"README.md","path":"/srv/projects/README.md","is_directory":false}
                    ]
                }""".trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val listing = client.loadHostDirectories(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            path = "/srv/projects",
        )

        assertEquals("/srv/projects", listing.path)
        assertEquals("/srv", listing.parentPath)
        assertTrue(listing.canChangePath)
        assertEquals(listOf("android"), listing.directories.map { it.name })
        assertEquals(listOf("/srv/projects/android"), listing.directories.map { it.path })
    }

    @Test
    fun hostDirectoryCreationUsesManagedMkdirThenReloadsCanonicalPath() = runTest {
        val requestedPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedPaths += request.url.encodedPath
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
                "/api/files/mkdir" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(
                        """{"path":"/srv/projects/New Folder"}""",
                        (request.body as TextContent).text,
                    )
                    respond(
                        content = """{
                            "ok":true,
                            "path":"/srv/projects/New Folder",
                            "entry":{"name":"New Folder","path":"/srv/projects/New Folder","is_directory":true},
                            "root":null,"locked_root":null,"can_change_path":true
                        }""".trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                "/api/files" -> {
                    assertEquals("/srv/projects/New Folder", request.url.parameters["path"])
                    respond(
                        content = """{
                            "path":"/srv/projects/New Folder",
                            "parent":"/srv/projects",
                            "entries":[],
                            "root":null,"locked_root":null,"can_change_path":true
                        }""".trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val listing = client.createHostDirectory(
            serverOrigin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            parentPath = "/srv/projects",
            name = "New Folder",
        )

        assertEquals(listOf("/api/files/mkdir", "/api/files"), requestedPaths)
        assertEquals("/srv/projects/New Folder", listing.path)
        assertEquals("/srv/projects", listing.parentPath)
    }

    @Test
    fun cronTriggerUsesBoundedAuthenticatedOfficialEndpointAndNoBody() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/cron/jobs/job%2F1/trigger", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            assertFalse(request.body is TextContent)
            respond(
                """{"id":"job/1","name":"Nightly","schedule":"0 2 * * *","enabled":true,"last_run_at":"2026-08-16T02:00:00Z"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val job = HttpHermesConnectionClient(HttpClient(engine)).triggerCronJob(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            "job/1",
        )

        assertEquals("job/1", job.jobId)
        assertEquals("2026-08-16T02:00:00Z", job.lastRunAt)
    }

    @Test
    fun cronRunsUsesBoundedProfileScopedEndpointAndPreservesReturnedOptionalFields() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/cron/jobs/job-1/runs", request.url.encodedPath)
            assertEquals("work", request.url.parameters["profile"])
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            respond(
                """{"runs":[{"id":"cron_job-1_1","title":"Run","preview":"hello","source":"cron","started_at":1700000000.0,"ended_at":1700000002.0,"message_count":4}],"limit":20}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val runs = HttpHermesConnectionClient(HttpClient(engine)).loadCronJobRuns(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
            "job-1",
            limit = 20,
        )

        assertEquals("cron_job-1_1", runs.single().id)
        assertEquals("hello", runs.single().preview)
        assertEquals(1_700_000_000.0, runs.single().startedAt)
        assertEquals(1_700_000_002.0, runs.single().endedAt)
        assertEquals(4L, runs.single().messageCount)
    }

    @Test
    fun cronRest404405And409RemainTypedWithoutExposingResponseBody() = runTest {
        val origin = ServerOrigin.parse("https://hermes.example")
        val notFound = HttpHermesConnectionClient(
            HttpClient(MockEngine {
                respond("secret response body", HttpStatusCode.NotFound)
            }),
        )
        val unsupported = runCatching {
            notFound.loadCronJobRuns(origin, "opaque-access", "default", "job-1")
        }.exceptionOrNull()
        assertTrue(unsupported is HermesCronRestUnsupportedException)
        assertTrue(unsupported?.message?.contains("secret") == false)

        val methodNotAllowed = HttpHermesConnectionClient(
            HttpClient(MockEngine {
                respond("secret response body", HttpStatusCode.MethodNotAllowed)
            }),
        )
        assertTrue(
            runCatching {
                methodNotAllowed.triggerCronJob(origin, "opaque-access", "default", "job-1")
            }.exceptionOrNull() is HermesCronRestUnsupportedException,
        )

        val claimed = HttpHermesConnectionClient(
            HttpClient(MockEngine {
                respond("private detail", HttpStatusCode.Conflict)
            }),
        )
        val claimedError = runCatching {
            claimed.triggerCronJob(origin, "opaque-access", "default", "job-1")
        }.exceptionOrNull()
        assertTrue(claimedError is HermesCronJobClaimedException)
        assertEquals("Cron job is already running or was claimed by another scheduler", claimedError?.message)
    }

    @Test
    fun bulkDeleteUsesOfficialBoundedProfileScopedContractAndParsesAuthoritativeCount() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/sessions/bulk-delete", request.url.encodedPath)
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            val body = (request.body as TextContent).text
            val jsonBody = Json.parseToJsonElement(body).jsonObject
            assertEquals(
                listOf("stored-1", "stored-2"),
                jsonBody.getValue("ids").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals("work", jsonBody.getValue("profile").jsonPrimitive.content)
            respond(
                """{"ok":true,"deleted":2}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpHermesConnectionClient(HttpClient(engine))

        val result = client.bulkDeleteSessions(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            listOf(DurableSessionId("stored-1"), DurableSessionId("stored-2")),
            profile = "work",
        )

        assertTrue(result.ok)
        assertEquals(2, result.deleted)
    }

    @Test
    fun bulkDelete404And405AreTypedCapabilityFallbacks() = runTest {
        val origin = ServerOrigin.parse("https://hermes.example")
        listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed).forEach { status ->
            val client = HttpHermesConnectionClient(
                HttpClient(MockEngine { respond("", status) }),
            )
            val failure = runCatching {
                client.bulkDeleteSessions(origin, "opaque-access", listOf(DurableSessionId("stored-1")))
            }.exceptionOrNull()
            assertTrue(failure is HermesSessionBulkDeleteUnsupportedException)
        }
    }

    @Test
    fun bulkDeleteRejectsMoreThan500IdsBeforeDispatch() = runTest {
        var dispatched = false
        val client = HttpHermesConnectionClient(
            HttpClient(MockEngine {
                dispatched = true
                respond("{}")
            }),
        )

        val failure = runCatching {
            client.bulkDeleteSessions(
                ServerOrigin.parse("https://hermes.example"),
                "opaque-access",
                (0..500).map { DurableSessionId("stored-$it") },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(dispatched)
    }

    @Test
    fun profileSessionReloadUsesExactProfileScope() = runTest {
        val requestedProfiles = mutableListOf<String?>()
        val engine = MockEngine { request ->
            requestedProfiles += request.url.parameters["profile"]
            when (request.url.encodedPath) {
                "/api/auth/me" -> respond(
                    """{"user_id":"user"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/profiles/sessions" -> respond(
                    """{"sessions":[{"session_key":"work-session","title":"Work session"}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }

        val sessions = HttpHermesConnectionClient(HttpClient(engine)).loadSessionsForProfile(
            ServerOrigin.parse("https://hermes.example"),
            "opaque-access",
            "work",
        )

        assertEquals(listOf("work"), requestedProfiles)
        assertEquals(listOf(DurableSessionId("work-session")), sessions.map { it.id })
    }

    @Test
    fun legacyClientsCanClassifyCronRestAsUnsupported() = runTest {
        val legacy = object : HermesConnectionClient {
            override suspend fun probe(serverOrigin: ServerOrigin) =
                HermesConnectionInfo(null, false, false, emptyList())
        }

        val failure = runCatching {
            legacy.loadCronJobRuns(
                ServerOrigin.parse("https://hermes.example"),
                "opaque-access",
                "default",
                "job-1",
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesCronRestLegacyUnsupportedException)
    }
}
