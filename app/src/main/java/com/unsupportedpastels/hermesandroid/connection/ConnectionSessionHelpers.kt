package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.reconcileProjectSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatMethodNotFoundException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun loadRelayProjects(
    session: HermesChatSession,
    profile: String,
    durableSessions: List<SessionSummary>,
): RelayProjectSnapshot = try {
    val tree = session.loadProjectTree(profile = profile)
    val projects = tree.projects.map { project ->
        project.copy(
            previewSessions = project.previewSessions.map { preview ->
                reconcileProjectSession(project.id, preview, durableSessions)
            },
        )
    }
    RelayProjectSnapshot(
        projects = projects,
        state = ProjectLoadState.Loaded(
            projects = projects,
            activeProjectId = tree.activeProjectId,
            scopedSessionIds = tree.scopedSessionIds,
        ),
        activeProjectId = tree.activeProjectId,
        scopedSessionIds = tree.scopedSessionIds,
    )
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: HermesChatMethodNotFoundException) {
    RelayProjectSnapshot(emptyList(), ProjectLoadState.Unsupported)
} catch (error: Exception) {
    RelayProjectSnapshot(
        projects = emptyList(),
        state = ProjectLoadState.TransientError(
            error.message?.take(160)?.takeIf(String::isNotBlank)
                ?: "Could not load project metadata",
        ),
    )
}

internal suspend fun closeChatSessionNonCancellably(session: HermesChatSession?) {
    if (session == null) return
    withContext(NonCancellable) {
        withTimeoutOrNull(5_000L) { runCatching { session.close() } }
    }
}
