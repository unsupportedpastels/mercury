package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.share.SharePayload

internal val DraftsSaver = Saver<SnapshotStateMap<String, String>, ArrayList<String>>(
    save = { drafts ->
        ArrayList(drafts.entries.flatMap { (sessionId, draft) -> listOf(sessionId, draft) })
    },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.chunked(2).forEach { pair ->
                if (pair.size == 2) put(pair[0], pair[1])
            }
        }
    },
)

internal data class PendingComposerSubmission(
    val referenceKey: String,
    val draftKey: String,
    val draft: String,
    val acceptedCount: Long,
    val rejectedCount: Long,
    val prompt: String,
    val references: Set<String>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareDestinationSheet(
    payload: SharePayload,
    sessions: List<SessionSummary>,
    projects: List<ProjectSummary>,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onProjectSelected: (ProjectId) -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Send to chat", style = MaterialTheme.typography.headlineSmall)
            Text(
                buildString {
                    if (payload.text.isNotBlank()) append("Shared text")
                    if (payload.text.isNotBlank() && payload.attachments.isNotEmpty()) append(" · ")
                    if (payload.attachments.isNotEmpty()) append("${payload.attachments.size} attachment(s)")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Text("New chat")
            }
            if (projects.isNotEmpty()) {
                Text("Projects", style = MaterialTheme.typography.titleSmall)
                projects.take(8).forEach { project ->
                    ListItem(
                        headlineContent = { Text(project.label) },
                        supportingContent = { Text("Start a new task here") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProjectSelected(project.id) }
                            .semantics {
                                contentDescription = "Share with project ${project.label}"
                            },
                    )
                }
            }
            if (sessions.isNotEmpty()) {
                Text("Recent chats", style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(sessions.take(20), key = { it.id.value }) { session ->
                        ListItem(
                            headlineContent = { Text(session.title) },
                            supportingContent = session.preview?.takeIf(String::isNotBlank)?.let { preview ->
                                { Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSessionSelected(session.id) }
                                .semantics {
                                    contentDescription = "Share with ${session.title}"
                                },
                        )
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
