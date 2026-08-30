package com.unsupportedpastels.mercury.core.transcript

/**
 * Typed chat-event surface shared by both clients, mirroring the Hermes wire
 * event set. [ChatEventDecoder] owns tolerant bounded payload decoding while
 * each platform retains its native transport envelope and lifecycle.
 */
enum class UnsupportedBlockingKind {
    Secret,
    Sudo,
    TerminalRead,
    PreviewRead,
    WindowRead;

    /** The request event type string this kind answers. */
    val requestType: String
        get() = when (this) {
            Secret -> "secret.request"
            Sudo -> "sudo.request"
            TerminalRead -> "terminal.read.request"
            PreviewRead -> "preview.read.request"
            WindowRead -> "window.read.request"
        }

    /** The expire event type string for this kind. */
    val expireType: String
        get() = when (this) {
            Secret -> "secret.expire"
            Sudo -> "sudo.expire"
            TerminalRead -> "terminal.read.expire"
            PreviewRead -> "preview.read.expire"
            WindowRead -> "window.read.expire"
        }
}

/** Structured billing-wall descriptor from `message.complete`. */
data class BillingInfo(
    val provider: String? = null,
    val billingUrl: String? = null,
    val isNous: Boolean = false,
    val message: String? = null,
)

sealed interface ChatEvent {
    val sessionId: String

    data class MessageStart(override val sessionId: String, val text: String?) : ChatEvent
    data class MessageDelta(override val sessionId: String, val text: String) : ChatEvent

    /**
     * [text] is null when the server omitted it; consumers keep their streamed
     * buffer in that case rather than replacing it with empty content.
     */
    data class MessageComplete(
        override val sessionId: String,
        val text: String? = null,
        val status: String? = null,
        val error: String? = null,
        val reasoning: String? = null,
        val warning: String? = null,
        val failureReason: String? = null,
        val recoverable: Boolean = false,
        val billing: BillingInfo? = null,
    ) : ChatEvent

    /** Reasoning text; [replace] is true for authoritative `reasoning.available` snapshots. */
    data class ReasoningDelta(override val sessionId: String, val text: String, val replace: Boolean) : ChatEvent

    /** Interim assistant commentary sealed as its own segment before tool calls. */
    data class MessageInterim(override val sessionId: String, val text: String, val alreadyStreamed: Boolean) : ChatEvent

    /** The model is generating arguments for a tool. */
    data class ToolGenerating(override val sessionId: String, val name: String) : ChatEvent

    /** Live session title rename pushed by the server. */
    data class SessionTitle(override val sessionId: String, val title: String) : ChatEvent

    /** Tolerant runtime metadata patch (`session.info`). */
    data class SessionInfo(
        override val sessionId: String,
        val storedSessionId: String? = null,
        val model: String? = null,
        val provider: String? = null,
        val reasoningEffort: String? = null,
        val fastMode: Boolean? = null,
        val title: String? = null,
        val running: Boolean? = null,
    ) : ChatEvent

    data class Error(override val sessionId: String, val message: String) : ChatEvent
    data class ToolStart(
        override val sessionId: String,
        val toolId: String,
        val name: String,
        val context: String?,
    ) : ChatEvent

    data class ToolComplete(
        override val sessionId: String,
        val toolId: String,
        val name: String,
        val summary: String?,
    ) : ChatEvent

    data class StatusUpdate(override val sessionId: String, val kind: String, val text: String) : ChatEvent

    data class ClarifyRequest(
        override val sessionId: String,
        val requestId: String,
        val question: String,
        val choices: List<String>,
        val multiSelect: Boolean,
    ) : ChatEvent

    data class ClarifyExpire(override val sessionId: String, val requestId: String) : ChatEvent

    data class ApprovalRequest(
        override val sessionId: String,
        val requestId: String?,
        val command: String?,
        val description: String?,
        val choices: List<String>,
    ) : ChatEvent

    data class ApprovalExpire(override val sessionId: String, val requestId: String) : ChatEvent

    data class UnsupportedBlockingRequest(
        override val sessionId: String,
        val kind: UnsupportedBlockingKind,
        val requestId: String,
        val prompt: String?,
    ) : ChatEvent

    data class UnsupportedBlockingExpire(
        override val sessionId: String,
        val kind: UnsupportedBlockingKind,
        val requestId: String,
    ) : ChatEvent
}
