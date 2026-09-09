package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.outlined.ChevronRight
import com.unsupportedpastels.mercury.core.transcript.FoldedTranscriptEntry
import com.unsupportedpastels.mercury.core.transcript.TranscriptRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import com.unsupportedpastels.mercury.core.transcript.TranscriptPresentationPolicy

/** Native identities and payloads retained around the shared turn policy. */
internal sealed interface FoldedEntry {
    data class Single(val index: Int, val message: ChatMessage) : FoldedEntry
    data class TurnActivity(
        val steps: List<IndexedChatMessage>,
        val answerReasoning: String?,
        val answerIndex: Int?,
    ) : FoldedEntry
}

internal fun foldTranscriptTurns(messages: List<ChatMessage>, turnActive: Boolean): List<FoldedEntry> {
    val rows = messages.mapIndexed { index, message ->
        TranscriptRow(index.toLong(), message.role.name.lowercase(), message.text,
            completed = !message.isStreaming, reasoningText = message.reasoningText)
    }
    val folded = com.unsupportedpastels.mercury.core.transcript.foldTranscriptTurns(rows, turnActive)
    return folded.mapIndexed { position, entry ->
        when (entry) {
            is FoldedTranscriptEntry.Message ->
                FoldedEntry.Single(entry.row.id.toInt(), messages[entry.row.id.toInt()])
            is FoldedTranscriptEntry.TurnActivity -> {
                val preceding = (folded.getOrNull(position - 1) as? FoldedTranscriptEntry.Message)?.row
                FoldedEntry.TurnActivity(
                    steps = entry.steps.map { IndexedChatMessage(it.id.toInt(), messages[it.id.toInt()]) },
                    answerReasoning = entry.answerReasoning,
                    answerIndex = preceding?.takeIf { it.role == "assistant" }?.id?.toInt(),
                )
            }
        }
    }
}

internal fun foldedEntryKey(entry: FoldedEntry, chat: ChatSessionSnapshot): String {
    val presentation = chat.transcriptPresentation?.takeIf { it.messages === chat.messages }
    fun rowIdentity(index: Int): String? = presentation?.state?.rows?.getOrNull(index)?.id?.let { "row:$it" }
    return when (entry) {
        is FoldedEntry.Single -> "message:${rowIdentity(entry.index) ?: "index:${entry.index}"}"
        is FoldedEntry.TurnActivity -> {
            val first = entry.steps.firstOrNull()?.index
            if (first != null) "turn-activity:${rowIdentity(first) ?: "index:$first"}"
            else "turn-activity:answer:${entry.answerIndex?.let(::rowIdentity) ?: entry.answerIndex}"
        }
    }
}

@Composable
internal fun TurnActivityGroup(
    entry: FoldedEntry.TurnActivity,
    expanded: Boolean,
    onToggle: () -> Unit,
    sessionKey: String,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    loadManagedVideo: (suspend (String) -> Result<ManagedVideoMedia>)? = null,
    peekManagedVideo: (suspend (String) -> ManagedVideoMedia?)? = null,
) {
    val count = entry.steps.size + if (entry.answerReasoning.isNullOrBlank()) 0 else 1
    val noun = if (count == 1) "step" else "steps"
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp).testTag("Turn activity")
                .clickable(onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Activity, $count $noun, ${if (expanded) "expanded" else "collapsed"}"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Activity · $count $noun", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                modifier = Modifier.size(16.dp).rotate(if (expanded) 90f else 0f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().drawBehind {
                    val width = 2.dp.toPx()
                    val x = if (layoutDirection == LayoutDirection.Ltr) width / 2 else size.width - width / 2
                    drawLine(ruleColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = width)
                }.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!entry.answerReasoning.isNullOrBlank()) {
                    var showAnswerReasoning by rememberSaveable(sessionKey, entry.answerIndex) { mutableStateOf(false) }
                    ThinkingBlock(entry.answerReasoning, false, showAnswerReasoning, { showAnswerReasoning = !showAnswerReasoning })
                }
                // Shared with iOS: the turn is already the disclosure; retain source order.
                val groups = remember(entry.steps) {
                    coalesceTranscriptEntries(entry.steps.map { it.message }, withinTurnActivity = true)
                }
                groups.forEach { group ->
                    val localIndex = when (group) {
                        is TranscriptEntry.Single -> group.index
                        is TranscriptEntry.ToolRun -> group.tools.first().index
                        is TranscriptEntry.WorkBurst -> group.reasoning.first().index
                    }
                    val index = entry.steps[localIndex].index
                    key(index) {
                        var innerExpanded by rememberSaveable(sessionKey, index) { mutableStateOf(false) }
                        when (group) {
                            is TranscriptEntry.Single -> {
                                if (group.message.reasoningText.isNotBlank()) {
                                    ThinkingBlock(group.message.reasoningText, group.message.isStreaming,
                                        innerExpanded, { innerExpanded = !innerExpanded })
                                }
                                if (group.message.text.isNotBlank()) {
                                    MarkdownMessage(group.message.text, loadManagedImage = loadManagedImage,
                                        loadManagedVideo = loadManagedVideo, peekManagedVideo = peekManagedVideo)
                                }
                            }
                            is TranscriptEntry.ToolRun -> TranscriptToolRunGroup(
                                group.tools.map { entry.steps[it.index] }, innerExpanded,
                                { innerExpanded = !innerExpanded }, sessionKey,
                                loadManagedImage, loadManagedVideo, peekManagedVideo)
                            is TranscriptEntry.WorkBurst -> WorkBurstGroup(
                                group.reasoning.map { entry.steps[it.index] }, group.tools.map { entry.steps[it.index] },
                                innerExpanded, { innerExpanded = !innerExpanded }, sessionKey,
                                loadManagedImage, loadManagedVideo, peekManagedVideo)
                        }
                    }
                }
            }
        }
    }
}

/** A transcript message paired with its stable index in the source list. */
internal data class IndexedChatMessage(val index: Int, val message: ChatMessage)

/**
 * A renderable transcript unit: either a single non-tool message or a run of
 * consecutive tool messages that collapse into one dropdown.
 */
internal sealed interface TranscriptEntry {
    data class Single(val index: Int, val message: ChatMessage) : TranscriptEntry

    data class ToolRun(val tools: List<IndexedChatMessage>) : TranscriptEntry

    /**
     * Consecutive reasoning-only assistant steps bundled with their tool runs
     * into one compact, expandable activity line (iOS WorkBurstView parity).
     */
    data class WorkBurst(
        val reasoning: List<IndexedChatMessage>,
        val tools: List<IndexedChatMessage>,
    ) : TranscriptEntry
}

/** Retained reducer IDs survive streaming and removal of earlier rows. */
internal fun transcriptEntryKey(entry: TranscriptEntry, chat: ChatSessionSnapshot): String {
    val index = when (entry) {
        is TranscriptEntry.Single -> entry.index
        is TranscriptEntry.ToolRun -> entry.tools.first().index
        is TranscriptEntry.WorkBurst -> entry.reasoning.first().index
    }
    val presentation = chat.transcriptPresentation?.takeIf { it.messages === chat.messages }
    val identity = presentation?.state?.rows?.getOrNull(index)?.id?.let { "row:$it" } ?: "index:$index"
    return when (entry) {
        is TranscriptEntry.ToolRun -> "tool-run:$identity"
        is TranscriptEntry.WorkBurst -> "work-burst:$identity"
        is TranscriptEntry.Single -> "message:$identity"
    }
}

/**
 * Fold a flat transcript into renderable entries using the shared engine's
 * grouping decision (`coalesceTranscriptEntries` in mercury-core): adjacent
 * tool messages collapse into one [TranscriptEntry.ToolRun], and reasoning-only
 * assistant steps followed by tool runs collapse into one
 * [TranscriptEntry.WorkBurst]. Original message indices are preserved so
 * per-message expansion state stays stable. Persisted tool rows remain in this
 * historical presentation even when a separate live activity card is present;
 * the current DTO does not carry an exact identity for safe suppression.
 */
internal fun coalesceTranscriptEntries(messages: List<ChatMessage>, withinTurnActivity: Boolean = false): List<TranscriptEntry> {
    val rows = messages.mapIndexed { index, message ->
        com.unsupportedpastels.mercury.core.transcript.TranscriptRow(
            id = index.toLong(),
            role = message.role.name.lowercase(),
            text = message.text,
            completed = !message.isStreaming,
            reasoningText = message.reasoningText,
        )
    }
    fun indexed(row: com.unsupportedpastels.mercury.core.transcript.TranscriptRow) =
        IndexedChatMessage(row.id.toInt(), messages[row.id.toInt()])
    val entries = if (withinTurnActivity) {
        com.unsupportedpastels.mercury.core.transcript.activityTranscriptEntries(rows)
    } else {
        com.unsupportedpastels.mercury.core.transcript.coalesceTranscriptEntries(rows)
    }
    return entries.map { entry ->
        when (entry) {
            is com.unsupportedpastels.mercury.core.transcript.TranscriptEntry.Message ->
                TranscriptEntry.Single(entry.row.id.toInt(), messages[entry.row.id.toInt()])
            is com.unsupportedpastels.mercury.core.transcript.TranscriptEntry.ToolRun ->
                TranscriptEntry.ToolRun(entry.rows.map(::indexed))
            is com.unsupportedpastels.mercury.core.transcript.TranscriptEntry.WorkBurst ->
                TranscriptEntry.WorkBurst(entry.reasoning.map(::indexed), entry.tools.map(::indexed))
        }
    }
}

/**
 * Presentation-only explanation for a current turn whose final assistant
 * prose has not arrived while an observed child is still active. This never
 * appends a synthetic transcript message.
 */
internal fun missingFinalResponseNotice(
    messages: List<ChatMessage>,
    activeChildCount: Int,
    parentTurnSending: Boolean,
): String? {
    val rows = messages.mapIndexed { index, message ->
        com.unsupportedpastels.mercury.core.transcript.TranscriptRow(
            id = index.toLong(),
            role = message.role.name.lowercase(),
            text = message.text,
            completed = !message.isStreaming,
            reasoningText = message.reasoningText,
        )
    }
    return TranscriptPresentationPolicy.missingFinalResponseNotice(
        rows = rows,
        activeChildCount = activeChildCount,
        parentTurnSending = parentTurnSending,
    )
}

/**
 * The tool name is the leading segment of a transcript tool message, before the
 * " · " context separator (see `transcriptToolText` on the ViewModel). Falls
 * back to the trimmed text when no separator is present.
 */
internal fun transcriptToolName(text: String): String =
    text.substringBefore(" · ").trim().ifEmpty { text.trim() }

/**
 * One collapsible card wrapping all tool activity for the current run, in the
 * style of Hermex's tool activity group: state icon, action count, a summary of
 * unique tool names, and the per-tool rows only when expanded.
 */
@Composable
internal fun ToolActivityGroup(
    tools: List<RunToolRow>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val anyRunning = tools.any { it.state == RunToolState.Running }
    val noun = if (tools.size == 1) "action" else "actions"
    val stateText = if (anyRunning) "running" else "completed"
    val summary = TranscriptPresentationPolicy.toolActivitySummary(
        completedNames = tools.filter { it.state == RunToolState.Completed }.map(RunToolRow::name),
        runningNames = tools.filter { it.state == RunToolState.Running }.map(RunToolRow::name),
    )
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${tools.size} $noun, $stateText, ${if (expanded) "expanded" else "collapsed"}"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (anyRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = semanticColors.active,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = semanticColors.completed,
                    )
                }
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                tools.forEachIndexed { index, tool ->
                    key(tool.toolId) {
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        RunToolRowContent(tool)
                    }
                }
            }
        }
    }
}

/**
 * Historical tool-role transcript message collapsed into the same card style as
 * the live tool activity group: a one-line preview, expanding to the full
 * markdown content inline.
 */
@Composable
internal fun ToolMessageBlock(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    loadManagedVideo: (suspend (String) -> Result<ManagedVideoMedia>)? = null,
    peekManagedVideo: (suspend (String) -> ManagedVideoMedia?)? = null,
) {
    val preview = remember(text) {
        text.replace('\n', ' ').trim().take(80)
    }
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (expanded) "Hide tool result" else "Show tool result"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalHermesSemanticColors.current.completed,
                )
                Text(
                    "Tool",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!expanded) {
                    Text(
                        preview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                MarkdownMessage(
                    text,
                    loadManagedImage = loadManagedImage,
                    loadManagedVideo = loadManagedVideo,
                    peekManagedVideo = peekManagedVideo,
                )
            }
        }
    }
}

/**
 * A run of consecutive completed tool messages in the persisted transcript,
 * collapsed into one dropdown mirroring the live [ToolActivityGroup]: a single
 * header ("N actions, completed, <summary>") that expands to the individual
 * tool rows, each independently expandable to its full result.
 */
@Composable
internal fun TranscriptToolRunGroup(
    tools: List<IndexedChatMessage>,
    expanded: Boolean,
    onToggle: () -> Unit,
    sessionKey: String,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    loadManagedVideo: (suspend (String) -> Result<ManagedVideoMedia>)? = null,
    peekManagedVideo: (suspend (String) -> ManagedVideoMedia?)? = null,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val noun = if (tools.size == 1) "action" else "actions"
    val summary = remember(tools) {
        TranscriptPresentationPolicy.toolActivitySummary(
            completedNames = tools.map { transcriptToolName(it.message.text) },
        )
    }
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${tools.size} $noun, completed, ${if (expanded) "expanded" else "collapsed"}"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = semanticColors.completed,
                )
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                tools.forEach { indexed ->
                    key(indexed.index) {
                        var showToolMessage by rememberSaveable(sessionKey, indexed.index) {
                            mutableStateOf(false)
                        }
                        ToolMessageBlock(
                            text = indexed.message.text,
                            expanded = showToolMessage,
                            onToggle = { showToolMessage = !showToolMessage },
                            loadManagedImage = loadManagedImage,
                            loadManagedVideo = loadManagedVideo,
                            peekManagedVideo = peekManagedVideo,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One collapsed work burst (iOS WorkBurstView parity): consecutive reasoning
 * steps bundled with their tool runs into a single compact activity line.
 * Expanding reveals each thinking block and tool result in original order.
 */
@Composable
internal fun WorkBurstGroup(
    reasoning: List<IndexedChatMessage>,
    tools: List<IndexedChatMessage>,
    expanded: Boolean,
    onToggle: () -> Unit,
    sessionKey: String,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    loadManagedVideo: (suspend (String) -> Result<ManagedVideoMedia>)? = null,
    peekManagedVideo: (suspend (String) -> ManagedVideoMedia?)? = null,
) {
    val stepCount = reasoning.size + tools.size
    val noun = if (stepCount == 1) "step" else "steps"
    val working = reasoning.any { it.message.isStreaming }
    val summary = "${if (working) "Working" else "Activity"} · $stepCount $noun"
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$summary, ${if (expanded) "expanded" else "collapsed"}"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                reasoning.forEach { indexed ->
                    key(indexed.index) {
                        var showReasoning by rememberSaveable(sessionKey, indexed.index) {
                            mutableStateOf(false)
                        }
                        ThinkingBlock(
                            reasoning = indexed.message.reasoningText,
                            streaming = indexed.message.isStreaming,
                            expanded = showReasoning,
                            onToggle = { showReasoning = !showReasoning },
                        )
                    }
                }
                if (tools.isNotEmpty()) {
                    var toolsExpanded by rememberSaveable(sessionKey, tools.first().index) {
                        mutableStateOf(false)
                    }
                    TranscriptToolRunGroup(
                        tools = tools,
                        expanded = toolsExpanded,
                        onToggle = { toolsExpanded = !toolsExpanded },
                        sessionKey = sessionKey,
                        loadManagedImage = loadManagedImage,
                        loadManagedVideo = loadManagedVideo,
                        peekManagedVideo = peekManagedVideo,
                    )
                }
            }
        }
    }
}
