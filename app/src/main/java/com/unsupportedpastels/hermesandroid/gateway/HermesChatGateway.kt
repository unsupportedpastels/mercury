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
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.hermesandroid.app.RunTodoStatus
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.HermesCredential
import com.unsupportedpastels.hermesandroid.connection.readBodyTextBounded
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.url
import io.ktor.http.isSuccess
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
private const val MAX_TICKET_RESPONSE_BYTES = 16 * 1024
private const val MAX_EVENT_BUFFER = 128
internal const val HERMES_CHAT_MAX_EVENT_ID_CHARS = 256
internal const val HERMES_CHAT_MAX_EVENT_NAME_CHARS = 256
internal const val HERMES_CHAT_MAX_EVENT_TEXT_CHARS = 4_096
internal const val HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS = 1024 * 1024
internal const val HERMES_CHAT_MAX_EVENT_CONTEXT_CHARS = 4_096
internal const val HERMES_CHAT_MAX_EVENT_CHOICE_CHARS = 256
internal const val HERMES_CHAT_MAX_EVENT_CHOICES = 32
const val DEFAULT_PROJECT_PREVIEW_LIMIT = 3
const val DEFAULT_PROJECT_SESSION_LIMIT = 500
private const val MAX_PROJECT_PREVIEW_LIMIT = 3
private const val MAX_PROJECT_SESSION_LIMIT = 500
private const val MAX_MODEL_PROVIDERS = 64
private const val MAX_MODELS_PER_PROVIDER = 512
private const val MAX_MODEL_PROVIDER_CHARS = 128
private const val MAX_MODEL_ID_CHARS = 512

/** A fresh, single-use ticket returned by /api/auth/ws-ticket. */
data class WsTicket(
    val ticket: String,
    val ttlSeconds: Long,
) {
    init {
        require(ticket.isNotBlank()) { "Hermes WebSocket ticket must not be blank" }
        require(ttlSeconds > 0) { "Hermes WebSocket ticket TTL must be positive" }
    }
}

interface WsTicketClient {
    suspend fun mintTicket(
        origin: ServerOrigin,
        credential: HermesCredential.NativeBearer,
    ): WsTicket
}

interface HermesChatSocket {
    suspend fun sendText(text: String)

    /** Returns null when the peer has closed the WebSocket. */
    suspend fun receiveText(): String?

    /**
     * The application close code the peer sent, read once [receiveText] has
     * returned null. Null when the socket dropped without a close frame, which
     * [classifySocketClose] treats as a transport failure rather than a
     * credential rejection.
     */
    suspend fun closeCode(): Int? = null

    suspend fun close()
}

interface ChatWebSocketFactory {
    suspend fun connect(url: String): HermesChatSocket
}

open class HermesChatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

open class HermesChatProtocolException(
    message: String,
    cause: Throwable? = null,
) : HermesChatException(message, cause)

class HermesChatMethodNotFoundException(
    val method: String,
) : HermesChatProtocolException("Hermes method is not supported: $method")

open class HermesChatTransportException(
    message: String,
    cause: Throwable? = null,
) : HermesChatException(message, cause)

/**
 * The peer closed the chat socket. A close is always a transport-level failure,
 * so every existing transport handler keeps working unchanged; [closeClass]
 * additionally says whether the credential was rejected, which is the only case
 * that may drive credential recovery.
 */
class HermesChatSocketClosedException(
    val closeClass: SocketCloseClass,
    val closeCode: Int?,
) : HermesChatTransportException("Hermes chat connection closed by peer")

data class ResumedChatSession(
    val runtimeSessionId: RuntimeSessionId,
    val durableSessionId: DurableSessionId?,
    val resumed: Boolean,
    val messages: List<JsonObject>,
    val running: Boolean,
    val inflight: InflightPrompt?,
    val model: String? = null,
    val provider: String? = null,
    val reasoningEffort: String? = null,
)

data class InflightPrompt(
    val user: String?,
    val assistant: String?,
    val streaming: Boolean,
)

data class PromptSubmission(
    val status: String,
)

enum class HermesChatResponseStatus {
    Ok,
    Expired,
    Interrupted,
    Resolved,
    Unknown,
    ;

    companion object {
        fun fromWire(value: String?): HermesChatResponseStatus = when (value?.trim()?.lowercase()) {
            "ok" -> Ok
            "expired" -> Expired
            "interrupted" -> Interrupted
            "resolved" -> Resolved
            else -> Unknown
        }
    }
}

data class HermesChatResponse(
    val status: HermesChatResponseStatus,
    val nextApproval: HermesChatEvent.ApprovalRequest? = null,
)

/**
 * One row of a `complete.slash` result. [text] is inserted into the composer;
 * [display] and [meta] are presentation only. Defined here (not in the UI layer)
 * so the chat transport owns its own result type.
 */
data class SlashCompletionItem(
    val text: String,
    val display: String = "/$text",
    val meta: String? = null,
)

data class HostDirectoryEntry(
    val name: String,
    val path: String,
)

data class HostDirectoryListing(
    val path: String,
    val directories: List<HostDirectoryEntry>,
    val parentPath: String? = null,
    val lockedRoot: String? = null,
    val canChangePath: Boolean = true,
)

/** Tolerantly parsed `complete.slash` JSON-RPC result. */
data class SlashCompletionResult(
    val items: List<SlashCompletionItem>,
    val replaceFrom: Int,
)

data class ModelSelection(
    val provider: String,
    val model: String,
)

/** Explicit per-model capabilities. A null field means the server did not advertise it. */
data class ModelCapabilities(
    val fast: Boolean? = null,
    val reasoning: Boolean? = null,
) {
    val hasExplicitCapability: Boolean
        get() = fast != null || reasoning != null
}

data class ModelProviderOption(
    val slug: String,
    val name: String,
    val models: List<String>,
    /** Explicit capabilities keyed by the exact model identifier; absent fields are unavailable. */
    val capabilities: Map<String, ModelCapabilities> = emptyMap(),
)

data class ModelOptions(
    val current: ModelSelection?,
    val providers: List<ModelProviderOption>,
    /** Non-null only for profile-scoped REST model options responses. */
    val profile: String? = null,
) {
    fun capabilitiesFor(selection: ModelSelection?): ModelCapabilities? = selection?.let { wanted ->
        providers.firstOrNull { it.slug == wanted.provider }?.capabilities?.get(wanted.model)
    }
}

/** Profile-scoped metadata for the one model currently effective on the host. */
data class CurrentModelInfo(
    val profile: String,
    val model: String?,
    val provider: String?,
    val effectiveContextLength: Int?,
    val capabilities: ModelCapabilities,
)

internal fun parseExplicitModelCapabilities(element: kotlinx.serialization.json.JsonElement?): ModelCapabilities {
    val objectValue = element as? JsonObject ?: return ModelCapabilities()
    fun booleanField(name: String): Boolean? =
        (objectValue[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.booleanOrNull
    return ModelCapabilities(
        fast = booleanField("fast"),
        reasoning = booleanField("reasoning"),
    )
}

internal fun parseModelCapabilities(element: kotlinx.serialization.json.JsonElement?): Map<String, ModelCapabilities> {
    val objectValue = element as? JsonObject ?: return emptyMap()
    return objectValue.entries.asSequence()
        .take(MAX_MODELS_PER_PROVIDER)
        .mapNotNull { (rawModel, rawCapabilities) ->
            val model = rawModel.trim().takeIf {
                it.isNotEmpty() && it.length <= MAX_MODEL_ID_CHARS && !it.hasControlCharacters()
            } ?: return@mapNotNull null
            val capabilities = parseExplicitModelCapabilities(rawCapabilities)
            model to capabilities
        }
        .toMap()
}

data class ModelSwitchResult(
    val accepted: Boolean,
    val deferred: Boolean = false,
    val confirmationRequired: Boolean = false,
    val confirmationMessage: String? = null,
)

val ValidReasoningEfforts = setOf(
    "none",
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
    "ultra",
)

fun canonicalReasoningEffort(value: String): String? = value
    .trim()
    .lowercase()
    .takeIf(ValidReasoningEfforts::contains)

interface HermesChatEvent {
    val sessionId: RuntimeSessionId

    data class MessageStart(
        override val sessionId: RuntimeSessionId,
        val text: String?,
    ) : HermesChatEvent

    data class MessageDelta(
        override val sessionId: RuntimeSessionId,
        val text: String,
    ) : HermesChatEvent

    data class MessageComplete(
        override val sessionId: RuntimeSessionId,
        val text: String?,
        val status: String?,
        val error: String? = null,
        val reasoning: String? = null,
        val warning: String? = null,
        val failureReason: String? = null,
        val recoverable: Boolean = false,
        val billing: BillingInfo? = null,
    ) : HermesChatEvent

    /** Structured billing-wall descriptor from `message.complete`. */
    data class BillingInfo(
        val provider: String?,
        val billingUrl: String?,
        val isNous: Boolean,
        val message: String?,
    )

    /** Reasoning text; `replace` is true for authoritative `reasoning.available` snapshots. */
    data class ReasoningDelta(
        override val sessionId: RuntimeSessionId,
        val text: String,
        val replace: Boolean = false,
    ) : HermesChatEvent

    /** Interim assistant commentary sealed as its own segment before tool calls. */
    data class MessageInterim(
        override val sessionId: RuntimeSessionId,
        val text: String,
        val alreadyStreamed: Boolean,
    ) : HermesChatEvent

    /** The model is generating arguments for a tool. */
    data class ToolGenerating(
        override val sessionId: RuntimeSessionId,
        val name: String,
    ) : HermesChatEvent

    /** Live session title rename pushed by the server. */
    data class SessionTitle(
        override val sessionId: RuntimeSessionId,
        val title: String,
    ) : HermesChatEvent

    /**
     * Tolerant runtime metadata patch (`session.info`). Only the fields HAM
     * surfaces are decoded; unknown/additive fields are ignored.
     */
    data class SessionInfo(
        override val sessionId: RuntimeSessionId,
        val storedSessionId: DurableSessionId? = null,
        val model: String? = null,
        val provider: String? = null,
        val reasoningEffort: String? = null,
        val title: String? = null,
        val running: Boolean? = null,
    ) : HermesChatEvent

    data class Error(
        override val sessionId: RuntimeSessionId,
        val message: String,
    ) : HermesChatEvent

    data class ToolStart(
        override val sessionId: RuntimeSessionId,
        val toolId: String,
        val name: String,
        val context: String?,
        val todos: List<RunTodoItem>? = null,
    ) : HermesChatEvent

    data class ToolComplete(
        override val sessionId: RuntimeSessionId,
        val toolId: String,
        val name: String,
        val summary: String?,
        val todos: List<RunTodoItem>? = null,
    ) : HermesChatEvent

    data class StatusUpdate(
        override val sessionId: RuntimeSessionId,
        val kind: String,
        val text: String,
    ) : HermesChatEvent

    data class ClarifyRequest(
        override val sessionId: RuntimeSessionId,
        val requestId: String,
        val question: String,
        val choices: List<String>,
        val multiSelect: Boolean,
    ) : HermesChatEvent

    data class ClarifyExpire(
        override val sessionId: RuntimeSessionId,
        val requestId: String,
    ) : HermesChatEvent

    data class ApprovalRequest(
        override val sessionId: RuntimeSessionId,
        val requestId: String?,
        val command: String?,
        val description: String?,
        val choices: List<String>,
    ) : HermesChatEvent

    data class ApprovalExpire(
        override val sessionId: RuntimeSessionId,
        val requestId: String,
    ) : HermesChatEvent

    data class UnsupportedBlockingRequest(
        override val sessionId: RuntimeSessionId,
        val kind: UnsupportedBlockingKind,
        val requestId: String,
        val prompt: String?,
    ) : HermesChatEvent

    data class UnsupportedBlockingExpire(
        override val sessionId: RuntimeSessionId,
        val kind: UnsupportedBlockingKind,
        val requestId: String,
    ) : HermesChatEvent
}

enum class UnsupportedBlockingKind {
    Secret,
    Sudo,
    TerminalRead,
    PreviewRead,
    WindowRead,
}

fun interface HermesChatConnector {
    suspend fun connect(origin: ServerOrigin, credential: HermesCredential): HermesChatSession
}

interface HermesChatSession {
    val events: Flow<HermesChatEvent>

    /**
     * How the peer closed this session, or null while it is still open and when
     * this client closed it. Read after the event stream ends to decide whether
     * the credential was rejected; a locally closed session never reports one,
     * so our own teardown can never be mistaken for a rejection.
     */
    val closeClass: SocketCloseClass?
        get() = null

    suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String? = null,
    ): ResumedChatSession

    /** Read-only project metadata; this never resumes or creates a runtime. */
    suspend fun loadProjectTree(
        profile: String? = null,
        previewLimit: Int = DEFAULT_PROJECT_PREVIEW_LIMIT,
        sessionLimit: Int = DEFAULT_PROJECT_SESSION_LIMIT,
    ): ProjectTreeResult = throw HermesChatMethodNotFoundException("projects.tree")

    /** Read-only durable sessions for one project; this never activates a runtime. */
    suspend fun loadProjectSessions(
        projectId: ProjectId,
        profile: String? = null,
        sessionLimit: Int = DEFAULT_PROJECT_SESSION_LIMIT,
    ): ProjectSessionsResult = throw HermesChatMethodNotFoundException("projects.project_sessions")

    /** Process-local delegated children from the authoritative gateway registry. */
    suspend fun loadDelegationStatus(): DelegationStatus =
        throw HermesChatMethodNotFoundException("delegation.status")

    /** Session-scoped background processes owned by this exact runtime. */
    suspend fun loadProcessList(runtimeSessionId: RuntimeSessionId): List<ProcessRow> =
        throw HermesChatMethodNotFoundException("process.list")

    /** Creates and activates a project rooted at an existing host directory. */
    suspend fun createProject(
        name: String,
        path: String,
        profile: String? = null,
    ): ProjectSummary = throw HermesChatMethodNotFoundException("projects.create")

    /**
     * Creates a fresh runtime (gateway `session.create`). The server persists the
     * durable row lazily on the first prompt; [durableSessionId] is the client-side
     * draft identity used until that row exists.
     */
    suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String? = null,
        workspacePath: String? = null,
    ): ResumedChatSession = throw HermesChatProtocolException("Session creation is not available")

    suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission

    /**
     * Submits a prompt with the voice barge-in annotation. The released gateway
     * latches `params.interrupted` so the turn's model message carries the
     * interruption note; it is sent only when true, so older servers see an
     * unchanged request. Implementations without the live transport just drop
     * the flag.
     */
    suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
        interrupted: Boolean,
    ): PromptSubmission = submitPrompt(runtimeSessionId, text)

    /** Adds bounded steering text to the currently running turn. */
    suspend fun steer(runtimeSessionId: RuntimeSessionId, text: String): SessionSteerResult =
        throw HermesChatMethodNotFoundException("session.steer")

    suspend fun loadSessionUsage(runtimeSessionId: RuntimeSessionId): SessionUsage =
        throw HermesChatMethodNotFoundException("session.usage")

    suspend fun loadContextBreakdown(runtimeSessionId: RuntimeSessionId): SessionContextBreakdown =
        throw HermesChatMethodNotFoundException("session.context_breakdown")

    suspend fun compressSession(runtimeSessionId: RuntimeSessionId, focusTopic: String? = null): SessionCompressResult =
        throw HermesChatMethodNotFoundException("session.compress")

    suspend fun undoSession(runtimeSessionId: RuntimeSessionId): SessionUndoResult =
        throw HermesChatMethodNotFoundException("session.undo")

    suspend fun branchSession(
        runtimeSessionId: RuntimeSessionId,
        count: Int? = null,
        name: String? = null,
    ): SessionBranchResult = throw HermesChatMethodNotFoundException("session.branch")

    suspend fun pauseDelegation(paused: Boolean): DelegationPauseResult =
        throw HermesChatMethodNotFoundException("delegation.pause")

    suspend fun interruptSubagent(subagentId: String): SubagentInterruptResult =
        throw HermesChatMethodNotFoundException("subagent.interrupt")

    suspend fun steerSubagent(
        runtimeSessionId: RuntimeSessionId,
        subagentId: String,
        text: String,
    ): SubagentSteerResult = throw HermesChatMethodNotFoundException("subagent.steer")

    /** include_disabled is intentionally true so paused jobs are visible. */
    suspend fun loadCronJobs(): List<CronJob> =
        throw HermesChatMethodNotFoundException("cron.manage")

    /** Profile-aware overload; legacy fakes and gateways remain source-compatible. */
    suspend fun loadCronJobsForProfile(profile: String): List<CronJob> = loadCronJobs()

    suspend fun manageCronJob(jobId: String, action: CronJobAction): Unit =
        throw HermesChatMethodNotFoundException("cron.manage")

    suspend fun respondToClarification(
        requestId: String,
        answer: String,
    ): HermesChatResponse = throw HermesChatProtocolException("Clarification response is not available")

    suspend fun respondToApproval(
        runtimeSessionId: RuntimeSessionId,
        choice: String,
        all: Boolean = false,
        requestId: String? = null,
    ): HermesChatResponse = throw HermesChatProtocolException("Approval response is not available")

    suspend fun respondToBlockingPrompt(
        kind: UnsupportedBlockingKind,
        requestId: String,
        value: String,
    ): HermesChatResponse = throw HermesChatProtocolException("Blocking prompt response is not available")

    suspend fun interruptSession(
        runtimeSessionId: RuntimeSessionId,
    ): HermesChatResponse = throw HermesChatProtocolException("Session interrupt is not available")

    /**
     * Stage a non-image file on the remote host via `file.attach` and return its
     * `@file:` ref text to prepend to the submitted prompt.
     */
    suspend fun attachFile(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        mimeType: String,
        base64Content: String,
    ): String = throw HermesChatProtocolException("File attachment is not available")

    /** Stage an image on the remote host via `image.attach_bytes`; it rides the next prompt. */
    suspend fun attachImage(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        base64Content: String,
    ): Unit = throw HermesChatProtocolException("Image attachment is not available")

    /** Live slash-command completion from the connected host; never a static local list. */
    suspend fun completeSlash(text: String): SlashCompletionResult =
        throw HermesChatProtocolException("Slash completion is not available")

    suspend fun loadModelOptions(runtimeSessionId: RuntimeSessionId): ModelOptions =
        throw HermesChatProtocolException("Model selection is not available")

    suspend fun setModel(
        runtimeSessionId: RuntimeSessionId,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean = false,
    ): ModelSwitchResult = throw HermesChatProtocolException("Model selection is not available")

    suspend fun setReasoning(
        runtimeSessionId: RuntimeSessionId,
        effort: String,
    ): Unit = throw HermesChatProtocolException("Reasoning selection is not available")

    suspend fun setFast(
        runtimeSessionId: RuntimeSessionId,
        fast: Boolean,
    ): Unit = throw HermesChatProtocolException("Fast mode selection is not available")

    suspend fun close()
}

/**
 * Ticketed JSON-RPC chat transport. The access token is consumed only by the ticket client;
 * the WebSocket factory receives a URL containing only the fresh single-use ticket.
 */
class HermesChatGateway(
    private val origin: ServerOrigin,
    private val credential: HermesCredential,
    private val ticketClient: WsTicketClient,
    private val socketFactory: ChatWebSocketFactory,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    private val parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    init {
        require(maxFrameBytes in 1..MAX_CONFIGURED_FRAME_BYTES) {
            "Hermes frame limit is out of bounds"
        }
    }

    suspend fun connect(): HermesChatConnection {
        val socketUrl = when (val current = credential) {
            is HermesCredential.NativeBearer -> {
                val ticket = ticketClient.mintTicket(origin, current)
                ticketWebSocketUrl(origin, ticket.ticket)
            }
            is HermesCredential.LoopbackSession ->
                "${origin.webSocketValue}/api/ws?token=${current.encodedWebSocketToken(origin)}"
            HermesCredential.None -> "${origin.webSocketValue}/api/ws"
        }
        val socket = try {
            socketFactory.connect(socketUrl)
        } catch (_: CancellationException) {
            throw CancellationException("Hermes chat connection cancelled")
        } catch (_: Exception) {
            // The factory has seen a signed URL. Never retain its arbitrary
            // exception/cause chain because it may echo that URL verbatim.
            throw HermesChatTransportException("Could not connect to Hermes chat")
        }
        return HermesChatConnection(
            socket = socket,
            maxFrameBytes = maxFrameBytes,
            parentScope = parentScope,
        )
    }

    private fun ticketWebSocketUrl(origin: ServerOrigin, ticket: String): String {
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
) : HermesChatSession {
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
    private val nextRequestId = AtomicLong(1)
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

    @Volatile
    private var peerCloseClass: SocketCloseClass? = null

    override val closeClass: SocketCloseClass?
        get() = peerCloseClass

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
    ): PromptSubmission {
        val params = buildJsonObject {
            put("session_id", runtimeSessionId.value)
            put("text", text)
            if (interrupted) put("interrupted", true)
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
    ): HermesChatResponse {
        val params = buildJsonObject {
            put("request_id", boundedRpcInput(requestId, HERMES_CHAT_MAX_EVENT_ID_CHARS, "request ID"))
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

    private fun parseInteractionResponse(result: JsonObject): HermesChatResponse {
        val wireStatus = result.stringValue("status")
            ?: result.booleanValue("resolved")?.let { resolved -> if (resolved) "ok" else "expired" }
            ?: result.longValue("resolved")?.let { resolved -> if (resolved > 0) "ok" else "expired" }
            ?: throw HermesChatProtocolException("Hermes interaction response was incomplete")
        return HermesChatResponse(HermesChatResponseStatus.fromWire(wireStatus))
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
        val rawItems = result["items"] as? JsonArray
        val items = rawItems.orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val itemText = row.stringValue("text")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val display = row.stringValue("display")
                ?.takeIf(String::isNotBlank)
                ?: "/$itemText"
            val meta = row.stringValue("meta")?.takeIf(String::isNotBlank)
            SlashCompletionItem(text = itemText, display = display, meta = meta)
        }
        val replaceFrom = result.longValue("replace_from")?.toInt() ?: 0
        return SlashCompletionResult(items = items, replaceFrom = replaceFrom)
    }

    override suspend fun loadModelOptions(runtimeSessionId: RuntimeSessionId): ModelOptions {
        val params = buildJsonObject {
            put("session_id", boundedRpcInput(runtimeSessionId.value, HERMES_CHAT_MAX_EVENT_ID_CHARS, "runtime session ID"))
            put("explicit_only", true)
            put("include_unconfigured", false)
        }
        val result = request("model.options", params)
        val providers = (result["providers"] as? JsonArray)
            .orEmpty()
            .take(MAX_MODEL_PROVIDERS)
            .mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                if (row.booleanValue("authenticated") == false) return@mapNotNull null
                val slug = row.boundedModelField("slug", MAX_MODEL_PROVIDER_CHARS)
                    ?.takeIf { it.none(Char::isWhitespace) && !it.startsWith('-') }
                    ?: return@mapNotNull null
                val name = row.boundedModelField("name", MAX_MODEL_PROVIDER_CHARS) ?: slug
                val capabilities = parseModelCapabilities(row["capabilities"])
                val seen = linkedSetOf<String>()
                val models = (row["models"] as? JsonArray)
                    .orEmpty()
                    .take(MAX_MODELS_PER_PROVIDER)
                    .mapNotNull { modelElement ->
                        (modelElement as? JsonPrimitive)
                            ?.contentOrNull
                            ?.trim()
                            ?.takeIf {
                                it.isNotEmpty() &&
                                    it.length <= MAX_MODEL_ID_CHARS &&
                                    !it.hasControlCharacters() &&
                                    it.none(Char::isWhitespace) &&
                                    !it.startsWith('-')
                            }
                    }
                    .filter(seen::add)
                if (models.isEmpty()) return@mapNotNull null
                ModelProviderOption(slug = slug, name = name, models = models, capabilities = capabilities)
            }
        val currentProvider = result.boundedModelField("provider", MAX_MODEL_PROVIDER_CHARS)
        val currentModel = result.boundedModelField("model", MAX_MODEL_ID_CHARS)
        return ModelOptions(
            current = if (currentProvider != null && currentModel != null) {
                ModelSelection(currentProvider, currentModel)
            } else {
                null
            },
            providers = providers,
        )
    }

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
                // Only a peer close is classified. A close code read after our
                // own teardown would let a local disconnect masquerade as a
                // credential rejection and start a bootstrap nobody asked for.
                val code = runCatching { socket.closeCode() }.getOrNull()
                val classification = classifySocketClose(code)
                peerCloseClass = classification
                failPending(HermesChatSocketClosedException(classification, code))
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
            handleEvent(message)
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

    private fun handleEvent(message: JsonObject) {
        val params = message["params"] as? JsonObject ?: return
        val sessionId = params.boundedRequired("session_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)?.let {
            runCatching { RuntimeSessionId(it) }.getOrNull()
        } ?: return
        val type = params.stringValue("type") ?: return
        val knownTypes = setOf(
            "message.start",
            "message.delta",
            "message.complete",
            "error",
            "tool.start",
            "tool.complete",
            "tool.generating",
            "status.update",
            "clarify.request",
            "clarify.expire",
            "approval.request",
            "approval.expire",
            "secret.request",
            "secret.expire",
            "sudo.request",
            "sudo.expire",
            "terminal.read.request",
            "terminal.read.expire",
            "preview.read.request",
            "preview.read.expire",
            "window.read.request",
            "window.read.expire",
            "session.info",
            "session.title",
            "reasoning.delta",
            "reasoning.available",
            "message.interim",
            // Intentionally ignored (no mobile surface in HAM): gateway.ready,
            // skin.changed, sessions.changed, cron.changed, pet.changed,
            // thinking.delta (spinner copy, not model reasoning), reaction,
            // moa.*, voice.*, wake.detected, browser.progress,
            // terminal.close, notification.clear, preview.restart.progress.
            // They are display chrome or desktop-only affordances; HAM polls
            // session lists instead of trusting change events.
        )
        if (type !in knownTypes) return
        val payload = params["payload"] as? JsonObject ?: return
        val event = when (type) {
            "message.start" -> HermesChatEvent.MessageStart(
                sessionId = sessionId,
                text = payload.boundedText("text", HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS),
            )

            "message.delta" -> payload.boundedText("text", HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS)?.let { text ->
                HermesChatEvent.MessageDelta(sessionId, text)
            }

            "message.complete" -> HermesChatEvent.MessageComplete(
                sessionId = sessionId,
                text = payload.boundedText("text", HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS),
                status = payload.boundedOptional("status", HERMES_CHAT_MAX_EVENT_NAME_CHARS),
                error = payload.boundedOptional("error", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                reasoning = payload.boundedText("reasoning", HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS),
                warning = payload.boundedOptional("warning", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                failureReason = payload.boundedOptional("failure_reason", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                recoverable = payload.booleanValue("recoverable") ?: false,
                billing = (payload["billing"] as? JsonObject)?.let { billing ->
                    HermesChatEvent.BillingInfo(
                        provider = billing.boundedOptional("provider", MAX_MODEL_PROVIDER_CHARS),
                        billingUrl = billing.boundedOptional("billing_url", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                        isNous = billing.booleanValue("is_nous") ?: false,
                        message = billing.boundedOptional("message", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                    )
                },
            )

            "reasoning.delta", "reasoning.available" ->
                payload.boundedText("text", HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS)?.let { text ->
                    HermesChatEvent.ReasoningDelta(
                        sessionId = sessionId,
                        text = text,
                        replace = type == "reasoning.available",
                    )
                }

            "message.interim" -> payload.boundedText("text", HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS)?.let { text ->
                HermesChatEvent.MessageInterim(
                    sessionId = sessionId,
                    text = text,
                    alreadyStreamed = payload.booleanValue("already_streamed") ?: false,
                )
            }

            "tool.generating" -> payload.boundedRequired("name", HERMES_CHAT_MAX_EVENT_NAME_CHARS)
                ?.let { HermesChatEvent.ToolGenerating(sessionId, it) }

            "session.title" -> payload.boundedRequired("title", HERMES_CHAT_MAX_EVENT_NAME_CHARS)
                ?.let { HermesChatEvent.SessionTitle(sessionId, it) }

            "session.info" -> HermesChatEvent.SessionInfo(
                sessionId = sessionId,
                storedSessionId = payload.boundedOptional(
                    "stored_session_id",
                    ProjectSummary.MAX_SESSION_TITLE_LENGTH,
                )?.let { runCatching { DurableSessionId(it) }.getOrNull() },
                model = payload.boundedOptional("model", MAX_MODEL_ID_CHARS),
                provider = payload.boundedOptional("provider", MAX_MODEL_PROVIDER_CHARS),
                reasoningEffort = payload.boundedOptional(
                    "reasoning_effort",
                    HERMES_CHAT_MAX_EVENT_NAME_CHARS,
                ),
                title = payload.boundedOptional("title", HERMES_CHAT_MAX_EVENT_NAME_CHARS),
                running = payload.booleanValue("running"),
            )

            "error" -> payload.boundedOptional("message", HERMES_CHAT_MAX_EVENT_TEXT_CHARS)
                ?.let { HermesChatEvent.Error(sessionId, it) }

            "tool.start" -> {
                val toolId = payload.boundedRequired("tool_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)
                val name = payload.boundedRequired("name", HERMES_CHAT_MAX_EVENT_NAME_CHARS)
                if (toolId == null || name == null) {
                    null
                } else {
                    HermesChatEvent.ToolStart(
                        sessionId = sessionId,
                        toolId = toolId,
                        name = name,
                        context = payload.boundedOptional("context", HERMES_CHAT_MAX_EVENT_CONTEXT_CHARS),
                        todos = payload.boundedTodoItems(),
                    )
                }
            }

            "tool.complete" -> {
                val toolId = payload.boundedRequired("tool_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)
                val name = payload.boundedRequired("name", HERMES_CHAT_MAX_EVENT_NAME_CHARS)
                if (toolId == null || name == null) {
                    null
                } else {
                    HermesChatEvent.ToolComplete(
                        sessionId = sessionId,
                        toolId = toolId,
                        name = name,
                        summary = payload.boundedOptional("summary", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                        todos = payload.boundedTodoItems(),
                    )
                }
            }

            "status.update" -> {
                val kind = payload.boundedRequired("kind", HERMES_CHAT_MAX_EVENT_NAME_CHARS)
                val text = payload.boundedRequired("text", HERMES_CHAT_MAX_EVENT_TEXT_CHARS)
                if (kind == null || text == null) null else HermesChatEvent.StatusUpdate(sessionId, kind, text)
            }

            "clarify.request" -> {
                val requestId = payload.boundedRequired("request_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)
                val question = payload.boundedRequired("question", HERMES_CHAT_MAX_EVENT_TEXT_CHARS)
                if (requestId == null || question == null) {
                    null
                } else {
                    HermesChatEvent.ClarifyRequest(
                        sessionId = sessionId,
                        requestId = requestId,
                        question = question,
                        choices = payload.boundedChoices(),
                        multiSelect = payload.booleanValue("multi_select") ?: false,
                    )
                }
            }

            "clarify.expire" -> payload.boundedRequired("request_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)
                ?.let { HermesChatEvent.ClarifyExpire(sessionId, it) }

            "approval.request" -> {
                val choices = payload.boundedChoices()
                if (choices.isEmpty()) {
                    null
                } else {
                    val approval = HermesChatEvent.ApprovalRequest(
                        sessionId = sessionId,
                        requestId = payload.boundedOptional("request_id", HERMES_CHAT_MAX_EVENT_ID_CHARS),
                        command = payload.boundedOptional("command", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                        description = payload.boundedOptional("description", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                        choices = choices,
                    )
                    synchronized(interactionLock) {
                        pendingApprovals.getOrPut(sessionId.value) { ArrayDeque() }
                            .addLast(
                                PendingApproval(
                                    requestId = approval.requestId,
                                    command = approval.command,
                                    description = approval.description,
                                    choices = choices,
                                ),
                            )
                    }
                    approval
                }
            }

            "approval.expire" -> payload.boundedRequired("request_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)
                ?.let { requestId ->
                    synchronized(interactionLock) {
                        pendingApprovals[sessionId.value]?.let { queue ->
                            queue.removeIf { it.requestId == requestId }
                            if (queue.isEmpty()) pendingApprovals.remove(sessionId.value)
                        }
                    }
                    HermesChatEvent.ApprovalExpire(sessionId, requestId)
                }

            "secret.request", "sudo.request", "terminal.read.request",
            "preview.read.request", "window.read.request",
            -> {
                val requestId = payload.boundedRequired("request_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)
                val kind = when (type) {
                    "secret.request" -> UnsupportedBlockingKind.Secret
                    "sudo.request" -> UnsupportedBlockingKind.Sudo
                    "preview.read.request" -> UnsupportedBlockingKind.PreviewRead
                    "window.read.request" -> UnsupportedBlockingKind.WindowRead
                    else -> UnsupportedBlockingKind.TerminalRead
                }
                requestId?.let {
                    HermesChatEvent.UnsupportedBlockingRequest(
                        sessionId = sessionId,
                        kind = kind,
                        requestId = it,
                        prompt = payload.boundedOptional("prompt", HERMES_CHAT_MAX_EVENT_TEXT_CHARS),
                    )
                }
            }

            "secret.expire", "sudo.expire", "terminal.read.expire",
            "preview.read.expire", "window.read.expire",
            -> {
                val requestId = payload.boundedRequired("request_id", HERMES_CHAT_MAX_EVENT_ID_CHARS)
                val kind = when (type) {
                    "secret.expire" -> UnsupportedBlockingKind.Secret
                    "sudo.expire" -> UnsupportedBlockingKind.Sudo
                    "preview.read.expire" -> UnsupportedBlockingKind.PreviewRead
                    "window.read.expire" -> UnsupportedBlockingKind.WindowRead
                    else -> UnsupportedBlockingKind.TerminalRead
                }
                requestId?.let { HermesChatEvent.UnsupportedBlockingExpire(sessionId, kind, it) }
            }

            else -> null
        }
        // With DROP_OLDEST the send only fails once the channel is closed, which
        // teardown already handles; a full buffer silently sheds the oldest event.
        if (event != null) eventChannel.trySend(event)
    }

    private fun parseResumeResult(
        result: JsonObject,
        requestedDurableSessionId: DurableSessionId,
    ): ResumedChatSession {
        val runtimeSessionId = result.stringValue("session_id")?.let {
            runCatching { RuntimeSessionId(it) }.getOrNull()
        } ?: throw HermesChatProtocolException("Resume response was incomplete")
        val durableSessionId = result.stringValue("session_key")
            ?.takeIf(String::isNotBlank)
            ?.let(::DurableSessionId)
        if (durableSessionId != null && durableSessionId != requestedDurableSessionId) {
            throw HermesChatProtocolException("Resume response referenced a different durable session")
        }
        val messages = result["messages"] as? JsonArray ?: JsonArray(emptyList())
        val inflight = (result["inflight"] as? JsonObject)?.let { value ->
            InflightPrompt(
                user = value.stringValue("user"),
                assistant = value.stringValue("assistant"),
                streaming = value.booleanValue("streaming") ?: false,
            )
        }
        val info = result["info"] as? JsonObject
        return ResumedChatSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            resumed = result.booleanValue("resumed") ?: false,
            messages = messages.filterIsInstance<JsonObject>(),
            running = result.booleanValue("running") ?: false,
            inflight = inflight,
            model = info?.boundedOptional("model", MAX_MODEL_ID_CHARS),
            provider = info?.boundedOptional("provider", MAX_MODEL_PROVIDER_CHARS),
            reasoningEffort = info?.boundedOptional(
                "reasoning_effort",
                HERMES_CHAT_MAX_EVENT_NAME_CHARS,
            ),
        )
    }

    private fun parseProjectTreeResult(result: JsonObject): ProjectTreeResult {
        val projects = (result["projects"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element -> parseProjectSummary(element as? JsonObject) }
            .distinctBy { it.id }
            .take(ProjectSummary.MAX_PROJECTS)
        val activeProjectId = parseProjectId(result["active_id"])
        val scopedSessionIds = (result["scoped_session_ids"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                val value = (element as? JsonPrimitive)?.contentOrNull
                    ?: (element as? JsonObject)?.stringValue("id")
                    ?: (element as? JsonObject)?.stringValue("session_key")
                value?.takeIf(String::isNotBlank)?.let { runCatching { DurableSessionId(it) }.getOrNull() }
            }
            .distinct()
            .take(ProjectSummary.MAX_SCOPED_SESSION_IDS)
            .toSet()
        return ProjectTreeResult(projects, activeProjectId, scopedSessionIds)
    }

    private fun parseProjectSessionsResult(
        result: JsonObject,
        requestedProjectId: ProjectId,
    ): ProjectSessionsResult {
        val projectJson = result["project"] as? JsonObject
        val sessions = projectSessionRows(projectJson, result)
            .mapNotNull { element -> parseSessionSummary(element as? JsonObject, requestedProjectId) }
            .distinctBy { it.id }
            .take(ProjectSummary.MAX_PROJECT_SESSIONS)
            .toList()
        val project = parseProjectSummary(projectJson, requestedProjectId, sessions.size)
            ?: ProjectSummary(
                id = requestedProjectId,
                label = requestedProjectId.value,
                primaryPath = null,
                sessionCount = sessions.size,
                previewSessions = emptyList(),
            )
        return ProjectSessionsResult(project, sessions)
    }

    private fun projectSessionRows(
        project: JsonObject?,
        result: JsonObject,
    ): Sequence<kotlinx.serialization.json.JsonElement> = sequence {
        val repos = (project?.get("repos") as? JsonArray).orEmpty()
            .take(ProjectSummary.MAX_PROJECTS)
        for (repoElement in repos) {
            val repo = repoElement as? JsonObject ?: continue
            val groups = (repo["groups"] as? JsonArray).orEmpty()
                .take(ProjectSummary.MAX_PROJECTS)
            for (groupElement in groups) {
                val group = groupElement as? JsonObject ?: continue
                val rows = (group["sessions"] as? JsonArray).orEmpty()
                    .take(ProjectSummary.MAX_PROJECT_SESSIONS)
                for (row in rows) yield(row)
            }
        }
        for (row in (project?.get("sessions") as? JsonArray).orEmpty()) yield(row)
        for (row in (result["sessions"] as? JsonArray).orEmpty()) yield(row)
    }

    private fun parseProjectSummary(
        value: JsonObject?,
        fallbackId: ProjectId? = null,
        fallbackSessionCount: Int = 0,
    ): ProjectSummary? {
        val id = parseProjectId(value?.get("id"))
            ?: parseProjectId(value?.get("project_id"))
            ?: fallbackId
            ?: return null
        val label = value?.stringValue("label")
            ?: value?.stringValue("name")
            ?: id.value
        val path = value?.stringValue("path")
            ?: value?.stringValue("primary_path")
        val previewSessions = (value?.get("previewSessions") as? JsonArray)
            ?: (value?.get("preview_sessions") as? JsonArray)
        val parsedPreview = previewSessions
            .orEmpty()
            .mapNotNull { element -> parseSessionSummary(element as? JsonObject, id) }
            .take(ProjectSummary.MAX_PREVIEW_SESSIONS)
        val sessionCount = value?.longValue("sessionCount")?.toInt()
            ?: value?.longValue("session_count")?.toInt()
            ?: value?.longValue("count")?.toInt()
            ?: fallbackSessionCount
        return ProjectSummary(id, label, path, sessionCount, parsedPreview)
    }

    private fun parseSessionSummary(
        value: JsonObject?,
        projectId: ProjectId?,
    ): SessionSummary? {
        val id = value?.stringValue("id")
            ?: value?.stringValue("session_key")
            ?: value?.stringValue("durable_id")
            ?: return null
        val durableId = runCatching { DurableSessionId(id) }.getOrNull() ?: return null
        val title = value?.stringValue("title")
            ?: value?.stringValue("name")
            ?: "Untitled session"
        val workspacePath = value?.stringValue("workspace_path")
            ?: value?.stringValue("workspace")
            ?: value?.stringValue("cwd")
        val lastActive = value?.get("last_active")?.jsonPrimitive?.doubleOrNull
            ?: value?.get("lastActive")?.jsonPrimitive?.doubleOrNull
        val messageCount = value?.longValue("message_count")?.toInt()
            ?: value?.longValue("messageCount")?.toInt()
        return SessionSummary(
            id = durableId,
            title = title.take(ProjectSummary.MAX_SESSION_TITLE_LENGTH).ifBlank { "Untitled session" },
            projectId = projectId,
            workspacePath = workspacePath?.take(ProjectSummary.MAX_PATH_LENGTH),
            preview = value?.stringValue("preview")?.take(ProjectSummary.MAX_SESSION_TITLE_LENGTH),
            lastActiveEpochSeconds = lastActive,
            messageCount = messageCount?.coerceAtLeast(0),
            model = value?.stringValue("model"),
            provider = value?.stringValue("provider") ?: value?.stringValue("billing_provider"),
            profile = value?.stringValue("profile"),
            pinned = value?.booleanValue("pinned") ?: false,
            archived = value?.booleanValue("archived") ?: false,
        )
    }

    private fun parseProjectId(value: kotlinx.serialization.json.JsonElement?): ProjectId? =
        (value as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { ProjectId(it) }.getOrNull() }

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

private fun JsonObject.boundedChoices(): List<String> =
    (this["choices"] as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= HERMES_CHAT_MAX_EVENT_CHOICE_CHARS }
        }
        .distinct()
        .take(HERMES_CHAT_MAX_EVENT_CHOICES)

private const val MAX_TODO_PARSE_DEPTH = 2
private val todoJson = Json { ignoreUnknownKeys = true }

/**
 * `todo` is an ordinary official tool event. Its live list has appeared as
 * `todos`, and older released payloads wrap the same list in `result`/`args`.
 * Parse only that bounded, typed shape; never turn arbitrary tool text into
 * activity state.
 */
private fun JsonObject.boundedTodoItems(): List<RunTodoItem>? {
    val name = stringValue("name")
    if (name != "todo" && !(name == null && containsKey("todos"))) return null

    for (key in listOf("todos", "result", "args")) {
        val value = this[key] ?: continue
        parseTodoElement(value, depth = 0)?.let { return it }
    }
    return null
}

private fun parseTodoElement(
    value: kotlinx.serialization.json.JsonElement,
    depth: Int,
): List<RunTodoItem>? {
    if (depth > MAX_TODO_PARSE_DEPTH) return null
    return when (value) {
        is JsonArray -> {
            if (value.isEmpty()) return emptyList()
            val parsed = value.mapNotNull { (it as? JsonObject)?.boundedTodoItem() }
            parsed.takeIf { it.isNotEmpty() }
                ?.distinctBy(RunTodoItem::id)
                ?.take(50)
        }
        is JsonObject -> (value["todos"] ?: return null).let {
            parseTodoElement(it, depth + 1)
        }
        is JsonPrimitive -> value.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= HERMES_CHAT_MAX_EVENT_TEXT_CHARS }
            ?.let { encoded ->
                runCatching { todoJson.parseToJsonElement(encoded) }
                    .getOrNull()
                    ?.let { parsed -> parseTodoElement(parsed, depth + 1) }
            }
    }
}

private fun JsonObject.boundedTodoItem(): RunTodoItem? {
    val id = stringValue("id")?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 256 && !it.hasControlCharacters() }
        ?: return null
    val content = stringValue("content")?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 4_096 && !it.hasControlCharacters() }
        ?: return null
    val status = when (stringValue("status")?.trim()?.lowercase()) {
        "pending" -> RunTodoStatus.Pending
        "in_progress", "in-progress", "in progress" -> RunTodoStatus.InProgress
        "completed", "complete", "done" -> RunTodoStatus.Completed
        "cancelled", "canceled" -> RunTodoStatus.Cancelled
        else -> return null
    }
    return RunTodoItem(id = id, content = content, status = status)
}

private fun sameHostPath(first: String, second: String): Boolean {
    fun normalized(value: String): String {
        val slashed = value.replace('\\', '/')
        val trimmed = slashed.trimEnd('/').ifEmpty { "/" }
        return if (trimmed.length >= 2 && trimmed[1] == ':') trimmed.lowercase() else trimmed
    }
    return normalized(first) == normalized(second)
}

private fun boundedRpcInput(
    value: String,
    maxChars: Int,
    label: String,
    allowBlank: Boolean = false,
): String {
    if (value.length > maxChars) throw HermesChatProtocolException("Hermes $label is too long")
    if (!allowBlank && value.isBlank()) throw HermesChatProtocolException("Hermes $label must not be blank")
    return value
}

private fun boundedModelInput(value: String, maxChars: Int, label: String): String {
    val bounded = boundedRpcInput(value.trim(), maxChars, label)
    if (bounded.hasControlCharacters() || bounded.any(Char::isWhitespace) || bounded.startsWith('-')) {
        throw HermesChatProtocolException("Hermes $label was invalid")
    }
    return bounded
}

private fun JsonObject.boundedModelField(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars && !it.hasControlCharacters() }

private fun String.hasControlCharacters(): Boolean = any(Char::isISOControl)

private fun JsonObject.boundedRequired(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars }

private fun JsonObject.boundedOptional(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(maxChars)

private fun JsonObject.boundedProcessRequired(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars && it.isSafeProcessText() }

private fun JsonObject.boundedProcessOptional(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars && it.isSafeProcessText() }

private fun String.isSafeProcessText(): Boolean = all {
    !it.isISOControl() || it == '\n' || it == '\r' || it == '\t'
}

/**
 * Bounded read for message TEXT fields (message.start/delta/complete).
 *
 * Unlike [boundedOptional] this never trims: streaming tokenizers attach the
 * inter-word space to the FRONT of the next token ("HE", " WORLD"), so trimming
 * each delta destroys word boundaries and jams the streamed text together.
 * Metadata fields (status, error, question, ...) keep the trimming read.
 */
private fun JsonObject.boundedText(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.takeIf(String::isNotEmpty)
        ?.take(maxChars)

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanValue(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.longValue(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

class KtorWsTicketClient(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : WsTicketClient {
    override suspend fun mintTicket(
        origin: ServerOrigin,
        credential: HermesCredential.NativeBearer,
    ): WsTicket {
        return try {
            val response = client.post("${origin.value}/api/auth/ws-ticket") {
                credential.apply(this)
            }
            val body = response.readBodyTextBounded(MAX_TICKET_RESPONSE_BYTES)
            if (!response.status.isSuccess()) {
                throw HermesChatTransportException(
                    "Hermes ticket request returned HTTP ${response.status.value}",
                )
            }
            val value = json.parseToJsonElement(body).jsonObject
            val ticket = value.stringValue("ticket")
                ?.takeIf(String::isNotBlank)
                ?: throw HermesChatProtocolException("Hermes ticket response was incomplete")
            val ttlSeconds = value.longValue("ttl_seconds")
                ?: throw HermesChatProtocolException("Hermes ticket response was incomplete")
            WsTicket(ticket, ttlSeconds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HermesChatException) {
            throw error
        } catch (error: Exception) {
            throw HermesChatTransportException("Could not mint Hermes chat ticket", error)
        }
    }
}

class KtorChatWebSocketFactory(
    private val client: HttpClient,
) : ChatWebSocketFactory {
    override suspend fun connect(url: String): HermesChatSocket {
        return try {
            KtorHermesChatSocket(client.webSocketSession { url(url) })
        } catch (_: CancellationException) {
            throw CancellationException("Hermes chat connection cancelled")
        } catch (_: Exception) {
            // Ktor failures may include the signed request URL.
            throw HermesChatTransportException("Could not connect to Hermes chat")
        }
    }
}

private class KtorHermesChatSocket(
    private val session: DefaultWebSocketSession,
) : HermesChatSocket {
    override suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun receiveText(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            if (frame is Frame.Text) return String(frame.data, StandardCharsets.UTF_8)
        }
    }

    /**
     * Bounded: a peer that half-closes the read side without ever sending a
     * close frame must not park the reader forever. A missing reason classifies
     * as a transport failure, which is the safe default.
     */
    override suspend fun closeCode(): Int? =
        withTimeoutOrNull(CLOSE_REASON_TIMEOUT_MILLIS) {
            session.closeReason.await()
        }?.code?.toInt()

    override suspend fun close() {
        session.cancel()
    }

    private companion object {
        const val CLOSE_REASON_TIMEOUT_MILLIS = 2_000L
    }
}
