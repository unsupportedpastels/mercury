package com.unsupportedpastels.mercury.core.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Hermes server→client JSON-RPC requests (`hermes serve`, `tui_gateway/server_requests.py`).
 *
 * Current Hermes no longer pushes `clarify.request` / `approval.request` / `secret.request` /
 * `sudo.request` notifications. It sends a JSON-RPC *request* frame
 * (`{"jsonrpc": "2.0", "id": "srq-…", "method": "clarify", "params": {...}}`) and waits for an
 * answer, and it only does so for a connection that advertised
 * `client.capabilities {"server_requests": true}`. For every other client approvals fail closed
 * and clarify fails open (the agent proceeds as if the user skipped the question).
 *
 * This policy is the shared decision for each request method:
 * - [Disposition.Surface]: re-shape the request into the notification payload that
 *   [com.unsupportedpastels.mercury.core.transcript.ChatEventDecoder] already decodes, so the
 *   existing clarify / approval / secure-input cards render it unchanged. Answers go back through
 *   official RPCs that report whether they landed: `clarify.lock` per question, `approval.respond`
 *   for approvals (it resolves the queue entry the request is backed by), and `request.answer`
 *   carrying [valueResult] for one-string prompts.
 * - [Disposition.Decline]: answer with a JSON-RPC error frame so the request settles now instead of
 *   after the server deadline. Desktop window bridges get [NOT_SHOWN_CODE] (the same answer the
 *   desktop gives from a window that does not show the session); anything else with no mobile
 *   surface gets "method not found", which Hermes treats as "this client has no handler".
 */
object ServerRequestPolicy {
    const val CAPABILITIES_METHOD = "client.capabilities"
    const val CANCEL_EVENT = "request.cancel"
    const val CLARIFY_LOCK_METHOD = "clarify.lock"
    const val REQUEST_ANSWER_METHOD = "request.answer"

    /** Hermes `NOT_SHOWN_CODE`: no window of this client shows that session. */
    const val NOT_SHOWN_CODE = 4404L
    const val NOT_SHOWN_MESSAGE = "This client does not show that surface"
    const val METHOD_NOT_FOUND_CODE = -32601L
    const val METHOD_NOT_FOUND_MESSAGE = "Method not found"

    const val MAX_REQUEST_ID_CHARS = 256
    const val MAX_METHOD_CHARS = 128
    private const val MAX_QUESTIONS = 32
    private const val MAX_OPEN_REQUESTS = 32

    private val json = Json { ignoreUnknownKeys = true }

    /** Bridges only the desktop window showing the session can answer. */
    private val windowOwnedMethods = setOf(
        "terminal.read",
        "preview.read",
        "preview.act",
        "window.read",
        "tour",
        "theme.apply",
    )

    sealed interface Disposition {
        /**
         * Render through the existing card for [eventType]. [legacyRequestId] is the id that card
         * answers with: the server request id, except for approvals, which answer with the approval
         * queue's own `request_id`. [questionIds] lists a clarify request's unanswered questions.
         */
        data class Surface(
            val eventType: String,
            val payloadJson: String,
            val legacyRequestId: String,
            val questionIds: List<String> = emptyList(),
        ) : Disposition

        /** Answer with a JSON-RPC error frame. */
        data class Decline(val code: Long, val message: String) : Disposition

        /** Leave it alone (nothing left to ask). */
        data object Ignore : Disposition
    }

    /** Bounded string id of a server request frame; client-minted ids are integers, so never match. */
    fun requestId(frame: JsonObject): String? =
        (frame["id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_REQUEST_ID_CHARS }

    /** Method of a server request frame; null for the `event` notification and for responses. */
    fun method(frame: JsonObject): String? =
        (frame["method"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_METHOD_CHARS && it != "event" }

    /** Decides what to do with one server request. [paramsJson] is the frame's `params` object. */
    fun classify(method: String, requestId: String, paramsJson: String): Disposition {
        val params = parseObject(paramsJson) ?: JsonObject(emptyMap())
        return when (method) {
            "clarify" -> clarify(requestId, params)
            "approval" -> approval(params)
            "secret" -> Disposition.Surface(
                eventType = "secret.request",
                payloadJson = buildJsonObject {
                    put("request_id", requestId)
                    params.string("prompt")?.let { put("prompt", it) }
                }.toString(),
                legacyRequestId = requestId,
            )
            "sudo" -> Disposition.Surface(
                eventType = "sudo.request",
                payloadJson = buildJsonObject { put("request_id", requestId) }.toString(),
                legacyRequestId = requestId,
            )
            in windowOwnedMethods -> Disposition.Decline(NOT_SHOWN_CODE, NOT_SHOWN_MESSAGE)
            else -> Disposition.Decline(METHOD_NOT_FOUND_CODE, METHOD_NOT_FOUND_MESSAGE)
        }
    }

    /** The legacy expire event for a withdrawn request of [method], or null when none applies. */
    fun expireEventType(method: String): String? = when (method) {
        "clarify" -> "clarify.expire"
        "approval" -> "approval.expire"
        "secret" -> "secret.expire"
        "sudo" -> "sudo.expire"
        else -> null
    }

    /** `client.capabilities` params: this connection answers server→client requests. */
    fun capabilitiesParams(): String = buildJsonObject { put("server_requests", true) }.toString()

    /** `ValueResult`: the answer to any one-string prompt (`""` = skipped / declined). */
    fun valueResult(value: String): String = buildJsonObject { put("value", value) }.toString()

    /** `request.answer` params: answer the open request [requestId] with [resultJson]. */
    fun requestAnswerParams(requestId: String, resultJson: String): String = buildJsonObject {
        put("id", requestId)
        put("result", parseObject(resultJson) ?: JsonObject(emptyMap()))
    }.toString()

    /**
     * `clarify.lock` params. Hermes sends every clarify as a batch (one `qid` per question); each
     * answer locks one question and the last lock resolves the request. A skipped question
     * ([ClarifyAnswerPolicy.SKIP_ANSWER]) locks as `null`, the wire's "skipped".
     */
    fun clarifyLockParams(requestId: String, questionId: String, answer: String): String = buildJsonObject {
        put("request_id", requestId)
        put("question_id", questionId)
        if (answer.isBlank()) put("answer", JsonNull) else put("answer", answer)
    }.toString()

    /** Error frame declining server request [requestId]. */
    fun errorFrame(requestId: String, code: Long, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    /**
     * Request frames carried in a `session.resume` result's `open_requests`: questions that were
     * already waiting when this client attached. Each entry is `{"id", "method", "params"}`.
     */
    fun openRequests(resultJson: String): List<JsonObject> {
        val result = parseObject(resultJson) ?: return emptyList()
        return (result["open_requests"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { requestId(it) != null && method(it) != null }
            .take(MAX_OPEN_REQUESTS)
    }

    private fun clarify(requestId: String, params: JsonObject): Disposition {
        // A reconnect replay carries the answers the server already locked under `answers`.
        val locked = (params["answers"] as? JsonObject)?.keys.orEmpty()
        val questions = (params["questions"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { row -> row.string("qid")?.let { it !in locked } == true }
            .take(MAX_QUESTIONS)
        if (questions.isEmpty()) return Disposition.Ignore
        return Disposition.Surface(
            eventType = "clarify.request",
            payloadJson = buildJsonObject {
                put("request_id", requestId)
                put("questions", JsonArray(questions))
            }.toString(),
            legacyRequestId = requestId,
            questionIds = questions.mapNotNull { it.string("qid") },
        )
    }

    private fun approval(params: JsonObject): Disposition {
        // `approval.respond` resolves the approval queue entry by its own id; the server then
        // withdraws this request with `request.cancel`. Without that id there is nothing to answer.
        val approvalId = params.string("request_id")?.takeIf { it.length <= MAX_REQUEST_ID_CHARS }
            ?: return Disposition.Decline(METHOD_NOT_FOUND_CODE, METHOD_NOT_FOUND_MESSAGE)
        val payload = JsonObject(params - "session_id" + ("request_id" to JsonPrimitive(approvalId)))
        return Disposition.Surface(
            eventType = "approval.request",
            payloadJson = payload.toString(),
            legacyRequestId = approvalId,
        )
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}
