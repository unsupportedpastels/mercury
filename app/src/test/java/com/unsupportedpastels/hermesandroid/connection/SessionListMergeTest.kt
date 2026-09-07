package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListMergeTest {

    private val draft = SessionSummary(
        id = DurableSessionId("draft-1"),
        title = "New chat",
        isLocalDraft = true,
    )
    private val server1 = SessionSummary(DurableSessionId("stored-1"), "Alpha")
    private val server2 = SessionSummary(DurableSessionId("stored-2"), "Beta")

    @Test
    fun `pending draft survives server session list refresh`() {
        val merged = mergeServerSessionsPreservingDrafts(
            serverSessions = listOf(server1, server2),
            currentSessions = listOf(draft, server1),
            pendingDrafts = setOf(draft.id),
        )
        assertEquals(listOf(draft, server1, server2), merged)
    }

    @Test
    fun `draft already promoted to server list is not duplicated`() {
        val promoted = draft.copy(isLocalDraft = false)
        val merged = mergeServerSessionsPreservingDrafts(
            serverSessions = listOf(promoted, server1),
            currentSessions = listOf(draft, server1),
            pendingDrafts = setOf(draft.id),
        )
        assertEquals(listOf(promoted, server1), merged)
    }

}
