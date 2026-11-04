package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting a conversation left it in the list because the row was a compression-chain tip; the root
 * became the new tip under a different id. Only the title is stable across a chain.
 */
class SessionChainCollapseTest {

    private fun row(id: String, title: String) =
        SessionSummary(DurableSessionId(id), title)

    @Test
    fun everyRowWithTheDeletedTitleIsReturnedEvenUnderANewId() {
        val listed = listOf(
            row("chain-root", "Deploy the SSH tunnel"),
            row("other", "Unrelated"),
            row("chain-tip", "Deploy the SSH tunnel"),
        )

        assertEquals(
            listOf("chain-root", "chain-tip"),
            listed.rowsForDeletedConversation("Deploy the SSH tunnel").map { it.value },
        )
    }

    @Test
    fun anUntitledConversationNeverSweepsTheList() {
        val listed = listOf(row("a", ""), row("b", "   "))
        assertTrue(listed.rowsForDeletedConversation(null).isEmpty())
        assertTrue(listed.rowsForDeletedConversation("   ").isEmpty())
    }
}
