package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.mercury.core.transcript.TranscriptPresentationPolicy
import com.unsupportedpastels.hermesandroid.app.RunTodoStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState

/**
 * The selected controller's bounded activity surface. This is deliberately a
 * presentation of already-authoritative state: it does not join persisted
 * sessions, or infer loops from transcript text.
 */
@Composable
internal fun ActivityStack(
    runState: RunEventState,
    processRows: List<ProcessRow> = emptyList(),
    runActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val toolCount = runState.tools.size
    val processCount = processRows.size
    val countedTodos = runState.todos.filter { it.status != RunTodoStatus.Cancelled }
    val completedTodos = countedTodos.count { it.status == RunTodoStatus.Completed }
    val hasActivity = runState.status != null ||
        runState.tools.isNotEmpty() ||
        runState.todos.isNotEmpty() ||
        processRows.isNotEmpty()
    if (!hasActivity) return

    var expanded by remember { mutableStateOf(false) }
    val taskLabel = "$completedTodos/${countedTodos.size} tasks"
    val noun = if (toolCount == 1) "tool" else "tools"
    val processLabel = if (processCount == 1) "1 process-local process" else "$processCount process-local processes"
    val stateLabel = if (
        runActive ||
        runState.tools.any { it.state == RunToolState.Running } ||
        runState.todos.any { it.status == RunTodoStatus.Pending || it.status == RunTodoStatus.InProgress } ||
        processRows.any { it.status.equals("running", ignoreCase = true) }
    ) "running" else "completed"
    val accessibilityLabel = if (runState.todos.isEmpty() && processCount == 0) {
        "$toolCount actions, $stateLabel, " + if (expanded) "expanded" else "collapsed"
    } else {
        "Activity stack, $toolCount $noun, $taskLabel" +
            (if (processCount == 0) "" else ", $processLabel") +
            ", " + if (expanded) "expanded" else "collapsed"
    }

    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .testTag("Unified activity stack")
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.heightIn(max = 280.dp) else Modifier)
                .then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stateLabel == "running") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    TranscriptPresentationPolicy.activitySummary(toolCount, completedTodos, countedTodos.size, processCount = processCount),
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
                runState.status?.let { status ->
                    ActivityStatusRow(status.kind, status.text)
                }
                if (runState.todos.isNotEmpty()) {
                    ActivitySectionLabel("Tasks")
                    runState.todos.forEach { todo ->
                        key("todo:${todo.id}") {
                            TodoActivityRow(todo)
                        }
                    }
                }
                if (runState.tools.isNotEmpty()) {
                    ActivitySectionLabel("Tools")
                    runState.tools.forEach { tool ->
                        key("tool:${tool.toolId}") {
                            ToolActivityRow(tool)
                        }
                    }
                }
                if (processRows.isNotEmpty()) {
                    ActivitySectionLabel("Processes · process-local")
                    processRows.forEach { process ->
                        key("process:${process.processId}") {
                            ProcessActivityRow(process)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivitySectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun ActivityStatusRow(kind: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TodoActivityRow(todo: RunTodoItem) {
    val completed = todo.status == RunTodoStatus.Completed
    val marker = when (todo.status) {
        RunTodoStatus.Completed -> "✓"
        RunTodoStatus.Cancelled -> "–"
        RunTodoStatus.InProgress -> "•"
        RunTodoStatus.Pending -> "○"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Task ${todo.content}, ${todo.status.activityLabel()}" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            marker,
            color = if (completed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            todo.content,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (completed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun ToolActivityRow(tool: RunToolRow) {
    val running = tool.state == RunToolState.Running
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) {
                contentDescription = when {
                    running -> "Running tool ${tool.name}: ${tool.context.orEmpty()}"
                    else -> "Completed tool ${tool.name}: ${tool.summary.orEmpty()}"
                }
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                tool.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (running) "Running" else "Completed",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
        }
        (if (running) tool.context else tool.summary)?.takeIf(String::isNotBlank)?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProcessActivityRow(process: ProcessRow) {
    val title = process.command.lineSequence().firstOrNull()?.trim().orEmpty()
        .ifBlank { "background process" }
    val status = process.exitCode?.let { "${process.status} ($it)" } ?: process.status
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Process-local process ${process.processId}: $title, $status"
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = if (process.status.equals("running", ignoreCase = true)) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun RunTodoStatus.activityLabel(): String = when (this) {
    RunTodoStatus.Pending -> "pending"
    RunTodoStatus.InProgress -> "in progress"
    RunTodoStatus.Completed -> "completed"
    RunTodoStatus.Cancelled -> "cancelled"
}
