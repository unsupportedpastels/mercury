package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot

internal fun HermesGatewaySnapshot.mapSession(
    sessionId: DurableSessionId,
    transform: (SessionSummary) -> SessionSummary,
): HermesGatewaySnapshot = copy(
    durableSessions = durableSessions.map { if (it.id == sessionId) transform(it) else it },
    projects = projects.map { project ->
        project.copy(previewSessions = project.previewSessions.map {
            if (it.id == sessionId) transform(it) else it
        })
    },
    projectSessions = projectSessions.mapValues { (_, sessions) ->
        sessions.map { if (it.id == sessionId) transform(it) else it }
    },
)

internal fun HermesGatewaySnapshot.removeSession(sessionId: DurableSessionId): HermesGatewaySnapshot = copy(
    durableSessions = durableSessions.filterNot { it.id == sessionId },
    projects = projects.map { project ->
        project.copy(
            sessionCount = (project.sessionCount - project.previewSessions.count { it.id == sessionId })
                .coerceAtLeast(0),
            previewSessions = project.previewSessions.filterNot { it.id == sessionId },
        )
    },
    projectSessions = projectSessions.mapValues { (_, sessions) ->
        sessions.filterNot { it.id == sessionId }
    },
    chatSessions = chatSessions - sessionId,
    activeRuntimes = activeRuntimes.filterNot { it.durableSessionId == sessionId },
)
