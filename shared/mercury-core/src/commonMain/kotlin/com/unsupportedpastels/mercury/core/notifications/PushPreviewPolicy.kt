package com.unsupportedpastels.mercury.core.notifications

/** Deterministic, privacy-preserving preview decisions shared by native clients. */
object PushPreviewPolicy {
    data class Preferences(
        val completion: Boolean,
        val attention: Boolean,
        val includeTitle: Boolean,
        val includeResponseExcerpt: Boolean,
    )
    data class Content(val kind: String, val title: String?, val body: String?)

    fun completion(text: String, observedTitle: String?, preferences: Preferences): Content? {
        if (!preferences.completion) return null
        val body = if (preferences.includeResponseExcerpt) utf8Prefix(NotificationTextPolicy.finalResponsePreview(text), PushPreviewContract.MAX_BODY_UTF8_BYTES) else null
        val title = if (preferences.includeTitle) normalizedTitle(observedTitle) else null
        return Content("completion", title, body)
    }

    fun attention(kind: NotificationInputKind, preferences: Preferences): Content? {
        if (!preferences.attention) return null
        val title = NotificationTextPolicy.inputHeading(kind)
        val body = when (kind) {
            NotificationInputKind.APPROVAL -> NotificationTextPolicy.APPROVAL_FALLBACK
            NotificationInputKind.CLARIFICATION -> NotificationTextPolicy.CLARIFICATION_FALLBACK
            NotificationInputKind.SECURE_INPUT -> NotificationTextPolicy.SECURE_INPUT_FALLBACK
        }
        return Content("attention", title, body)
    }

    fun normalizedTitle(value: String?): String? {
        val first = value
            ?.replace("\r\n", "\n")
            ?.replace('\r', '\n')
            ?.filter { it == '\n' || !it.isISOControl() }
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull(String::isNotEmpty)
            ?.replace(Regex("^#{1,6}\\s+"), "")
            ?.replace("**", "")
            ?.replace("__", "")
            ?.replace("`", "")
            ?.trim()
            ?.take(120)
        return first?.takeIf(String::isNotEmpty)?.let {
            utf8Prefix(it, PushPreviewContract.MAX_TITLE_UTF8_BYTES).trim().takeIf(String::isNotEmpty)
        }
    }

    fun utf8Prefix(value: String, maxBytes: Int): String {
        if (value.encodeToByteArray().size <= maxBytes) return value
        val out = StringBuilder()
        for (character in value) {
            val next = out.toString() + character
            if (next.encodeToByteArray().size > maxBytes) break
            out.append(character)
        }
        return out.toString()
    }
}
