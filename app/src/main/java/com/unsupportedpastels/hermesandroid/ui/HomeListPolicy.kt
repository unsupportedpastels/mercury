package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.SessionSearchResult
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot

private const val HOME_RECENT_SESSION_PREVIEW_LIMIT = 10

private fun mergeSessionCollections(
    durableSessions: List<SessionSummary>,
    projectSessions: List<SessionSummary>,
    searchResults: List<SessionSearchResult>,
): List<SessionSummary> {
    val merged = linkedMapOf<DurableSessionId, SessionSummary>()
    durableSessions.forEach { session -> merged[session.id] = session }
    projectSessions.forEach { projectSession ->
        val existing = merged[projectSession.id]
        merged[projectSession.id] = if (existing == null) {
            projectSession
        } else {
            existing.copy(
                projectId = existing.projectId ?: projectSession.projectId,
                workspacePath = existing.workspacePath ?: projectSession.workspacePath,
            )
        }
    }
    searchResults.forEach { result ->
        if (result.sessionId !in merged) {
            merged[result.sessionId] = SessionSummary(
                id = result.sessionId,
                title = result.title,
                preview = result.snippet,
            )
        }
    }
    return merged.values.toList()
}

internal fun projectForSessionWorkspace(
    session: SessionSummary,
    projects: List<ProjectSummary>,
): ProjectSummary? {
    val workspace = validProjectWorkspacePath(session.workspacePath)
        ?.trimEnd('/', '\\')
        ?: return null
    return projects.asSequence()
        .filter { project ->
            val projectPath = validProjectWorkspacePath(project.primaryPath)
                ?.trimEnd('/', '\\')
                ?: return@filter false
            workspace == projectPath ||
                workspace.startsWith("$projectPath/") ||
                workspace.startsWith("$projectPath\\")
        }
        .maxByOrNull { project ->
            validProjectWorkspacePath(project.primaryPath)?.length ?: 0
        }
}

/**
 * Session collections are metadata projections of the gateway snapshot. Keeping the
 * keys explicit prevents transcript/chat deltas from re-running merge, workspace
 * attribution, and home sorting when the inbox inputs did not change.
 */
internal data class SessionInboxMetadata(
    val sessions: List<SessionSummary>,
    val recentSessions: List<SessionSummary>,
)

@Composable
internal fun rememberSessionInboxMetadata(
    durableSessions: List<SessionSummary>,
    projectSessions: Map<ProjectId, List<SessionSummary>>,
    recentSessionMetadata: List<SessionSummary>,
    transcriptSearchResults: List<SessionSearchResult>,
    projects: List<ProjectSummary>,
): SessionInboxMetadata = remember(
    durableSessions,
    projectSessions,
    recentSessionMetadata,
    transcriptSearchResults,
    projects,
) {
    val mergedSessions = mergeSessionCollections(
        durableSessions = durableSessions,
        projectSessions = projectSessions.values.flatten() + recentSessionMetadata,
        searchResults = transcriptSearchResults,
    )
    val sessions = mergedSessions.map { session ->
        session.copy(projectId = session.projectId ?: projectForSessionWorkspace(session, projects)?.id)
    }
    SessionInboxMetadata(
        sessions = sessions,
        recentSessions = sessions
            .sortedByDescending { it.lastActiveEpochSeconds ?: Double.NEGATIVE_INFINITY }
            .take(HOME_RECENT_SESSION_PREVIEW_LIMIT),
    )
}

internal data class HomeListPinDecision(
    val userHasScrolled: Boolean,
    val pinToTop: Boolean,
)

internal fun decideHomeListPinning(
    userHasScrolled: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): HomeListPinDecision {
    return HomeListPinDecision(
        userHasScrolled = userHasScrolled,
        pinToTop = !userHasScrolled &&
            (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0),
    )
}

internal fun isUserInitiatedHomeScroll(
    available: Offset,
    source: NestedScrollSource,
): Boolean = source == NestedScrollSource.UserInput && available != Offset.Zero

internal fun shouldRequestSwipeDelete(
    pointerPressed: Boolean,
    gestureSettlingToDelete: Boolean,
): Boolean = pointerPressed || gestureSettlingToDelete

internal fun connectionContext(
    snapshot: HermesGatewaySnapshot,
    serverOrigin: ServerOrigin?,
): String = when (snapshot.connectionState) {
    ConnectionState.Connected -> when (snapshot.authenticationState) {
        AuthenticationState.SignInRequired -> "Sign in required"
        AuthenticationState.SigningIn -> "Signing in"
        else -> "Connected"
    }
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Recovering -> "Reconnecting"
    ConnectionState.Disconnected ->
        if (snapshot.relayTargetId == null && serverOrigin == null) "Not configured" else "Offline"
}
