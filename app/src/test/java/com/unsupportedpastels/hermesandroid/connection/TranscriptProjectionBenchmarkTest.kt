package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.*
import org.junit.Assert.*
import org.junit.Test

/** Host microbenchmark + allocation-identity guard, not a device frame-time benchmark. */
class TranscriptProjectionBenchmarkTest {
    @Test fun twoLongSessionsKeepHistoricalObjectsAndMatchLegacyOutput() {
        val histories = List(2) { session -> List(500) { index ->
            ChatMessage(ChatMessageRole.User, "synthetic history $session/$index")
        } }
        val events = List(300) { HermesChatEvent.MessageDelta(RuntimeSessionId("benchmark"), "word ") }
        fun exercise(legacy: Boolean): Pair<Long, Int> {
            val states = histories.map { ChatSessionSnapshot(messages = it) }.toMutableList()
            var replacements = 0
            val started = System.nanoTime()
            events.forEachIndexed { index, event ->
                val sid = index % 2
                val before = states[sid]
                val after = if (legacy) before.legacyApplyTranscriptEvent(event) else before.applyTranscriptEvent(event)
                replacements += histories[sid].indices.count { after.messages[it] !== before.messages[it] }
                states[sid] = after
            }
            val elapsed = System.nanoTime() - started
            states.forEach { assertEquals("word ".repeat(150), it.messages.last().text) }
            return elapsed to replacements
        }
        repeat(2) { exercise(true); exercise(false) }
        val legacy = List(5) { exercise(true) }
        val retained = List(5) { exercise(false) }
        assertEquals(150_000, legacy.first().second)
        assertEquals(0, retained.first().second)
        println("TRANSCRIPT_BENCHMARK rows=500 sessions=2 deltas=300 legacyMedianNs=${legacy.map { it.first }.sorted()[2]} retainedMedianNs=${retained.map { it.first }.sorted()[2]} historicalReplacements=${legacy.first().second}->${retained.first().second}")
    }
}
