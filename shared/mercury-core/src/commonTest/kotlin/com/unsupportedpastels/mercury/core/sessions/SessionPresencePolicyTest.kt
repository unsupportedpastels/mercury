package com.unsupportedpastels.mercury.core.sessions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionPresencePolicyTest {

    private fun row(
        id: String,
        sessionKey: String,
        status: String,
    ) = mapOf("id" to id, "session_key" to sessionKey, "status" to status)

    @Test
    fun parseExtractsWellFormedRows() {
        val rows = SessionPresencePolicy.parseRows(
            mapOf(
                "sessions" to listOf(
                    row("r1", "dur-1", "working"),
                    row("r2", "dur-2", "idle"),
                ),
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(SessionActiveListRow("r1", "dur-1", "working"), rows[0])
        assertEquals(SessionActiveListRow("r2", "dur-2", "idle"), rows[1])
    }

    @Test
    fun parseRejectsMalformedAndMissingFields() {
        val rows = SessionPresencePolicy.parseRows(
            mapOf(
                "sessions" to listOf(
                    mapOf("id" to "r1", "status" to "working"), // no session_key
                    mapOf("id" to "r2", "session_key" to "", "status" to "working"), // empty key
                    mapOf("id" to "r3", "session_key" to "dur-3"), // no status
                    mapOf("id" to "r4", "session_key" to "dur-4", "status" to "  "), // blank status
                    row("r5", "dur-5", "working"),
                    "not-a-map",
                    42,
                ),
            ),
        )
        assertEquals(listOf(SessionActiveListRow("r5", "dur-5", "working")), rows)
    }

    @Test
    fun parseToleratesNullAndWrongShapes() {
        assertEquals(emptyList(), SessionPresencePolicy.parseRows(null))
        assertEquals(emptyList(), SessionPresencePolicy.parseRows(mapOf<String, Any>()))
        assertEquals(emptyList(), SessionPresencePolicy.parseRows(mapOf("sessions" to "nope")))
        assertEquals(emptyList(), SessionPresencePolicy.parseRows(Any() as? Map<*, *>))
    }

    @Test
    fun parseBoundsRowCount() {
        val many = (1..(SessionPresencePolicy.MAX_ROWS + 50)).map { index ->
            row("r$index", "dur-$index", "working")
        }
        val rows = SessionPresencePolicy.parseRows(mapOf("sessions" to many))
        assertEquals(SessionPresencePolicy.MAX_ROWS, rows.size)
    }

    @Test
    fun onlyWorkingStatusLightsTheIndicator() {
        val rows = SessionPresencePolicy.parseRows(
            mapOf(
                "sessions" to listOf(
                    row("r1", "dur-working", "working"),
                    row("r2", "dur-idle", "idle"),
                    row("r3", "dur-starting", "starting"),
                    row("r4", "dur-waiting", "waiting"),
                    row("r5", "dur-trim", "  working  "),
                    row("r6", "dur-unknown", "dancing"),
                ),
            ),
        )
        val working = SessionPresencePolicy.workingSessionIds(rows)
        // Trimmed whitespace normalizes to the exact status; untrimmed unknown
        // statuses are inert. `waiting` (approval/clarification pends) must not
        // read as model work.
        assertEquals(setOf("dur-working", "dur-trim"), working)
    }

    @Test
    fun joinIsBySessionKeyNotRuntimeId() {
        val rows = SessionPresencePolicy.parseRows(
            mapOf(
                "sessions" to listOf(row("runtime-id-value", "durable-1", "working")),
            ),
        )
        val working = SessionPresencePolicy.workingSessionIds(rows)
        assertTrue("durable-1" in working)
        assertTrue("runtime-id-value" !in working)
    }

    @Test
    fun emptySnapshotFailsClosedToEmptyWorkingSet() {
        assertEquals(emptySet(), SessionPresencePolicy.workingSessionIds(emptyList()))
    }
}
