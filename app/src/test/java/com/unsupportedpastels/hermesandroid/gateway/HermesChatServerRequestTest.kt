package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hermes server→client requests (`client.capabilities {server_requests: true}`, `srq-*` request
 * frames, `request.cancel`). Without them current Hermes fails approvals closed and auto-skips
 * clarify for this client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesChatServerRequestTest {
    private class Socket : HermesChatSocket {
        val sent = mutableListOf<JsonObject>()
        private val incoming = Channel<String>(Channel.UNLIMITED)
        var reply: (JsonObject) -> String? = { request ->
            """{"jsonrpc":"2.0","id":${request["id"]},"result":{"status":"ok"}}"""
        }

        override suspend fun sendText(text: String) {
            val frame = Json.parseToJsonElement(text).jsonObject
            sent += frame
            if (frame["method"] != null) reply(frame)?.let { incoming.send(it) }
        }

        override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()

        override suspend fun close() {
            incoming.close()
        }

        suspend fun push(frame: String) = incoming.send(frame)

        fun calls(method: String) = sent.filter { it["method"]?.jsonPrimitive?.content == method }
    }

    private fun TestScope.connect(socket: Socket, answers: Boolean = true) = HermesChatConnection(
        socket = socket,
        maxFrameBytes = HERMES_CHAT_MAX_FRAME_BYTES,
        parentScope = backgroundScope,
        answersServerRequests = answers,
    )

    @Test
    fun gatewayAdvertisesServerRequestCapabilityBeforeHandingOutTheConnection() = runTest {
        val socket = Socket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            accessToken = "opaque-access",
            ticketClient = object : WsTicketClient {
                override suspend fun mintTicket(origin: ServerOrigin, accessToken: String) = WsTicket("ticket-1", 30)
            },
            socketFactory = object : ChatWebSocketFactory {
                override suspend fun connect(url: String): HermesChatSocket = socket
            },
            parentScope = backgroundScope,
            answersServerRequests = true,
        ).connect()

        val first = socket.sent.first()
        assertEquals("client.capabilities", first["method"]!!.jsonPrimitive.content)
        assertTrue(first["params"]!!.jsonObject["server_requests"]!!.jsonPrimitive.boolean)
        connection.close()
    }

    @Test
    fun olderHermesWithoutTheCapabilityMethodStillConnects() = runTest {
        val socket = Socket()
        socket.reply = { request ->
            """{"jsonrpc":"2.0","id":${request["id"]},"error":{"code":-32601,"message":"unknown method"}}"""
        }
        val connection = connect(socket)
        connection.advertiseServerRequests()

        assertEquals(1, socket.calls("client.capabilities").size)
        connection.close()
    }

    @Test
    fun doesNotAdvertiseOrAnswerWhenDisabled() = runTest {
        val socket = Socket()
        val connection = connect(socket, answers = false)
        connection.advertiseServerRequests()
        socket.push("""{"jsonrpc":"2.0","id":"srq-1","method":"terminal.read","params":{"session_id":"rt-1"}}""")
        runCurrent()

        assertTrue(socket.calls("client.capabilities").isEmpty())
        assertTrue(socket.sent.none { it["error"] != null })
        connection.close()
    }

    @Test
    fun clarifyRequestSurfacesAndLocksEachAnswer() = runTest {
        val socket = Socket()
        var remaining = listOf("q2")
        socket.reply = { request ->
            val id = request["id"]
            when (request["method"]!!.jsonPrimitive.content) {
                "clarify.lock" -> {
                    val body = """{"jsonrpc":"2.0","id":$id,"result":{"status":"ok","remaining":${Json.encodeToString(remaining)}}}"""
                    remaining = emptyList()
                    body
                }
                else -> """{"jsonrpc":"2.0","id":$id,"result":{}}"""
            }
        }
        val connection = connect(socket)
        socket.push(
            """{"jsonrpc":"2.0","id":"srq-1","method":"clarify","params":{"session_id":"rt-1","questions":[
                {"qid":"q1","question":"Which one?","choices":["a","b"],"multi_select":false},
                {"qid":"q2","question":"Why?","choices":[],"multi_select":false}]}}""",
        )

        val event = withTimeout(1_000) { connection.events.first() } as HermesChatEvent.ClarifyRequest
        assertEquals("srq-1", event.requestId)
        assertEquals(listOf("q1", "q2"), event.questions.map { it.qid })

        assertEquals(HermesChatResponseStatus.Ok, connection.respondToClarification("srq-1", "q1", "a").status)
        assertEquals(HermesChatResponseStatus.Ok, connection.respondToClarification("srq-1", "q2", "").status)

        val locks = socket.calls("clarify.lock").map { it["params"]!!.jsonObject }
        assertEquals(listOf("q1", "q2"), locks.map { it["question_id"]!!.jsonPrimitive.content })
        assertEquals("srq-1", locks[0]["request_id"]!!.jsonPrimitive.content)
        assertEquals("a", locks[0]["answer"]!!.jsonPrimitive.content)
        // A skipped question locks as null, the wire's "skipped".
        assertEquals(JsonNull, locks[1]["answer"])
        assertTrue(socket.calls("clarify.respond").isEmpty())
        connection.close()
    }

    @Test
    fun approvalRequestAnswersThroughApprovalRespondAndExpiresOnCancel() = runTest {
        val socket = Socket()
        val connection = connect(socket)
        socket.push(
            """{"jsonrpc":"2.0","id":"srq-2","method":"approval","params":{"session_id":"rt-1",
                "request_id":"appr-1","command":"rm -rf build","description":"recursive delete",
                "choices":["once","session","always","deny"]}}""",
        )

        val request = withTimeout(1_000) { connection.events.first() } as HermesChatEvent.ApprovalRequest
        assertEquals("appr-1", request.requestId)
        assertEquals("rm -rf build", request.command)
        assertEquals(listOf("once", "session", "always", "deny"), request.choices)

        val response = connection.respondToApproval(RuntimeSessionId("rt-1"), "once", all = false, requestId = "appr-1")
        assertEquals(HermesChatResponseStatus.Ok, response.status)
        val respond = socket.calls("approval.respond").single()["params"]!!.jsonObject
        assertEquals("appr-1", respond["request_id"]!!.jsonPrimitive.content)
        assertEquals("once", respond["choice"]!!.jsonPrimitive.content)

        // The server withdraws the request once the approval queue settles.
        socket.push(
            """{"jsonrpc":"2.0","method":"event","params":{"session_id":"rt-1","type":"request.cancel",
                "payload":{"id":"srq-2","method":"approval","reason":"resolved"}}}""",
        )
        val expire = withTimeout(1_000) { connection.events.first() } as HermesChatEvent.ApprovalExpire
        assertEquals("appr-1", expire.requestId)
        connection.close()
    }

    @Test
    fun secretAndSudoAnswerTheOpenRequest() = runTest {
        val socket = Socket()
        val connection = connect(socket)
        socket.push("""{"jsonrpc":"2.0","id":"srq-3","method":"secret","params":{"session_id":"rt-1","env_var":"K","prompt":"API key"}}""")
        socket.push("""{"jsonrpc":"2.0","id":"srq-4","method":"sudo","params":{"session_id":"rt-1","command":"apt"}}""")

        val secret = withTimeout(1_000) { connection.events.first() } as HermesChatEvent.UnsupportedBlockingRequest
        assertEquals(UnsupportedBlockingKind.Secret, secret.kind)
        assertEquals("API key", secret.prompt)
        val sudo = withTimeout(1_000) { connection.events.first() } as HermesChatEvent.UnsupportedBlockingRequest
        assertEquals(UnsupportedBlockingKind.Sudo, sudo.kind)

        connection.respondToBlockingPrompt(UnsupportedBlockingKind.Secret, secret.requestId, "opaque-value")
        connection.respondToBlockingPrompt(UnsupportedBlockingKind.Sudo, sudo.requestId, "opaque-password")

        val answers = socket.calls("request.answer").map { it["params"]!!.jsonObject }
        assertEquals(listOf("srq-3", "srq-4"), answers.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("opaque-value", answers[0]["result"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("opaque-password", answers[1]["result"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertTrue(socket.calls("secret.respond").isEmpty())
        assertTrue(socket.calls("sudo.respond").isEmpty())
        connection.close()
    }

    @Test
    fun desktopOnlyAndUnknownRequestsAreDeclinedAtOnce() = runTest {
        val socket = Socket()
        val connection = connect(socket)
        socket.push("""{"jsonrpc":"2.0","id":"srq-5","method":"terminal.read","params":{"session_id":"rt-1"}}""")
        socket.push("""{"jsonrpc":"2.0","id":"srq-6","method":"vault.code","params":{"session_id":"rt-1"}}""")
        runCurrent()

        val errors = socket.sent.filter { it["error"] != null }.associate {
            it["id"]!!.jsonPrimitive.content to it["error"]!!.jsonObject["code"]!!.jsonPrimitive.long
        }
        assertEquals(mapOf("srq-5" to 4404L, "srq-6" to -32601L), errors)
        connection.close()
    }

    @Test
    fun resumeSurfacesRequestsThatWereAlreadyOpen() = runTest {
        val socket = Socket()
        socket.reply = { request ->
            val id = request["id"]
            when (request["method"]!!.jsonPrimitive.content) {
                "session.resume" -> """{"jsonrpc":"2.0","id":$id,"result":{"session_id":"rt-1","resumed":"durable-1",
                    "messages":[],"open_requests":[{"id":"srq-7","method":"clarify","params":{"session_id":"rt-1",
                    "questions":[{"qid":"q1","question":"A?"},{"qid":"q2","question":"B?"}],"answers":{"q1":"x"}}}]}}"""
                else -> """{"jsonrpc":"2.0","id":$id,"result":{}}"""
            }
        }
        val connection = connect(socket)
        connection.resume(DurableSessionId("durable-1"), null)

        val event = withTimeout(1_000) { connection.events.first() } as HermesChatEvent.ClarifyRequest
        assertEquals("srq-7", event.requestId)
        // q1 was locked before the reconnect; only q2 is still asked.
        assertEquals(listOf("q2"), event.questions.map { it.qid })
        connection.close()
    }
}
