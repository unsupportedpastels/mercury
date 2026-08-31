package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesChatGatewayTest {
    @Test
    fun blockingPromptResponsesUseAuditedMethodAndValueKeys() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            socket.offer("""{"jsonrpc":"2.0","id":$id,"result":{"status":"ok"}}""")
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        connection.respondToBlockingPrompt(UnsupportedBlockingKind.Secret, "secret-1", "opaque-value")
        connection.respondToBlockingPrompt(UnsupportedBlockingKind.Sudo, "sudo-1", "opaque-password")
        connection.respondToBlockingPrompt(UnsupportedBlockingKind.TerminalRead, "terminal-1", "")

        val requests = socket.sentFrames.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("secret.respond", "sudo.respond", "terminal.read.respond"), requests.map {
            it["method"]!!.jsonPrimitive.content
        })
        assertEquals("secret-1", requests[0]["params"]!!.jsonObject["request_id"]!!.jsonPrimitive.content)
        assertEquals("opaque-value", requests[0]["params"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("opaque-password", requests[1]["params"]!!.jsonObject["password"]!!.jsonPrimitive.content)
        assertEquals("", requests[2]["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        connection.close()
    }

    @Test
    fun appliesCanonicalSessionScopedReasoningEffort() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            socket.offer(
                """{"jsonrpc":"2.0","id":$id,"result":{"key":"reasoning","value":"medium","scope":"session"}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        connection.setReasoning(RuntimeSessionId("runtime-1"), "medium")

        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals("config.set", request["method"]!!.jsonPrimitive.content)
        val params = request["params"]!!.jsonObject
        assertEquals("runtime-1", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("reasoning", params["key"]!!.jsonPrimitive.content)
        assertEquals("medium", params["value"]!!.jsonPrimitive.content)
        connection.close()
    }

    @Test
    fun appliesCanonicalSessionScopedFastMode() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            socket.offer(
                """{"jsonrpc":"2.0","id":$id,"result":{"key":"fast","value":"fast","scope":"session"}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        connection.setFast(RuntimeSessionId("runtime-1"), fast = true)

        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals("config.set", request["method"]!!.jsonPrimitive.content)
        val params = request["params"]!!.jsonObject
        assertEquals("runtime-1", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("fast", params["key"]!!.jsonPrimitive.content)
        assertEquals("fast", params["value"]!!.jsonPrimitive.content)
        connection.close()
    }

    @Test
    fun loadsConfiguredModelOptionsAndAppliesCanonicalSessionScopedSelection() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            when (request["method"]!!.jsonPrimitive.content) {
                "model.options" -> socket.offer(
                    """{"jsonrpc":"2.0","id":$id,"result":{"provider":"nous","model":"Hermes-4-405B","providers":[{"slug":"nous","name":"Nous Research","is_current":true,"authenticated":true,"models":["Hermes-4-405B","gpt-5.6-luna"],"capabilities":{"Hermes-4-405B":{"fast":true,"reasoning":false},"gpt-5.6-luna":{"fast":"true","reasoning":true}}},{"slug":"openrouter","name":"OpenRouter","authenticated":false,"models":["anthropic/claude-sonnet-4.6"]}]}}""",
                )
                "config.set" -> socket.offer(
                    """{"jsonrpc":"2.0","id":$id,"result":{"key":"model","value":"gpt-5.6-luna","scope":"session","deferred":false,"confirm_required":false}}""",
                )
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val options = connection.loadModelOptions(RuntimeSessionId("runtime-1"))
        val result = connection.setModel(
            runtimeSessionId = RuntimeSessionId("runtime-1"),
            provider = "nous",
            model = "gpt-5.6-luna",
        )

        assertEquals(ModelSelection("nous", "Hermes-4-405B"), options.current)
        assertEquals(listOf("Hermes-4-405B", "gpt-5.6-luna"), options.providers.single().models)
        assertEquals(true, options.providers.single().capabilities["Hermes-4-405B"]?.fast)
        assertEquals(false, options.providers.single().capabilities["Hermes-4-405B"]?.reasoning)
        assertEquals(null, options.providers.single().capabilities["gpt-5.6-luna"]?.fast)
        assertEquals(true, options.providers.single().capabilities["gpt-5.6-luna"]?.reasoning)
        assertTrue(result.accepted)
        assertFalse(result.deferred)
        assertFalse(result.confirmationRequired)
        assertEquals(2, socket.sentFrames.size)
        val optionsParams = Json.parseToJsonElement(socket.sentFrames[0]).jsonObject["params"]!!.jsonObject
        assertEquals("runtime-1", optionsParams["session_id"]!!.jsonPrimitive.content)
        assertTrue(optionsParams["explicit_only"]!!.jsonPrimitive.boolean)
        assertFalse(optionsParams["include_unconfigured"]!!.jsonPrimitive.boolean)
        val setParams = Json.parseToJsonElement(socket.sentFrames[1]).jsonObject["params"]!!.jsonObject
        assertEquals("runtime-1", setParams["session_id"]!!.jsonPrimitive.content)
        assertEquals("model", setParams["key"]!!.jsonPrimitive.content)
        assertEquals("gpt-5.6-luna --provider nous --session", setParams["value"]!!.jsonPrimitive.content)
        assertFalse(setParams["confirm_expensive_model"]!!.jsonPrimitive.boolean)
        connection.close()
    }

    @Test
    fun modelOptionsExcludeUnauthenticatedProvidersAndSwitchReportsConfirmationWithoutAcceptance() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            when (request["method"]!!.jsonPrimitive.content) {
                "model.options" -> socket.offer(
                    """{"jsonrpc":"2.0","id":$id,"result":{"provider":"nous","model":"current","providers":[{"slug":"nous","name":"Nous","authenticated":true,"models":["current","expensive"]},{"slug":"other","name":"Other","authenticated":false,"models":["unusable"]}]}}""",
                )
                "config.set" -> socket.offer(
                    """{"jsonrpc":"2.0","id":$id,"result":{"key":"model","value":"expensive","scope":"session","confirm_required":true,"confirm_message":"This model is expensive"}}""",
                )
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val options = connection.loadModelOptions(RuntimeSessionId("runtime-1"))
        val result = connection.setModel(RuntimeSessionId("runtime-1"), "nous", "expensive")

        assertEquals(listOf("nous"), options.providers.map(ModelProviderOption::slug))
        assertFalse(result.accepted)
        assertTrue(result.confirmationRequired)
        assertEquals("This model is expensive", result.confirmationMessage)
        connection.close()
    }

    @Test
    fun connectsWithTicketAndResumesUsingSeparateIdentities() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        val socketFactory = RecordingSocketFactory(socket)
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "session.resume") {
                socket.offer(
                    """
                    {"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{
                      "session_id":"runtime-1","session_key":"durable-1","resumed":true,
                      "messages":[{"role":"assistant","text":"hello","future":true}],"running":true,
                      "info":{"model":"gpt-5.6-sol","provider":"openai-codex","reasoning_effort":"medium"},
                      "inflight":{"user":"prompt","assistant":"partial","streaming":true},
                      "future_field":{"ignored":true}
                    }}
                    """.trimIndent(),
                )
            }
        }

        val gateway = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )
        val connection = gateway.connect()
        val resumed = connection.resume(
            durableSessionId = com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
            profile = "default",
        )

        assertEquals(1, ticketClient.calls)
        assertEquals(
            listOf("wss://hermes.example/api/ws?ticket=ticket-1"),
            socketFactory.urls,
        )
        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals("2.0", request["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("session.resume", request["method"]!!.jsonPrimitive.content)
        val params = request["params"]!!.jsonObject
        assertEquals("durable-1", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("default", params["profile"]!!.jsonPrimitive.content)
        assertFalse(params["close_on_disconnect"]!!.jsonPrimitive.boolean)
        assertEquals("runtime-1", resumed.runtimeSessionId.value)
        assertEquals("durable-1", resumed.durableSessionId?.value)
        assertTrue(resumed.resumed)
        assertTrue(resumed.running)
        assertEquals("hello", resumed.messages.single()["text"]!!.jsonPrimitive.content)
        assertEquals("gpt-5.6-sol", resumed.model)
        assertEquals("openai-codex", resumed.provider)
        assertEquals("medium", resumed.reasoningEffort)
        assertEquals("prompt", resumed.inflight?.user)
        assertEquals("partial", resumed.inflight?.assistant)
        assertTrue(resumed.inflight?.streaming == true)

        connection.close()
    }

    @Test
    fun connectsToHttpOriginUsingUnencryptedWebSocketTransport() = runTest {
        val socket = ScriptedSocket()
        val socketFactory = RecordingSocketFactory(socket)
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("http://10.0.1.2"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient(
                ticket = "ticket-1",
                expectedOrigin = ServerOrigin.parse("http://10.0.1.2"),
            ),
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        ).connect()

        assertEquals(
            listOf("ws://10.0.1.2/api/ws?ticket=ticket-1"),
            socketFactory.urls,
        )
        connection.close()
    }

    @Test
    fun createsSessionSendsValidCwdAndPreservesRuntimeAndCanonicalStoredAlias() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("session.create", request["method"]!!.jsonPrimitive.content)
            socket.offer(
                """
                {"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{
                  "session_id":"runtime-created","stored_session_id":"stored-canonical",
                  "future_field":{"ignored":true}
                }}
                """.trimIndent(),
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val created = connection.createSession(
            durableSessionId = DurableSessionId("draft-local"),
            profile = "default",
            workspacePath = "/workspace/project",
        )

        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        val params = request["params"]!!.jsonObject
        assertEquals("default", params["profile"]!!.jsonPrimitive.content)
        assertFalse(params["close_on_disconnect"]!!.jsonPrimitive.boolean)
        assertEquals("/workspace/project", params["cwd"]!!.jsonPrimitive.content)
        assertFalse(socket.sentFrames.single().contains("draft-local"))
        assertEquals("runtime-created", created.runtimeSessionId.value)
        assertEquals("stored-canonical", created.durableSessionId?.value)
        assertFalse(created.resumed)
        assertTrue(created.messages.isEmpty())

        connection.close()
    }

    @Test
    fun createsSessionOmitsInvalidOrMissingCwdWhileKeepingRequiredParams() = runTest {
        val invalidWorkspacePaths = listOf<String?>(
            null,
            "",
            "   ",
            "relative/project",
            "/workspace\u0000project",
            "/".repeat(ProjectSummary.MAX_PATH_LENGTH + 1),
        )
        val socket = ScriptedSocket()
        val sentParams = mutableListOf<kotlinx.serialization.json.JsonObject>()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("session.create", request["method"]!!.jsonPrimitive.content)
            sentParams += request["params"]!!.jsonObject
            val index = sentParams.lastIndex
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"session_id":"runtime-$index","stored_session_id":"stored-$index","unknown":true}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        invalidWorkspacePaths.forEachIndexed { index, workspacePath ->
            val created = connection.createSession(
                durableSessionId = DurableSessionId("draft-$index"),
                profile = "default",
                workspacePath = workspacePath,
            )
            assertEquals("runtime-$index", created.runtimeSessionId.value)
            assertEquals("stored-$index", created.durableSessionId?.value)
        }

        assertEquals(invalidWorkspacePaths.size, sentParams.size)
        sentParams.forEach { params ->
            assertEquals("default", params["profile"]!!.jsonPrimitive.content)
            assertFalse(params["close_on_disconnect"]!!.jsonPrimitive.boolean)
            assertFalse(params.containsKey("cwd"))
            assertFalse(params.containsKey("session_id"))
        }
        assertTrue(
            socket.sentFrames.none { frame ->
                invalidWorkspacePaths.filterNotNull().filter(String::isNotEmpty).any(frame::contains)
            },
        )

        connection.close()
    }

    @Test
    fun requestsSlashCompletionAndParsesItems() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "complete.slash") {
                socket.offer(
                    """
                    {"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{
                      "items":[
                        {"text":"goal","display":"/goal","meta":"Set a standing goal"},
                        {"text":"help"},
                        {"text":""},
                        {"future":"row"}
                      ],
                      "replace_from":1,
                      "unknown_field":true
                    }}
                    """.trimIndent(),
                )
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val result = connection.completeSlash("/go")

        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals("complete.slash", request["method"]!!.jsonPrimitive.content)
        assertEquals("/go", request["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1, result.replaceFrom)
        assertEquals(2, result.items.size)
        assertEquals("goal", result.items[0].text)
        assertEquals("/goal", result.items[0].display)
        assertEquals("Set a standing goal", result.items[0].meta)
        assertEquals("help", result.items[1].text)
        assertEquals("/help", result.items[1].display)

        connection.close()
    }

    @Test
    fun reportsJsonRpcErrorsWithoutParsingNullResult() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":null,"error":{"code":-32602,"message":"details omitted"}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.resume(
                com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesChatProtocolException)
        assertEquals("Hermes RPC request failed (-32602)", failure?.message)
        connection.close()
    }

    @Test
    fun submitsPromptAndPreservesEventsThatRaceThePromptAck() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                val id = request["id"]!!.jsonPrimitive.content
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"unknown.future","session_id":"runtime-1","payload":"ignored"}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"runtime-1","payload":{"text":"draft"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":"hel","future":1}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"runtime-1","payload":{"text":"hello","status":"error","error":"terminal failure"}}}""",
                )
                socket.offer("""{"jsonrpc":"2.0","method":"event","params":{"type":"error","session_id":"runtime-1","payload":{"message":"temporary failure"}}}""")
                socket.offer("""{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming","future":true}}""")
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = ticketClient,
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val ack = connection.submitPrompt(RuntimeSessionId("runtime-1"), "hello")
        val events = connection.events.take(4).toList()

        assertEquals("streaming", ack.status)
        assertEquals(
            listOf(
                HermesChatEvent.MessageStart(RuntimeSessionId("runtime-1"), "draft"),
                HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-1"), "hel"),
                HermesChatEvent.MessageComplete(
                    RuntimeSessionId("runtime-1"),
                    "hello",
                    "error",
                    "terminal failure",
                ),
                HermesChatEvent.Error(RuntimeSessionId("runtime-1"), "temporary failure"),
            ),
            events,
        )
        connection.close()
    }

    @Test
    fun malformedFrameFailsPendingRequestInsteadOfLeavingItSuspended() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                socket.offer("this is not json")
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = ticketClient,
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        try {
            withTimeout(1_000) {
                connection.submitPrompt(RuntimeSessionId("runtime-1"), "hello")
            }
            fail("Malformed response must fail the pending request")
        } catch (error: HermesChatProtocolException) {
            assertEquals("Hermes chat frame was invalid", error.message)
        }
        connection.close()
    }

    @Test
    fun streamingDeltasPreserveInterTokenLeadingWhitespace() = runTest {
        // DeepSeek-style tokenizers attach the inter-word space to the FRONT of
        // the next token ("HE", " WORLD"). Trimming each delta strips that
        // leading space and jams the streamed text together ("HEWORLD"); the
        // final message.complete text survives because it is one string whose
        // internal spaces are untouched. Delta text must never be trimmed.
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                val id = request["id"]!!.jsonPrimitive.content
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":"HE"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":" WORLD"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":" SPACES"}}}""",
                )
                socket.offer("""{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming"}}""")
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        connection.submitPrompt(RuntimeSessionId("runtime-1"), "hello")
        val events = connection.events.take(3).toList()

        assertEquals(
            listOf(
                HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-1"), "HE"),
                HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-1"), " WORLD"),
                HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-1"), " SPACES"),
            ),
            events,
        )
        connection.close()
    }

    @Test
    fun parsesSessionInfoReasoningInterimGeneratingTitleClarifyAndBlockingKinds() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                val id = request["id"]!!.jsonPrimitive.content
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"session.info","session_id":"runtime-1","payload":{"stored_session_id":"stored-1","model":"deepseek/deepseek-v4-flash-0731","provider":"nous","reasoning_effort":"medium","title":"T","running":true,"future":1}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"thinking.delta","session_id":"runtime-1","payload":{"text":"hmm"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"reasoning.delta","session_id":"runtime-1","payload":{"text":"partial thought"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"reasoning.available","session_id":"runtime-1","payload":{"text":"authoritative thought"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.interim","session_id":"runtime-1","payload":{"text":"Checking config…","already_streamed":true}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.generating","session_id":"runtime-1","payload":{"name":"terminal"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"session.title","session_id":"runtime-1","payload":{"title":"New title"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"runtime-1","payload":{"request_id":"clarify-1","question":"Which folder?","choices":[]}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"preview.read.request","session_id":"runtime-1","payload":{"request_id":"pv-1"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"window.read.request","session_id":"runtime-1","payload":{"request_id":"win-1"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"runtime-1","payload":{"text":"done","status":"error","billing":{"provider":"nous","billing_url":"https://billing.example","is_nous":true,"message":"Add credits"},"failure_reason":"billing","recoverable":true,"reasoning":"last thought","warning":"warn"}}}""",
                )
                socket.offer("""{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming"}}""")
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        connection.submitPrompt(RuntimeSessionId("runtime-1"), "hello")
        val events = connection.events.take(10).toList()

        val runtimeId = RuntimeSessionId("runtime-1")
        assertEquals(
            listOf(
                HermesChatEvent.SessionInfo(
                    sessionId = runtimeId,
                    storedSessionId = DurableSessionId("stored-1"),
                    model = "deepseek/deepseek-v4-flash-0731",
                    provider = "nous",
                    reasoningEffort = "medium",
                    title = "T",
                    running = true,
                ),
                HermesChatEvent.ReasoningDelta(runtimeId, "partial thought"),
                HermesChatEvent.ReasoningDelta(runtimeId, "authoritative thought", replace = true),
                HermesChatEvent.MessageInterim(runtimeId, "Checking config…", alreadyStreamed = true),
                HermesChatEvent.ToolGenerating(runtimeId, "terminal"),
                HermesChatEvent.SessionTitle(runtimeId, "New title"),
                HermesChatEvent.ClarifyRequest(
                    sessionId = runtimeId,
                    requestId = "clarify-1",
                    question = "Which folder?",
                    choices = emptyList(),
                    multiSelect = false,
                ),
                HermesChatEvent.UnsupportedBlockingRequest(
                    sessionId = runtimeId,
                    kind = UnsupportedBlockingKind.PreviewRead,
                    requestId = "pv-1",
                    prompt = null,
                ),
                HermesChatEvent.UnsupportedBlockingRequest(
                    sessionId = runtimeId,
                    kind = UnsupportedBlockingKind.WindowRead,
                    requestId = "win-1",
                    prompt = null,
                ),
                HermesChatEvent.MessageComplete(
                    sessionId = runtimeId,
                    text = "done",
                    status = "error",
                    error = null,
                    reasoning = "last thought",
                    warning = "warn",
                    failureReason = "billing",
                    recoverable = true,
                    billing = HermesChatEvent.BillingInfo(
                        provider = "nous",
                        billingUrl = "https://billing.example",
                        isNous = true,
                        message = "Add credits",
                    ),
                ),
            ),
            events,
        )
        connection.close()
    }

    @Test
    fun longMessageTextSurvivesTheEventBound() = runTest {
        val longText = "x".repeat(5_000)
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                val id = request["id"]!!.jsonPrimitive.content
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-1","payload":{"text":"$longText"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"runtime-1","payload":{"text":"$longText"}}}""",
                )
                socket.offer("""{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming"}}""")
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        connection.submitPrompt(RuntimeSessionId("runtime-1"), "hello")
        val events = connection.events.take(2).toList()

        assertEquals(
            listOf(
                HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-1"), longText),
                HermesChatEvent.MessageComplete(
                    sessionId = RuntimeSessionId("runtime-1"),
                    text = longText,
                    status = null,
                ),
            ),
            events,
        )
        connection.close()
    }

    @Test
    fun parsesTypedToolLifecycleEventsWithoutRetainingRawArgumentsOrResults() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                socket.offer(
                    """
                    {"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"runtime-tools","payload":{
                      "tool_id":"tool-1","name":"terminal","context":"pwd",
                      "args":{"command":"SECRET_RAW_ARG"},"args_text":"SECRET_RAW_ARGS_TEXT"
                    }}}
                    """.trimIndent(),
                )
                socket.offer(
                    """
                    {"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"runtime-tools","payload":{
                      "tool_id":"tool-1","name":"terminal","summary":"command completed",
                      "args":{"command":"SECRET_RAW_ARG"},"result":{"secret":"SECRET_RAW_RESULT"},
                      "result_text":"SECRET_RAW_RESULT_TEXT","inline_diff":"SECRET_RAW_DIFF"
                    }}}
                    """.trimIndent(),
                )
                socket.offer(
                    """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"status":"streaming"}}""",
                )
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val acknowledgement = connection.submitPrompt(RuntimeSessionId("runtime-tools"), "run")
        val events = connection.events.take(2).toList()

        assertEquals("streaming", acknowledgement.status)
        assertEquals(
            HermesChatEvent.ToolStart(
                sessionId = RuntimeSessionId("runtime-tools"),
                toolId = "tool-1",
                name = "terminal",
                context = "pwd",
            ),
            events[0],
        )
        assertEquals(
            HermesChatEvent.ToolComplete(
                sessionId = RuntimeSessionId("runtime-tools"),
                toolId = "tool-1",
                name = "terminal",
                summary = "command completed",
            ),
            events[1],
        )
        assertTrue(events.all { event ->
            val text = event.toString()
            "SECRET_RAW_ARG" !in text &&
                "SECRET_RAW_RESULT" !in text &&
                "SECRET_RAW_DIFF" !in text
        })
        connection.close()
    }

    @Test
    fun parsesClarificationApprovalExpiryAndUnsupportedBlockingEventsSafely() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                val id = request["id"]!!.jsonPrimitive.content
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"status.update","session_id":"runtime-interactions","payload":{"kind":"working","text":"Doing work","unknown":"ignored"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"runtime-interactions","payload":{"request_id":"clarify-1","question":"Choose a mode","choices":["fast","safe"],"multi_select":true}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.expire","session_id":"runtime-interactions","payload":{"request_id":"clarify-1"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"runtime-interactions","payload":{"command":"redacted command","description":"Allow the command?","choices":["once","deny"],"future":{"raw":"ignored"}}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.expire","session_id":"runtime-interactions","payload":{"request_id":"approval-1"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"secret.request","session_id":"runtime-interactions","payload":{"request_id":"secret-1","prompt":"Enter a value","env_var":"DO_NOT_RETAIN"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"sudo.request","session_id":"runtime-interactions","payload":{"request_id":"sudo-1","prompt":"Authorization required"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","method":"event","params":{"type":"terminal.read.request","session_id":"runtime-interactions","payload":{"request_id":"terminal-1","prompt":"Read terminal"}}}""",
                )
                socket.offer(
                    """{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming"}}""",
                )
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        connection.submitPrompt(RuntimeSessionId("runtime-interactions"), "run")
        val events = connection.events.take(8).toList()

        assertEquals(
            listOf(
                HermesChatEvent.StatusUpdate(RuntimeSessionId("runtime-interactions"), "working", "Doing work"),
                HermesChatEvent.ClarifyRequest(
                    RuntimeSessionId("runtime-interactions"),
                    "clarify-1",
                    "Choose a mode",
                    listOf("fast", "safe"),
                    true,
                ),
                HermesChatEvent.ClarifyExpire(RuntimeSessionId("runtime-interactions"), "clarify-1"),
                HermesChatEvent.ApprovalRequest(
                    RuntimeSessionId("runtime-interactions"),
                    null,
                    "redacted command",
                    "Allow the command?",
                    listOf("once", "deny"),
                ),
                HermesChatEvent.ApprovalExpire(RuntimeSessionId("runtime-interactions"), "approval-1"),
                HermesChatEvent.UnsupportedBlockingRequest(
                    RuntimeSessionId("runtime-interactions"),
                    UnsupportedBlockingKind.Secret,
                    "secret-1",
                    "Enter a value",
                ),
                HermesChatEvent.UnsupportedBlockingRequest(
                    RuntimeSessionId("runtime-interactions"),
                    UnsupportedBlockingKind.Sudo,
                    "sudo-1",
                    "Authorization required",
                ),
                HermesChatEvent.UnsupportedBlockingRequest(
                    RuntimeSessionId("runtime-interactions"),
                    UnsupportedBlockingKind.TerminalRead,
                    "terminal-1",
                    "Read terminal",
                ),
            ),
            events,
        )
        assertTrue(events.none { event -> "DO_NOT_RETAIN" in event.toString() })
        connection.close()
    }

    @Test
    fun sendsClarifyApprovalAndInterruptRpcFramesWithBoundedResponses() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val method = request["method"]!!.jsonPrimitive.content
            val result = when (method) {
                "clarify.respond" -> """{"status":"ok"}"""
                "approval.respond" -> """{"status":"ok"}"""
                "session.interrupt" -> """{"status":"interrupted"}"""
                else -> error("unexpected method: $method")
            }
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":$result}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        socket.offer(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"runtime-1","payload":{"choices":["once","deny"],"command":"redacted","description":"description"}}}""",
        )
        advanceUntilIdle()
        val clarify = connection.respondToClarification("clarify-1", "choice-a")
        val approval = connection.respondToApproval(RuntimeSessionId("runtime-1"), "once", all = true)
        val interrupt = connection.interruptSession(RuntimeSessionId("runtime-1"))

        val requests = socket.sentFrames.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            listOf("clarify.respond", "approval.respond", "session.interrupt"),
            requests.map { it["method"]!!.jsonPrimitive.content },
        )
        assertEquals(
            mapOf("request_id" to "clarify-1", "answer" to "choice-a"),
            requests[0]["params"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            mapOf("session_id" to "runtime-1", "choice" to "once", "all" to "true"),
            requests[1]["params"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            mapOf("session_id" to "runtime-1"),
            requests[2]["params"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(HermesChatResponseStatus.Ok, clarify.status)
        assertEquals(HermesChatResponseStatus.Ok, approval.status)
        assertEquals(HermesChatResponseStatus.Interrupted, interrupt.status)
        connection.close()
    }

    @Test
    fun approvalResponseTargetsTheDisplayedQueuedRequestById() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"resolved":1}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        socket.offer(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"runtime-1","payload":{"request_id":"approval-1","choices":["once","deny"],"description":"first"}}}""",
        )
        socket.offer(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.request","session_id":"runtime-1","payload":{"request_id":"approval-2","choices":["session","deny"],"description":"second"}}}""",
        )
        val approvals = connection.events.take(2).toList()
        assertEquals("approval-2", (approvals.last() as HermesChatEvent.ApprovalRequest).requestId)

        val response = connection.respondToApproval(
            runtimeSessionId = RuntimeSessionId("runtime-1"),
            requestId = "approval-2",
            choice = "session",
            all = false,
        )

        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals(
            mapOf(
                "session_id" to "runtime-1",
                "request_id" to "approval-2",
                "choice" to "session",
                "all" to "false",
            ),
            request["params"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(HermesChatResponseStatus.Ok, response.status)
        assertEquals("approval-1", response.nextApproval?.requestId)
        assertEquals("first", response.nextApproval?.description)
        assertEquals(listOf("once", "deny"), response.nextApproval?.choices)
        connection.close()
    }

    @Test
    fun rejectsOversizedRuntimeIdentityBeforeInterruptRequestIsSent() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"status":"interrupted"}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.interruptSession(RuntimeSessionId("x".repeat(HERMES_CHAT_MAX_EVENT_ID_CHARS + 1)))
        }.exceptionOrNull()

        assertTrue(failure is HermesChatProtocolException)
        assertTrue(socket.sentFrames.isEmpty())
        connection.close()
    }

    @Test
    fun eventBufferOverflowDropsOldestEventsInsteadOfTearingDownTheConnection() = runTest {
        // A slow collector during a fast delta stream sheds the oldest buffered
        // events; the connection, pending acks, and newest events all survive.
        // The final text is restored by message.complete, which carries it whole.
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "prompt.submit") {
                val id = request["id"]!!.jsonPrimitive.content
                repeat(129) { index ->
                    socket.offer(
                        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-overflow","payload":{"text":"$index"}}}""",
                    )
                }
                socket.offer("""{"jsonrpc":"2.0","id":$id,"result":{"status":"streaming"}}""")
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("overflow-ticket"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val ack = connection.submitPrompt(RuntimeSessionId("runtime-overflow"), "hello")
        assertEquals("streaming", ack.status)

        val events = connection.events.take(128).toList()
        assertEquals(
            HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-overflow"), "1"),
            events.first(),
        )
        assertEquals(
            HermesChatEvent.MessageDelta(RuntimeSessionId("runtime-overflow"), "128"),
            events.last(),
        )
        connection.close()
    }

    @Test
    fun correlatesConcurrentResponsesByJsonRpcRequestId() = runTest {
        val socket = ScriptedSocket()
        val sentIds = mutableListOf<String>()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            sentIds += request["id"]!!.jsonPrimitive.content
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val first = async { connection.submitPrompt(RuntimeSessionId("runtime-1"), "first") }
        val second = async { connection.submitPrompt(RuntimeSessionId("runtime-1"), "second") }
        advanceUntilIdle()
        assertEquals(2, sentIds.size)

        socket.offer("""{"jsonrpc":"2.0","id":${sentIds[1]},"result":{"status":"streaming"}}""")
        socket.offer("""{"jsonrpc":"2.0","id":${sentIds[0]},"result":{"status":"queued"}}""")

        assertEquals("queued", first.await().status)
        assertEquals("streaming", second.await().status)
        connection.close()
    }

    @Test
    fun closeFailsPendingRequestsAndStopsTheTransport() = runTest {
        val socket = ScriptedSocket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()
        val pending = async {
            runCatching {
                connection.submitPrompt(RuntimeSessionId("runtime-1"), "waiting")
            }.exceptionOrNull()
        }
        advanceUntilIdle()

        connection.close()

        assertTrue(pending.await() is HermesChatTransportException)
        assertTrue(socket.closeCount > 0)
    }

    @Test
    fun rejectsFramesOverTheConfiguredBoundBeforeSending() = runTest {
        val socket = ScriptedSocket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            maxFrameBytes = 64,
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.submitPrompt(RuntimeSessionId("runtime-1"), "x".repeat(256))
        }.exceptionOrNull()

        assertTrue(failure is HermesChatProtocolException)
        assertTrue(socket.sentFrames.isEmpty())
        connection.close()
    }

    @Test
    fun preservesCancellationFromTicketMintingAndSocketConnection() = runTest {
        val origin = ServerOrigin.parse("https://hermes.example")
        val ticketFailure = runCatching {
            HermesChatGateway(
                origin = origin,
                credential = HermesCredential.NativeBearer.create("opaque-access"),
                ticketClient = object : WsTicketClient {
                    override suspend fun mintTicket(
                        origin: ServerOrigin,
                        credential: HermesCredential.NativeBearer,
                    ): WsTicket = throw CancellationException("cancel ticket")
                },
                socketFactory = RecordingSocketFactory(ScriptedSocket()),
                parentScope = backgroundScope,
            ).connect()
        }.exceptionOrNull()
        assertTrue(ticketFailure is CancellationException)

        val socketFailure = runCatching {
            HermesChatGateway(
                origin = origin,
                credential = HermesCredential.NativeBearer.create("opaque-access"),
                ticketClient = RecordingTicketClient("ticket-1"),
                socketFactory = object : ChatWebSocketFactory {
                    override suspend fun connect(url: String): HermesChatSocket =
                        throw CancellationException("cancel socket")
                },
                parentScope = backgroundScope,
            ).connect()
        }.exceptionOrNull()
        assertTrue(socketFailure is CancellationException)
    }

    @Test
    fun rejectsResumeForDifferentDurableSession() = runTest {
        val socket = ScriptedSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"session_id":"runtime-1","session_key":"different-durable","running":false}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching {
            connection.resume(com.unsupportedpastels.hermesandroid.app.DurableSessionId("requested-durable"))
        }.exceptionOrNull()

        assertTrue(failure is HermesChatProtocolException)
        connection.close()
    }

    @Test
    fun defaultFrameLimitAllowsBoundedLargeResumeResponse() = runTest {
        val socket = ScriptedSocket()
        val largeText = "x".repeat(70_000)
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"session_id":"runtime-1","session_key":"durable-1","messages":[{"role":"assistant","text":"$largeText"}],"running":false}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            parentScope = backgroundScope,
        ).connect()

        val resumed = connection.resume(
            com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
        )

        assertEquals(70_000, resumed.messages.single()["text"]!!.jsonPrimitive.content.length)
        connection.close()
    }

    @Test
    fun rejectsOversizedIncomingFramesAndFailsPendingRequests() = runTest {
        val socket = ScriptedSocket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = RecordingTicketClient("ticket-1"),
            socketFactory = RecordingSocketFactory(socket),
            maxFrameBytes = 64,
            parentScope = backgroundScope,
        ).connect()
        val pending = async {
            runCatching {
                connection.resume(
                    com.unsupportedpastels.hermesandroid.app.DurableSessionId("durable-1"),
                )
            }.exceptionOrNull()
        }
        advanceUntilIdle()
        socket.offer("x".repeat(65))

        advanceUntilIdle()
        val failure = pending.await()

        assertTrue(failure is HermesChatProtocolException)
        assertFalse(failure!!.message!!.contains("x".repeat(65)))
        connection.close()
    }

    @Test
    fun mintsTicketsWithBearerPostWithoutPuttingAccessTokenInWebSocketUrl() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/auth/ws-ticket", request.url.encodedPath)
            assertEquals("Bearer opaque-access", request.headers[HttpHeaders.Authorization])
            assertFalse(request.url.toString().contains("opaque-access"))
            respond(
                content = """{"ticket":"fresh-ticket","ttl_seconds":30,"future":true}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)

        val ticket = KtorWsTicketClient(client).mintTicket(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
        )

        assertEquals("fresh-ticket", ticket.ticket)
        assertEquals(30, ticket.ttlSeconds)
        client.close()
    }

    @Test
    fun closeRacingRequestRegistrationNeverLeavesRequestPending() = runTest {
        repeat(100) {
            val connection = HermesChatConnection(
                socket = ScriptedSocket(),
                maxFrameBytes = HERMES_CHAT_MAX_FRAME_BYTES,
                parentScope = backgroundScope,
            )
            val request = async {
                runCatching { connection.resume(DurableSessionId("durable-race"), "default") }
            }
            yield()
            connection.close()

            val result = withTimeout(1_000) { request.await() }
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun requestsASeparateFreshTicketForEveryConnection() = runTest {
        val sockets = listOf(ScriptedSocket(), ScriptedSocket())
        val urls = mutableListOf<String>()
        var socketIndex = 0
        val factory = object : ChatWebSocketFactory {
            override suspend fun connect(url: String): HermesChatSocket {
                urls += url
                return sockets[socketIndex++]
            }
        }
        val ticketClient = object : WsTicketClient {
            var calls = 0
            override suspend fun mintTicket(
                origin: ServerOrigin,
                credential: HermesCredential.NativeBearer,
            ): WsTicket = WsTicket("ticket-${++calls}", 30)
        }
        val gateway = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = ticketClient,
            socketFactory = factory,
            parentScope = backgroundScope,
        )

        val first = gateway.connect()
        val second = gateway.connect()
        first.close()
        second.close()

        assertEquals(
            listOf(
                "wss://hermes.example/api/ws?ticket=ticket-1",
                "wss://hermes.example/api/ws?ticket=ticket-2",
            ),
            urls,
        )
    }

    @Test
    fun stagesFileAttachmentAndReturnsRefText() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        val socketFactory = RecordingSocketFactory(socket)
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "file.attach") {
                socket.offer(
                    """
                    {"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{
                      "attached": true, "name": "report.txt",
                      "path": "/workspace/.hermes/desktop-attachments/report.txt",
                      "ref_path": ".hermes/desktop-attachments/report.txt",
                      "ref_text": "@file:.hermes/desktop-attachments/report.txt",
                      "uploaded": true
                    }}
                    """.trimIndent(),
                )
            }
        }

        val gateway = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )
        val connection = gateway.connect()
        val refText = connection.attachFile(
            runtimeSessionId = RuntimeSessionId("runtime-1"),
            filename = "report.txt",
            mimeType = "text/plain",
            base64Content = "aGVsbG8=",
        )

        assertEquals("@file:.hermes/desktop-attachments/report.txt", refText)
        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals("file.attach", request["method"]!!.jsonPrimitive.content)
        val params = request["params"]!!.jsonObject
        assertEquals("runtime-1", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("report.txt", params["name"]!!.jsonPrimitive.content)
        assertEquals("report.txt", params["path"]!!.jsonPrimitive.content)
        assertEquals("data:text/plain;base64,aGVsbG8=", params["data_url"]!!.jsonPrimitive.content)

        connection.close()
    }

    @Test
    fun fileAttachWithoutRefTextFailsClosed() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        val socketFactory = RecordingSocketFactory(socket)
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "file.attach") {
                socket.offer(
                    """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"attached":true}}""",
                )
            }
        }

        val gateway = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )
        val connection = gateway.connect()

        val error = runCatching {
            connection.attachFile(
                runtimeSessionId = RuntimeSessionId("runtime-1"),
                filename = "report.txt",
                mimeType = "text/plain",
                base64Content = "aGVsbG8=",
            )
        }.exceptionOrNull()
        assertTrue(error is HermesChatProtocolException)

        connection.close()
    }

    @Test
    fun stagesImageAttachmentViaAttachBytes() = runTest {
        val ticketClient = RecordingTicketClient("ticket-1")
        val socket = ScriptedSocket()
        val socketFactory = RecordingSocketFactory(socket)
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            if (request["method"]?.jsonPrimitive?.content == "image.attach_bytes") {
                socket.offer(
                    """
                    {"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{
                      "attached": true, "path": "/images/upload_1.png", "count": 1,
                      "text": "[User attached image: upload_1.png]", "bytes": 7
                    }}
                    """.trimIndent(),
                )
            }
        }

        val gateway = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("opaque-access"),
            ticketClient = ticketClient,
            socketFactory = socketFactory,
            parentScope = backgroundScope,
        )
        val connection = gateway.connect()
        connection.attachImage(
            runtimeSessionId = RuntimeSessionId("runtime-1"),
            filename = "photo.png",
            base64Content = "aGVsbG8=",
        )

        val request = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject
        assertEquals("image.attach_bytes", request["method"]!!.jsonPrimitive.content)
        val params = request["params"]!!.jsonObject
        assertEquals("runtime-1", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("photo.png", params["filename"]!!.jsonPrimitive.content)
        assertEquals("aGVsbG8=", params["content_base64"]!!.jsonPrimitive.content)

        connection.close()
    }
}

private class RecordingTicketClient(
    private val ticket: String,
    private val expectedOrigin: ServerOrigin = ServerOrigin.parse("https://hermes.example"),
) : WsTicketClient {
    var calls = 0

    override suspend fun mintTicket(
        origin: ServerOrigin,
        credential: HermesCredential.NativeBearer,
    ): WsTicket {
        assertEquals(expectedOrigin.value, origin.value)
        calls += 1
        return WsTicket(ticket = ticket, ttlSeconds = 30)
    }
}

private class RecordingSocketFactory(private val socket: ScriptedSocket) : ChatWebSocketFactory {
    val urls = mutableListOf<String>()

    override suspend fun connect(url: String): HermesChatSocket {
        urls += url
        return socket
    }
}

private class ScriptedSocket : HermesChatSocket {
    val sentFrames = mutableListOf<String>()
    var closeCount = 0
    private val incoming = Channel<String>(Channel.UNLIMITED)
    var onSend: (suspend (String) -> Unit)? = null

    override suspend fun sendText(text: String) {
        sentFrames += text
        onSend?.invoke(text)
    }

    override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun close() {
        closeCount += 1
        incoming.close()
    }

    suspend fun offer(frame: String) {
        incoming.send(frame)
    }
}
