package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.mercury.core.transcript.ClarifyQuestion
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private const val MAX_MODEL_PROVIDERS = 64
private const val MAX_MODELS_PER_PROVIDER = 512
internal const val MAX_MODEL_PROVIDER_CHARS = 128
internal const val MAX_MODEL_ID_CHARS = 512

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
    suspend fun mintTicket(origin: ServerOrigin, accessToken: String): WsTicket
}

interface HermesChatSocket {
    suspend fun sendText(text: String)

    /** Returns null when the peer has closed the WebSocket. */
    suspend fun receiveText(): String?

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

class HermesChatTransportException(
    message: String,
    cause: Throwable? = null,
) : HermesChatException(message, cause)

/**
 * An authenticated Hermes request was rejected with HTTP 401/403. Distinct from
 * [HermesChatTransportException]: the transport is healthy but the presented
 * access token is stale/rejected, so callers must refresh or drop to sign-in
 * rather than blindly retrying the same token — retrying spins a reconnect loop
 * against a credential the server will never accept.
 */
class HermesChatUnauthorizedException(
    message: String = "Hermes rejected the request as unauthorized",
    cause: Throwable? = null,
) : HermesChatException(message, cause)

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
    val display: String = com.unsupportedpastels.mercury.core.slash.SlashCommandPolicy.defaultDisplay(text),
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
    val root: String? = null,
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

val ValidReasoningEfforts = com.unsupportedpastels.mercury.core.slash.SlashCommandPolicy.VALID_REASONING_EFFORTS

fun canonicalReasoningEffort(value: String): String? =
    com.unsupportedpastels.mercury.core.slash.SlashCommandPolicy.canonicalReasoningEffort(value)

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
        val fastMode: Boolean? = null,
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
        val historical: Boolean = false,
    ) : HermesChatEvent

    data class ToolComplete(
        override val sessionId: RuntimeSessionId,
        val toolId: String,
        val name: String,
        val summary: String?,
        val todos: List<RunTodoItem>? = null,
        val progressSnapshot: com.unsupportedpastels.hermesandroid.app.DurableProgress? = null,
        val historical: Boolean = false,
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
        /** Batch form: answered one `qid` at a time; empty for a single question. */
        val questions: List<ClarifyQuestion> = emptyList(),
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
    suspend fun connect(origin: ServerOrigin, accessToken: String): HermesChatSession
}
