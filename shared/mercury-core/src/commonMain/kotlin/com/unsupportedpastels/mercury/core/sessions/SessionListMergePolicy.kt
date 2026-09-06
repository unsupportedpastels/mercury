package com.unsupportedpastels.mercury.core.sessions

/** The two facts about a rendered session row the merge decision reads. */
data class SessionMergeEntry(
    val id: String,
    val isLocalDraft: Boolean,
)

/**
 * Session-list merge decided once for both clients: replace the durable
 * session list with a server-fetched list while keeping local draft sessions
 * ("New chat" entries that only exist on this client) visible. Without this,
 * a background refresh landing while a draft is open removes the draft and
 * the open detail route degrades to "Session is no longer available".
 *
 * Determinism: the server list is authoritative — its contents and order win
 * verbatim, nothing is re-sorted or deduped out of it. Preserved drafts keep
 * their relative order and are prepended ahead of the server rows.
 *
 * The decision is returned as indices into `currentSessions` so each platform
 * keeps its own typed session model; a draft survives only while it is still
 * pending (not yet promoted) and not already represented in the server list.
 */
object SessionListMergePolicy {
    fun preservedDraftIndices(
        serverIds: List<String>,
        currentSessions: List<SessionMergeEntry>,
        pendingDraftIds: Set<String>,
    ): List<Int> {
        if (pendingDraftIds.isEmpty()) return emptyList()
        val server = serverIds.toHashSet()
        return currentSessions.withIndex()
            .filter { (_, session) ->
                session.isLocalDraft && session.id in pendingDraftIds && session.id !in server
            }
            .map { it.index }
    }
}
