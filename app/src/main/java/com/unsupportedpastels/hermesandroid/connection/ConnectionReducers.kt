package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.CurrentModelInfo
import com.unsupportedpastels.hermesandroid.gateway.HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.relay.RelayConnectionException
import com.unsupportedpastels.hermesandroid.relay.RelayConnectionFailure
import com.unsupportedpastels.hermesandroid.session.BulkDeleteSelectionDecision
import com.unsupportedpastels.mercury.core.transcript.InterruptSentinel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun bulkDeletePolicyError(decision: BulkDeleteSelectionDecision): String = when {
    decision.tooMany -> "Select at most 500 sessions"
    decision.invalidSessionIds.isNotEmpty() -> "Selection contains a session that is no longer visible"
    decision.blockedSessionIds.isNotEmpty() -> "Stop active work before bulk deleting sessions"
    else -> "Select at least one durable session"
}

internal fun resolveModelCapabilities(
    currentInfo: CurrentModelInfo?,
    options: ModelOptions?,
    selection: ModelSelection,
): ModelCapabilities? {
    val currentCapabilities = currentInfo
        ?.takeIf {
            it.provider == selection.provider &&
                modelIdentifiersMatch(it.model, selection.model) &&
                it.capabilities.hasExplicitCapability
        }
        ?.capabilities
    if (currentCapabilities != null) return currentCapabilities

    val provider = options?.providers?.firstOrNull { it.slug == selection.provider } ?: return null
    provider.capabilities[selection.model]
        ?.takeIf(ModelCapabilities::hasExplicitCapability)
        ?.let { return it }
    val matchingCapabilities = provider.capabilities
        .filterKeys { modelIdentifiersMatch(it, selection.model) }
        .values
        .filter(ModelCapabilities::hasExplicitCapability)
        .distinct()
    return matchingCapabilities.singleOrNull()
}

internal fun modelIdentifiersMatch(first: String?, second: String?): Boolean {
    if (first == null || second == null) return false
    if (first == second) return true
    val firstQualified = '/' in first
    val secondQualified = '/' in second
    return firstQualified != secondQualified &&
        first.substringAfterLast('/') == second.substringAfterLast('/')
}

internal fun chatMessageFromJson(row: JsonObject): ChatMessage? {
    val role = when (row["role"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
        "user" -> ChatMessageRole.User
        "assistant" -> ChatMessageRole.Assistant
        "system" -> ChatMessageRole.System
        "tool" -> ChatMessageRole.Tool
        else -> return null
    }
    val text = when (role) {
        ChatMessageRole.Tool -> row.transcriptToolText()
        else -> row["content"]?.jsonPrimitive?.contentOrNull
            ?: row["text"]?.jsonPrimitive?.contentOrNull
    }
    val reasoning = if (role == ChatMessageRole.Assistant) {
        row.assistantReasoningText()
    } else {
        null
    }
    if (text == null && reasoning == null) return null
    // Persisted interrupt sentinels are cancellation metadata written by
    // servers that predate the upstream transcript fix; never render them.
    if (role == ChatMessageRole.Assistant &&
        text != null && InterruptSentinel.isInterruptSentinel(text)
    ) {
        return null
    }
    return ChatMessage(
        role = role,
        text = text.orEmpty(),
        reasoningText = reasoning.orEmpty(),
    )
}

internal fun parseRelaySessionRows(result: JsonObject): List<SessionSummary> =
    (result["sessions"] as? JsonArray)
        .orEmpty()
        .take(MAX_RELAY_SESSIONS)
        .mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            fun string(name: String): String? =
                (row[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            val id = string("id") ?: string("session_key") ?: return@mapNotNull null
            runCatching {
                SessionSummary(
                    id = DurableSessionId(id.take(256)),
                    title = string("title")?.take(MAX_SESSION_TITLE_CHARS) ?: "Untitled session",
                    workspacePath = string("cwd")?.take(4_096),
                    preview = string("preview")?.take(HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS),
                    lastActiveEpochSeconds = (row["last_active"] as? JsonPrimitive)?.doubleOrNull,
                    messageCount = (row["message_count"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0),
                    model = string("model")?.take(512),
                    provider = (string("provider") ?: string("billing_provider"))?.take(128),
                    profile = string("profile")?.take(64),
                    pinned = (row["pinned"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    archived = (row["archived"] as? JsonPrimitive)?.booleanOrNull ?: false,
                )
            }.getOrNull()
        }
        .distinctBy(SessionSummary::id)

internal fun parseRelayTranscriptRows(result: JsonObject): List<ChatMessage> =
    (result["messages"] as? JsonArray)
        .orEmpty()
        .take(100)
        .mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val role = when ((row["role"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "user" -> ChatMessageRole.User
                "assistant" -> ChatMessageRole.Assistant
                "system" -> ChatMessageRole.System
                "tool" -> ChatMessageRole.Tool
                else -> return@mapNotNull null
            }
            val text = when (role) {
                ChatMessageRole.Tool -> row.transcriptToolText()
                else -> (row["content"] as? JsonPrimitive)?.contentOrNull
                    ?: (row["text"] as? JsonPrimitive)?.contentOrNull
            }
            val reasoning = if (role == ChatMessageRole.Assistant) row.assistantReasoningText() else null
            if (text == null && reasoning == null) return@mapNotNull null
            if (role == ChatMessageRole.Assistant &&
                text != null && InterruptSentinel.isInterruptSentinel(text)
            ) {
                return@mapNotNull null
            }
            ChatMessage(role = role, text = text.orEmpty(), reasoningText = reasoning.orEmpty())
        }

private fun JsonObject.assistantReasoningText(): String? =
    sequenceOf("reasoning", "reasoning_content", "reasoning_details")
        .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull(String::isNotBlank)
        ?.take(HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS)

private fun JsonObject.transcriptToolText(): String? {
    val explicitText = (this["content"] as? JsonPrimitive)?.contentOrNull
        ?: (this["text"] as? JsonPrimitive)?.contentOrNull
    if (!explicitText.isNullOrBlank()) return explicitText
    val name = (this["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    val context = (this["context"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    return listOfNotNull(name, context?.takeUnless { it == name })
        .joinToString(" · ")
        .takeIf(String::isNotEmpty)
        ?.take(HERMES_CHAT_MAX_MESSAGE_TEXT_CHARS)
}

/** User-facing relay failure text, distinct per cause (iOS ConnectionController parity). */
internal fun relayConnectionErrorMessage(error: Throwable): String {
    val failure = generateSequence(error) { it.cause }.filterIsInstance<RelayConnectionException>().firstOrNull()?.failure
    return when (failure) {
        RelayConnectionFailure.RoutingRejected ->
            "The relay refused this phone's pairing token. Remove this relay and pair again from your host's Mercury Relay page."
        RelayConnectionFailure.NoHost ->
            "Your Hermes host isn't connected to the relay. Start Hermes on the host, then retry."
        RelayConnectionFailure.NotAuthorized ->
            "The host hasn't approved this device, or it was revoked. Approve it on the host, then retry."
        else -> "The relay or host is unreachable. Check that the host is online, then retry."
    }
}
