package com.unsupportedpastels.mercury.core.sessions

/**
 * One row of the official read-only `session.active_list` JSON-RPC snapshot
 * (live, in-memory sessions of the connected gateway process).
 *
 * `sessionKey` is the durable stored session ID used by REST session lists —
 * the only field a presence join may key on. The runtime `id` is opaque and
 * must never be joined to durable rows.
 */
data class SessionActiveListRow(
    val id: String,
    val sessionKey: String,
    val status: String,
)

/**
 * Working-state presence decided once for both clients from the official
 * `session.active_list` snapshot.
 *
 * Authoritative for sessions live in the connected gateway process: a session
 * is "working" only when its `status` is exactly `working`. The other
 * documented statuses (`starting`, `idle`, `waiting`) never light the
 * indicator — `waiting` includes approval/clarification pends and must not
 * read as model work, and `idle` rows must not be relabelled as active.
 *
 * The result is observer-only presence for rendering a working indicator; it
 * never implies runtime ownership and must never gate resume/activate
 * affordances. The snapshot is process-local (profile-scoped Desktop/TUI
 * child backends are invisible), so absence of a row is "unknown", not
 * "idle" — callers fail closed by simply clearing the indicator.
 */
object SessionPresencePolicy {
    const val WORKING_STATUS = "working"
    const val MAX_ROWS = 256

    /** Bounded, tolerant parse of the `sessions` array of `session.active_list`. */
    fun parseRows(result: Map<*, *>?): List<SessionActiveListRow> {
        if (result == null) return emptyList()
        val rows = result["sessions"] as? List<*> ?: return emptyList()
        return rows.asSequence()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull { row ->
                val id = (row["id"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val sessionKey = row["session_key"] as? String ?: return@mapNotNull null
                if (sessionKey.isEmpty()) return@mapNotNull null
                val status = (row["status"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                SessionActiveListRow(id = id, sessionKey = sessionKey, status = status)
            }
            .take(MAX_ROWS)
            .toList()
    }

    /** Durable session IDs currently running a turn (`status == "working"`). */
    fun workingSessionIds(rows: List<SessionActiveListRow>): Set<String> =
        rows.asSequence()
            .filter { it.status == WORKING_STATUS }
            .map { it.sessionKey }
            .toSet()
}
