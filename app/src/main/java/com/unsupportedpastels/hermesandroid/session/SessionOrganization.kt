package com.unsupportedpastels.hermesandroid.session

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.mercury.core.sessions.SessionListFilterPolicy
import com.unsupportedpastels.mercury.core.sessions.SessionListFilterSpec

const val MAX_SAVED_FILTERS_PER_SCOPE = 20
const val MAX_SAVED_FILTER_SCOPES = 64
const val MAX_SAVED_FILTER_NAME_CHARS = 64
const val MAX_SAVED_FILTER_QUERY_CHARS = SessionListFilterPolicy.MAX_QUERY_CHARS
const val MAX_BULK_SELECTION = 500

/** The only predicates currently understood by the Home session list. */
data class SessionListFilter(
    val query: String = "",
    val pinnedOnly: Boolean = false,
    val archivedOnly: Boolean = false,
) {
    init {
        require(query.length <= MAX_SAVED_FILTER_QUERY_CHARS) {
            "Session filter query is too long"
        }
    }

    fun toSearchQuery(): String =
        SessionListFilterPolicy.format(SessionListFilterSpec(query, pinnedOnly, archivedOnly))

    companion object {
        /** The predicate grammar is a shared decision; iOS parses the same box the same way. */
        fun fromSearchQuery(value: String): SessionListFilter {
            val spec = SessionListFilterPolicy.parse(value)
            return SessionListFilter(spec.query, spec.pinnedOnly, spec.archivedOnly)
        }
    }
}

/** Local-only, content-free saved list filter. */
data class SavedSessionFilter(
    val name: String,
    val filter: SessionListFilter,
) {
    init {
        require(name.trim().isNotEmpty()) { "Saved filter name must not be blank" }
        require(name.length <= MAX_SAVED_FILTER_NAME_CHARS) {
            "Saved filter name is too long"
        }
    }

    val normalizedName: String
        get() = name.trim()
}

/** A saved-filter scope is never just an origin: profile is part of its identity. */
data class SessionFilterScope(
    val serverOrigin: ServerOrigin,
    val profile: String,
) {
    init {
        require(profile.isNotBlank() && profile == profile.trim()) {
            "Session filter profile is invalid"
        }
        require(profile.length <= 64) { "Session filter profile is too long" }
    }
}

data class BulkDeleteSelectionDecision(
    val selectedSessionIds: List<DurableSessionId>,
    val invalidSessionIds: Set<DurableSessionId> = emptySet(),
    val blockedSessionIds: Set<DurableSessionId> = emptySet(),
    val tooMany: Boolean = false,
) {
    val canDelete: Boolean
        get() = selectedSessionIds.isNotEmpty() &&
            invalidSessionIds.isEmpty() &&
            blockedSessionIds.isEmpty() &&
            !tooMany
}

fun toggleBulkSelection(
    selectedSessionIds: Set<DurableSessionId>,
    session: SessionSummary,
): Set<DurableSessionId> {
    if (session.isLocalDraft) return selectedSessionIds
    return if (session.id in selectedSessionIds) {
        selectedSessionIds - session.id
    } else if (selectedSessionIds.size < MAX_BULK_SELECTION) {
        selectedSessionIds + session.id
    } else {
        selectedSessionIds
    }
}

fun evaluateBulkDeleteSelection(
    selectedIds: Collection<DurableSessionId>,
    sessions: Collection<SessionSummary>,
    controllerRuntimeSessionIds: Set<DurableSessionId>,
    activeTurnSessionIds: Set<DurableSessionId>,
): BulkDeleteSelectionDecision {
    val distinctIds = selectedIds.distinct()
    val sessionById = sessions.associateBy(SessionSummary::id)
    val invalid = distinctIds.filterTo(linkedSetOf()) { id ->
        val session = sessionById[id]
        session == null || session.isLocalDraft
    }
    val blocked = distinctIds.filterTo(linkedSetOf()) { id ->
        id in controllerRuntimeSessionIds || id in activeTurnSessionIds
    }
    return BulkDeleteSelectionDecision(
        selectedSessionIds = distinctIds,
        invalidSessionIds = invalid,
        blockedSessionIds = blocked,
        tooMany = distinctIds.size > MAX_BULK_SELECTION,
    )
}
