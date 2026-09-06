package com.unsupportedpastels.mercury.core.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionListMergePolicyTest {

    private fun draft(id: String) = SessionMergeEntry(id, isLocalDraft = true)
    private fun server(id: String) = SessionMergeEntry(id, isLocalDraft = false)

    @Test
    fun noPendingDraftsPreservesNothing() {
        assertEquals(
            emptyList(),
            SessionListMergePolicy.preservedDraftIndices(
                serverIds = listOf("a"),
                currentSessions = listOf(draft("d1")),
                pendingDraftIds = emptySet(),
            ),
        )
    }

    @Test
    fun pendingDraftSurvivesRefresh() {
        assertEquals(
            listOf(0),
            SessionListMergePolicy.preservedDraftIndices(
                serverIds = listOf("a", "b"),
                currentSessions = listOf(draft("d1"), server("a")),
                pendingDraftIds = setOf("d1"),
            ),
        )
    }

    @Test
    fun promotedDraftIsNotDuplicated() {
        // The server now knows the draft's id — the server row wins.
        assertEquals(
            emptyList(),
            SessionListMergePolicy.preservedDraftIndices(
                serverIds = listOf("d1"),
                currentSessions = listOf(draft("d1")),
                pendingDraftIds = setOf("d1"),
            ),
        )
    }

    @Test
    fun nonPendingDraftIsNotResurrected() {
        assertEquals(
            emptyList(),
            SessionListMergePolicy.preservedDraftIndices(
                serverIds = listOf("a"),
                currentSessions = listOf(draft("d1")),
                pendingDraftIds = setOf("other"),
            ),
        )
    }

    @Test
    fun nonDraftRowsNeverSurviveEvenWhenPending() {
        assertEquals(
            emptyList(),
            SessionListMergePolicy.preservedDraftIndices(
                serverIds = emptyList(),
                currentSessions = listOf(server("d1")),
                pendingDraftIds = setOf("d1"),
            ),
        )
    }

    @Test
    fun multipleDraftsKeepRelativeOrder() {
        assertEquals(
            listOf(0, 2),
            SessionListMergePolicy.preservedDraftIndices(
                serverIds = listOf("a"),
                currentSessions = listOf(draft("d1"), server("a"), draft("d2")),
                pendingDraftIds = setOf("d1", "d2"),
            ),
        )
    }

    @Test
    fun emptyServerListKeepsDrafts() {
        assertEquals(
            listOf(0),
            SessionListMergePolicy.preservedDraftIndices(
                serverIds = emptyList(),
                currentSessions = listOf(draft("d1")),
                pendingDraftIds = setOf("d1"),
            ),
        )
    }

    @Test
    fun repeatCallsAreDeterministic() {
        val server = listOf("a", "b")
        val current = listOf(draft("d1"), server("a"), draft("d2"))
        val pending = setOf("d1", "d2")
        val first = SessionListMergePolicy.preservedDraftIndices(server, current, pending)
        assertEquals(first, SessionListMergePolicy.preservedDraftIndices(server, current, pending))
    }
}
