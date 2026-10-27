package com.unsupportedpastels.hermesandroid.app

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class DurableSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Durable session ID must not be blank" }
    }
}

data class SessionSummary(
    val id: DurableSessionId,
    val title: String,
    val projectId: ProjectId? = null,
    val workspacePath: String? = null,
    val isLocalDraft: Boolean = false,
    val preview: String? = null,
    val lastActiveEpochSeconds: Double? = null,
    val messageCount: Int? = null,
    val model: String? = null,
    val provider: String? = null,
    val profile: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Where the backend says this session came from: `cron`, `weixin`, `desktop`, `cli`, … */
    val source: String? = null,
    /** The backend asked for this session to stay out of the way, or it is a sub-agent's child. */
    val isInternal: Boolean = false,
) {
    /**
     * A scheduled run's transcript is not a conversation anyone started. The backend stamps these
     * with `source: "cron"`, and an hourly job buries every real session under its own history.
     */
    val isScheduled: Boolean
        get() = source?.equals("cron", ignoreCase = true) == true

    /**
     * The single rule every surface that lists sessions has to apply. Screens that page straight from
     * the backend (the "View all" list) do not share Home's filter, so keeping this in one place is
     * what stops the next list from quietly showing scheduled runs again.
     */
    val isConversation: Boolean
        get() = !isInternal && !isScheduled
}

data class HermesAppState(
    val sessions: List<SessionSummary> = emptyList(),
    val selectedSessionId: DurableSessionId? = null,
    private val drafts: Map<DurableSessionId, String> = emptyMap(),
) {
    init {
        require(selectedSessionId == null || sessions.any { it.id == selectedSessionId }) {
            "Selected session must exist in the durable session list"
        }
    }

    val selectedDraft: String
        get() = selectedSessionId?.let(::draftFor).orEmpty()

    fun draftFor(sessionId: DurableSessionId): String = drafts[sessionId].orEmpty()

    fun selectSession(sessionId: DurableSessionId): HermesAppState {
        require(sessions.any { it.id == sessionId }) {
            "Cannot select an unknown durable session"
        }
        return copy(selectedSessionId = sessionId)
    }

    fun updateSelectedDraft(draft: String): HermesAppState {
        val sessionId = checkNotNull(selectedSessionId) {
            "Cannot update a composer draft without a selected durable session"
        }
        return copy(drafts = drafts + (sessionId to draft))
    }

    fun reconcileSessions(updatedSessions: List<SessionSummary>): HermesAppState {
        val currentIds = updatedSessions.mapTo(mutableSetOf()) { it.id }
        return copy(
            sessions = updatedSessions,
            selectedSessionId = selectedSessionId?.takeIf(currentIds::contains),
            drafts = drafts.filterKeys(currentIds::contains),
        )
    }
}
