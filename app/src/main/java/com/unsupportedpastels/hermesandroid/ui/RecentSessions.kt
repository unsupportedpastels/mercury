package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentSessionsScreen(
    snapshot: HermesGatewaySnapshot,
    projects: List<ProjectSummary>,
    showBack: Boolean,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onLoadMore: () -> Unit,
    onRefreshWorkingPresence: () -> Unit = {},
    onSessionSelected: (DurableSessionId) -> Unit,
) {
    val state = snapshot.recentSessions
    val listState = rememberLazyListState()
    val projectBySessionId = buildMap {
        snapshot.projectSessions.forEach { (projectId, sessions) ->
            sessions.forEach { session -> put(session.id, projectId) }
        }
    }
    val projectById = projects.associateBy(ProjectSummary::id)
    val sessions = state.sessions.map { session ->
        val projectId = session.projectId
            ?: projectBySessionId[session.id]
            ?: projectForSessionWorkspace(session, projects)?.id
        val projectSession = projectId?.let { id ->
            snapshot.projectSessions[id]?.firstOrNull { it.id == session.id }
        }
        session.copy(
            projectId = projectId,
            workspacePath = session.workspacePath ?: projectSession?.workspacePath,
        )
    }
    val activeControllerSessionIds = snapshot.activeRuntimes
        .filter { it.access == RuntimeAccess.Controller }
        .mapNotNull { it.durableSessionId }
        .toSet()
    val activeWorkingSessionIds = snapshot.activeWorkingSessionIds

    // Same silent presence poll as Home so the full list stays live.
    LaunchedEffect(snapshot.connectionState, snapshot.authenticationState) {
        if (snapshot.connectionState != ConnectionState.Connected ||
            snapshot.authenticationState !in setOf(
                AuthenticationState.Authenticated,
                AuthenticationState.NotRequired,
            )
        ) {
            return@LaunchedEffect
        }
        onRefreshWorkingPresence()
        while (true) {
            delay(WORKING_PRESENCE_POLL_MILLIS)
            onRefreshWorkingPresence()
        }
    }

    LaunchedEffect(snapshot.selectedProfile, snapshot.authenticationState) {
        if (snapshot.authenticationState in setOf(
                AuthenticationState.Authenticated,
                AuthenticationState.NotRequired,
            )
        ) onLoad()
    }
    LaunchedEffect(listState, state.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= sessions.size - 5) onLoadMore()
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Recent Sessions")
                        val count = state.total ?: sessions.size
                        Text(
                            "$count across all projects",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) {
                            Text("Back")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("Recent sessions full list"),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            ),
        ) {
            if (state.isLoading && sessions.isEmpty()) {
                item(key = "recent-sessions-loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            } else if (state.error != null && sessions.isEmpty()) {
                item(key = "recent-sessions-error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onLoad) { Text("Retry") }
                    }
                }
            } else if (sessions.isEmpty()) {
                item(key = "recent-sessions-page-empty") {
                    Text(
                        "No recent sessions",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                items(sessions, key = { "recent-page-session:${it.id.value}" }) { session ->
                    val projectLabel = session.projectId
                        ?.let(projectById::get)
                        ?.label
                        ?: session.projectId?.value
                        ?: "No project"
                    RecentSessionFullRow(
                        session = session,
                        projectLabel = projectLabel,
                        current = session.id in activeControllerSessionIds,
                        isWorking = session.id in activeWorkingSessionIds,
                        onClick = dropUnlessResumed { onSessionSelected(session.id) },
                    )
                }
                if (state.isLoadingMore) {
                    item(key = "recent-sessions-loading-more") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                } else if (state.error != null) {
                    item(key = "recent-sessions-load-more-error") {
                        TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Could not load more · Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSessionFullRow(
    session: SessionSummary,
    projectLabel: String,
    current: Boolean,
    isWorking: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (current) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                if (current) stateDescription = "Current controller session"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isWorking) {
                    Spacer(Modifier.width(8.dp))
                    WorkingIndicator()
                }
            }
            Text(
                projectLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            session.workspacePath?.let { workspace ->
                Text(
                    workspace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            session.preview?.let { preview ->
                Text(
                    preview,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
