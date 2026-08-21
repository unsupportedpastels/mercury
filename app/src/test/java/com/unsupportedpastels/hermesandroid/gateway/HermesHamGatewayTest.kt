package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesHamGatewayTest {
    @Test
    fun steerUsesExactRuntimeSessionIdAndText() = runTest {
        val socket = HamSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("session.steer", request["method"]!!.jsonPrimitive.content)
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"status":"queued","text":"focus on tests"}}""",
            )
        }
        val connection = newHamConnection(socket)

        val result = connection.steer(RuntimeSessionId("runtime-current"), "focus on tests")

        val params = Json.parseToJsonElement(socket.sentFrames.single()).jsonObject["params"]!!.jsonObject
        assertEquals("runtime-current", params["session_id"]!!.jsonPrimitive.content)
        assertEquals("focus on tests", params["text"]!!.jsonPrimitive.content)
        assertEquals("queued", result.status)
        connection.close()
    }

    @Test
    fun usageAndContextLoadUseRuntimeIdentityAndParseBoundedShapes() = runTest {
        val socket = HamSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            val result = when (request["method"]!!.jsonPrimitive.content) {
                "session.usage" -> """{"calls":2,"input":12,"output":7,"total":19,"context_used":19,"context_max":100,"context_percent":19.5,"credits_lines":["credits: 1.2"]}"""
                "session.context_breakdown" -> """{"categories":[{"id":"system","label":"System","tokens":4,"color":"blue"},{"id":"history","label":"History","tokens":15,"color":"gray"}],"context_used":19,"context_max":100,"context_percent":19.0,"estimated_total":21,"model":"hermes"}"""
                else -> error("unexpected method")
            }
            socket.offer("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        val connection = newHamConnection(socket)

        val usage = connection.loadSessionUsage(RuntimeSessionId("runtime-usage"))
        val context = connection.loadContextBreakdown(RuntimeSessionId("runtime-usage"))

        val requests = socket.sentFrames.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("session.usage", "session.context_breakdown"), requests.map { it["method"]!!.jsonPrimitive.content })
        assertEquals("runtime-usage", requests[0]["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
        assertEquals(19L, usage.totalTokens)
        assertEquals(19.5, usage.contextPercent!!, 0.0)
        assertEquals(listOf("System", "History"), context.categories.map { it.name })
        assertEquals(15L, context.categories[1].tokens)
        assertEquals(100L, context.maxTokens)
        connection.close()
    }

    @Test
    fun compressUndoAndBranchUseExactMutatingParametersAndDurableBranchIdentity() = runTest {
        val socket = HamSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            val result = when (request["method"]!!.jsonPrimitive.content) {
                "session.compress" -> """{"status":"compressed","removed":3,"before_messages":8,"after_messages":2,"before_tokens":100,"after_tokens":42,"summary":"summary","messages":[{"role":"assistant","text":"summary"}],"usage":{"total":42},"info":{"model":"hermes","detail":"do not display"}}"""
                "session.undo" -> """{"removed":3}"""
                "session.branch" -> """{"session_id":"runtime-branch","stored_session_id":"stored-branch","title":"Branch","messages":[{"role":"user","text":"hello"}]}"""
                else -> error("unexpected method")
            }
            socket.offer("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        val connection = newHamConnection(socket)

        val compressed = connection.compressSession(RuntimeSessionId("runtime-source"), "focus topic")
        val undone = connection.undoSession(RuntimeSessionId("runtime-source"))
        val branch = connection.branchSession(RuntimeSessionId("runtime-source"), count = 2, name = "Branch")

        val requests = socket.sentFrames.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("session.compress", "session.undo", "session.branch"), requests.map { it["method"]!!.jsonPrimitive.content })
        assertEquals("runtime-source", requests[0]["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
        assertEquals("focus topic", requests[0]["params"]!!.jsonObject["focus_topic"]!!.jsonPrimitive.content)
        assertEquals("runtime-source", requests[1]["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
        assertEquals(2, requests[2]["params"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("Branch", requests[2]["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(42L, compressed.usage?.totalTokens)
        assertEquals("compressed", compressed.status)
        assertTrue(compressed.info == null)
        assertEquals(3, undone.removed)
        assertEquals(RuntimeSessionId("runtime-branch"), branch.runtimeSessionId)
        assertEquals(DurableSessionId("stored-branch"), branch.durableSessionId)
        assertEquals("Branch", branch.title)
        connection.close()
    }

    @Test
    fun delegationPauseAndSubagentControlsCarryAuthoritativeOwnershipParameters() = runTest {
        val socket = HamSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            val result = when (request["method"]!!.jsonPrimitive.content) {
                "delegation.pause" -> """{"paused":true}"""
                "subagent.interrupt" -> """{"found":true,"subagent_id":"child-1"}"""
                "subagent.steer" -> """{"status":"queued","text":"change direction"}"""
                else -> error("unexpected method")
            }
            socket.offer("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
        }
        val connection = newHamConnection(socket)

        val paused = connection.pauseDelegation(true)
        val interrupted = connection.interruptSubagent("child-1")
        val steered = connection.steerSubagent(
            runtimeSessionId = RuntimeSessionId("runtime-parent"),
            subagentId = "child-1",
            text = "change direction",
        )

        val requests = socket.sentFrames.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("delegation.pause", "subagent.interrupt", "subagent.steer"), requests.map { it["method"]!!.jsonPrimitive.content })
        assertTrue(requests[0]["params"]!!.jsonObject["paused"]!!.jsonPrimitive.boolean)
        assertEquals("child-1", requests[1]["params"]!!.jsonObject["subagent_id"]!!.jsonPrimitive.content)
        val steerParams = requests[2]["params"]!!.jsonObject
        assertEquals("runtime-parent", steerParams["session_id"]!!.jsonPrimitive.content)
        assertEquals("child-1", steerParams["subagent_id"]!!.jsonPrimitive.content)
        assertEquals("change direction", steerParams["text"]!!.jsonPrimitive.content)
        assertTrue(paused.paused)
        assertTrue(interrupted.found)
        assertEquals("child-1", interrupted.subagentId)
        assertEquals("queued", steered.status)
        connection.close()
    }

    @Test
    fun cronListUsesReadOnlyProcessVisibleShapeAndToleratesMalformedDuplicateRows() = runTest {
        val longName = "n".repeat(700)
        val socket = HamSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("cron.manage", request["method"]!!.jsonPrimitive.content)
            val params = request["params"]!!.jsonObject
            assertEquals("list", params["action"]!!.jsonPrimitive.content)
            assertTrue(params["include_disabled"]!!.jsonPrimitive.boolean)
            assertEquals("work", params["profile"]!!.jsonPrimitive.content)
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"jobs":[{"job_id":"job-1","name":"Nightly","schedule":"0 0 * * *","enabled":false,"last_delivery_error":"delivery failed"},{"job_id":"job-1","name":"duplicate","schedule":"ignored"},{"id":"job-2","name":"$longName","schedule":"@hourly","next_run_at":123},{"name":"missing id","schedule":"never"},{"job_id":"bad","schedule":"missing name"}]}}""",
            )
        }
        val connection = newHamConnection(socket)

        val jobs = connection.loadCronJobsForProfile("work")

        assertEquals(listOf("job-1", "job-2"), jobs.map(CronJob::jobId))
        assertEquals("Nightly", jobs[0].name)
        assertEquals("delivery failed", jobs[0].lastDeliveryError)
        assertEquals(512, jobs[1].name.length)
        assertEquals("123", jobs[1].nextRunAt)
        connection.close()
    }

    @Test
    fun cronManageMapsLifecycleActionsToServerVerbsWithJobName() = runTest {
        val socket = HamSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("cron.manage", request["method"]!!.jsonPrimitive.content)
            socket.offer("""{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"success":true}}""")
        }
        val connection = newHamConnection(socket)

        connection.manageCronJob("job-1", CronJobAction.Enable)
        connection.manageCronJob("job-2", CronJobAction.Disable)

        val params = socket.sentFrames.map { Json.parseToJsonElement(it).jsonObject["params"]!!.jsonObject }
        assertEquals(
            listOf("resume", "pause"),
            params.map { it["action"]!!.jsonPrimitive.content },
        )
        assertEquals(
            listOf("job-1", "job-2"),
            params.map { it["name"]!!.jsonPrimitive.content },
        )
        connection.close()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.newHamConnection(socket: HamSocket): HermesChatSession =
        HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access"),
            ticketClient = object : WsTicketClient {
                override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) = WsTicket("ticket", 30)
            },
            socketFactory = object : ChatWebSocketFactory {
                override suspend fun connect(url: String) = socket
            },
            parentScope = backgroundScope,
        ).connect()
}

private class HamSocket : HermesChatSocket {
    val sentFrames = mutableListOf<String>()
    private val incoming = Channel<String>(Channel.UNLIMITED)
    var onSend: (suspend (String) -> Unit)? = null

    override suspend fun sendText(text: String) {
        sentFrames += text
        onSend?.invoke(text)
    }

    override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()
    override suspend fun close() { incoming.close() }
    suspend fun offer(frame: String) { incoming.send(frame) }
}
