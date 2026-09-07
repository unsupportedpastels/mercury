package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectSessionsResult
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectTreeResult
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.MAX_PROCESS_ROWS
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.mercury.core.profiles.ProfileCatalogPolicy
import com.unsupportedpastels.mercury.core.sessions.SessionPresencePolicy
import com.unsupportedpastels.mercury.core.rpc.RpcResultDecoder
import com.unsupportedpastels.mercury.core.transcript.ChatEventDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// Latest unchanged Hermes Serve raises uvicorn's attachment WebSocket ceiling to
// 384 MiB (desktop backend contract v5). Android remains deliberately far lower:
// enough for a 24 MiB image after base64/JSON expansion, but bounded for memory.
internal const val HERMES_CHAT_MAX_FRAME_BYTES = 36 * 1024 * 1024
private const val MAX_CONFIGURED_FRAME_BYTES = HERMES_CHAT_MAX_FRAME_BYTES
private const val DEFAULT_MAX_FRAME_BYTES = MAX_CONFIGURED_FRAME_BYTES
private const val MAX_EVENT_BUFFER = 128
internal const val HERMES_CHAT_MAX_EVENT_ID_CHARS = ChatEventDecoder.MAX_EVENT_ID_CHARS
internal const val HERMES_CHAT_MAX_EVENT_NAME_CHARS = ChatEventDecoder.MAX_EVENT_NAME_CHARS
internal const val HERMES_CHAT_MAX_EVENT_TEXT_CHARS = ChatEventDecoder.MAX_EVENT_TEXT_CHARS
internal const val HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS = ChatEventDecoder.MAX_MESSAGE_TEXT_CHARS
internal const val HERMES_CHAT_MAX_EVENT_CHOICE_CHARS = ChatEventDecoder.MAX_EVENT_CHOICE_CHARS
private const val MAX_PROJECT_PREVIEW_LIMIT = 3
private const val MAX_PROJECT_SESSION_LIMIT = 500

/**
 * Ticketed JSON-RPC chat transport. The access token is consumed only by the ticket client;
 * the WebSocket factory receives a URL containing only the fresh single-use ticket.
 */
class HermesChatGateway(
    private val origin: ServerOrigin,
    private val accessToken: String,
    private val ticketClient: WsTicketClient,
    private val socketFactory: ChatWebSocketFactory,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    init {
        // A blank access token is valid for cookie-backed sessions (basic auth):
        // the ticket is minted from the shared client's session cookie, and the
        // ticket client omits the bearer header when the token is blank. Only
        // bearer/OAuth sessions carry a non-blank token here.
        require(maxFrameBytes in 1..MAX_CONFIGURED_FRAME_BYTES) {
            "Hermes frame limit is out of bounds"
        }
    }

    suspend fun connect(): HermesChatConnection {
        val ticket = ticketClient.mintTicket(origin, accessToken)
        val socketUrl = websocketUrl(origin, ticket.ticket)
        val socket = try {
            socketFactory.connect(socketUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HermesChatException) {
            throw error
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not connect to Hermes chat", error)
        }
        return HermesChatConnection(
            socket = socket,
            maxFrameBytes = maxFrameBytes,
            parentScope = parentScope,
        )
    }

    private fun websocketUrl(origin: ServerOrigin, ticket: String): String {
        val encodedTicket = URLEncoder.encode(ticket, StandardCharsets.UTF_8.name())
        return "${origin.webSocketValue}/api/ws?ticket=$encodedTicket"
    }
}

private data class PendingApproval(
    val requestId: String?,
    val command: String?,
    val description: String?,
    val choices: List<String>,
) {
    fun toEvent(sessionId: RuntimeSessionId): HermesChatEvent.ApprovalRequest =
        HermesChatEvent.ApprovalRequest(
            sessionId = sessionId,
            requestId = requestId,
            command = command,
            description = description,
            choices = choices,
        )
}

class HermesChatConnection internal constructor(
    private val socket: HermesChatSocket,
    private val maxFrameBytes: Int,
    parentScope: CoroutineScope,
    private val nextRequestId: AtomicLong = AtomicLong(1),
) : HermesChatSession {
    override val relayLeaseSnapshot: com.unsupportedpastels.hermesandroid.relay.RelayLeaseSnapshot?
        get() = (socket as? com.unsupportedpastels.hermesandroid.relay.RelayLeaseRecoverySocket)?.snapshot
    override fun acknowledgeRelayEvent(eventId: String?) {
        (socket as? com.unsupportedpastels.hermesandroid.relay.RelayLeaseRecoverySocket)?.acknowledge(eventId)
    }
    override suspend fun relayRequest(method: String, params: JsonObject): JsonObject {
        if (!method.startsWith("relay.")) throw HermesChatProtocolException("Unsafe relay method")
        return request(method, params)
    }

    override suspend fun loadProfiles(): List<String> {
        val result = request(
            "profiles.list",
            buildJsonObject { put("include_sessions", false) },
        )
        return ProfileCatalogPolicy.sanitizeRpcNames(
            (result["profiles"] as? JsonArray).orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                (row["name"] as? JsonPrimitive)?.contentOrNull
            },
        )
    }

    override suspend fun loadDelegationStatus(): DelegationStatus {
        val result = request("delegation.status", buildJsonObject {})
        val active = (result["active"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val subagentId = row.boundedRequired("subagent_id", 256) ?: return@mapNotNull null
                val goal = row.boundedRequired("goal", 2_000) ?: return@mapNotNull null
                val status = row.boundedRequired("status", 64) ?: return@mapNotNull null
                DelegatedSubagent(
                    subagentId = subagentId,
                    goal = goal,
                    status = status,
                    parentSubagentId = row.boundedOptional("parent_id", 256)
                        ?: row.boundedOptional("parent_subagent_id", 256),
                    startedAtEpochSeconds = row.longValue("started_at")?.coerceAtLeast(0),
                )
            }
            .distinctBy(DelegatedSubagent::subagentId)
            .take(32)
        return DelegationStatus(
            active = active,
            paused = result.booleanValue("paused") ?: false,
            maxSpawnDepth = result.longValue("max_spawn_depth")?.coerceIn(0, 32)?.toInt(),
            maxConcurrentChildren = result.longValue("max_concurrent_children")?.coerceIn(0, 128)?.toInt(),
        )
    }

    override suspend fun loadActiveSessionPresence(): Set<DurableSessionId> {
        val result = request(
            "session.active_list",
            buildJsonObject { put("current_session_id", "") },
        )
        // The shared policy consumes plain decoded shapes so both platforms run
        // the same decision over their own JSON model; adapt JsonObject here.
        fun JsonElement.asPlain(): Any? = when (this) {
            is JsonObject -> entries.associate { it.key to it.value.asPlain() }
            is JsonArray -> map { it.asPlain() }
            is JsonPrimitive -> content
        }
        return SessionPresencePolicy
            .parseRows(result.asPlain() as? Map<*, *>)
            .let(SessionPresencePolicy::workingSessionIds)
            .map { durableSessionId ->
                DurableSessionId(durableSessionId)
            }
            .toSet()
    }

    override suspend fun loadProcessList(runtimeSessionId: RuntimeSessionId): List<ProcessRow> {
        val result = request(
            "process.list",
            buildJsonObject { put("session_id", runtimeSessionId.value) },
        )
        return (result["processes"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val processId = row.boundedProcessRequired("session_id", 256) ?: return@mapNotNull null
                val command = row.boundedProcessRequired("command", 4_096) ?: return@mapNotNull null
                val status = row.boundedProcessRequired("status", 64) ?: return@mapNotNull null
                ProcessRow(
                    processId = processId,
                    command = command,
                    status = status,
                    outputTail = row.boundedProcessOptional("output_tail", 4_000),
                    exitCode = row.longValue("exit_code")
                        ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        ?.toInt(),
                    uptimeSeconds = row.longValue("uptime_seconds")?.coerceAtLeast(0),
                )
            }
            .distinctBy(ProcessRow::processId)
            .take(MAX_PROCESS_ROWS)
    }
    private val closed = AtomicBoolean(false)
    private val lifecycleLock = Any()

    private val pendingRequests = ConcurrentHashMap<Long, kotlinx.coroutines.CompletableDeferred<JsonObject>>()
    private val pendingRequestMethods = ConcurrentHashMap<Long, String>()
    private val interactionLock = Any()
    private val pendingApprovals = HashMap<String, ArrayDeque<PendingApproval>>()
    // DROP_OLDEST: a slow Main-thread collector (fold/unfold recomposition jank
    // during a fast delta stream) must degrade to lost intermediate deltas, not a
    // torn-down connection. Terminal events arrive last, so they are the least
    // likely to be dropped, and resume reconciliation restores authoritative state.
    private val eventChannel = Channel<HermesChatEvent>(
        capacity = MAX_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val connectionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val connectionScope = CoroutineScope(parentScope.coroutineContext + connectionJob)
    private val readerJob: Job = connectionScope.launch { readLoop() }
    private val json = Json { ignoreUnknownKeys = true }

    override val events: Flow<HermesChatEvent> = eventChannel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        val params = buildJsonObject {
            put("session_id", durableSessionId.value)
            profile?.let { put("profile", it) }
            put("close_on_disconnect", false)
        }
        return parseResumeResult(request("session.resume", params), durableSessionId)
    }

    override suspend fun loadProjectTree(
        profile: String?,
        previewLimit: Int,
        sessionLimit: Int,
    ): ProjectTreeResult {
        val params = buildJsonObject {
            profile?.let { put("profile", it) }
            put("preview_limit", previewLimit.coerceIn(0, MAX_PROJECT_PREVIEW_LIMIT))
            put("session_limit", sessionLimit.coerceIn(0, MAX_PROJECT_SESSION_LIMIT))
        }
        return parseProjectTreeResult(request("projects.tree", params))
    }

    override suspend fun loadProjectSessions(
        projectId: ProjectId,
        profile: String?,
        sessionLimit: Int,
    ): ProjectSessionsResult {
        val params = buildJsonObject {
            put("project_id", projectId.value)
            profile?.let { put("profile", it) }
            put("session_limit", sessionLimit.coerceIn(0, MAX_PROJECT_SESSION_LIMIT))
        }
        return parseProjectSessionsResult(request("projects.project_sessions", params), projectId)
    }

    override suspend fun createProject(
        name: String,
        path: String,
        profile: String?,
    ): ProjectSummary {
        val projectName = name.trim()
            .takeIf { it.isNotBlank() && it.length <= ProjectSummary.MAX_LABEL_LENGTH && !it.hasControlCharacters() }
            ?: throw HermesChatProtocolException("Project name is invalid")
        val requestedPath = validProjectWorkspacePath(path)
            ?: throw HermesChatProtocolException("Host folder path must be absolute")
        val resolveParams = buildJsonObject {
            put("cwd", requestedPath)
            profile?.let { put("profile", it) }
        }
        val resolvedPath = request("projects.for_cwd", resolveParams)
            .stringValue("cwd")
            ?.let(::validProjectWorkspacePath)
            ?: throw HermesChatProtocolException("Hermes did not return a valid host folder")
        if (!sameHostPath(requestedPath, resolvedPath)) {
            throw HermesChatProtocolException("Host folder does not exist")
        }
        val params = buildJsonObject {
            put("name", projectName)
            put("folders", JsonArray(listOf(JsonPrimitive(resolvedPath))))
            put("primary_path", resolvedPath)
            put("use", true)
            profile?.let { put("profile", it) }
        }
        val project = request("projects.create", params)["project"] as? JsonObject
        return parseProjectSummary(project)
            ?: throw HermesChatProtocolException("Project creation response was incomplete")
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = submitPrompt(runtimeSessionId, text, interrupted = false)

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
        interrupted: Boolean,
    ): PromptSubmission = submitPrompt(runtimeSessionId, text, interrupted, submissionId = null)

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
        interrupted: Boolean,
        submissionId: String?,
    ): PromptSubmission {
        if (submissionId != null && submissionId.length !in 1..128) {
            throw HermesChatProtocolException("Invalid submission identifier")
        }
        val params = buildJsonObject {
            put("session_id", runtimeSessionId.value)
            put("text", text)
            if (interrupted) put("interrupted", true)
            submissionId?.let { put("submission_id", it) }
        }
        val result = request("prompt.submit", params)
        val status = result.stringValue("status")
            ?: throw HermesChatProtocolException("Prompt response was incomplete")
        return PromptSubmission(status)
    }

    override suspend fun steer(runtimeSessionId: RuntimeSessionId, text: String): SessionSteerResult {
        val bounded = boundedRpcInput(text, HERMES_CHAT_MAX_EVENT_TEXT_CHARS, "steer text")
        val result = request("session.steer", buildJsonObject {
            put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
            put("text", bounded)
        })
        val status = result.stringValue("status")
            ?.takeIf { it == "queued" || it == "rejected" }
            ?: throw HermesChatProtocolException("Steer response was incomplete")
        return SessionSteerResult(status, result.stringValue("text")?.take(HERMES_CHAT_MAX_EVENT_TEXT_CHARS))
    }

    override suspend fun loadSessionUsage(runtimeSessionId: RuntimeSessionId): SessionUsage =
        parseSessionUsage(request("session.usage", sessionParams(runtimeSessionId)))

    override suspend fun loadContextBreakdown(runtimeSessionId: RuntimeSessionId): SessionContextBreakdown =
        parseContextBreakdown(request("session.context_breakdown", sessionParams(runtimeSessionId)))

    override suspend fun compressSession(runtimeSessionId: RuntimeSessionId, focusTopic: String?): SessionCompressResult =
        parseCompressResult(request("session.compress", buildJsonObject {
            put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
            focusTopic?.trim()?.takeIf(String::isNotBlank)?.let {
                put("focus_topic", it.take(HERMES_CHAT_MAX_EVENT_TEXT_CHARS))
            }
        }))

    override suspend fun undoSession(runtimeSessionId: RuntimeSessionId): SessionUndoResult {
        val result = request("session.undo", sessionParams(runtimeSessionId))
        return SessionUndoResult(result.longValue("removed")?.coerceAtLeast(0)?.toInt()
            ?: throw HermesChatProtocolException("Undo response was incomplete"))
    }

    override suspend fun branchSession(runtimeSessionId: RuntimeSessionId, count: Int?, name: String?): SessionBranchResult =
        parseBranchResult(request("session.branch", buildJsonObject {
            put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
            count?.coerceIn(1, 500)?.let { put("count", it) }
            name?.trim()?.takeIf(String::isNotBlank)?.let { put("name", it.take(512)) }
        }))

    override suspend fun pauseDelegation(paused: Boolean): DelegationPauseResult {
        val result = request("delegation.pause", buildJsonObject { put("paused", paused) })
        return DelegationPauseResult(
            result.booleanValue("paused") ?: throw HermesChatProtocolException("Delegation pause response was incomplete"),
        )
    }

    override suspend fun interruptSubagent(subagentId: String): SubagentInterruptResult {
        val result = request("subagent.interrupt", buildJsonObject {
            put("subagent_id", boundedRpcInput(subagentId, HERMES_CHAT_MAX_EVENT_ID_CHARS, "subagent ID"))
        })
        return SubagentInterruptResult(
            found = result.booleanValue("found") ?: false,
            subagentId = result.stringValue("subagent_id")?.take(HERMES_CHAT_MAX_EVENT_ID_CHARS),
        )
    }

    override suspend fun steerSubagent(runtimeSessionId: RuntimeSessionId, subagentId: String, text: String): SubagentSteerResult {
        val result = request("subagent.steer", buildJsonObject {
            put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
            put("subagent_id", boundedRpcInput(subagentId, HERMES_CHAT_MAX_EVENT_ID_CHARS, "subagent ID"))
            put("text", boundedRpcInput(text, HERMES_CHAT_MAX_EVENT_TEXT_CHARS, "steer text"))
        })
        val status = result.stringValue("status")
            ?.takeIf { it == "queued" || it == "rejected" }
            ?: throw HermesChatProtocolException("Subagent steer response was incomplete")
        return SubagentSteerResult(status, result.stringValue("text")?.take(HERMES_CHAT_MAX_EVENT_TEXT_CHARS))
    }

    override suspend fun loadCronJobs(): List<CronJob> = loadCronJobsForProfile("default")

    override suspend fun loadCronJobsForProfile(profile: String): List<CronJob> {
        val boundedProfile = profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesChatProtocolException("Cron profile is invalid")
        val result = request("cron.manage", buildJsonObject {
            put("action", "list")
            put("include_disabled", true)
            put("profile", boundedProfile)
        })
        return parseCronJobs(result)
    }

    override suspend fun manageCronJob(jobId: String, action: CronJobAction) {
        // The gateway resolves jobs by ID or name through the `name` param.
        request("cron.manage", buildJsonObject {
            put("action", action.wireValue)
            put("name", boundedRpcInput(jobId, HERMES_CHAT_MAX_EVENT_ID_CHARS, "cron job ID"))
        })
    }

    private fun sessionParams(runtimeSessionId: RuntimeSessionId): JsonObject = buildJsonObject {
        put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
    }

    override suspend fun respondToClarification(
        requestId: String,
        answer: String,
    ): HermesChatResponse = respondToClarification(requestId, questionId = null, answer = answer)

    override suspend fun respondToClarification(
        requestId: String,
        questionId: String?,
        answer: String,
    ): HermesChatResponse {
        val params = buildJsonObject {
            put("request_id", boundedRpcInput(requestId, HERMES_CHAT_MAX_EVENT_ID_CHARS, "request ID"))
            questionId?.let { put("question_id", boundedRpcInput(it, HERMES_CHAT_MAX_EVENT_ID_CHARS, "question ID")) }
            put("answer", boundedRpcInput(answer, HERMES_CHAT_MAX_EVENT_TEXT_CHARS, "answer", allowBlank = true))
        }
        return parseInteractionResponse(request("clarify.respond", params))
    }

    override suspend fun respondToApproval(
        runtimeSessionId: RuntimeSessionId,
        choice: String,
        all: Boolean,
        requestId: String?,
    ): HermesChatResponse {
        val boundedChoice = boundedRpcInput(choice, HERMES_CHAT_MAX_EVENT_CHOICE_CHARS, "approval choice")
        val sessionKey = boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID")
        val boundedRequestId = requestId?.let {
            boundedRpcInput(it, HERMES_CHAT_MAX_EVENT_ID_CHARS, "request ID")
        }
        synchronized(interactionLock) {
            val queue = pendingApprovals[sessionKey]
            val pending = if (boundedRequestId == null) {
                queue?.peekLast()
            } else {
                queue?.firstOrNull { it.requestId == boundedRequestId }
            }
                ?: throw HermesChatProtocolException("No pending approval choices for this session")
            if (boundedChoice !in pending.choices) {
                throw HermesChatProtocolException("Approval choice was not advertised")
            }
        }
        val params = buildJsonObject {
            put("session_id", sessionKey)
            boundedRequestId?.let { put("request_id", it) }
            put("choice", boundedChoice)
            put("all", all)
        }
        val response = parseInteractionResponse(request("approval.respond", params))
        val nextApproval = synchronized(interactionLock) {
            val queue = pendingApprovals[sessionKey]
            if (response.status in setOf(
                    HermesChatResponseStatus.Ok,
                    HermesChatResponseStatus.Resolved,
                    HermesChatResponseStatus.Expired,
                )
            ) {
                when {
                    queue == null -> Unit
                    all -> queue.clear()
                    boundedRequestId != null -> queue.removeIf { it.requestId == boundedRequestId }
                    queue.isNotEmpty() -> queue.removeLast()
                }
            }
            if (queue == null || queue.isEmpty()) pendingApprovals.remove(sessionKey)
            queue?.peekLast()?.toEvent(runtimeSessionId)
        }
        return response.copy(nextApproval = nextApproval)
    }

    override suspend fun respondToBlockingPrompt(
        kind: UnsupportedBlockingKind,
        requestId: String,
        value: String,
    ): HermesChatResponse {
        val (method, valueKey) = when (kind) {
            UnsupportedBlockingKind.Secret -> "secret.respond" to "value"
            UnsupportedBlockingKind.Sudo -> "sudo.respond" to "password"
            UnsupportedBlockingKind.TerminalRead -> "terminal.read.respond" to "text"
            UnsupportedBlockingKind.PreviewRead -> "preview.read.respond" to "text"
            UnsupportedBlockingKind.WindowRead -> "window.read.respond" to "text"
        }
        val params = buildJsonObject {
            put("request_id", boundedRpcInput(requestId, HERMES_CHAT_MAX_EVENT_ID_CHARS, "request ID"))
            put(valueKey, boundedRpcInput(value, HERMES_CHAT_MAX_EVENT_TEXT_CHARS, "response", allowBlank = true))
        }
        return parseInteractionResponse(request(method, params))
    }

    override suspend fun interruptSession(
        runtimeSessionId: RuntimeSessionId,
    ): HermesChatResponse {
        val params = buildJsonObject {
            put(
                "session_id",
                boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"),
            )
        }
        return parseInteractionResponse(request("session.interrupt", params))
    }

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        val params = buildJsonObject {
            put("close_on_disconnect", false)
            profile?.let { put("profile", it) }
            validProjectWorkspacePath(workspacePath)?.let { put("cwd", it) }
        }
        val result = request("session.create", params)
        val runtimeSessionId = result.stringValue("session_id")?.let {
            runCatching { RuntimeSessionId(it) }.getOrNull()
        } ?: throw HermesChatProtocolException("Create response was incomplete")
        val stored = result.stringValue("stored_session_id")
            ?.takeIf(String::isNotBlank)
            ?.let(::DurableSessionId)
        return ResumedChatSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = stored ?: durableSessionId,
            resumed = false,
            messages = emptyList(),
            running = false,
            inflight = null,
        )
    }

    override suspend fun attachFile(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        mimeType: String,
        base64Content: String,
    ): String {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId.value)
            put("path", filename)
            put("name", filename)
            put("data_url", "data:$mimeType;base64,$base64Content")
        }
        val result = request("file.attach", params)
        return result.stringValue("ref_text")
            ?.takeIf(String::isNotBlank)
            ?: throw HermesChatProtocolException("File attach response was incomplete")
    }

    override suspend fun attachImage(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        base64Content: String,
    ) {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId.value)
            put("filename", filename)
            put("content_base64", base64Content)
        }
        request("image.attach_bytes", params)
    }

    override suspend fun completeSlash(text: String): SlashCompletionResult {
        val params = buildJsonObject { put("text", text) }
        val result = request("complete.slash", params)
        val decoded = decodeShared { RpcResultDecoder.slashCompletion(result.toString(), inputLength = text.length) }
        return SlashCompletionResult(
            items = decoded.items.map { SlashCompletionItem(text = it.text, display = it.display, meta = it.meta) },
            replaceFrom = decoded.replaceFrom,
        )
    }

    override suspend fun loadModelOptions(runtimeSessionId: RuntimeSessionId): ModelOptions {
        val params = buildJsonObject {
            put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
            put("explicit_only", true)
            put("include_unconfigured", false)
        }
        return parseModelOptions(request("model.options", params))
    }

    override suspend fun loadProfileModelOptions(): ModelOptions = parseModelOptions(
        request(
            "model.options",
            buildJsonObject {
                put("explicit_only", true)
                put("include_unconfigured", false)
            },
        ),
    )

    override suspend fun setModel(
        runtimeSessionId: RuntimeSessionId,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean,
    ): ModelSwitchResult {
        val boundedProvider = boundedModelInput(provider, MAX_MODEL_PROVIDER_CHARS, "model provider")
        val boundedModel = boundedModelInput(model, MAX_MODEL_ID_CHARS, "model ID")
        val params = buildJsonObject {
            put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
            put("key", "model")
            put("value", "$boundedModel --provider $boundedProvider --session")
            put("confirm_expensive_model", confirmExpensiveModel)
        }
        val result = request("config.set", params)
        val scope = result.stringValue("scope")
        if (scope != null && scope != "session") {
            throw HermesChatProtocolException("Hermes model switch returned an unsafe scope")
        }
        val confirmationRequired = result.booleanValue("confirm_required") == true
        return ModelSwitchResult(
            accepted = !confirmationRequired,
            deferred = result.booleanValue("deferred") == true,
            confirmationRequired = confirmationRequired,
            confirmationMessage = result.stringValue("confirm_message")
                ?.trim()
                ?.take(1_000)
                ?.takeIf(String::isNotEmpty),
        )
    }

    override suspend fun setReasoning(
        runtimeSessionId: RuntimeSessionId,
        effort: String,
    ) {
        val canonicalEffort = canonicalReasoningEffort(effort)
            ?: throw HermesChatProtocolException("Reasoning effort is invalid")
        val params = buildJsonObject {
            put(
                "session_id",
                boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"),
            )
            put("key", "reasoning")
            put("value", canonicalEffort)
        }
        val result = request("config.set", params)
        val scope = result.stringValue("scope")
        if (scope != null && scope != "session") {
            throw HermesChatProtocolException("Hermes reasoning switch returned an unsafe scope")
        }
        val key = result.stringValue("key")
        if (key != null && key != "reasoning") {
            throw HermesChatProtocolException("Hermes reasoning switch returned the wrong key")
        }
    }

    override suspend fun setFast(
        runtimeSessionId: RuntimeSessionId,
        fast: Boolean,
    ) {
        val params = buildJsonObject {
            put(
                "session_id",
                boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"),
            )
            put("key", "fast")
            put("value", if (fast) "fast" else "normal")
        }
        val result = request("config.set", params)
        val scope = result.stringValue("scope")
        if (scope != null && scope != "session") {
            throw HermesChatProtocolException("Hermes fast switch returned an unsafe scope")
        }
        val key = result.stringValue("key")
        if (key != null && key != "fast") {
            throw HermesChatProtocolException("Hermes fast switch returned the wrong key")
        }
        val value = result.stringValue("value")
        if (value != null && value != "fast" && value != "normal") {
            throw HermesChatProtocolException("Hermes fast switch returned an unsafe value")
        }
    }

    override suspend fun close() {
        if (!markClosed()) return
        connectionJob.cancel()
        failPending(HermesChatTransportException("Hermes chat connection closed"))
        eventChannel.close()
        runCatching { socket.close() }
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        val id = nextRequestId.getAndIncrement()
        val deferred = kotlinx.coroutines.CompletableDeferred<JsonObject>()
        synchronized(lifecycleLock) {
            if (closed.get()) {
                throw HermesChatTransportException("Hermes chat connection is closed")
            }
            pendingRequests[id] = deferred
            pendingRequestMethods[id] = method
        }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        try {
            ensureFrameSize(frame)
            socket.sendText(frame)
            return deferred.await()
        } catch (error: CancellationException) {
            throw error
        } catch (error: HermesChatException) {
            throw error
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not send Hermes chat request", error)
        } finally {
            pendingRequests.remove(id, deferred)
            pendingRequestMethods.remove(id)
        }
    }

    private suspend fun readLoop() {
        try {
            while (connectionScope.isActive) {
                val frame = socket.receiveText() ?: break
                ensureFrameSize(frame)
                handleFrame(frame)
            }
            if (!closed.get()) {
                failPending(HermesChatTransportException("Hermes chat connection closed by peer"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HermesChatException) {
            failPending(error)
        } catch (error: Exception) {
            failPending(HermesChatTransportException("Hermes chat receive failed", error))
        } finally {
            markClosed()
            failPending(HermesChatTransportException("Hermes chat connection closed"))
            eventChannel.close()
            runCatching { socket.close() }
        }
    }

    private fun handleFrame(frame: String) {
        val message = try {
            json.parseToJsonElement(frame).jsonObject
        } catch (error: Exception) {
            // A syntactically malformed frame could be the only response to an
            // outstanding RPC. Fail the connection so pending callers cannot wait
            // forever; forward compatibility is handled by ignoring unknown,
            // well-formed event types below.
            throw HermesChatProtocolException("Hermes chat frame was invalid", error)
        }
        if (message.stringValue("jsonrpc") != "2.0") return

        if (message.stringValue("method") == "event") {
            handleSharedEvent(message)
            return
        }

        val id = message.longValue("id") ?: return
        val deferred = pendingRequests.remove(id) ?: return
        val method = pendingRequestMethods.remove(id).orEmpty()
        val error = message["error"] as? JsonObject
        if (error != null) {
            val code = error.longValue("code")
            if (code == -32601L) {
                deferred.completeExceptionally(HermesChatMethodNotFoundException(method))
                return
            }
            val suffix = code?.let { " ($it)" }.orEmpty()
            deferred.completeExceptionally(
                HermesChatProtocolException("Hermes RPC request failed$suffix"),
            )
            return
        }
        val result = message["result"] as? JsonObject
        if (result == null) {
            deferred.completeExceptionally(HermesChatProtocolException("Hermes response was incomplete"))
        } else {
            deferred.complete(result)
        }
    }

    private fun handleSharedEvent(message: JsonObject) {
        val params = message["params"] as? JsonObject ?: return
        val sessionId = params.boundedRequired("session_id", ChatEventDecoder.MAX_EVENT_ID_CHARS)
            ?: return
        val type = params.stringValue("type") ?: return
        val payloadElement = params["payload"] ?: return
        (payloadElement as? JsonObject)?.let { payload ->
            decodeBackgroundTaskEvent(type, sessionId, payload)?.let { event ->
                val eventId = params.boundedRequired("relay_event_id", 512)
                    ?: params.boundedRequired("event_id", 512)
                    ?: params.longValue("seq")?.let { "$sessionId:$it" }
                eventChannel.trySend(event.copy(eventId = eventId, historical = params.booleanValue("relay_replay") == true))
                return
            }
        }
        val shared = ChatEventDecoder.decode(type, sessionId, payloadElement.toString()) ?: return
        val payload = payloadElement as? JsonObject
        val todos = if (shared is com.unsupportedpastels.mercury.core.transcript.ChatEvent.ToolStart ||
            shared is com.unsupportedpastels.mercury.core.transcript.ChatEvent.ToolComplete
        ) {
            payload?.boundedTodoItems()
        } else {
            null
        }
        val event = shared.toAndroidEvent(todos) ?: return

        when (event) {
            is HermesChatEvent.ApprovalRequest -> synchronized(interactionLock) {
                pendingApprovals.getOrPut(event.sessionId.value) { ArrayDeque() }
                    .addLast(
                        PendingApproval(
                            requestId = event.requestId,
                            command = event.command,
                            description = event.description,
                            choices = event.choices,
                        ),
                    )
            }
            is HermesChatEvent.ApprovalExpire -> synchronized(interactionLock) {
                pendingApprovals[event.sessionId.value]?.let { queue ->
                    queue.removeIf { it.requestId == event.requestId }
                    if (queue.isEmpty()) pendingApprovals.remove(event.sessionId.value)
                }
            }
            else -> Unit
        }
        eventChannel.trySend(event)
    }

    private fun ensureFrameSize(frame: String) {
        if (frame.toByteArray(StandardCharsets.UTF_8).size > maxFrameBytes) {
            throw HermesChatProtocolException("Hermes chat frame exceeds the size limit")
        }
    }

    private fun markClosed(): Boolean = synchronized(lifecycleLock) {
        closed.compareAndSet(false, true)
    }

    private fun failPending(error: HermesChatException) {
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
        pendingRequestMethods.clear()
    }
}
