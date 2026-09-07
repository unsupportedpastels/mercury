package com.unsupportedpastels.mercury.core.transcript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Tolerant, bounded decoder for the payload of an official Hermes `event`
 * notification. Transport envelopes and frame limits remain platform-owned;
 * recognized event semantics are decoded once for both clients.
 */
object ChatEventDecoder {
    const val MAX_EVENT_ID_CHARS = 256
    const val MAX_EVENT_NAME_CHARS = 256
    const val MAX_EVENT_TEXT_CHARS = 4_096
    /** Shown when a terminal `error` event carries a missing or blank message. */
    const val ERROR_MESSAGE_FALLBACK = "Hermes reported an error"
    const val MAX_MESSAGE_TEXT_CHARS = 1024 * 1024
    const val MAX_EVENT_CONTEXT_CHARS = 4_096
    const val MAX_EVENT_CHOICE_CHARS = 256
    const val MAX_EVENT_CHOICES = 32
    /** Summary shown for a `delegate_task` completion that only dispatched background work. */
    const val BACKGROUND_DELEGATION_SUMMARY = "Started background tasks"

    private const val MAX_MODEL_PROVIDER_CHARS = 128
    private const val MAX_MODEL_ID_CHARS = 512

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(type: String, sessionId: String, payloadJson: String): ChatEvent? {
        if (type.length > MAX_EVENT_NAME_CHARS) return null
        val boundedSessionId = sessionId.trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_EVENT_ID_CHARS }
            ?: return null
        val payload = runCatching { json.parseToJsonElement(payloadJson) as? JsonObject }
            .getOrNull()
            ?: return null

        return when (type) {
            "message.start" -> ChatEvent.MessageStart(
                boundedSessionId,
                payload.optionalText("text", MAX_MESSAGE_TEXT_CHARS),
            )
            "message.delta" -> payload.boundedText("text", MAX_MESSAGE_TEXT_CHARS)
                ?.let { ChatEvent.MessageDelta(boundedSessionId, it) }
            "message.complete" -> ChatEvent.MessageComplete(
                sessionId = boundedSessionId,
                text = payload.optionalText("text", MAX_MESSAGE_TEXT_CHARS),
                status = payload.boundedOptional("status", MAX_EVENT_NAME_CHARS),
                error = payload.boundedOptional("error", MAX_EVENT_TEXT_CHARS),
                reasoning = payload.boundedText("reasoning", MAX_MESSAGE_TEXT_CHARS),
                warning = payload.boundedOptional("warning", MAX_EVENT_TEXT_CHARS),
                failureReason = payload.boundedOptional("failure_reason", MAX_EVENT_TEXT_CHARS),
                recoverable = payload.booleanValue("recoverable") ?: false,
                billing = (payload["billing"] as? JsonObject)?.let { billing ->
                    BillingInfo(
                        provider = billing.boundedOptional("provider", MAX_MODEL_PROVIDER_CHARS),
                        billingUrl = billing.boundedOptional("billing_url", MAX_EVENT_TEXT_CHARS),
                        isNous = billing.booleanValue("is_nous") ?: false,
                        message = billing.boundedOptional("message", MAX_EVENT_TEXT_CHARS),
                    )
                },
            )
            "reasoning.delta", "reasoning.available" ->
                payload.boundedText("text", MAX_MESSAGE_TEXT_CHARS)?.let {
                    ChatEvent.ReasoningDelta(boundedSessionId, it, replace = type == "reasoning.available")
                }
            "message.interim" -> payload.boundedText("text", MAX_MESSAGE_TEXT_CHARS)?.let {
                ChatEvent.MessageInterim(
                    boundedSessionId,
                    it,
                    alreadyStreamed = payload.booleanValue("already_streamed") ?: false,
                )
            }
            "tool.generating" -> payload.boundedRequired("name", MAX_EVENT_NAME_CHARS)
                ?.let { ChatEvent.ToolGenerating(boundedSessionId, it) }
            "session.title" -> payload.boundedRequired("title", MAX_EVENT_NAME_CHARS)
                ?.let { ChatEvent.SessionTitle(boundedSessionId, it) }
            "session.info" -> ChatEvent.SessionInfo(
                sessionId = boundedSessionId,
                storedSessionId = payload.boundedOptional("stored_session_id", MAX_EVENT_ID_CHARS),
                model = payload.boundedOptional("model", MAX_MODEL_ID_CHARS),
                provider = payload.boundedOptional("provider", MAX_MODEL_PROVIDER_CHARS),
                reasoningEffort = payload.boundedOptional("reasoning_effort", MAX_EVENT_NAME_CHARS),
                fastMode = payload.booleanValue("fast_mode") ?: payload.booleanValue("fast"),
                title = payload.boundedOptional("title", MAX_EVENT_NAME_CHARS),
                running = payload.booleanValue("running"),
            )
            // A terminal error with no usable message still ends the turn on
            // both platforms; a generic fallback keeps the UI from spinning.
            "error" -> ChatEvent.Error(
                boundedSessionId,
                payload.boundedOptional("message", MAX_EVENT_TEXT_CHARS) ?: ERROR_MESSAGE_FALLBACK,
            )
            "tool.start" -> {
                val toolId = payload.boundedRequired("tool_id", MAX_EVENT_ID_CHARS)
                val name = payload.boundedRequired("name", MAX_EVENT_NAME_CHARS)
                if (toolId == null || name == null) null else ChatEvent.ToolStart(
                    boundedSessionId,
                    toolId,
                    name,
                    payload.boundedOptional("context", MAX_EVENT_CONTEXT_CHARS),
                )
            }
            "tool.complete" -> {
                val toolId = payload.boundedRequired("tool_id", MAX_EVENT_ID_CHARS)
                val name = payload.boundedRequired("name", MAX_EVENT_NAME_CHARS)
                if (toolId == null || name == null) null else ChatEvent.ToolComplete(
                    boundedSessionId,
                    toolId,
                    name,
                    if (name == "delegate_task" && payload.isDispatchedBackgroundDelegation()) {
                        BACKGROUND_DELEGATION_SUMMARY
                    } else {
                        payload.boundedOptional("summary", MAX_EVENT_TEXT_CHARS)
                    },
                )
            }
            "status.update" -> {
                val kind = payload.boundedRequired("kind", MAX_EVENT_NAME_CHARS)
                val text = payload.boundedRequired("text", MAX_EVENT_TEXT_CHARS)
                if (kind == null || text == null) null else ChatEvent.StatusUpdate(boundedSessionId, kind, text)
            }
            "clarify.request" -> {
                val requestId = payload.boundedRequired("request_id", MAX_EVENT_ID_CHARS)
                val question = payload.boundedRequired("question", MAX_EVENT_TEXT_CHARS)
                if (requestId == null || question == null) null else ChatEvent.ClarifyRequest(
                    boundedSessionId,
                    requestId,
                    question,
                    payload.boundedChoices(),
                    payload.booleanValue("multi_select") ?: false,
                )
            }
            "clarify.expire" -> payload.boundedRequired("request_id", MAX_EVENT_ID_CHARS)
                ?.let { ChatEvent.ClarifyExpire(boundedSessionId, it) }
            "approval.request" -> payload.boundedChoices().takeIf(List<String>::isNotEmpty)?.let { choices ->
                ChatEvent.ApprovalRequest(
                    boundedSessionId,
                    payload.boundedOptional("request_id", MAX_EVENT_ID_CHARS),
                    payload.boundedOptional("command", MAX_EVENT_TEXT_CHARS),
                    payload.boundedOptional("description", MAX_EVENT_TEXT_CHARS),
                    choices,
                )
            }
            "approval.expire" -> payload.boundedRequired("request_id", MAX_EVENT_ID_CHARS)
                ?.let { ChatEvent.ApprovalExpire(boundedSessionId, it) }
            else -> decodeUnsupportedBlocking(type, boundedSessionId, payload)
        }
    }

    private fun decodeUnsupportedBlocking(type: String, sessionId: String, payload: JsonObject): ChatEvent? {
        val requestKind = UnsupportedBlockingKind.entries.firstOrNull { it.requestType == type }
        if (requestKind != null) {
            return payload.boundedRequired("request_id", MAX_EVENT_ID_CHARS)?.let {
                ChatEvent.UnsupportedBlockingRequest(
                    sessionId,
                    requestKind,
                    it,
                    payload.boundedOptional("prompt", MAX_EVENT_TEXT_CHARS),
                )
            }
        }
        val expireKind = UnsupportedBlockingKind.entries.firstOrNull { it.expireType == type }
        return expireKind?.let { kind ->
            payload.boundedRequired("request_id", MAX_EVENT_ID_CHARS)?.let {
                ChatEvent.UnsupportedBlockingExpire(sessionId, kind, it)
            }
        }
    }

    /** A delegate_task result that dispatched background tasks rather than finishing inline. */
    private fun JsonObject.isDispatchedBackgroundDelegation(): Boolean {
        val result = this["result"] as? JsonObject ?: return false
        return result.stringValue("status") == "dispatched" && result.stringValue("mode") == "background"
    }

    private fun JsonObject.boundedChoices(): List<String> =
        (this["choices"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_EVENT_CHOICE_CHARS }
            .distinct()
            .take(MAX_EVENT_CHOICES)

    private fun JsonObject.boundedRequired(name: String, maxChars: Int): String? =
        stringValue(name)?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxChars }

    private fun JsonObject.boundedOptional(name: String, maxChars: Int): String? =
        stringValue(name)?.trim()?.takeIf(String::isNotEmpty)?.take(maxChars)

    /** Message text is never trimmed because leading whitespace can be a token boundary. */
    private fun JsonObject.boundedText(name: String, maxChars: Int): String? =
        stringValue(name)?.takeIf(String::isNotEmpty)?.take(maxChars)

    /** Completion/start text distinguishes an explicit empty value from an omitted field. */
    private fun JsonObject.optionalText(name: String, maxChars: Int): String? =
        stringValue(name)?.take(maxChars)

    private fun JsonObject.stringValue(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanValue(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull
}
