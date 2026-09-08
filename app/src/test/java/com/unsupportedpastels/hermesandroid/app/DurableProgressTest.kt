package com.unsupportedpastels.hermesandroid.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DurableProgressTest {
    @Test fun officialResultRestoresFullSnapshotButArgumentsNeverDo() {
        val rows = listOf(
            row("""{"role":"assistant","tool_calls":[{"id":"call-1","function":{"name":"todo_list","arguments":"{\"todos\":[{\"id\":\"wrong\",\"content\":\"Partial args\",\"status\":\"completed\"}]}"}}]}"""),
            row("""{"role":"tool","tool_call_id":"call-1","timestamp":1700000000.25,"content":"{\"todos\":[{\"id\":\"one\",\"content\":\"Implement\",\"status\":\"in_progress\"}],\"revision\":7}"}"""),
        )
        val progress = DurableProgressParser.parse(rows)
        assertEquals(listOf("one"), progress.milestones.map { it.id })
        assertEquals(RunTodoStatus.InProgress, progress.milestones.single().status)
        assertEquals(7L, progress.revision)
        assertEquals(1700000000250L, progress.lastObservedAtEpochMillis)
        assertTrue(progress.restored)
        assertTrue(DurableProgressParser.parse(rows.take(1)).milestones.isEmpty())
    }

    @Test fun aliasesUnversionedEmptyClearAndMalformedResults() {
        val old = DurableProgressParser.parse(listOf(result("todo", """{"todos":[{"id":"a","content":"Work","status":"pending"}]}""")))
        assertTrue(old.hasMilestoneSnapshot)
        assertNull(old.revision)
        val clear = DurableProgressParser.parse(listOf(result("todo_list", """{"todos":[]}""")))
        assertTrue(clear.hasMilestoneSnapshot)
        assertTrue(old.recover(clear, old.observationVersion).milestones.isEmpty())
        for (bad in listOf("not-json", """{"todos":[{"id":"x","content":"Work","status":"made_up"}]}""", """{"args":{"todos":[]}}""")) {
            assertFalse(DurableProgressParser.parse(listOf(result("todo", bad))).hasMilestoneSnapshot)
        }
    }

    @Test fun refreshDoesNotInventHeartbeatAndNewLiveEvidenceWins() {
        val history = DurableProgressParser.parse(listOf(result("todo_list", """{"todos":[],"revision":2}""")))
        val restored = DurableProgress().recover(history, 0)
        assertEquals(restored, restored.recover(history, restored.observationVersion))
        val live = restored.observe(com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent.ToolComplete(
            com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId("runtime"), "actual", "terminal", "exit 0",
        ), 2000)
        assertEquals(live, live.recover(history, restored.observationVersion))
        assertEquals(2000L, live.lastObservedAtEpochMillis)
        assertTrue(live.restored) // A tool receipt does not reclassify historical milestones.
        assertTrue(live.milestones.isEmpty())
        assertEquals("terminal", live.evidence.last().toolName)
    }

    @Test fun olderRevisionCannotRegressAndHostClockSkewCannotBlockNewRevision() {
        val latest = DurableProgress(hasMilestoneSnapshot = true, revision = 9, lastObservedAtEpochMillis = 9000)
        assertEquals(latest, latest.recover(latest.copy(revision = 8, restored = true), 0))
        val recovered = latest.recover(latest.copy(revision = 10, lastObservedAtEpochMillis = 1000, restored = true), 0)
        assertEquals(10L, recovered.revision)
        assertEquals(1000L, recovered.lastObservedAtEpochMillis)
    }

    @Test fun newPromptCannotPromotePreviousTurnAndEmptyReadHasNoTimestamp() {
        val previous = DurableProgressParser.parse(listOf(result("todo", """{"todos":[]}""")))
        val next = previous.beginTurn(1900000000000)
        assertNull(next.lastObservedAtEpochMillis)
        assertFalse(next.hasMilestoneSnapshot)
        assertEquals(next, next.recover(previous, previous.observationVersion))
        assertNull(DurableProgress().recover(DurableProgressParser.parse(emptyList()), 0).lastObservedAtEpochMillis)
    }

    @Test fun liveResultsNeverConsumePartialArgsAndEvidenceIsBounded() {
        assertNull(DurableProgressParser.liveSnapshot(row("""{"name":"todo_list","args":{"todos":[]}}""")))
        assertTrue(DurableProgressParser.liveSnapshot(row("""{"name":"todo","result":{"todos":[]}}"""))!!.hasMilestoneSnapshot)
        val rows = (0..30).map { result("terminal", "x".repeat(1000), "call-$it") }
        val evidence = DurableProgressParser.parse(rows).evidence
        assertEquals(20, evidence.size)
        assertTrue(evidence.all { it.summary!!.length <= 400 })
        assertTrue(evidence.all { it.summary!!.startsWith("Observed result:") })
    }

    @Test fun replayDoesNotFakeReceiptAndPartialWindowDoesNotEraseKnownMilestones() {
        val history = DurableProgressParser.parse(listOf(result("todo", """{"todos":[{"id":"a","content":"Work","status":"pending"}]}""")))
        val event = com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent.ToolComplete(
            com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId("runtime"), "actual", "terminal", "exit 0", historical = true,
        )
        assertEquals(history, history.observe(event, 9000000))
        val window = DurableProgressParser.parse(listOf(result("terminal", "Reported output", "other")))
        val recovered = history.recover(window, history.observationVersion)
        assertEquals(history.milestones, recovered.milestones)
        assertTrue(recovered.hasMilestoneSnapshot)
        val status = com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent.StatusUpdate(
            com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId("runtime"), "thinking", "Working",
        )
        assertEquals(42L, recovered.observe(status, 42).lastObservedAtEpochMillis)
    }

    private fun result(name: String, content: String, id: String = "call") = kotlinx.serialization.json.buildJsonObject {
        put("role", kotlinx.serialization.json.JsonPrimitive("tool"))
        put("tool_name", kotlinx.serialization.json.JsonPrimitive(name))
        put("tool_call_id", kotlinx.serialization.json.JsonPrimitive(id))
        put("timestamp", kotlinx.serialization.json.JsonPrimitive(1700000000))
        put("content", kotlinx.serialization.json.JsonPrimitive(content))
    }

    private fun row(text: String) = Json.parseToJsonElement(text).jsonObject
}
