package com.unsupportedpastels.mercury.core.progress

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DurableProgressBridgeTest {
    @Test fun nativeHistoryBridgeUsesCanonicalDecoder() {
        val raw = """[{"role":"assistant","tool_calls":[{"id":"plan","function":{"name":"todo_list"}}]},{"role":"tool","tool_call_id":"plan","timestamp":1700000000,"content":{"revision":3,"todos":[{"id":"a","content":"Run checks","status":"completed"}]}}]"""
        assertEquals(DurableProgressParser.parse(Json.parseToJsonElement(raw).jsonArray.map { it.jsonObject }), DurableProgressBridge.parseJson(raw))
        assertEquals(RunTodoStatus.Completed, DurableProgressBridge.parseJson(raw).milestones.single().status)
    }

    @Test fun nativeLiveBridgeUsesCanonicalDecoderAndIgnoresArguments() {
        val raw = """{"name":"todo_list","result":{"revision":4,"todos":[]}}"""
        assertEquals(DurableProgressParser.liveSnapshot(Json.parseToJsonElement(raw).jsonObject), DurableProgressBridge.liveSnapshotJson(raw))
        assertNull(DurableProgressBridge.liveSnapshotJson("""{"name":"todo_list","arguments":{"todos":[]}}"""))
        assertNull(DurableProgressBridge.liveSnapshotJson("invalid"))
        assertEquals(DurableProgressParser.parse(emptyList()), DurableProgressBridge.parseJson("invalid"))
    }
}
