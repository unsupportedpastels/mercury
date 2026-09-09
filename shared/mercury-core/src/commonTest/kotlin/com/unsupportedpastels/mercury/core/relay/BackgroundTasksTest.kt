package com.unsupportedpastels.mercury.core.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class BackgroundTasksTest {
    @Test fun duplicateReplayDoesNotRegressProgressOrRefreshActivityAge() {
        val first = event("subagent.tool").copy(eventId = "lease:1", action = "First")
        val next = first.copy(eventId = "lease:2", action = "Second")
        val state = BackgroundTasks().reduce(first, runtime, 1000).reduce(next, runtime, 2000)
        assertEquals(state, state.reduce(first, runtime, 9000))
        assertEquals(state, state.reduce(next.copy(sessionId = ("rebound")), ("rebound"), 10000))
        assertEquals("Second", state.rows.single().action)
        assertEquals(2000L, state.rows.single().observedAtMillis)
    }

    @Test fun replayedChildCompletionAfterOwnedRuntimeReplacementUpdatesOneRow() {
        val old = BackgroundTasks().reduce(event("subagent.start"), runtime, 1000).unavailable()
        val next = ("replacement")
        val complete = BackgroundTaskEvent(next, BackgroundTaskEventKind.Complete, "child", null, null, BackgroundTaskStatus.Finished)
        val recovered = old.reduce(complete, next, 2000)
        assertEquals(1, recovered.rows.size)
        assertEquals(BackgroundTaskStatus.Finished, recovered.rows.single().status)
        assertEquals("Review tests", recovered.rows.single().goal)
        assertEquals(recovered, recovered.reduce(complete, next, 3000))
        assertEquals(recovered, recovered.reduce(complete.copy(kind = BackgroundTaskEventKind.Start), next, 4000))
        // A different durable session owns a different reducer; it imports no old rows.
        assertTrue(BackgroundTasks().rows.isEmpty())
        assertEquals(old, old.reduce(complete, ("foreign"), 2000))
    }

    @Test fun failuresStopsAndGlobalRegistryAbsenceAreNotSuccess() {
        for ((wire, status) in listOf("failed" to BackgroundTaskStatus.Failed, "timeout" to BackgroundTaskStatus.Failed, "interrupted" to BackgroundTaskStatus.Stopped)) {
            val state = BackgroundTasks().reduce(event("subagent.complete", """{"subagent_id":"child","status":"$wire"}"""), runtime, 1000)
            assertEquals(status, state.rows.single().status)
        }
        val stale = BackgroundTasks().reduce(event("subagent.start"), runtime, 1000).unavailable()
        val global = listOf(
            BackgroundTaskRegistryEntry("foreign", "running"),
        )
        assertEquals(stale, stale.reconcile(global, runtime))
        assertEquals(stale, stale.reconcile(emptyList(), runtime))
    }

    @Test fun malformedIdentityNeverCreatesAnActiveClaim() {
        val state = BackgroundTasks().reduce(event("subagent.start", """{"subagent_id":42,"goal":true}"""), runtime, 1000)
        assertEquals(0, state.activeCount(1001))
        assertEquals(BackgroundTaskStatus.Unknown, state.rows.single().status)
        assertNull(decodeBackgroundTaskEvent("unrelated", runtime, Json.parseToJsonElement("{}").jsonObject))
    }
    @Test fun invalidTypedIdentityCannotBeCountedOrCorrelatedWithTerminal() {
        for (id in listOf("", " ", "x".repeat(513))) {
            val state = BackgroundTasks().reduce(BackgroundTaskEvent(runtime, BackgroundTaskEventKind.Start, id, null, null), runtime, 1000)
            assertEquals(0, state.activeCount(1001))
            val complete = state.reduce(BackgroundTaskEvent(runtime, BackgroundTaskEventKind.Complete, id, null, null, BackgroundTaskStatus.Finished), runtime, 2000)
            assertFalse(complete.rows.single().terminal)
        }
    }

    @Test fun globalRegistryNeverReactivatesMissingIdentityFallback() {
        val unknown = BackgroundTasks().reduce(BackgroundTaskEvent(runtime, BackgroundTaskEventKind.Start, null, null, null), runtime, 1000).unavailable()
        val status = listOf(
            BackgroundTaskRegistryEntry("identity-unavailable", "running"),
        )
        assertEquals(unknown, unknown.reconcile(status, runtime))
    }

    @Test fun verifiedRuntimeRebindKeepsOnlyExactOwnedChildAndDoesNotRefreshAge() {
        val next = ("replacement")
        val old = BackgroundTasks().reduce(event("subagent.start"), runtime, 1000).unavailable()
        val registry = listOf(
            BackgroundTaskRegistryEntry("child", "running"),
            BackgroundTaskRegistryEntry("foreign", "running"),
        )
        assertEquals(old, old.reconcile(registry, next))
        val rebound = old.reconcile(registry, next, previousRuntime = runtime)
        assertEquals(1, rebound.rows.size)
        assertEquals(next, rebound.rows.single().runtimeId)
        assertEquals(1000L, rebound.rows.single().observedAtMillis)
        assertEquals("Review tests", rebound.rows.single().goal)
        assertEquals(0, rebound.activeCount(121001))
        val finished = rebound.reduce(BackgroundTaskEvent(next, BackgroundTaskEventKind.Complete, "child", null, null, BackgroundTaskStatus.Finished), next, 130000)
        assertEquals(1, finished.rows.size)
        assertTrue(finished.rows.single().terminal)
        assertEquals(finished, finished.reduce(event("subagent.tool"), next, 140000))
        assertEquals(old, old.reconcile(emptyList(), next, previousRuntime = runtime))
    }

    @Test fun rebindRejectsAmbiguousRegistryAndKeepsTerminalAndForeignScopes() {
        val next = ("replacement")
        val old = BackgroundTasks().reduce(event("subagent.start"), runtime, 1000).unavailable()
        fun registry(vararg states: String) = states.map {
            BackgroundTaskRegistryEntry("child", it)
        }
        for (states in listOf(arrayOf("completed"), arrayOf("unknown"), arrayOf("running", "running"))) {
            assertEquals(old, old.reconcile(registry(*states), next, previousRuntime = runtime))
        }
        assertEquals(old, old.reconcile(registry("running"), next, previousRuntime = ("foreign")))
        val terminal = old.reduce(event("subagent.complete", """{"subagent_id":"child","status":"failed"}"""), runtime, 2000)
        assertEquals(terminal, terminal.reconcile(registry("running"), next, previousRuntime = runtime))
        val collision = old.copy(rows = old.rows + old.rows.single().copy(runtimeId = next, status = BackgroundTaskStatus.Finished))
        assertEquals(collision, collision.reconcile(registry("running"), next, previousRuntime = runtime))
    }

    private val runtime = ("runtime")
    private fun event(type: String, payload: String = """{"subagent_id":"child","goal":"Review tests"}""") =
        requireNotNull(decodeBackgroundTaskEvent(type, runtime, Json.parseToJsonElement(payload).jsonObject))

    @Test fun terminalIsExactAndStaleRuntimeCannotContaminate() {
        val started = BackgroundTasks().reduce(event("subagent.start"), runtime, 1000)
        assertEquals(started, started.reduce(event("subagent.complete"), ("other"), 2000))
        val unknown = started.reduce(event("subagent.complete", """{"subagent_id":"child","status":"unknown"}"""), runtime, 2000)
        assertEquals(BackgroundTaskStatus.Unknown, unknown.rows.single().status)
        val finished = started.reduce(event("subagent.complete", """{"subagent_id":"child","status":"completed"}"""), runtime, 2000)
        assertEquals(BackgroundTaskStatus.Finished, finished.rows.single().status)
        assertEquals(finished, finished.reduce(event("subagent.tool"), runtime, 3000))
    }

    @Test fun unavailableRetainsLastKnownAndNeverClaimsRunning() {
        val started = BackgroundTasks().reduce(event("subagent.start"), runtime, 1000)
        val stale = started.unavailable()
        assertEquals(1, stale.rows.size)
        assertEquals(0, stale.activeCount(1001))
        assertEquals(1000L, stale.rows.single().observedAtMillis)
        assertEquals(0, started.activeCount(121001))
    }

    @Test fun longGoalIsClippedToLegibleTitleNotDropped() {
        val goal = ("Write one original long poem, 80-100 lines, titled 'The House That Kept the Rain'. " +
            "Literary free verse about siblings clearing their late mother's house and discovering she secretly " +
            "repaired neighbors' broken things for decades. Concrete images, restraint, no rhyme, and an ending that " +
            "lands on an object rather than a statement.")
        assertTrue(goal.length > GOAL_DISPLAY_CHARS)
        val decoded = event("subagent.tool", """{"subagent_id":"child","goal":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(goal))},"text":"humanizer"}""")
        val title = requireNotNull(decoded.goal)
        assertTrue(title.length <= GOAL_DISPLAY_CHARS)
        assertTrue(title.endsWith("\u2026"))
        assertTrue(title.startsWith("Write one original long poem"))
        assertFalse(title.dropLast(1).endsWith(" "))
        assertEquals("humanizer", decoded.action)
        val row = BackgroundTasks().reduce(decoded, runtime, 1000).rows.single()
        assertEquals(title, row.goal)
    }

    @Test fun clipKeepsFirstLineAndCutsAtWordBoundary() {
        assertEquals("first line", clipDisplayText("  first line \nsecond", 100))
        assertEquals("alpha beta\u2026", clipDisplayText("alpha beta gamma delta", 14))
        assertEquals("abcdefghijklm\u2026", clipDisplayText("abcdefghijklmnopqrstuvwxyz", 14))
        assertEquals("", clipDisplayText("  \n  ", 14))
    }

    @Test fun runningRowsShowOnlyRecentAvailableIdentifiedActivity() {
        val active = BackgroundTasks().reduce(event("subagent.tool"), runtime, 100_000).rows.single()
        val stale = active.copy(id = "stale", observedAtMillis = 1_000)
        val unavailable = active.copy(id = "gone", available = false)
        val finished = active.copy(id = "done", status = BackgroundTaskStatus.Finished)
        val anonymous = active.copy(id = "identity-unavailable", identityKnown = false, status = BackgroundTaskStatus.Unknown)
        val newer = active.copy(id = "newer", observedAtMillis = 101_000)
        val rows = listOf(stale, unavailable, active, finished, anonymous, newer)
        assertEquals(listOf(newer, active), BackgroundTaskPresentationPolicy.runningRows(rows, 125_000))
        assertEquals(emptyList(), BackgroundTaskPresentationPolicy.runningRows(rows, 250_000))
    }
}
