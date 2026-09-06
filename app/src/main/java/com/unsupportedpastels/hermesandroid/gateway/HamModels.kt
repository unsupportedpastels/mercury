package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val MAX_HAM_TEXT = 4096
private const val MAX_HAM_ROWS = 128
private const val MAX_HAM_CATEGORIES = 64
private const val MAX_HAM_FIELD = 512

/** Bounded result for an explicit active-turn steer. */
data class SessionSteerResult(val status: String, val text: String?)

data class SessionUsage(
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

data class ContextBreakdownCategory(
    val name: String,
    val tokens: Long? = null,
    val percent: Double? = null,
)

data class SessionContextBreakdown(
    val categories: List<ContextBreakdownCategory> = emptyList(),
    val usedTokens: Long? = null,
    val maxTokens: Long? = null,
    val percent: Double? = null,
)

data class SessionCompressResult(
    val status: String? = null,
    val aborted: Boolean = false,
    val messages: List<JsonObject> = emptyList(),
    val info: String? = null,
    val usage: SessionUsage? = null,
)

data class SessionUndoResult(val removed: Int)

data class SessionBranchResult(
    val runtimeSessionId: RuntimeSessionId?,
    val durableSessionId: DurableSessionId,
    val title: String?,
    val messages: List<JsonObject> = emptyList(),
)

data class DelegationPauseResult(val paused: Boolean)
data class SubagentInterruptResult(val found: Boolean, val subagentId: String?)
data class SubagentSteerResult(val status: String, val text: String?)

data class CronJob(
    val jobId: String,
    val name: String,
    val schedule: String,
    val enabled: Boolean? = null,
    val state: String? = null,
    val nextRunAt: String? = null,
    val lastRunAt: String? = null,
    val lastStatus: String? = null,
    val lastDeliveryError: String? = null,
)

/** Stable scope for REST cron data; results must never cross origins, profiles, or jobs. */
data class CronJobScope(
    val serverOrigin: String,
    val profile: String,
    val jobId: String,
)

data class CronJobRun(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val source: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val profile: String? = null,
    val cwd: String? = null,
    val startedAt: Double? = null,
    val endedAt: Double? = null,
    val lastActive: Double? = null,
    val isActive: Boolean? = null,
    val status: String? = null,
    val finishReason: String? = null,
    val error: String? = null,
    val messageCount: Long? = null,
    val toolCallCount: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

sealed interface CronJobRunsState {
    data object Collapsed : CronJobRunsState
    data object Loading : CronJobRunsState
    data class Cached(val runs: List<CronJobRun>) : CronJobRunsState
    data class Ready(val runs: List<CronJobRun>) : CronJobRunsState
    data object Unsupported : CronJobRunsState
    data class Error(val message: String) : CronJobRunsState
}

enum class CronRestCapability {
    Unknown,
    Supported,
    Unsupported,
}

sealed interface CronJobsState {
    data object Idle : CronJobsState
    data object Loading : CronJobsState
    data class Ready(
        val jobs: List<CronJob>,
        val profile: String = "default",
    ) : CronJobsState
    data object Unsupported : CronJobsState
    data class Error(val message: String) : CronJobsState
}

/**
 * Lifecycle controls forwarded to `cron.manage`, limited to the actions the
 * gateway accepts: enable/disable ride the server's `resume`/`pause` verbs.
 */
enum class CronJobAction(val wireValue: String, val failureVerb: String) {
    Enable("resume", "enable"),
    Disable("pause", "disable"),
}

/** Parse only the bounded, display-safe subset of `cron.manage list`. */
fun parseCronJobs(result: JsonObject): List<CronJob> =
    (result["jobs"] as? JsonArray)
        .orEmpty()
        .take(MAX_HAM_ROWS)
        .mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row.text("job_id", MAX_HAM_FIELD) ?: row.text("id", MAX_HAM_FIELD) ?: return@mapNotNull null
            val name = row.text("name", MAX_HAM_FIELD) ?: return@mapNotNull null
            val schedule = row.text("schedule", MAX_HAM_FIELD) ?: return@mapNotNull null
            CronJob(
                jobId = id,
                name = name,
                schedule = schedule,
                enabled = row["enabled"].asBoolean(),
                state = row.text("state", MAX_HAM_FIELD),
                nextRunAt = row.scalarText("next_run_at"),
                lastRunAt = row.scalarText("last_run_at"),
                lastStatus = row.text("last_status", MAX_HAM_FIELD),
                lastDeliveryError = row.text("last_delivery_error", MAX_HAM_FIELD)
                    ?: row.text("delivery_error", MAX_HAM_FIELD),
            )
        }
        .distinctBy(CronJob::jobId)

/** Parse the refreshed job returned by the official trigger endpoint. */
fun parseCronJob(result: JsonObject): CronJob {
    val id = result.text("job_id", MAX_HAM_FIELD)
        ?: result.text("id", MAX_HAM_FIELD)
        ?: throw IllegalArgumentException("Cron job response was incomplete")
    val name = result.text("name", MAX_HAM_FIELD)
        ?: throw IllegalArgumentException("Cron job response was incomplete")
    val schedule = result.text("schedule", MAX_HAM_FIELD)
        ?: throw IllegalArgumentException("Cron job response was incomplete")
    return CronJob(
        jobId = id,
        name = name,
        schedule = schedule,
        enabled = result["enabled"].asBoolean(),
        state = result.text("state", MAX_HAM_FIELD),
        nextRunAt = result.scalarText("next_run_at"),
        lastRunAt = result.scalarText("last_run_at"),
        lastStatus = result.text("last_status", MAX_HAM_FIELD),
        lastDeliveryError = result.text("last_delivery_error", MAX_HAM_FIELD)
            ?: result.text("delivery_error", MAX_HAM_FIELD),
    )
}

/** Parse the official `{runs, limit}` envelope with a client-side hard bound. */
fun parseCronJobRuns(
    result: JsonObject,
    scope: CronJobScope,
    limit: Int = MAX_HAM_ROWS,
): List<CronJobRun> {
    require(scope.serverOrigin.isNotBlank() && scope.profile.isNotBlank() && scope.jobId.isNotBlank()) {
        "Cron run scope is incomplete"
    }
    val rows = result["runs"] as? JsonArray
        ?: throw IllegalArgumentException("Cron runs response was incomplete")
    return rows.take(limit.coerceIn(0, MAX_HAM_ROWS)).mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = row.text("id", MAX_HAM_FIELD)
            ?: row.text("session_key", MAX_HAM_FIELD)
            ?: return@mapNotNull null
        CronJobRun(
            id = id,
            title = row.text("title", MAX_HAM_FIELD),
            preview = row.text("preview", MAX_HAM_TEXT),
            source = row.text("source", MAX_HAM_FIELD),
            model = row.text("model", MAX_HAM_FIELD),
            provider = row.text("provider", MAX_HAM_FIELD),
            profile = row.text("profile", MAX_HAM_FIELD),
            cwd = row.text("cwd", MAX_HAM_FIELD),
            startedAt = row.scalarDouble("started_at"),
            endedAt = row.scalarDouble("ended_at"),
            lastActive = row.scalarDouble("last_active"),
            isActive = row["is_active"].asBoolean(),
            status = row.text("status", MAX_HAM_FIELD),
            finishReason = row.text("finish_reason", MAX_HAM_FIELD),
            error = row.text("error", MAX_HAM_FIELD),
            messageCount = row.long("message_count"),
            toolCallCount = row.long("tool_call_count"),
            inputTokens = row.long("input_tokens"),
            outputTokens = row.long("output_tokens"),
        )
    }.distinctBy(CronJobRun::id)
}

internal fun parseSessionUsage(result: JsonObject): SessionUsage = SessionUsage(
    inputTokens = result.long("input_tokens", "input", "prompt_tokens"),
    outputTokens = result.long("output_tokens", "output", "completion_tokens"),
    totalTokens = result.long("total_tokens", "total"),
    contextUsedTokens = result.long("context_used_tokens", "context_used", "used_tokens"),
    contextMaxTokens = result.long("context_max_tokens", "context_max", "max_tokens"),
    contextPercent = result.number("context_percent", "context_percentage", "percent"),
    calls = result.long("calls", "request_count", "requests"),
    creditsLines = (result["credits_lines"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)
            ?.takeIf { primitive -> primitive.isString }
            ?.contentOrNull
            ?.take(MAX_HAM_FIELD) }
        .take(MAX_HAM_ROWS),
    rawInfo = result.text("info", MAX_HAM_FIELD),
)

internal fun parseContextBreakdown(result: JsonObject): SessionContextBreakdown {
    val rows = (result["categories"] as? JsonArray)
        ?: (result["breakdown"] as? JsonArray)
        ?: JsonArray(emptyList())
    val categories = rows.take(MAX_HAM_CATEGORIES).mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val name = row.text("name", MAX_HAM_FIELD)
            ?: row.text("category", MAX_HAM_FIELD)
            ?: row.text("label", MAX_HAM_FIELD)
            ?: return@mapNotNull null
        ContextBreakdownCategory(
            name = name,
            tokens = row.long("tokens", "token_count", "count"),
            percent = row.number("percent", "percentage"),
        )
    }.distinctBy(ContextBreakdownCategory::name)
    return SessionContextBreakdown(
        categories = categories,
        usedTokens = result.long("used_tokens", "context_used_tokens", "context_used"),
        maxTokens = result.long("max_tokens", "context_max_tokens", "context_max"),
        percent = result.number("percent", "context_percent"),
    )
}

internal fun parseCompressResult(result: JsonObject): SessionCompressResult {
    val messages = (result["messages"] as? JsonArray)
        .orEmpty().take(MAX_HAM_ROWS).mapNotNull { it as? JsonObject }
    return SessionCompressResult(
        status = result.text("status", MAX_HAM_FIELD),
        aborted = result["aborted"].asBoolean() == true ||
            result.text("status", MAX_HAM_FIELD)?.lowercase() in setOf("aborted", "cancelled", "canceled"),
        messages = messages,
        info = result.text("info", MAX_HAM_FIELD),
        usage = (result["usage"] as? JsonObject)?.let(::parseSessionUsage),
    )
}

internal fun parseBranchResult(result: JsonObject): SessionBranchResult {
    val durable = result.text("stored_session_id", MAX_HAM_FIELD)
        ?: result.text("durable_session_id", MAX_HAM_FIELD)
        ?: throw HermesChatProtocolException("Branch response was incomplete")
    return SessionBranchResult(
        runtimeSessionId = result.text("session_id", MAX_HAM_FIELD)?.let { value ->
            runCatching { RuntimeSessionId(value) }.getOrNull()
        },
        durableSessionId = DurableSessionId(durable),
        title = result.text("title", MAX_HAM_FIELD),
        messages = (result["messages"] as? JsonArray).orEmpty().take(MAX_HAM_ROWS).mapNotNull { it as? JsonObject },
    )
}

private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.text(key: String, max: Int): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.take(max)
private fun JsonObject.scalarText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.take(MAX_HAM_FIELD)
private fun JsonObject.scalarDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
private fun JsonObject.long(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0)
}
private fun JsonObject.number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
}
