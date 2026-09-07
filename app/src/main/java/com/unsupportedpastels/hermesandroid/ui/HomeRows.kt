package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath

@Composable
internal fun SwipeSessionRow(
    onDeleteRequest: () -> Unit,
    backgroundPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    backgroundShape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    // The state's confirmValueChange lambda is captured once at creation, so
    // route the callback through rememberUpdatedState to avoid stale captures
    // when the row recomposes with a fresh SessionSummary.
    val currentDeleteRequest by rememberUpdatedState(onDeleteRequest)
    // Resizing the list pane can make swipe anchors coincide and request a
    // dismissal without input. Track a real pointer transition through the
    // post-release settlement phase instead of requiring the pointer to remain
    // pressed while confirmValueChange runs.
    var pointerPressed by remember { mutableStateOf(false) }
    var gestureSettlingToDelete by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    val requestDelete = shouldRequestSwipeDelete(
                        pointerPressed = pointerPressed,
                        gestureSettlingToDelete = gestureSettlingToDelete,
                    )
                    gestureSettlingToDelete = false
                    if (requestDelete) currentDeleteRequest()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> false
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                SwipeActionBackground(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    icon = Icons.Outlined.Delete,
                    label = "Delete",
                    alignment = Alignment.CenterStart,
                    padding = backgroundPadding,
                    shape = backgroundShape,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pointerPressed = true
                    gestureSettlingToDelete = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        pointerPressed = false
                        gestureSettlingToDelete =
                            dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
                    }
                }
            },
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeActionBackground(
    color: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    label: String,
    alignment: Alignment,
    padding: PaddingValues,
    shape: Shape,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(color, shape)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Text(label, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun RunningSubagentRow(subagent: DelegatedSubagent) {
    val statusLine = buildString {
        append(subagent.status)
        subagent.parentSubagentId?.let { append(" · child of ").append(it) }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Running subagent: ${subagent.goal}, $statusLine"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                subagent.goal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ProjectHomeRow(
    project: ProjectSummary,
    working: Boolean,
    onClick: () -> Unit,
) {
    val sessionLabel = if (project.sessionCount == 1) "1 session" else "${project.sessionCount} sessions"
    val latestTitle = project.previewSessions.firstOrNull()?.title
    val description = buildString {
        append("Project ")
        append(project.label)
        if (working) append(", active session running")
        append(", ")
        append(sessionLabel)
        if (latestTitle != null) {
            append(", latest ")
            append(latestTitle)
        }
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (working) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (working) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = if (working) 1.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("Project home row:${project.label}")
            .semantics(mergeDescendants = true) {
                selected = working
                contentDescription = description
                if (working) stateDescription = "Active session running"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    project.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (working) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    sessionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                validProjectWorkspacePath(project.primaryPath) ?: "No workspace",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            latestTitle?.let { latest ->
                Text(
                    "Latest · $latest",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
internal fun RecentSessionHomeRow(
    session: SessionSummary,
    projectLabel: String? = null,
    current: Boolean,
    isWorking: Boolean = false,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (current) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("Recent session row:${session.id.value}")
            .combinedClickable(onClick = onClick, onLongClick = onRename)
            .semantics(mergeDescendants = true) {
                if (current) stateDescription = "Current controller session"
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val supportingLabel = listOfNotNull(
                    if (session.isLocalDraft) "Draft" else null,
                    if (current) "Controller active" else null,
                    projectLabel,
                ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isWorking) {
                        Spacer(Modifier.width(8.dp))
                        WorkingIndicator()
                    }
                }
                if (supportingLabel.isNotEmpty()) {
                    Text(
                        supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}
