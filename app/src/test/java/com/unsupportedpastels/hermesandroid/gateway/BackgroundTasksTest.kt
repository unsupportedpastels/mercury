package com.unsupportedpastels.hermesandroid.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BackgroundTasksTest {
    @Test fun malformedIdentityNeverCreatesAnActiveClaim() {
        val state = BackgroundTasks().reduce(event("subagent.start", """{"subagent_id":42,"goal":true}"""), runtime, 1000)
        assertEquals(0, state.activeCount(1001))
        assertEquals(BackgroundTaskStatus.Unknown, state.rows.single().status)
        assertNull(decodeBackgroundTaskEvent("unrelated", runtime.value, Json.parseToJsonElement("{}").jsonObject))
    }
    private val runtime = RuntimeSessionId("runtime")
    private fun event(type: String, payload: String = """{"subagent_id":"child","goal":"Review tests"}""") =
        requireNotNull(decodeBackgroundTaskEvent(type, runtime.value, Json.parseToJsonElement(payload).jsonObject))

    @Test fun parentCompletionAndNewPromptDoNotFinishChildren() {
        val started = BackgroundTasks().reduce(event("subagent.start"), runtime, 1000)
        assertEquals(1, started.activeCount(1001))
        val completed = started.reduce(HermesChatEvent.MessageComplete(runtime, "Done", "ok"), runtime, 2000)
        val nextPrompt = completed.reduce(HermesChatEvent.MessageStart(runtime, ""), runtime, 3000)
        assertEquals(started, nextPrompt)
    }

}
