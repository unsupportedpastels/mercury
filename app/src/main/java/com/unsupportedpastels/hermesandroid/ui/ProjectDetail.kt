package com.unsupportedpastels.hermesandroid.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectDetailScreen(
    project: ProjectSummary,
    state: ProjectSessionLoadState?,
    sessions: List<SessionSummary>,
    workingSessionIds: Set<DurableSessionId>,
    unreadCompletedSessionIds: Set<DurableSessionId>,
    modifier: Modifier = Modifier,
    showBack: Boolean,
    showNewTaskAction: Boolean = true,
    onBack: () -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
    onNewTask: () -> Unit,
    onDeleteSession: suspend (DurableSessionId) -> Result<Unit>,
) {
    val semanticColors = LocalHermesSemanticColors.current
    var deletingSession by remember { mutableStateOf<SessionSummary?>(null) }
    val sessionActionScope = rememberCoroutineScope()
    val loadedSessions = when (state) {
        is ProjectSessionLoadState.Loaded ->
            if (sessions.isEmpty()) state.sessions else sessions
        else -> emptyList()
    }
    val workspace = validProjectWorkspacePath(project.primaryPath)
    val workspaceLabel = workspace ?: "No workspace"
    Scaffold(
        modifier = modifier.semantics {
            contentDescription = "Project sessions for ${project.label}"
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(project.label)
                        Text("Project", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (showNewTaskAction && state is ProjectSessionLoadState.Loaded) {
                        TextButton(onClick = dropUnlessResumed { onNewTask() }) {
                            Text("New task")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Sessions inbox, ${project.sessionCount} sessions"
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sessions",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        project.sessionCount.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    "Workspace: $workspaceLabel",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (workspace == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            when (state) {
                null,
                ProjectSessionLoadState.Loading,
                -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading project sessions")
                    }
                }
                ProjectSessionLoadState.Unsupported -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Project sessions unavailable")
                    }
                }
                is ProjectSessionLoadState.TransientError -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Could not load project sessions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ProjectSessionLoadState.Loaded -> {
                    if (loadedSessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No sessions in this project")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                        ) {
                            items(loadedSessions, key = { it.id.value }) { session ->
                                val isWorking = session.id in workingSessionIds
                                val isUnreadComplete = !isWorking && session.id in unreadCompletedSessionIds
                                SwipeSessionRow(
                                    onDeleteRequest = { deletingSession = session },
                                    backgroundPadding = PaddingValues(0.dp),
                                    backgroundShape = RectangleShape,
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface) {
                                        SessionInboxRow(
                                            session = session,
                                            projectLabel = project.label,
                                            isWorking = isWorking,
                                            isUnreadComplete = isUnreadComplete,
                                            activeColor = semanticColors.active,
                                            completedColor = semanticColors.completed,
                                            onClick = { onSessionSelected(session.id) },
                                        )
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    deletingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("Delete session?") },
            text = { Text("This permanently deletes ${session.title} from Hermes Serve.") },
            confirmButton = {
                TextButton(onClick = {
                    sessionActionScope.launch { onDeleteSession(session.id) }
                    deletingSession = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingSession = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SessionInboxRow(
    session: SessionSummary,
    projectLabel: String,
    isWorking: Boolean,
    isUnreadComplete: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    completedColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ownerLabel: String = session.profile ?: projectLabel,
) {
    val workspace = validProjectWorkspacePath(session.workspacePath)
    val workspaceLabel = workspace ?: "No workspace"
    val preview = session.preview?.trim()?.takeIf(String::isNotEmpty)
    val recency = session.lastActiveEpochSeconds?.let(::formatSessionRecency)
    val metadata = listOfNotNull(
        session.model?.trim()?.takeIf(String::isNotEmpty),
        session.messageCount?.let { count -> "$count ${if (count == 1) "message" else "messages"}" },
    ).joinToString(" · ")
    val rowDescription = buildString {
        append("Session ${session.title}, $workspaceLabel")
        if (isWorking) append(", running")
        if (isUnreadComplete) append(", completed unread")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = dropUnlessResumed { onClick() })
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                contentDescription = rowDescription
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isWorking -> PulsingSessionStatusIndicator(
                    color = activeColor,
                    contentDescription = "${session.title} is running",
                    size = 10.dp,
                )
                isUnreadComplete -> Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(completedColor, androidx.compose.foundation.shape.CircleShape)
                        .semantics {
                            contentDescription = "${session.title} completed; unread"
                        },
                )
                else -> Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ownerLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (recency != null) {
                    Text(
                        recency,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics {
                            contentDescription = "Last active time available"
                        },
                    )
                }
            }
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (preview != null) {
                Text(
                    preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (metadata.isNotEmpty()) {
                Text(
                    metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    workspaceLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (workspace == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.isLocalDraft) {
                Text(
                    "Draft",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatSessionRecency(epochSeconds: Double): String {
    val timestampMillis = (epochSeconds * 1_000.0).toLong()
    return DateUtils.getRelativeTimeSpanString(
        timestampMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@Composable
private fun PulsingSessionStatusIndicator(
    color: androidx.compose.ui.graphics.Color,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val pulse = rememberInfiniteTransition(label = "Session running pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SESSION_STATUS_PULSE_MILLIS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Session running indicator alpha",
    )
    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .background(color, androidx.compose.foundation.shape.CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                sessionStatusPulseAlpha = alpha
            },
    )
}

@Composable
internal fun MissingProjectScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Project is no longer available")
    }
}
