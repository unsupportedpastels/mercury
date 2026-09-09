package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.artifacts.Artifact
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ContextBreakdownCategory
import java.util.Locale

private enum class SessionMaintenanceAction {
    Compress,
    Undo,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionInsightsSheet(
    sessionTitle: String,
    chat: ChatSessionSnapshot,
    workspaceLabel: String? = null,
    provider: String? = null,
    maintenanceAvailable: Boolean,
    maintenanceEnabled: Boolean,
    artifacts: List<Artifact>,
    onOpenArtifacts: () -> Unit,
    onRefresh: () -> Unit,
    onCompress: (String?) -> Unit,
    onUndo: () -> Unit,
    onBranch: (Int?, String?) -> Unit,
    onDismiss: () -> Unit,
    onOpenActivity: () -> Unit = {},
) {
    var pendingAction by remember { mutableStateOf<SessionMaintenanceAction?>(null) }
    var branchDialogOpen by remember { mutableStateOf(false) }
    var branchName by remember(sessionTitle) { mutableStateOf("$sessionTitle branch") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Session details", style = MaterialTheme.typography.headlineSmall)
                TextButton(
                    onClick = onRefresh,
                    enabled = maintenanceAvailable && !chat.insightsLoading,
                ) {
                    Text("Refresh")
                }
            }
            TextButton(
                onClick = onOpenActivity,
                modifier = Modifier.semantics { contentDescription = "Open activity details" },
            ) { Text("Activity") }
            listOfNotNull(
                provider?.takeIf(String::isNotBlank)?.let { "Provider: $it" },
                workspaceLabel?.let { "Workspace: $it" },
            ).takeIf(List<String>::isNotEmpty)?.let { details ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    details.forEach { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (chat.insightsLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Loading session details" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Loading session details…")
                }
            }
            chat.insightsError?.takeIf { maintenanceAvailable }?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Session details error" },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Could not load session details", style = MaterialTheme.typography.titleSmall)
                        Text(error.take(180))
                    }
                }
            }
            if (!chat.insightsLoading) {
                SessionUsageCard(chat)
            }
            SessionArtifactsCard(
                artifacts = artifacts,
                onOpenArtifacts = onOpenArtifacts,
            )
            if (!chat.insightsLoading) {
                SessionContextCard(chat)
            }
            if (chat.maintenanceLoading ||
                chat.maintenanceError != null ||
                chat.notice != null
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Maintenance", style = MaterialTheme.typography.titleSmall)
                        if (chat.maintenanceLoading) {
                            Text("Applying session maintenance…")
                        }
                        chat.maintenanceError?.let { error ->
                            Text(
                                error.take(180),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        chat.notice?.let { notice ->
                            Text(
                                notice.take(180),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
            if (maintenanceAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Session maintenance", style = MaterialTheme.typography.titleMedium)
                        if (!maintenanceEnabled && !chat.maintenanceLoading) {
                            Text(
                                "Available when the session is idle",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = { pendingAction = SessionMaintenanceAction.Compress },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Compress")
                        }
                        Button(
                            onClick = { pendingAction = SessionMaintenanceAction.Undo },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Undo")
                        }
                        Button(
                            onClick = { branchDialogOpen = true },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Branch")
                        }
                    }
                }
            }
        }
    }
    pendingAction?.let { action ->
        val title = when (action) {
            SessionMaintenanceAction.Compress -> "Compress session?"
            SessionMaintenanceAction.Undo -> "Undo last turn?"
        }
        val confirmLabel = when (action) {
            SessionMaintenanceAction.Compress -> "Confirm compression"
            SessionMaintenanceAction.Undo -> "Confirm undo"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = {
                Text(
                    when (action) {
                        SessionMaintenanceAction.Compress -> "Compress this session context?"
                        SessionMaintenanceAction.Undo -> "Remove the last user turn from this session?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        when (action) {
                            SessionMaintenanceAction.Compress -> onCompress(null)
                            SessionMaintenanceAction.Undo -> onUndo()
                        }
                    },
                    enabled = maintenanceEnabled,
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
            },
        )
    }
    if (branchDialogOpen) {
        AlertDialog(
            onDismissRequest = { branchDialogOpen = false },
            title = { Text("Branch session") },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Branch name") },
                    singleLine = true,
                    enabled = maintenanceEnabled,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = branchName.trim()
                        if (maintenanceEnabled && name.isNotEmpty()) {
                            branchDialogOpen = false
                            onBranch(null, name)
                        }
                    },
                    enabled = maintenanceEnabled && branchName.isNotBlank(),
                ) {
                    Text("Create branch")
                }
            },
            dismissButton = {
                TextButton(onClick = { branchDialogOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionUsageCard(chat: ChatSessionSnapshot) {
    val usage = chat.sessionUsage
    val context = chat.contextBreakdown
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Token usage", style = MaterialTheme.typography.titleMedium)
            SessionInsightMetric("Input tokens", formatSessionTokens(usage?.inputTokens))
            SessionInsightMetric("Output tokens", formatSessionTokens(usage?.outputTokens))
            SessionInsightMetric("Total tokens", formatSessionTokens(usage?.totalTokens))
            Text("Context used", style = MaterialTheme.typography.labelLarge)
            Text(
                formatContextSummary(
                    used = usage?.contextUsedTokens ?: context?.usedTokens,
                    max = usage?.contextMaxTokens ?: context?.maxTokens,
                    percent = usage?.contextPercent ?: context?.percent,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Model: ${chat.model?.takeIf(String::isNotBlank) ?: "Unknown"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SessionContextCard(chat: ChatSessionSnapshot) {
    val categories = chat.contextBreakdown?.categories.orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Context categories", style = MaterialTheme.typography.titleMedium)
            if (categories.isEmpty()) {
                Text(
                    "No context categories reported",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                categories.forEach { category ->
                    ContextCategoryRow(category)
                }
            }
        }
    }
}

@Composable
private fun SessionArtifactsCard(
    artifacts: List<Artifact>,
    onOpenArtifacts: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Artifacts", style = MaterialTheme.typography.titleMedium)
            Text(
                if (artifacts.size == 1) "1 artifact" else "${artifacts.size} artifacts",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (artifacts.isEmpty()) {
                Text(
                    "No images, audio, or files referenced in this chat",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                artifacts.take(3).forEach { artifact ->
                    Text(
                        artifact.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(
                    onClick = onOpenArtifacts,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("View all artifacts")
                }
            }
        }
    }
}

@Composable
private fun ContextCategoryRow(category: ContextBreakdownCategory) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.name, modifier = Modifier.weight(1f))
        Text(
            "${formatSessionTokens(category.tokens)} tokens",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SessionInsightMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatSessionTokens(value: Long?): String =
    value?.let { String.format(Locale.US, "%,d", it) } ?: "—"

private fun formatContextSummary(used: Long?, max: Long?, percent: Double?): String {
    val tokenSummary = when {
        used != null && max != null -> "${formatSessionTokens(used)} / ${formatSessionTokens(max)}"
        used != null -> formatSessionTokens(used)
        max != null -> "— / ${formatSessionTokens(max)}"
        else -> "—"
    }
    return if (percent == null) tokenSummary else "$tokenSummary (${formatPercent(percent)}%)"
}

internal fun formatPercent(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.1f", value)
