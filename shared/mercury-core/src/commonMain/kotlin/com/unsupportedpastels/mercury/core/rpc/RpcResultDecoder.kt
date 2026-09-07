package com.unsupportedpastels.mercury.core.rpc

import com.unsupportedpastels.mercury.core.slash.SlashCommandPolicy
import com.unsupportedpastels.mercury.core.transcript.ChatEventDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A JSON-RPC result that the shared decoder could not accept. Message is safe to show. */
class RpcResultException(message: String) : Exception(message)

data class ModelCapabilitiesSpec(val fast: Boolean? = null, val reasoning: Boolean? = null)

data class ModelProviderSpec(
    val slug: String,
    val name: String,
    val models: List<String>,
    val capabilities: Map<String, ModelCapabilitiesSpec>,
)

data class ModelSelectionSpec(val provider: String, val model: String)

data class ModelOptionsResult(val current: ModelSelectionSpec?, val providers: List<ModelProviderSpec>)

data class SessionUsageResult(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val contextUsedTokens: Long? = null,
    val contextMaxTokens: Long? = null,
    val contextPercent: Double? = null,
    val calls: Long? = null,
    val creditsLines: List<String> = emptyList(),
    val rawInfo: String? = null,
)

data class ContextCategoryResult(val name: String, val tokens: Long? = null, val percent: Double? = null)

data class ContextBreakdownResult(
    val categories: List<ContextCategoryResult> = emptyList(),
    val usedTokens: Long? = null,
    val maxTokens: Long? = null,
    val percent: Double? = null,
)

/** Message rows stay JSON text: each platform's transcript restore owns their schema. */
data class CompressResult(
    val status: String? = null,
    val aborted: Boolean = false,
    val messagesJson: List<String> = emptyList(),
    val info: String? = null,
    val usage: SessionUsageResult? = null,
)

data class BranchResult(
    val runtimeSessionId: String?,
    val durableSessionId: String,
    val title: String?,
    val messagesJson: List<String> = emptyList(),
)

data class SlashCompletionRow(val text: String, val display: String, val meta: String?)

data class SlashCompletionDecoded(val items: List<SlashCompletionRow>, val replaceFrom: Int)

data class ResumeResult(
    val runtimeSessionId: String,
    val durableSessionId: String?,
    val resumed: Boolean,
    val messagesJson: List<String>,
    val running: Boolean,
    val inflightUser: String?,
    val inflightAssistant: String?,
    val inflightStreaming: Boolean,
    val hasInflight: Boolean,
    val model: String?,
    val provider: String?,
    val reasoningEffort: String?,
    val fastMode: Boolean?,
)

enum class InteractionStatus {
    Ok, Expired, Interrupted, Resolved, Unknown;

    companion object {
        fun fromWire(value: String?): InteractionStatus = when (value?.trim()?.lowercase()) {
            "ok" -> Ok
            "expired" -> Expired
            "interrupted" -> Interrupted
            "resolved" -> Resolved
            else -> Unknown
        }
    }
}

/**
 * Tolerant, bounded decoding of official Hermes JSON-RPC results, decided
 * once for both clients. Unknown fields are ignored, malformed rows are
 * skipped independently, and only structurally required fields throw.
 */
object RpcResultDecoder {
    const val MAX_MODEL_PROVIDERS = 64
    const val MAX_MODELS_PER_PROVIDER = 512
    const val MAX_MODEL_PROVIDER_CHARS = 128
    const val MAX_MODEL_ID_CHARS = 512
    const val MAX_RESULT_ROWS = 128
    const val MAX_CONTEXT_CATEGORIES = 64
    const val MAX_FIELD_CHARS = 512

    private val json = Json { ignoreUnknownKeys = true }

    fun modelOptions(resultJson: String): ModelOptionsResult {
        val result = parseObject(resultJson) ?: return ModelOptionsResult(null, emptyList())
        val seenProviders = LinkedHashSet<String>()
        val providers = (result["providers"] as? JsonArray).orEmpty()
            .take(MAX_MODEL_PROVIDERS)
            .mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                // Only an explicit JSON false means unauthenticated; missing or
                // mistyped additive fields never hide an otherwise valid row.
                if (row.strictBoolean("authenticated") == false) return@mapNotNull null
                val slug = row.validModelField("slug", MAX_MODEL_PROVIDER_CHARS) ?: return@mapNotNull null
                if (!seenProviders.add(slug)) return@mapNotNull null
                val name = row.boundedOptional("name", MAX_MODEL_PROVIDER_CHARS) ?: slug
                val seenModels = LinkedHashSet<String>()
                val models = (row["models"] as? JsonArray).orEmpty()
                    .take(MAX_MODELS_PER_PROVIDER)
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                    .mapNotNull { validModelValue(it, MAX_MODEL_ID_CHARS) }
                    .filter(seenModels::add)
                if (models.isEmpty()) return@mapNotNull null
                val capabilities = (row["capabilities"] as? JsonObject)?.entries.orEmpty()
                    .take(MAX_MODELS_PER_PROVIDER)
                    .mapNotNull { (rawModel, rawValue) ->
                        val model = validModelValue(rawModel, MAX_MODEL_ID_CHARS) ?: return@mapNotNull null
                        val value = rawValue as? JsonObject ?: return@mapNotNull null
                        model to ModelCapabilitiesSpec(value.strictBoolean("fast"), value.strictBoolean("reasoning"))
                    }
                    .toMap()
                ModelProviderSpec(slug, name, models, capabilities)
            }
        val provider = result.validModelField("provider", MAX_MODEL_PROVIDER_CHARS)
        val model = result.validModelField("model", MAX_MODEL_ID_CHARS)
        val current = if (provider != null && model != null) ModelSelectionSpec(provider, model) else null
        return ModelOptionsResult(current, providers)
    }

    fun sessionUsage(resultJson: String): SessionUsageResult =
        parseObject(resultJson)?.let(::sessionUsage) ?: SessionUsageResult()

    private fun sessionUsage(result: JsonObject): SessionUsageResult = SessionUsageResult(
        inputTokens = result.nonNegative("input_tokens", "input", "prompt_tokens"),
        outputTokens = result.nonNegative("output_tokens", "output", "completion_tokens"),
        totalTokens = result.nonNegative("total_tokens", "total"),
        contextUsedTokens = result.nonNegative("context_used_tokens", "context_used", "used_tokens"),
        contextMaxTokens = result.nonNegative("context_max_tokens", "context_max", "max_tokens"),
        contextPercent = result.percent("context_percent", "context_percentage", "percent"),
        calls = result.nonNegative("calls", "request_count", "requests"),
        creditsLines = (result["credits_lines"] as? JsonArray).orEmpty()
            .take(MAX_RESULT_ROWS)
            .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim() }
            .filter(String::isNotEmpty)
            .map { it.take(MAX_FIELD_CHARS) },
        rawInfo = result.boundedOptional("info", MAX_FIELD_CHARS),
    )

    fun contextBreakdown(resultJson: String): ContextBreakdownResult {
        val result = parseObject(resultJson) ?: return ContextBreakdownResult()
        val rows = (result["categories"] as? JsonArray) ?: (result["breakdown"] as? JsonArray) ?: JsonArray(emptyList())
        val seen = LinkedHashSet<String>()
        val categories = rows.take(MAX_CONTEXT_CATEGORIES).mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val name = row.firstBounded("name", "category", "label") ?: return@mapNotNull null
            if (!seen.add(name)) return@mapNotNull null
            ContextCategoryResult(
                name = name,
                tokens = row.nonNegative("tokens", "token_count", "count"),
                percent = row.percent("percent", "percentage"),
            )
        }
        return ContextBreakdownResult(
            categories = categories,
            usedTokens = result.nonNegative("used_tokens", "context_used_tokens", "context_used"),
            maxTokens = result.nonNegative("max_tokens", "context_max_tokens", "context_max"),
            percent = result.percent("percent", "context_percent"),
        )
    }

    fun compress(resultJson: String): CompressResult {
        val result = parseObject(resultJson) ?: return CompressResult()
        val status = result.boundedOptional("status", MAX_FIELD_CHARS)
        return CompressResult(
            status = status,
            aborted = result.strictBoolean("aborted") == true ||
                status?.lowercase() in setOf("aborted", "cancelled", "canceled"),
            messagesJson = result.messageRows("messages", MAX_RESULT_ROWS),
            info = result.boundedOptional("info", MAX_FIELD_CHARS),
            usage = (result["usage"] as? JsonObject)?.let(::sessionUsage),
        )
    }

    @Throws(RpcResultException::class)
    fun branch(resultJson: String): BranchResult {
        val result = parseObject(resultJson) ?: throw RpcResultException("Branch response was incomplete")
        val durable = result.boundedRequired("stored_session_id", MAX_FIELD_CHARS)
            ?: result.boundedRequired("durable_session_id", MAX_FIELD_CHARS)
            ?: throw RpcResultException("Branch response was incomplete")
        return BranchResult(
            runtimeSessionId = result.boundedOptional("session_id", ChatEventDecoder.MAX_EVENT_ID_CHARS),
            durableSessionId = durable,
            title = result.boundedOptional("title", MAX_FIELD_CHARS),
            messagesJson = result.messageRows("messages", MAX_RESULT_ROWS),
        )
    }

    /** [inputLength] is the UTF-16 length of the completed text; `replace_from` is clamped to it. */
    @Throws(RpcResultException::class)
    fun slashCompletion(resultJson: String, inputLength: Int): SlashCompletionDecoded {
        val result = parseObject(resultJson) ?: throw RpcResultException("Slash completion response was incomplete")
        val items = (result["items"] as? JsonArray).orEmpty().take(MAX_RESULT_ROWS).mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val text = row.boundedRequired("text", MAX_FIELD_CHARS) ?: return@mapNotNull null
            SlashCompletionRow(
                text = text,
                display = row.boundedOptional("display", MAX_FIELD_CHARS) ?: SlashCommandPolicy.defaultDisplay(text),
                meta = row.boundedOptional("meta", MAX_FIELD_CHARS),
            )
        }
        val replaceFrom = when {
            !result.containsKey("replace_from") -> 0
            else -> result.strictLong("replace_from")?.takeIf { it >= 0 && it <= Int.MAX_VALUE }
                ?.let { minOf(it.toInt(), inputLength) }
                ?: throw RpcResultException("Slash completion response was incomplete")
        }
        return SlashCompletionDecoded(items, replaceFrom)
    }

    @Throws(RpcResultException::class)
    fun resume(resultJson: String, requestedDurableSessionId: String): ResumeResult {
        val result = parseObject(resultJson) ?: throw RpcResultException("Resume response was incomplete")
        val runtimeSessionId = result.stringValue("session_id")?.takeIf { it.isNotBlank() }
            ?: throw RpcResultException("Resume response was incomplete")
        val durableSessionId = result.stringValue("session_key")?.takeIf(String::isNotEmpty)
        if (durableSessionId != null && durableSessionId != requestedDurableSessionId) {
            throw RpcResultException("Resume response referenced a different durable session")
        }
        val inflight = result["inflight"] as? JsonObject
        val info = result["info"] as? JsonObject
        return ResumeResult(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            resumed = result.strictBoolean("resumed") ?: false,
            messagesJson = result.messageRows("messages", Int.MAX_VALUE),
            running = result.strictBoolean("running") ?: false,
            inflightUser = inflight?.stringValue("user"),
            inflightAssistant = inflight?.stringValue("assistant"),
            inflightStreaming = inflight?.strictBoolean("streaming") ?: false,
            hasInflight = inflight != null,
            model = info?.boundedOptional("model", MAX_MODEL_ID_CHARS),
            provider = info?.boundedOptional("provider", MAX_MODEL_PROVIDER_CHARS),
            reasoningEffort = info?.boundedOptional("reasoning_effort", ChatEventDecoder.MAX_EVENT_NAME_CHARS),
            fastMode = info?.strictBoolean("fast"),
        )
    }

    @Throws(RpcResultException::class)
    fun interactionResponse(resultJson: String): InteractionStatus {
        val result = parseObject(resultJson) ?: throw RpcResultException("Hermes interaction response was incomplete")
        val wireStatus = result.stringValue("status")
            ?: result.strictBoolean("resolved")?.let { if (it) "ok" else "expired" }
            ?: result.strictLong("resolved")?.let { if (it > 0) "ok" else "expired" }
            ?: throw RpcResultException("Hermes interaction response was incomplete")
        return InteractionStatus.fromWire(wireStatus)
    }

    // --- field helpers -------------------------------------------------------

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.messageRows(name: String, limit: Int): List<String> =
        (this[name] as? JsonArray).orEmpty().asSequence()
            .filterIsInstance<JsonObject>()
            .take(limit)
            .map(JsonObject::toString)
            .toList()

    private fun JsonObject.stringValue(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    /** JSON booleans only: strings and numbers are never coerced. */
    private fun JsonObject.strictBoolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

    /** Whole JSON numbers only (a fractional or non-numeric value is absent). */
    private fun JsonObject.strictLong(name: String): Long? {
        val primitive = (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString) ?: return null
        if (primitive.booleanOrNull != null) return null
        primitive.longOrNull?.let { return it }
        val double = primitive.doubleOrNull ?: return null
        if (!double.isFinite() || double != kotlin.math.floor(double) || kotlin.math.abs(double) >= 9.2e18) return null
        return double.toLong()
    }

    private fun JsonObject.finiteNumber(name: String): Double? {
        val primitive = (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString) ?: return null
        if (primitive.booleanOrNull != null) return null
        return primitive.doubleOrNull?.takeIf(Double::isFinite)
    }

    private fun JsonObject.nonNegative(vararg aliases: String): Long? =
        aliases.firstNotNullOfOrNull { strictLong(it) }?.coerceAtLeast(0)

    private fun JsonObject.percent(vararg aliases: String): Double? =
        aliases.firstNotNullOfOrNull { finiteNumber(it) }?.coerceIn(0.0, 100.0)

    private fun JsonObject.boundedOptional(name: String, maxChars: Int): String? =
        stringValue(name)?.trim()?.takeIf(String::isNotEmpty)?.take(maxChars)

    private fun JsonObject.boundedRequired(name: String, maxChars: Int): String? =
        stringValue(name)?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxChars }

    private fun JsonObject.firstBounded(vararg aliases: String): String? =
        aliases.firstNotNullOfOrNull { boundedOptional(it, MAX_FIELD_CHARS) }

    private fun JsonObject.validModelField(name: String, maxChars: Int): String? =
        stringValue(name)?.let { validModelValue(it, maxChars) }

    private fun validModelValue(value: String, maxChars: Int): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.length > maxChars || trimmed.startsWith('-')) return null
        if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return null
        return trimmed
    }
}

private fun Char.isISOControl(): Boolean = code in 0x00..0x1F || code in 0x7F..0x9F
