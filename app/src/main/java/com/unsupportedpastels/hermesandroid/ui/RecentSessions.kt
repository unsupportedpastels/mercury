package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
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
    clock: () -> Long = System::currentTimeMillis,
) {
    val nowMillis = rememberSessionRecencyTime(clock)
    val semanticColors = LocalHermesSemanticColors.current
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
                    SessionInboxRow(
                        session = session,
                        projectLabel = projectLabel,
                        // All-project rows must identify the project even when a profile is present.
                        ownerLabel = projectLabel,
                        isWorking = session.id in activeWorkingSessionIds,
                        // Completion-unread state is owned by the project inbox, not this snapshot.
                        isUnreadComplete = false,
                        activeColor = semanticColors.active,
                        completedColor = semanticColors.completed,
                        nowMillis = nowMillis,
                        modifier = Modifier.background(
                            if (session.id in activeControllerSessionIds) {
                                semanticColors.active.copy(alpha = 0.10f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ).semantics {
                            if (session.id in activeControllerSessionIds) {
                                stateDescription = "Current controller session"
                            }
                        },
                        onClick = { onSessionSelected(session.id) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
