package com.unsupportedpastels.mercury.core.artifacts

internal data class MarkdownFence(val marker: Char, val length: Int, val info: String) {
    fun closes(line: String, candidate: MarkdownFence?): Boolean =
        candidate?.marker == marker && candidate.length >= length &&
            line.dropWhile { it == ' ' }.drop(candidate.length).all { it == ' ' || it == '\t' }
}

internal fun markdownFenceAtLineStart(line: String): MarkdownFence? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3 || indent >= line.length) return null
    val marker = line[indent].takeIf { it == '`' || it == '~' } ?: return null
    var end = indent
    while (end < line.length && line[end] == marker) end += 1
    val length = end - indent
    if (length < 3) return null
    val info = line.substring(end).trim()
    if (marker == '`' && info.contains('`')) return null
    return MarkdownFence(marker, length, info)
}

/** Deterministic Markdown block decisions shared by Android and iOS adapters. */
object MarkdownPresentationPolicy {
    fun fencedSegments(source: String): List<MarkdownFenceSegment> {
        if (source.isEmpty()) return emptyList()
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        val segments = ArrayList<MarkdownFenceSegment>()
        var textStart = 0
        var lineStart = 0

        while (lineStart < text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline
            val opening = markdownFenceAtLineStart(text.substring(lineStart, lineEnd))
            if (opening == null) {
                if (newline < 0) break
                lineStart = newline + 1
                continue
            }

            if (lineStart > textStart) {
                segments += MarkdownFenceSegment(
                    kind = MarkdownFenceSegmentKind.Text,
                    text = text.substring(textStart, lineStart),
                )
            }
            val codeStart = if (newline < 0) lineEnd else newline + 1
            var scanStart = codeStart
            var closingStart = text.length
            var afterClosing = text.length
            while (scanStart < text.length) {
                val closeNewline = text.indexOf('\n', scanStart)
                val closeEnd = if (closeNewline < 0) text.length else closeNewline
                val closeLine = text.substring(scanStart, closeEnd)
                if (opening.closes(closeLine, markdownFenceAtLineStart(closeLine))) {
                    closingStart = scanStart
                    afterClosing = if (closeNewline < 0) closeEnd else closeNewline + 1
                    break
                }
                if (closeNewline < 0) break
                scanStart = closeNewline + 1
            }
            segments += MarkdownFenceSegment(
                kind = MarkdownFenceSegmentKind.Code,
                text = text.substring(codeStart, closingStart).trimEnd('\n'),
                language = opening.info.take(32).ifBlank { null },
            )
            textStart = afterClosing
            lineStart = afterClosing
        }

        if (textStart < text.length) {
            segments += MarkdownFenceSegment(MarkdownFenceSegmentKind.Text, text.substring(textStart))
        }
        return segments
    }

    fun stablePrefixLength(source: String): Int {
        var stableEnd = 0
        var openFence: MarkdownFence? = null
        var cursor = 0
        while (cursor < source.length) {
            val newline = source.indexOf('\n', cursor)
            val lineEnd = if (newline < 0) source.length else newline
            val line = source.substring(cursor, lineEnd).removeSuffix("\r")
            val candidate = markdownFenceAtLineStart(line)
            if (openFence?.closes(line, candidate) == true) {
                openFence = null
            } else if (openFence == null && candidate != null) {
                openFence = candidate
            } else if (openFence == null && line.isBlank() && newline >= 0) {
                stableEnd = newline + 1
            }
            if (newline < 0) break
            cursor = newline + 1
        }
        return stableEnd
    }
}
