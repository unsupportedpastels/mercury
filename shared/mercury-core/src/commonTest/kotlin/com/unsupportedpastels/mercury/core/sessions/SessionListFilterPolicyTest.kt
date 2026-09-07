package com.unsupportedpastels.mercury.core.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionListFilterPolicyTest {
    @Test
    fun predicatesRoundTripWithoutPersistingRawSyntax() {
        val spec = SessionListFilterPolicy.parse(" is:pinned lifecycle race IS:ARCHIVED ")
        assertEquals(SessionListFilterSpec("lifecycle race", pinnedOnly = true, archivedOnly = true), spec)
        assertEquals("lifecycle race is:pinned is:archived", SessionListFilterPolicy.format(spec))
        assertEquals(SessionListFilterSpec("is:pinnedx"), SessionListFilterPolicy.parse("is:pinnedx"))
    }

    @Test
    fun queryIsBounded() {
        val long = "x".repeat(200)
        assertEquals(SessionListFilterPolicy.MAX_QUERY_CHARS, SessionListFilterPolicy.parse(long).query.length)
    }
}
