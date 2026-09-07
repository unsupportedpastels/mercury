package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem

internal const val TOKEN_REFRESH_SKEW_SECONDS = 30L
internal const val MAX_SESSION_TITLE_CHARS = 256
internal const val SLASH_COMPLETION_DEBOUNCE_MS = 60L
internal const val OPERATIONAL_STATUS_POLL_INTERVAL_MILLIS = 60_000L
internal const val RECENT_SESSIONS_PAGE_SIZE = 20
internal const val MAX_RELAY_SESSIONS = 100
// Consecutive HTTP-503 auth-provider-unavailable connect failures tolerated
// (with a silent reconnect) before the client stops looping and surfaces a
// recoverable sign-in prompt. Small enough that the user isn't stuck for long,
// large enough to ride out a genuinely brief upstream-IDP blip.
internal const val MAX_AUTH_503_BEFORE_SIGN_IN = 3

/** Published slash-completion menu state for one composer. */
data class SlashCompletionState(
    val composerText: String,
    val items: List<SlashCompletionItem>,
    val replaceFrom: Int,
)

sealed interface ModelPickerState {
    data object Closed : ModelPickerState

    data class Loading(val durableSessionId: DurableSessionId) : ModelPickerState

    data class Ready(
        val durableSessionId: DurableSessionId,
        val options: ModelOptions,
        val applying: Boolean = false,
        val error: String? = null,
        val pendingSelection: ModelSelection? = null,
        val confirmationMessage: String? = null,
    ) : ModelPickerState

    data class Error(
        val durableSessionId: DurableSessionId,
        val message: String,
    ) : ModelPickerState
}

internal data class OperationalStatusFetch(
    val origin: ServerOrigin,
    val generation: Long,
    val profile: String,
    val attemptedAtEpochSeconds: Long,
)

/** Identifies a completed durable-session fetch so identical filter refreshes are skipped. */
internal data class DurableSessionsFetchKey(
    val origin: String,
    val generation: Long,
    val profile: String,
    val archivedOnly: Boolean,
)

internal data class ProjectMetadataSessionRecord(
    val origin: ServerOrigin,
    val generation: Long,
    val accessToken: String,
    val session: HermesChatSession,
)

internal data class RelayProjectSnapshot(
    val projects: List<ProjectSummary>,
    val state: ProjectLoadState,
    val activeProjectId: ProjectId? = null,
    val scopedSessionIds: Set<DurableSessionId> = emptySet(),
)
