package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.mercury.core.sessions.SessionListMergePolicy
import com.unsupportedpastels.mercury.core.sessions.SessionMergeEntry

/**
 * Replaces the durable session list with a server-fetched list while keeping
 * local draft sessions ("New chat" entries that only exist on this client)
 * visible. Without this, a background session-list refresh that lands while a
 * draft is open removes the draft from the snapshot and the open detail route
 * degrades to "Session is no longer available".
 *
 * Drafts are kept only while they are still pending (not yet promoted to a
 * server session) and not already represented in the server list.
 */
internal fun mergeServerSessionsPreservingDrafts(
    serverSessions: List<SessionSummary>,
    currentSessions: List<SessionSummary>,
    pendingDrafts: Set<DurableSessionId>,
): List<SessionSummary> {
    val preserved = SessionListMergePolicy.preservedDraftIndices(
        serverIds = serverSessions.map { it.id.value },
        currentSessions = currentSessions.map {
            SessionMergeEntry(id = it.id.value, isLocalDraft = it.isLocalDraft)
        },
        pendingDraftIds = pendingDrafts.mapTo(mutableSetOf()) { it.value },
    )
    if (preserved.isEmpty()) return serverSessions
    return preserved.map(currentSessions::get) + serverSessions
}
