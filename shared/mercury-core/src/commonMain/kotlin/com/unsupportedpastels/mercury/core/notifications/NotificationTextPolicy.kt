package com.unsupportedpastels.mercury.core.notifications

import com.unsupportedpastels.mercury.core.transcript.InterruptSentinel

/** Turn-completion outcome derived from the wire status. */
enum class CompletionStatus { FINISHED, FAILED, CANCELLED }

/** Blocking-input request kinds that produce attention notifications. */
enum class NotificationInputKind { APPROVAL, CLARIFICATION, SECURE_INPUT }

/**
 * Notification text decided once for both clients: previews, headings, wire
 * status mapping, and fallback prompts. All strings are user-visible contract;
 * platforms must surface them verbatim.
 *
 * Both apps are single-locale today. If localization ever lands, this policy
 * shifts to emitting decision keys plus parameters and string rendering
 * returns to the platforms.
 */
object NotificationTextPolicy {
    const val MAX_PREVIEW_CHARS = 240
    const val DEFAULT_PREVIEW_LINES = 3

    /** Shown when a blocking approval request carries no prompt text. */
    const val APPROVAL_FALLBACK = "Authorization is required to continue"
    /** Shown when a blocking clarification request carries no prompt text. */
    const val CLARIFICATION_FALLBACK = "Clarification is required to continue"
    /** Shown when a blocking secure-input request carries no prompt text. */
    const val SECURE_INPUT_FALLBACK = "Secure input is required to continue"

    /**
     * Strip markdown headings/emphasis from a final response, keep the first
     * [maxLines] non-blank lines, cap at [MAX_PREVIEW_CHARS], and fall back to
     * "Response completed". The interrupt sentinel is cancellation metadata,
     * not a response — it falls through to the fallback instead of being
     * quoted.
     */
    fun finalResponsePreview(
        text: String,
        maxLines: Int = DEFAULT_PREVIEW_LINES,
    ): String {
        val source = if (InterruptSentinel.isInterruptSentinel(text)) "" else text
        val cleaned = source
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.matches(Regex("^#{1,6}\\s+.+"))) return@mapNotNull null
                trimmed
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
            }
            .filter(String::isNotBlank)
            .take(maxLines.coerceAtLeast(1))
            .joinToString("\n")
            .trim()
        return cleaned.ifEmpty { "Response completed" }
            .take(MAX_PREVIEW_CHARS)
    }

    /** Preview for a blocking-input prompt: cap first, then trim. */
    fun inputPreview(text: String): String =
        text.take(MAX_PREVIEW_CHARS).trim()

    fun completionStatusFromWire(status: String?): CompletionStatus =
        when (status?.lowercase()) {
            "error", "failed" -> CompletionStatus.FAILED
            "cancelled", "canceled", "interrupted" -> CompletionStatus.CANCELLED
            else -> CompletionStatus.FINISHED
        }

    fun completionHeading(status: CompletionStatus): String = when (status) {
        CompletionStatus.FINISHED -> "Mercury finished"
        CompletionStatus.FAILED -> "Mercury task failed"
        CompletionStatus.CANCELLED -> "Mercury task was cancelled"
    }

    fun inputHeading(kind: NotificationInputKind): String = when (kind) {
        NotificationInputKind.APPROVAL -> "Hermes needs approval"
        NotificationInputKind.CLARIFICATION -> "Hermes needs your input"
        NotificationInputKind.SECURE_INPUT -> "Hermes needs secure input"
    }

    /** Title of the ongoing "working" surface (Android foreground service). */
    fun activeTurnTitle(count: Int): String =
        if (count <= 1) "Hermes is working" else "Hermes is working in $count sessions"
}
