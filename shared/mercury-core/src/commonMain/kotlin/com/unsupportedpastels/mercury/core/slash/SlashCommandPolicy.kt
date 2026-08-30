package com.unsupportedpastels.mercury.core.slash

/**
 * Slash-command predicates and completion application decided once for both
 * clients, mirroring the desktop `looksLikeSlashCommand` / `applyCompletion`
 * contract.
 *
 * Whitespace is classified explicitly (Unicode whitespace including the
 * no-break family) so behavior is identical on the JVM and Kotlin/Native —
 * `Char.isWhitespace` alone differs between targets for U+00A0/2007/202F.
 */
object SlashCommandPolicy {
    val VALID_REASONING_EFFORTS = setOf(
        "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra",
    )

    private fun Char.isSpaceLike(): Boolean =
        isWhitespace() || this == '\u00A0' || this == '\u2007' || this == '\u202F'

    fun isModelPickerCommand(text: String): Boolean =
        text.trim { it.isSpaceLike() } == "/model"

    fun isSteerCommand(text: String): Boolean {
        val command = text.dropWhile { it.isSpaceLike() }
        return command == "/steer" || command.startsWith("/steer ")
    }

    fun canonicalReasoningEffort(value: String): String? = value
        .trim { it.isSpaceLike() }
        .lowercase()
        .takeIf(VALID_REASONING_EFFORTS::contains)

    fun reasoningEffortCommand(text: String): String? {
        val tokens = text.split { it.isSpaceLike() }
        if (tokens.size != 2 || tokens[0] != "/reasoning") return null
        return canonicalReasoningEffort(tokens[1])
    }

    /**
     * True when [text] is a slash-command completion context: a leading `/`
     * command token with no second slash before the first whitespace, then any
     * in-progress argument text. Absolute paths (`/home/user/file`) and prose
     * containing `/` are not completion contexts.
     */
    fun isSlashCommandContext(text: String): Boolean {
        if (text.firstOrNull() != '/') return false
        for (character in text.drop(1)) {
            if (character.isSpaceLike()) return true
            if (character == '/') return false
        }
        return true
    }

    /**
     * Applies a completion row using desktop `replace_from` semantics (UTF-16
     * offsets): keep the prefix before [replaceFrom], append [itemText], drop
     * the remainder. A leading slash on the item is dropped only when the kept
     * prefix already ends in `/`. An offset inside a surrogate pair is clamped
     * backward to a character boundary rather than manufacturing invalid text.
     */
    fun applySlashCompletion(current: String, itemText: String, replaceFrom: Int): String {
        var clamped = replaceFrom.coerceIn(0, current.length)
        if (clamped in 1 until current.length &&
            current[clamped].isLowSurrogate() &&
            current[clamped - 1].isHighSurrogate()
        ) {
            clamped -= 1
        }
        val prefix = current.substring(0, clamped)
        val addition = if (prefix.endsWith("/") && itemText.startsWith("/")) {
            itemText.drop(1)
        } else {
            itemText
        }
        return prefix + addition
    }

    /** Default display text for a completion row: exactly one leading slash. */
    fun defaultDisplay(text: String): String =
        if (text.startsWith("/")) text else "/$text"

    private fun String.split(isSeparator: (Char) -> Boolean): List<String> {
        val tokens = mutableListOf<String>()
        val builder = StringBuilder()
        for (character in this) {
            if (isSeparator(character)) {
                if (builder.isNotEmpty()) {
                    tokens += builder.toString()
                    builder.clear()
                }
            } else {
                builder.append(character)
            }
        }
        if (builder.isNotEmpty()) tokens += builder.toString()
        return tokens
    }
}
