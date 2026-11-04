package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary

/**
 * Which listed rows belong to the conversation the user just deleted.
 *
 * The listing shows the *tip* of a compression chain, and the delete endpoint removes only that
 * physical row: the chain's root then becomes the new tip and the conversation reappears under a
 * different id (upstream #57543). Matching by title — the one attribute the whole chain shares — is
 * what lets a client collapse it without server support. Blank titles never match, because a list of
 * untitled rows would otherwise turn one delete into a sweep.
 */
internal fun List<SessionSummary>.rowsForDeletedConversation(title: String?): List<DurableSessionId> {
    val wanted = title?.trim().orEmpty()
    if (wanted.isEmpty()) return emptyList()
    return filter { it.title.trim() == wanted }.map { it.id }
}
