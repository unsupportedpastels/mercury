package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsupportedpastels.hermesandroid.app.RunStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors

internal fun sessionContextPercent(chat: ChatSessionSnapshot): Double? {
    chat.sessionUsage?.let { usage ->
        usage.contextPercent?.let { return it }
        val used = usage.contextUsedTokens
        val max = usage.contextMaxTokens
        if (used != null && max != null && max > 0) return used * 100.0 / max
    }
    return chat.contextBreakdown?.percent
}

@Composable
internal fun SessionContextRing(
    percent: Double?,
    artifactCount: Int,
    onClick: () -> Unit,
) {
    val fraction = percent?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
    val ringColor = when {
        fraction == null -> MaterialTheme.colorScheme.onSurfaceVariant
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.75f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .semantics {
                contentDescription = "Open session details"
                val contextDescription = percent
                    ?.let { "Context ${formatPercent(it)} percent used" }
                    ?: "Context usage unknown"
                val artifactDescription = if (artifactCount == 1) {
                    "1 artifact"
                } else {
                    "$artifactCount artifacts"
                }
                stateDescription = "$contextDescription, $artifactDescription"
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { fraction ?: 0f },
                modifier = Modifier.size(30.dp),
                color = ringColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeWidth = 3.dp,
            )
            Text(
                percent?.let { it.coerceIn(0.0, 99.0).toInt().toString() } ?: "–",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun RunStatusPill(status: RunStatus) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.semantics {
            contentDescription = "Current status: ${status.kind} — ${status.text}"
            stateDescription = "Current"
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(status.kind, style = MaterialTheme.typography.labelMedium)
            Text(
                status.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun RunToolRowContent(tool: RunToolRow) {
    val semanticColors = LocalHermesSemanticColors.current
    val toolContext = tool.context?.takeIf(String::isNotBlank)
    val toolSummary = tool.summary?.takeIf(String::isNotBlank)
    val description = when (tool.state) {
        RunToolState.Running -> "Running tool ${tool.name}${toolContext?.let { ": $it" }.orEmpty()}"
        RunToolState.Completed -> "Completed tool ${tool.name}${toolSummary?.let { ": $it" }.orEmpty()}"
    }
    val hasDetail = toolContext != null || toolSummary != null
    var detailExpanded by rememberSaveable(tool.toolId) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasDetail) {
                    Modifier.clickable { detailExpanded = !detailExpanded }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = description
                stateDescription = tool.state.name
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tool.state) {
            RunToolState.Running -> CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = "Running" },
                color = semanticColors.active,
                strokeWidth = 2.dp,
            )
            RunToolState.Completed -> Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = "Completed" },
                tint = semanticColors.completed,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
            toolContext?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (detailExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            toolSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (detailExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hasDetail) {
            Icon(
                if (detailExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
