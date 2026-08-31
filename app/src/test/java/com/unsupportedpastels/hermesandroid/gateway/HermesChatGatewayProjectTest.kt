package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.MAX_PROCESS_ROWS
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.hermesandroid.app.RunTodoStatus
import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesChatGatewayProjectTest {
    @Test
    fun todoPayloadInOfficialToolEventIsParsedAsBoundedLiveState() = runTest {
        val socket = MetadataSocket()
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access-token"),
            ticketClient = object : WsTicketClient {
                override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) =
                    WsTicket("ticket", 30)
            },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        socket.offer(
            """{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime-1","type":"tool.complete","payload":{"tool_id":"todo-tool","name":"todo","summary":"updated","todos":[{"id":"one","content":"Inspect gateway","status":"completed"},{"id":"two","content":"Render UI","status":"in_progress"},{"id":"bad","content":"ignored","status":"unknown"}]}}}""",
        )

        val event = connection.events.first()

        assertTrue(event is HermesChatEvent.ToolComplete)
        val todoEvent = event as HermesChatEvent.ToolComplete
        assertEquals("todo", todoEvent.name)
        assertEquals(
            listOf(RunTodoStatus.Completed, RunTodoStatus.InProgress),
            todoEvent.todos?.map(RunTodoItem::status),
        )
        connection.close()
    }

    @Test
    fun processListUsesTheSelectedRuntimeAndBoundsOfficialRows() = runTest {
        val socket = MetadataSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("process.list", request["method"]!!.jsonPrimitive.content)
            assertEquals(
                "runtime-1",
                request["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content,
            )
            val rows = (1..(MAX_PROCESS_ROWS + 4)).joinToString(",") { index ->
                """{"session_id":"process-$index","command":"python server.py --port $index","status":"running","uptime_seconds":12,"output_tail":"tail-$index"}"""
            }
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"processes":[$rows],"future":true}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access-token"),
            ticketClient = object : WsTicketClient {
                override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) =
                    WsTicket("ticket", 30)
            },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        val rows = connection.loadProcessList(RuntimeSessionId("runtime-1"))

        assertEquals(MAX_PROCESS_ROWS, rows.size)
        assertEquals("process-1", rows.first().processId)
        assertEquals("python server.py --port 1", rows.first().command)
        assertEquals("tail-1", rows.first().outputTail)
        assertEquals(12L, rows.first().uptimeSeconds)
        connection.close()
    }

    @Test
    fun delegationStatusReturnsBoundedAuthoritativeActiveChildren() = runTest {
        val socket = MetadataSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("delegation.status", request["method"]!!.jsonPrimitive.content)
            assertTrue(request["params"]!!.jsonObject.isEmpty())
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"active":[{"subagent_id":"child-1","goal":"Inspect lifecycle races","status":"running","parent_id":"parent-1","started_at":1720000000,"depth":1,"model":"test","tool_count":3},{"subagent_id":"","goal":"invalid"}],"paused":false,"max_spawn_depth":2,"max_concurrent_children":3,"future":true}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access-token"),
            ticketClient = object : WsTicketClient {
                override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) =
                    WsTicket("ticket", 30)
            },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        val result = connection.loadDelegationStatus()

        assertEquals(1, result.active.size)
        assertEquals("child-1", result.active.single().subagentId)
        assertEquals("Inspect lifecycle races", result.active.single().goal)
        assertEquals("parent-1", result.active.single().parentSubagentId)
        assertEquals(1720000000L, result.active.single().startedAtEpochSeconds)
        assertFalse(result.paused)
        assertEquals(2, result.maxSpawnDepth)
        assertEquals(3, result.maxConcurrentChildren)
        connection.close()
    }

    @Test
    fun projectTreeUsesReadOnlyMetadataRpcWithoutResumingOrCreatingRuntime() = runTest {
        val socket = MetadataSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("projects.tree", request["method"]!!.jsonPrimitive.content)
            val params = request["params"]!!.jsonObject
            assertEquals(3, params["preview_limit"]!!.jsonPrimitive.content.toInt())
            assertEquals(20, params["session_limit"]!!.jsonPrimitive.content.toInt())
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"projects":[{"id":"p1","label":"App","path":"/workspace/app","sessionCount":1,"previewSessions":[{"id":"stored-1","title":"First"}]}],"future":true}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access"),
            ticketClient = object : WsTicketClient { override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) = WsTicket("ticket", 30) },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        val result = connection.loadProjectTree(profile = "default", previewLimit = 3, sessionLimit = 20)

        assertEquals(ProjectId("p1"), result.projects.single().id)
        assertEquals("stored-1", result.projects.single().previewSessions.single().id.value)
        assertFalse(socket.sentMethods.contains("session.resume"))
        assertFalse(socket.sentMethods.contains("session.create"))
        connection.close()
    }

    @Test
    fun projectCreationValidatesHostFolderAndUsesAuthoritativeProjectId() = runTest {
        val socket = MetadataSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            val id = request["id"]!!.jsonPrimitive.content
            val params = request["params"]!!.jsonObject
            when (request["method"]!!.jsonPrimitive.content) {
                "projects.for_cwd" -> socket.offer(
                    """{"jsonrpc":"2.0","id":$id,"result":{"cwd":"/srv/demo","project":null}}""",
                )
                "projects.create" -> {
                    assertEquals("Demo", params["name"]!!.jsonPrimitive.content)
                    assertEquals("/srv/demo", params["primary_path"]!!.jsonPrimitive.content)
                    assertEquals("/srv/demo", params["folders"]!!.jsonArray.single().jsonPrimitive.content)
                    assertTrue(params["use"]!!.jsonPrimitive.content.toBoolean())
                    assertEquals("default", params["profile"]!!.jsonPrimitive.content)
                    socket.offer(
                        """{"jsonrpc":"2.0","id":$id,"result":{"project":{"id":"p_server","name":"Demo","primary_path":"/srv/demo","folders":[{"path":"/srv/demo"}]}}}""",
                    )
                }
            }
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access"),
            ticketClient = object : WsTicketClient { override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) = WsTicket("ticket", 30) },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        val project = connection.createProject(" Demo ", "/srv/demo", profile = "default")

        assertEquals(ProjectId("p_server"), project.id)
        assertEquals("Demo", project.label)
        assertEquals("/srv/demo", project.primaryPath)
        assertEquals(listOf("projects.for_cwd", "projects.create"), socket.sentMethods)
        connection.close()
    }

    @Test
    fun projectMethodNotFoundIsClassifiedAsUnsupportedNotTransient() = runTest {
        val socket = MetadataSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"error":{"code":-32601,"message":"unknown method"}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access"),
            ticketClient = object : WsTicketClient { override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) = WsTicket("ticket", 30) },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        val failure = runCatching { connection.loadProjectTree() }.exceptionOrNull()

        assertTrue(failure is HermesChatMethodNotFoundException)
        assertEquals("projects.tree", (failure as HermesChatMethodNotFoundException).method)
        connection.close()
    }

    @Test
    fun projectSessionsRequestsFullSessionWindowAndFlattensEveryLaneRow() = runTest {
        val socket = MetadataSocket()
        var requestedSessionLimit: Int? = null
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("projects.project_sessions", request["method"]!!.jsonPrimitive.content)
            requestedSessionLimit = request["params"]!!.jsonObject["session_limit"]!!.jsonPrimitive.content.toInt()
            val rows = (1..25).joinToString(",") { index ->
                """{"id":"stored-$index","title":"Session $index","cwd":"/workspace/app"}"""
            }
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"project":{"id":"p1","label":"App","path":"/workspace/app","sessionCount":25,"repos":[{"groups":[{"sessions":[$rows]}]}]}}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access"),
            ticketClient = object : WsTicketClient { override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) = WsTicket("ticket", 30) },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        val result = connection.loadProjectSessions(ProjectId("p1"), profile = "default")

        assertEquals(25, result.sessions.size)
        assertEquals(25, result.project.sessionCount)
        assertTrue((requestedSessionLimit ?: 0) > 20)
        connection.close()
    }

    @Test
    fun projectSessionsFlattenAuthoritativeRepoLaneShapeAndKeepWorkspace() = runTest {
        val socket = MetadataSocket()
        socket.onSend = { frame ->
            val request = Json.parseToJsonElement(frame).jsonObject
            assertEquals("projects.project_sessions", request["method"]!!.jsonPrimitive.content)
            socket.offer(
                """{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":{"project":{"id":"p1","label":"App","path":"/workspace/app","sessionCount":2,"repos":[{"groups":[{"sessions":[{"id":"stored-1","session_key":"runtime-wrong","title":"First","cwd":"/workspace/app"},{"id":"stored-1","title":"Duplicate"},{"id":"stored-2","title":"Second","cwd":"/workspace/app/sub"}]}]}]}}}""",
            )
        }
        val connection = HermesChatGateway(
            origin = ServerOrigin.parse("https://hermes.example"),
            credential = HermesCredential.NativeBearer.create("access"),
            ticketClient = object : WsTicketClient { override suspend fun mintTicket(origin: ServerOrigin, credential: HermesCredential.NativeBearer) = WsTicket("ticket", 30) },
            socketFactory = object : ChatWebSocketFactory { override suspend fun connect(url: String) = socket },
            parentScope = backgroundScope,
        ).connect()

        val result = connection.loadProjectSessions(ProjectId("p1"), profile = "default")

        assertEquals(listOf("stored-1", "stored-2"), result.sessions.map { it.id.value })
        assertEquals("/workspace/app", result.sessions.first().workspacePath)
        assertEquals(ProjectId("p1"), result.sessions.first().projectId)
        connection.close()
    }
}

private class MetadataSocket : HermesChatSocket {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    val sentMethods = mutableListOf<String>()
    val sentFrames = mutableListOf<String>()
    var onSend: (suspend (String) -> Unit)? = null

    override suspend fun sendText(text: String) {
        sentFrames += text
        sentMethods += Json.parseToJsonElement(text).jsonObject["method"]!!.jsonPrimitive.content
        onSend?.invoke(text)
    }

    override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()
    override suspend fun close() { incoming.close() }
    fun offer(frame: String) { incoming.trySend(frame) }
}
