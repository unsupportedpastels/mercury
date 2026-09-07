package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectSessionsResult
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectTreeResult
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.hermesandroid.app.RunTodoStatus
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.mercury.core.rpc.InteractionStatus
import com.unsupportedpastels.mercury.core.rpc.RpcResultDecoder
import com.unsupportedpastels.mercury.core.rpc.RpcResultException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun parseInteractionResponse(result: JsonObject): HermesChatResponse =
    HermesChatResponse(decodeShared { RpcResultDecoder.interactionResponse(result.toString()) }.toAndroidStatus())

internal fun parseModelOptions(result: JsonObject): ModelOptions {
    val decoded = RpcResultDecoder.modelOptions(result.toString())
    return ModelOptions(
        current = decoded.current?.let { ModelSelection(it.provider, it.model) },
        providers = decoded.providers.map { provider ->
            ModelProviderOption(
                slug = provider.slug,
                name = provider.name,
                models = provider.models,
                capabilities = provider.capabilities.mapValues { (_, value) ->
                    ModelCapabilities(fast = value.fast, reasoning = value.reasoning)
                },
            )
        },
    )
}

internal fun parseResumeResult(
    result: JsonObject,
    requestedDurableSessionId: DurableSessionId,
): ResumedChatSession {
    val decoded = decodeShared { RpcResultDecoder.resume(result.toString(), requestedDurableSessionId.value) }
    val runtimeSessionId = runCatching { RuntimeSessionId(decoded.runtimeSessionId) }.getOrNull()
        ?: throw HermesChatProtocolException("Resume response was incomplete")
    return ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = decoded.durableSessionId?.let(::DurableSessionId),
        resumed = decoded.resumed,
        messages = decoded.messagesJson.toJsonObjects(),
        running = decoded.running,
        inflight = if (decoded.hasInflight) {
            InflightPrompt(decoded.inflightUser, decoded.inflightAssistant, decoded.inflightStreaming)
        } else {
            null
        },
        model = decoded.model,
        provider = decoded.provider,
        reasoningEffort = decoded.reasoningEffort,
    )
}

internal fun parseProjectTreeResult(result: JsonObject): ProjectTreeResult {
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

internal fun parseProjectSessionsResult(
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

internal fun parseProjectSummary(
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

private const val MAX_TODO_PARSE_DEPTH = 2
private val todoJson = Json { ignoreUnknownKeys = true }

/**
 * `todo` is an ordinary official tool event. Its live list has appeared as
 * `todos`, and older released payloads wrap the same list in `result`/`args`.
 * Parse only that bounded, typed shape; never turn arbitrary tool text into
 * activity state.
 */
internal fun JsonObject.boundedTodoItems(): List<RunTodoItem>? {
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

internal fun sameHostPath(first: String, second: String): Boolean {
    fun normalized(value: String): String {
        val slashed = value.replace('\\', '/')
        val trimmed = slashed.trimEnd('/').ifEmpty { "/" }
        return if (trimmed.length >= 2 && trimmed[1] == ':') trimmed.lowercase() else trimmed
    }
    return normalized(first) == normalized(second)
}

internal fun boundedRpcInput(
    value: String,
    maxChars: Int,
    label: String,
    allowBlank: Boolean = false,
): String {
    if (value.length > maxChars) throw HermesChatProtocolException("Hermes $label is too long")
    if (!allowBlank && value.isBlank()) throw HermesChatProtocolException("Hermes $label must not be blank")
    return value
}

internal fun boundedModelInput(value: String, maxChars: Int, label: String): String {
    val bounded = boundedRpcInput(value.trim(), maxChars, label)
    if (bounded.hasControlCharacters() || bounded.any(Char::isWhitespace) || bounded.startsWith('-')) {
        throw HermesChatProtocolException("Hermes $label was invalid")
    }
    return bounded
}

internal fun String.hasControlCharacters(): Boolean = any(Char::isISOControl)

internal fun JsonObject.boundedRequired(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars }

internal fun JsonObject.boundedOptional(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(maxChars)

internal fun JsonObject.boundedProcessRequired(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars && it.isSafeProcessText() }

internal fun JsonObject.boundedProcessOptional(name: String, maxChars: Int): String? =
    stringValue(name)
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars && it.isSafeProcessText() }

private fun String.isSafeProcessText(): Boolean = all {
    !it.isISOControl() || it == '\n' || it == '\r' || it == '\t'
}


internal fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.booleanValue(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.longValue(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

/** Runs a shared decoder and surfaces its rejection as the Android protocol exception. */
internal inline fun <T> decodeShared(block: () -> T): T = try {
    block()
} catch (rejected: RpcResultException) {
    throw HermesChatProtocolException(rejected.message ?: "Hermes response was incomplete")
}

internal fun InteractionStatus.toAndroidStatus(): HermesChatResponseStatus = when (this) {
    InteractionStatus.Ok -> HermesChatResponseStatus.Ok
    InteractionStatus.Expired -> HermesChatResponseStatus.Expired
    InteractionStatus.Interrupted -> HermesChatResponseStatus.Interrupted
    InteractionStatus.Resolved -> HermesChatResponseStatus.Resolved
    InteractionStatus.Unknown -> HermesChatResponseStatus.Unknown
}

private val sharedRowJson = Json { ignoreUnknownKeys = true }

/** Message rows come back from the shared decoder as JSON text; the transcript restore reads objects. */
internal fun List<String>.toJsonObjects(): List<JsonObject> =
    mapNotNull { runCatching { sharedRowJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
