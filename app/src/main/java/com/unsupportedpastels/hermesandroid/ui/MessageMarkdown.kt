package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicy
import com.unsupportedpastels.hermesandroid.files.MarkdownLinkTarget
import com.unsupportedpastels.hermesandroid.files.MediaLineKind

internal sealed interface MarkdownBlock

internal enum class MarkdownTextKind {
    Paragraph,
    Heading,
    Bullet,
    Numbered,
    Quote,
}

internal data class MarkdownInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

internal data class MarkdownTextBlock(
    val inlines: List<MarkdownInline>,
    val kind: MarkdownTextKind = MarkdownTextKind.Paragraph,
    val prefix: String? = null,
    val headingLevel: Int = 0,
    val indentLevel: Int = 0,
) : MarkdownBlock {
    val plainText: String = inlines.joinToString(separator = "") { it.text }
}

internal data class MarkdownCodeBlock(
    val code: String,
    val language: String? = null,
) : MarkdownBlock

internal data class MarkdownImageBlock(
    val url: String,
) : MarkdownBlock

internal data class MarkdownFileChipBlock(
    val source: String,
    val displayName: String,
) : MarkdownBlock

internal enum class MarkdownTableAlignment {
    Start,
    Center,
    End,
}

internal data class MarkdownTableCell(
    val inlines: List<MarkdownInline>,
) {
    val plainText: String = inlines.joinToString(separator = "") { it.text }
}

internal data class MarkdownTableBlock(
    val header: List<MarkdownTableCell>,
    val alignments: List<MarkdownTableAlignment>,
    val rows: List<List<MarkdownTableCell>>,
) : MarkdownBlock

private val unorderedListPattern = Regex("^(\\s*)[-+*]\\s+(.+)$")
private val orderedListPattern = Regex("^(\\s*)(\\d+[.)])\\s+(.+)$")
private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")
private val mediaDirectivePattern = Regex("^MEDIA:(\\S+)$")
private val imageAttachmentMarkerPattern = Regex(
    pattern = """^\[Image attached at: [^\]\r\n]+]\r?\n(?=data:image/)""",
    option = RegexOption.MULTILINE,
)
private const val MESSAGE_RENDER_CHUNK_CHARS = 4_000
private const val MIN_EMBEDDED_PAYLOAD_CHARS = 512
private val webUrlPrefixes = listOf("https://", "http://")
private val pairedWebUrlDelimiters = listOf(
    '(' to ')',
    '[' to ']',
    '{' to '}',
    '<' to '>',
)

private fun surroundingWebUrlQuote(text: String, start: Int): Char? {
    var precedingIndex = start - 1
    while (
        precedingIndex >= 0 &&
        pairedWebUrlDelimiters.any { (open, _) -> text[precedingIndex] == open }
    ) {
        precedingIndex -= 1
    }
    return text.getOrNull(precedingIndex)?.takeIf { it == '\'' || it == '"' }
}

private fun trimBareWebUrlEnd(text: String, start: Int, scannedEnd: Int): Int {
    val surroundingQuote = surroundingWebUrlQuote(text, start)
    val delimiterBalances = pairedWebUrlDelimiters.associate { (_, close) -> close to 0 }.toMutableMap()
    for (index in start until scannedEnd) {
        val character = text[index]
        pairedWebUrlDelimiters.forEach { (open, close) ->
            when (character) {
                open -> delimiterBalances[close] = delimiterBalances.getValue(close) - 1
                close -> delimiterBalances[close] = delimiterBalances.getValue(close) + 1
            }
        }
    }

    var end = scannedEnd
    while (end > start) {
        val trailing = text[end - 1]
        when {
            trailing in ".,;:!?" -> end -= 1
            trailing == surroundingQuote -> end -= 1
            delimiterBalances.getOrDefault(trailing, 0) > 0 -> {
                delimiterBalances[trailing] = delimiterBalances.getValue(trailing) - 1
                end -= 1
            }
            else -> return end
        }
    }
    return end
}

private fun splitMarkdownTableRow(line: String): List<String>? {
    val trimmed = line.trim()
    if ('|' !in trimmed) return null
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var index = if (trimmed.startsWith('|')) 1 else 0
    var inCode = false
    var foundSeparator = false
    while (index < trimmed.length) {
        val character = trimmed[index]
        when {
            character == '\\' && index + 1 < trimmed.length && trimmed[index + 1] == '|' -> {
                cell.append('|')
                index += 2
                continue
            }
            character == '`' -> {
                inCode = !inCode
                cell.append(character)
            }
            character == '|' && !inCode -> {
                cells += cell.toString().trim()
                cell.clear()
                foundSeparator = true
            }
            else -> cell.append(character)
        }
        index += 1
    }
    if (cell.isNotEmpty() || !trimmed.endsWith('|')) cells += cell.toString().trim()
    return cells.takeIf { foundSeparator && it.size >= 2 }
}

private fun parseTableDelimiter(cells: List<String>): List<MarkdownTableAlignment>? {
    if (cells.size < 2) return null
    return cells.map { cell ->
        val value = cell.trim()
        if (!Regex("^:?-{3,}:?$").matches(value)) return null
        when {
            value.startsWith(':') && value.endsWith(':') -> MarkdownTableAlignment.Center
            value.endsWith(':') -> MarkdownTableAlignment.End
            else -> MarkdownTableAlignment.Start
        }
    }
}

private fun tableCells(values: List<String>, columnCount: Int): List<MarkdownTableCell> =
    List(columnCount) { column ->
        MarkdownTableCell(parseMarkdownInlines(values.getOrElse(column) { "" }))
    }

internal fun compactEmbeddedPayloads(source: String): String {
    val text = imageAttachmentMarkerPattern.replace(source, "")
    val output = StringBuilder(text.length.coerceAtMost(MESSAGE_RENDER_CHUNK_CHARS))
    var cursor = 0
    while (cursor < text.length) {
        val dataStart = text.indexOf("data:", startIndex = cursor)
        if (dataStart < 0) {
            output.append(text, cursor, text.length)
            break
        }
        val comma = text.indexOf(',', startIndex = dataStart + 5)
        val headerIsBounded = comma in (dataStart + 6)..(dataStart + 256)
        val metadata = if (headerIsBounded) text.substring(dataStart + 5, comma) else ""
        if (!metadata.endsWith(";base64", ignoreCase = true)) {
            output.append(text, cursor, dataStart + 5)
            cursor = dataStart + 5
            continue
        }

        var payloadEnd = comma + 1
        while (payloadEnd < text.length && text[payloadEnd].isBase64PayloadCharacter()) {
            payloadEnd += 1
        }
        if (payloadEnd - (comma + 1) < MIN_EMBEDDED_PAYLOAD_CHARS) {
            output.append(text, cursor, comma + 1)
            cursor = comma + 1
            continue
        }

        val mimeType = metadata.substringBefore(';').lowercase()
        val kind = when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("audio/") -> "audio"
            mimeType.startsWith("video/") -> "video"
            else -> "file"
        }
        output.append(text, cursor, dataStart)
        output.append("Attached $kind · embedded data hidden")
        cursor = payloadEnd
    }
    return output.toString()
}

private fun Char.isBase64PayloadCharacter(): Boolean =
    isLetterOrDigit() || this == '+' || this == '/' || this == '=' || this == '-' || this == '_'

/**
 * Length of the prefix of a streaming message that is safe to render as markdown.
 *
 * The boundary is the last blank line outside a code fence: every block before it
 * is finalized (its terminating blank line has arrived), while the tail may still
 * contain unclosed fences, half-built tables, or unterminated inline markers and
 * must render as plain text until more of the stream arrives.
 */
internal fun stableMarkdownPrefixLength(text: String): Int {
    var stableEnd = 0
    var inFence = false
    var cursor = 0
    while (cursor < text.length) {
        val newline = text.indexOf('\n', cursor)
        val lineEnd = if (newline < 0) text.length else newline
        val line = text.substring(cursor, lineEnd)
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
        } else if (!inFence && line.isBlank() && newline >= 0) {
            stableEnd = newline + 1
        }
        if (newline < 0) break
        cursor = newline + 1
    }
    return stableEnd
}

internal fun parseMessageMarkdown(source: String): List<MarkdownBlock> {
    if (source.isEmpty()) return emptyList()
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val text = paragraph.joinToString("\n").trimEnd()
        if (text.isNotEmpty()) {
            blocks += MarkdownTextBlock(parseMarkdownInlines(text))
        }
        paragraph.clear()
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmedStart = line.trimStart()
        val media = mediaDirectivePattern.matchEntire(trimmedStart)
        if (media != null) {
            when (val kind = HostFileOpenPolicy.mediaLineKind(media.groupValues[1])) {
                is MediaLineKind.InAppImage -> {
                    flushParagraph()
                    blocks += MarkdownImageBlock(kind.source)
                    index += 1
                    continue
                }
                is MediaLineKind.FileChip -> {
                    flushParagraph()
                    blocks += MarkdownFileChipBlock(kind.source, kind.displayName)
                    index += 1
                    continue
                }
                MediaLineKind.Ignore -> Unit
            }
        }
        if (trimmedStart.startsWith("```")) {
            flushParagraph()
            val language = trimmedStart.removePrefix("```").trim()
                .take(32)
                .ifBlank { null }
            val codeLines = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                codeLines += lines[index]
                index += 1
            }
            blocks += MarkdownCodeBlock(
                code = codeLines.joinToString("\n").trimEnd('\n'),
                language = language,
            )
            if (index < lines.size) index += 1
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            index += 1
            continue
        }

        val tableHeader = splitMarkdownTableRow(line)
        val tableDelimiter = lines.getOrNull(index + 1)
            ?.let(::splitMarkdownTableRow)
            ?.let(::parseTableDelimiter)
        if (tableHeader != null && tableDelimiter != null && tableHeader.size == tableDelimiter.size) {
            flushParagraph()
            val columnCount = tableHeader.size
            val rows = mutableListOf<List<MarkdownTableCell>>()
            index += 2
            while (index < lines.size && lines[index].isNotBlank()) {
                val row = splitMarkdownTableRow(lines[index]) ?: break
                rows += tableCells(row, columnCount)
                index += 1
            }
            blocks += MarkdownTableBlock(
                header = tableCells(tableHeader, columnCount),
                alignments = tableDelimiter,
                rows = rows,
            )
            continue
        }

        val heading = headingPattern.matchEntire(trimmedStart)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(heading.groupValues[2].trimEnd()),
                kind = MarkdownTextKind.Heading,
                headingLevel = heading.groupValues[1].length,
            )
            index += 1
            continue
        }

        val unordered = unorderedListPattern.matchEntire(line)
        if (unordered != null) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(unordered.groupValues[2].trimEnd()),
                kind = MarkdownTextKind.Bullet,
                prefix = "•",
                indentLevel = (unordered.groupValues[1].length / 2).coerceAtMost(4),
            )
            index += 1
            continue
        }

        val ordered = orderedListPattern.matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(ordered.groupValues[3].trimEnd()),
                kind = MarkdownTextKind.Numbered,
                prefix = ordered.groupValues[2],
                indentLevel = (ordered.groupValues[1].length / 2).coerceAtMost(4),
            )
            index += 1
            continue
        }

        if (trimmedStart.startsWith("> ")) {
            flushParagraph()
            blocks += MarkdownTextBlock(
                inlines = parseMarkdownInlines(trimmedStart.removePrefix("> ").trimEnd()),
                kind = MarkdownTextKind.Quote,
            )
            index += 1
            continue
        }

        paragraph += line.trimEnd()
        index += 1
    }
    flushParagraph()
    return blocks
}

private data class InlineState(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

private fun parseMarkdownInlines(text: String, inherited: InlineState = InlineState()): List<MarkdownInline> {
    val output = mutableListOf<MarkdownInline>()
    val plainBuffer = StringBuilder()

    fun append(inline: MarkdownInline) {
        if (inline.text.isEmpty()) return
        val last = output.lastOrNull()
        if (last != null && last.copy(text = "") == inline.copy(text = "")) {
            output[output.lastIndex] = last.copy(text = last.text + inline.text)
        } else {
            output += inline
        }
    }

    fun appendPlain(value: String) {
        plainBuffer.append(value)
    }

    fun flushPlain() {
        if (plainBuffer.isEmpty()) return
        append(
            MarkdownInline(
                text = plainBuffer.toString(),
                bold = inherited.bold,
                italic = inherited.italic,
                strikethrough = inherited.strikethrough,
                link = inherited.link,
            ),
        )
        plainBuffer.clear()
    }

    var index = 0
    while (index < text.length) {
        if (text[index] == '`') {
            val close = text.indexOf('`', index + 1)
            if (close > index + 1) {
                flushPlain()
                append(
                    MarkdownInline(
                        text = text.substring(index + 1, close),
                        bold = inherited.bold,
                        italic = inherited.italic,
                        code = true,
                        strikethrough = inherited.strikethrough,
                        link = inherited.link,
                    ),
                )
                index = close + 1
                continue
            }
        }

        if (text[index] == '[') {
            val labelEnd = text.indexOf("](", index + 1)
            val urlEnd = if (labelEnd >= 0) text.indexOf(')', labelEnd + 2) else -1
            if (labelEnd > index + 1 && urlEnd > labelEnd + 2) {
                val label = text.substring(index + 1, labelEnd)
                val url = text.substring(labelEnd + 2, urlEnd).trim()
                if (url.isNotEmpty()) {
                    flushPlain()
                    parseMarkdownInlines(label, inherited.copy(link = url)).forEach(::append)
                    index = urlEnd + 1
                    continue
                }
            }
        }

        val webUrlPrefix = webUrlPrefixes.firstOrNull { prefix ->
            text.startsWith(prefix, index, ignoreCase = true) &&
                (index == 0 || text[index - 1].isWhitespace() || text[index - 1] in "([<{\"'")
        }
        if (inherited.link == null && webUrlPrefix != null) {
            var end = index + webUrlPrefix.length
            while (end < text.length && !text[end].isWhitespace()) end += 1
            end = trimBareWebUrlEnd(text, index, end)
            if (end > index + webUrlPrefix.length) {
                flushPlain()
                val url = text.substring(index, end)
                append(MarkdownInline(text = url, link = url))
                index = end
                continue
            }
        }

        val marker = when {
            text.startsWith("***", index) -> "***"
            text.startsWith("___", index) -> "___"
            text.startsWith("**", index) -> "**"
            text.startsWith("__", index) -> "__"
            text.startsWith("~~", index) -> "~~"
            text.startsWith("*", index) -> "*"
            text.startsWith("_", index) -> "_"
            else -> null
        }
        if (marker != null) {
            val close = text.indexOf(marker, index + marker.length)
            if (close > index + marker.length) {
                flushPlain()
                val nestedState = when (marker) {
                    "***", "___" -> inherited.copy(bold = true, italic = true)
                    "**", "__" -> inherited.copy(bold = true)
                    "~~" -> inherited.copy(strikethrough = true)
                    else -> inherited.copy(italic = true)
                }
                parseMarkdownInlines(
                    text.substring(index + marker.length, close),
                    nestedState,
                ).forEach(::append)
                index = close + marker.length
                continue
            }
        }

        appendPlain(text[index].toString())
        index += 1
    }
    flushPlain()
    return output
}

@Composable
internal fun MarkdownMessage(
    text: String,
    modifier: Modifier = Modifier,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    onOpenManagedPath: ((String) -> Unit)? = null,
) {
    val displayText = remember(text) { compactEmbeddedPayloads(text) }
    var requestedCharacters by rememberSaveable(displayText) {
        mutableIntStateOf(minOf(displayText.length, MESSAGE_RENDER_CHUNK_CHARS))
    }
    val visibleEnd = remember(displayText, requestedCharacters) {
        utf16SafeEnd(displayText, requestedCharacters)
    }
    val visibleText = remember(displayText, visibleEnd) { displayText.substring(0, visibleEnd) }
    val blocks = remember(visibleText) { parseMessageMarkdown(visibleText) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                blocks.forEach { block ->
                    when (block) {
                        is MarkdownTextBlock -> MarkdownText(block, onOpenManagedPath)
                        is MarkdownCodeBlock -> MarkdownCode(block)
                        is MarkdownImageBlock -> RemoteMediaImage(
                            source = block.url,
                            loadManagedImage = loadManagedImage,
                        )
                        is MarkdownFileChipBlock -> Text(block.displayName)
                        is MarkdownTableBlock -> MarkdownTable(block, onOpenManagedPath)
                    }
                }
            }
        }
        if (visibleEnd < displayText.length) {
            TextButton(
                onClick = {
                    requestedCharacters = minOf(
                        displayText.length,
                        requestedCharacters + MESSAGE_RENDER_CHUNK_CHARS,
                    )
                },
            ) {
                Text("Show more")
            }
        }
    }
}

private fun utf16SafeEnd(text: String, requestedCharacters: Int): Int {
    var end = requestedCharacters.coerceIn(0, text.length)
    if (
        end in 1 until text.length &&
        text[end - 1].isHighSurrogate() &&
        text[end].isLowSurrogate()
    ) {
        end -= 1
    }
    return end
}

private val MarkdownTableCellMinWidth = 96.dp
private val MarkdownTableCellMaxWidth = 240.dp

@Composable
private fun MarkdownTable(
    block: MarkdownTableBlock,
    onOpenManagedPath: ((String) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    val columnCount = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return
    val rows = listOf(block.header) + block.rows
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .semantics {
                    contentDescription =
                        "Markdown table, ${block.header.size} columns, ${block.rows.size} rows"
                },
        ) {
            // Sizes each column to its widest cell (clamped) so columns stay aligned across rows
            // while still adapting to content and font scale.
            Layout(
                content = {
                    rows.forEachIndexed { rowIndex, cells ->
                        repeat(columnCount) { column ->
                            MarkdownTableCellText(
                                cell = cells.getOrNull(column),
                                header = rowIndex == 0,
                                alignment = block.alignments.getOrElse(column) {
                                    MarkdownTableAlignment.Start
                                },
                                onOpenManagedPath = onOpenManagedPath,
                            )
                        }
                    }
                },
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(8.dp),
                ),
            ) { measurables, _ ->
                val spacing = 1.dp.roundToPx()
                val minCellWidth = MarkdownTableCellMinWidth.roundToPx()
                val maxCellWidth = MarkdownTableCellMaxWidth.roundToPx()
                val rowCount = rows.size
                val columnWidths = IntArray(columnCount)
                measurables.forEachIndexed { index, measurable ->
                    val column = index % columnCount
                    columnWidths[column] = maxOf(
                        columnWidths[column],
                        measurable.maxIntrinsicWidth(Constraints.Infinity)
                            .coerceIn(minCellWidth, maxCellWidth),
                    )
                }
                val rowHeights = IntArray(rowCount)
                measurables.forEachIndexed { index, measurable ->
                    val row = index / columnCount
                    rowHeights[row] = maxOf(
                        rowHeights[row],
                        measurable.minIntrinsicHeight(columnWidths[index % columnCount]),
                    )
                }
                val placeables = measurables.mapIndexed { index, measurable ->
                    measurable.measure(
                        Constraints.fixed(
                            columnWidths[index % columnCount],
                            rowHeights[index / columnCount],
                        ),
                    )
                }
                val tableWidth = columnWidths.sum() + spacing * (columnCount - 1)
                val tableHeight = rowHeights.sum() + spacing * (rowCount - 1)
                layout(tableWidth, tableHeight) {
                    var y = 0
                    var index = 0
                    repeat(rowCount) { row ->
                        var x = 0
                        repeat(columnCount) { column ->
                            placeables[index].place(x, y)
                            x += columnWidths[column] + spacing
                            index++
                        }
                        y += rowHeights[row] + spacing
                    }
                }
            }
        }
        if (scrollState.maxValue > 0) {
            Text(
                "Swipe horizontally to see more",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MarkdownTableCellText(
    cell: MarkdownTableCell?,
    header: Boolean,
    alignment: MarkdownTableAlignment,
    onOpenManagedPath: ((String) -> Unit)? = null,
) {
    Text(
        text = cell?.let { annotatedMarkdown(it.inlines, onOpenManagedPath) } ?: AnnotatedString(""),
        modifier = Modifier
            .background(
                if (header) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = if (header) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        fontWeight = if (header) FontWeight.SemiBold else null,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = when (alignment) {
            MarkdownTableAlignment.Start -> TextAlign.Start
            MarkdownTableAlignment.Center -> TextAlign.Center
            MarkdownTableAlignment.End -> TextAlign.End
        },
    )
}

@Composable
private fun MarkdownText(
    block: MarkdownTextBlock,
    onOpenManagedPath: ((String) -> Unit)? = null,
) {
    val annotated = annotatedMarkdown(block.inlines, onOpenManagedPath)
    val textStyle = when (block.kind) {
        MarkdownTextKind.Heading -> when (block.headingLevel) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        }
        else -> MaterialTheme.typography.bodyLarge
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(start = (block.indentLevel * 12).dp)
        .then(
            if (block.kind == MarkdownTextKind.Quote) {
                Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            } else {
                Modifier
            },
        )

    if (block.prefix != null) {
        Row(
            modifier = rowModifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = block.prefix,
                style = textStyle,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = annotated,
                modifier = Modifier.weight(1f),
                style = textStyle,
            )
        }
    } else {
        Text(
            text = annotated,
            modifier = rowModifier,
            style = textStyle,
        )
    }
}

@Composable
private fun annotatedMarkdown(
    inlines: List<MarkdownInline>,
    onOpenManagedPath: ((String) -> Unit)? = null,
): AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        inlines.forEach { inline ->
            val style = SpanStyle(
                fontWeight = if (inline.bold) FontWeight.SemiBold else null,
                fontStyle = if (inline.italic) FontStyle.Italic else null,
                fontFamily = if (inline.code) FontFamily.Monospace else null,
                background = if (inline.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
                color = if (inline.link != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = when {
                    inline.strikethrough && inline.link != null -> TextDecoration.combine(
                        listOf(TextDecoration.LineThrough, TextDecoration.Underline),
                    )
                    inline.strikethrough -> TextDecoration.LineThrough
                    inline.link != null -> TextDecoration.Underline
                    else -> null
                },
            )
            when (val target = inline.link?.let(HostFileOpenPolicy::markdownLinkTarget)) {
                is MarkdownLinkTarget.RemoteWeb -> withLink(LinkAnnotation.Url(target.url)) {
                    withStyle(style) { append(inline.text) }
                }
                is MarkdownLinkTarget.ManagedHostPath -> {
                    val click = LinkAnnotation.Clickable("host-file") {
                        onOpenManagedPath?.invoke(target.path)
                    }
                    withLink(click) { withStyle(style) { append(inline.text) } }
                }
                else -> withStyle(style) { append(inline.text) }
            }
        }
    }
}

@Composable
private fun MarkdownCode(block: MarkdownCodeBlock) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language?.lowercase() ?: "code",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(block.code)) },
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = "Copy code" },
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        ) {
            Text(
                text = block.code,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
