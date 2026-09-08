package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskRow

@Composable
private fun ProgressUpdateButton(onGetUpdate: () -> Unit, refreshing: Boolean) {
    IconButton(
        onClick = onGetUpdate,
        enabled = !refreshing,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = "Get progress update (read-only)"
            if (refreshing) stateDescription = "Refreshing"
        },
    ) {
        if (refreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.Refresh, contentDescription = null)
    }
}

@Composable
private fun ProgressRefreshStatus(refreshError: String?) {
    if (refreshError?.isNotBlank() == true) {
        Text("Couldn’t refresh", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * Detail sheet: reported-done/in-progress/blocked/remaining sections built only
 * from observed run state. Section rows carry the reported evidence text when
 * the host supplied any; rows without evidence show none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionActivitySheet(
    summary: SessionProgressSummary,
    connectionLost: Boolean,
    lastObservedAt: Long?,
    now: Long,
    onDismiss: () -> Unit,
    onRetryConnection: () -> Unit = {},
    onGetUpdate: () -> Unit = {},
    refreshing: Boolean = false,
    refreshError: String? = null,
    restored: Boolean = false,
    evidence: List<SessionProgressItem> = emptyList(),
    partialHistory: Boolean = false,
    backgroundRows: List<BackgroundTaskRow> = emptyList(),
    processRows: List<ProcessRow> = emptyList(),
    onDismissBackground: (List<BackgroundTaskRow>) -> Unit = {},
    tools: List<RunToolRow> = emptyList(),
    status: String? = null,
    isSending: Boolean = false,
    currentMessages: List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage> = emptyList(),
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    loadManagedVideo: (suspend (String) -> Result<com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia>)? = null,
    peekManagedVideo: (suspend (String) -> com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia?)? = null,
) {
    var evidenceExpanded by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("Session activity sheet"),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Activity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    ProgressUpdateButton(onGetUpdate, refreshing)
                }
                if (status != null && isSending) {
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                progressObservationLabel(lastObservedAt, now, restored).takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (connectionLost) {
                    TextButton(
                        onClick = onRetryConnection,
                        modifier = Modifier.semantics { contentDescription = "Reconnect to recover job status" },
                    ) { Text("Reconnect") }
                }
                ProgressRefreshStatus(refreshError)
                if (partialHistory) Text("Recent history", style = MaterialTheme.typography.bodySmall)
            }
            if (summary.blocked.isNotEmpty()) {
                item { ProgressSectionLabel("Needs you") }
                items(summary.blocked) { row -> ProgressRow(row, ActivitySheetProgressRowKind.Blocked) }
            }
            if (summary.inProgress.isNotEmpty()) {
                item { ProgressSectionLabel("In progress") }
                items(summary.inProgress) { row ->
                    ProgressRow(row, if (summary.active && !connectionLost && !restored) ActivitySheetProgressRowKind.Active else ActivitySheetProgressRowKind.Stale)
                }
            }
            if (currentMessages.isNotEmpty()) {
                item { ProgressSectionLabel("Current turn") }
                items(currentMessages.withIndex().filter { it.value.role != com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole.User }) { indexed ->
                    val message = indexed.value
                    var expanded by remember(indexed.index) { mutableStateOf(false) }
                    if (message.reasoningText.isNotBlank() && message.reasoningText != message.text) {
                        ThinkingBlock(message.reasoningText, message.isStreaming, expanded, { expanded = !expanded })
                    }
                    if (message.text.isNotBlank()) {
                        MarkdownMessage(message.text, loadManagedImage = loadManagedImage,
                            loadManagedVideo = loadManagedVideo, peekManagedVideo = peekManagedVideo)
                    }
                }
            }
            if (tools.isNotEmpty()) {
                item { ProgressSectionLabel("Tools") }
                items(tools) { tool -> ActivitySheetToolRow(tool, isSending && !connectionLost && !restored) }
            }
            if (summary.completed.isNotEmpty()) {
                item { ProgressSectionLabel("Done") }
                items(summary.completed) { row -> ProgressRow(row, ActivitySheetProgressRowKind.Done) }
            }
            if (summary.remaining.isNotEmpty()) {
                item { ProgressSectionLabel("Remaining") }
                items(summary.remaining) { row -> ProgressRow(row, ActivitySheetProgressRowKind.Remaining) }
            }
            if (backgroundRows.isNotEmpty()) {
                item { ProgressSectionLabel("Background tasks") }
                items(backgroundRows) { row ->
                    Column(Modifier.fillMaxWidth().semantics {
                        stateDescription = row.label(now)
                    }) {
                        val color = if (row.recentlyActive(now)) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        Text(row.goal, style = MaterialTheme.typography.bodySmall, color = color)
                        Text(row.label(now), style = MaterialTheme.typography.labelSmall, color = color)
                        if (row.observedAtMillis > 0L && row.observedAtMillis <= now) {
                            Text(row.timeLabel(now), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        row.action?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                val unavailable = backgroundRows.filter { !it.terminal && it.isDismissible(now) }
                if (unavailable.isNotEmpty()) item {
                    TextButton(onClick = { onDismissBackground(unavailable) }) { Text("Dismiss unavailable") }
                }
                val completed = backgroundRows.filter { it.terminal }
                if (completed.isNotEmpty()) item {
                    TextButton(onClick = { onDismissBackground(completed) }) { Text("Dismiss completed") }
                }
            }
            if (processRows.isNotEmpty()) {
                item { ProgressSectionLabel("Processes · last reported") }
                items(processRows) { ProcessActivityRow(it) }
            }
            if (evidence.isNotEmpty()) {
                item {
                    TextButton(
                        onClick = { evidenceExpanded = !evidenceExpanded },
                        modifier = Modifier.semantics {
                            stateDescription = if (evidenceExpanded) "Expanded" else "Collapsed"
                        },
                    ) { Text("Tool reports") }
                }
                if (evidenceExpanded) {
                    // Display caller-supplied reports as text, never parse them
                    // into completion or test-verification claims.
                    items(evidence.take(20)) { report ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                report.label.take(120),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            report.detail?.let { detail ->
                                Text(
                                    detail.take(2_000),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 12,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (evidence.size > 20) {
                        item { Text("Showing first 20 reports", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

        }
    }
}

private enum class ActivitySheetProgressRowKind { Done, Active, Stale, Remaining, Blocked }

@Composable
private fun ProgressSectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ProgressRow(row: SessionProgressItem, kind: ActivitySheetProgressRowKind) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        when (kind) {
            ActivitySheetProgressRowKind.Done -> Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
            ActivitySheetProgressRowKind.Active -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            ActivitySheetProgressRowKind.Stale -> Text(
                "•",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            ActivitySheetProgressRowKind.Remaining -> Text(
                "○",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            ActivitySheetProgressRowKind.Blocked -> Text(
                "!",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodySmall)
            row.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActivitySheetToolRow(tool: RunToolRow, live: Boolean) {
    val running = tool.state == RunToolState.Running
    val detail = (if (running) tool.context else tool.summary)?.takeIf(String::isNotBlank)
    val description = "${if (running) "Running" else "Completed"} tool ${tool.name}" +
        detail?.let { ": $it" }.orEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running && live) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
            color = LocalHermesSemanticColors.current.active)
        else if (running) Text("•", style = MaterialTheme.typography.bodySmall)
        else Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = LocalHermesSemanticColors.current.completed)
        Text(tool.name + detail?.let { " · $it" }.orEmpty(),
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
