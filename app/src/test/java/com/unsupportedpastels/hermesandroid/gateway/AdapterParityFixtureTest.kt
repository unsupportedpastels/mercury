package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.mercury.core.transcript.ChatEvent
import com.unsupportedpastels.mercury.core.transcript.ChatEventDecoder
import com.unsupportedpastels.mercury.core.transcript.ToolRowState
import com.unsupportedpastels.mercury.core.transcript.TranscriptEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android adapter boundary is checked against the one canonical corpus used
 * by the shared decoder and the Swift tests. Expected values live in that
 * corpus; this test does not maintain a second event/insight fixture.
 */
class AdapterParityFixtureTest {
    private val json = Json
    private val corpus by lazy { loadCorpus() }
    private val sessionId get() = corpus["session_id"]!!.jsonPrimitive.content

    @Test
    fun canonicalOriginsMatchAtNativeBoundary() {
        val records = corpus["origins"]!!.jsonArray
        assertTrue(records.isNotEmpty())
        for (record in records) {
            val value = record.jsonObject
            val input = value["input"]!!.jsonPrimitive.content
            val actual = runCatching {
                com.unsupportedpastels.hermesandroid.connection.ServerOrigin.parse(input).value
            }.getOrNull()
            assertEquals(input, value.stringOrNull("expected"), actual)
        }
    }

    @Test
    fun canonicalEventsDecodeAndRoundTripThroughAndroidAdapter() {
        val events = corpus["events"]!!.jsonArray
        assertTrue(events.isNotEmpty())
        for (record in events) {
            val value = record.jsonObject
            val shared = decode(value)
            val android = checkNotNull(shared.toAndroidEvent())
            assertEquals(value["id"]!!.jsonPrimitive.content, shared, android.toSharedEvent())
        }
    }

    @Test
    fun sessionInfoFastModeAndMetadataUseTrueFalseAndAbsentCorpusCases() {
        val records = corpus["events"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "session.info" }
        assertEquals(3, records.size)
        for (record in records) {
            val shared = decode(record) as ChatEvent.SessionInfo
            val expected = record["expected"]!!.jsonObject
            val expectedFast = expected["fast_mode"]?.jsonPrimitive?.booleanOrNull
            assertEquals(record["id"]!!.jsonPrimitive.content, expectedFast, shared.fastMode)
            if (expected.containsKey("stored_session_id")) {
                assertEquals(expected.stringOrNull("stored_session_id"), shared.storedSessionId)
            }
            if (expected.containsKey("model")) assertEquals(expected.stringOrNull("model"), shared.model)
            if (expected.containsKey("provider")) assertEquals(expected.stringOrNull("provider"), shared.provider)
            if (expected.containsKey("reasoning_effort")) {
                assertEquals(expected.stringOrNull("reasoning_effort"), shared.reasoningEffort)
            }
            if (expected.containsKey("title")) assertEquals(expected.stringOrNull("title"), shared.title)
            if (expected.containsKey("running")) {
                assertEquals(expected["running"]?.jsonPrimitive?.booleanOrNull, shared.running)
            }
            assertEquals(shared, shared.toAndroidEvent()!!.toSharedEvent())
        }
    }

    @Test
    fun malformedAndUnknownCorpusEventsAreIgnored() {
        for (record in corpus["malformed_events"]!!.jsonArray) {
            val value = record.jsonObject
            assertNull(value["id"]!!.jsonPrimitive.content, decodeOrNull(value))
        }
    }

    @Test
    fun representativeReducerSequenceUsesRoundTrippedAndroidEvents() {
        var state = TranscriptEngine.initial()
        for (eventId in corpus["reducer"]!!.jsonObject["event_ids"]!!.jsonArray) {
            val record = corpus["events"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == eventId.jsonPrimitive.content }.jsonObject
            val shared = decode(record)
            val roundTripped = checkNotNull(shared.toAndroidEvent()!!.toSharedEvent())
            state = TranscriptEngine.apply(state, roundTripped)
        }

        val expected = corpus["reducer"]!!.jsonObject["expected"]!!.jsonObject
        val expectedRows = expected["rows"]!!.jsonArray
        assertEquals(expectedRows.size, state.rows.size)
        for ((row, expectedRow) in state.rows.zip(expectedRows)) {
            val value = expectedRow.jsonObject
            assertEquals(value.stringOrNull("role"), row.role)
            assertEquals(value.stringOrNull("text"), row.text)
            assertEquals(value["completed"]!!.jsonPrimitive.booleanOrNull, row.completed)
            assertEquals(value.stringOrNull("reasoning_text") ?: "", row.reasoningText)
        }
        val expectedTools = expected["tools"]!!.jsonArray
        assertEquals(expectedTools.size, state.tools.size)
        for ((tool, expectedTool) in state.tools.zip(expectedTools)) {
            val value = expectedTool.jsonObject
            assertEquals(value.stringOrNull("tool_id"), tool.toolId)
            assertEquals(value.stringOrNull("name"), tool.name)
            assertEquals(value.stringOrNull("context"), tool.context)
            assertEquals(value.stringOrNull("summary"), tool.summary)
            assertEquals(value.stringOrNull("state"), tool.state.name.lowercase())
        }
        assertEquals(expected.stringOrNull("latest_status_text"), state.latestStatusText)
        assertEquals(expected["status_update_count"]!!.jsonPrimitive.intOrNull, state.statusUpdateCount)
        assertEquals(expected.stringOrNull("generating_status_text"), state.generatingStatusText)
        assertEquals(expected.stringOrNull("last_error"), state.lastError)
        assertEquals(ToolRowState.Completed, state.tools.single().state)
    }

    @Test
    fun usageAndContextCorpusRunsThroughAndroidGatewayParsers() = runTest {
        val socket = FixtureSocket { method ->
            val result = when (method) {
                "session.usage" -> corpus["insights"]!!.jsonObject["usage"]!!.jsonObject["result"]!!.toString()
                "session.context_breakdown" -> corpus["insights"]!!.jsonObject["context"]!!.jsonObject["result"]!!.toString()
                else -> error("unexpected method $method")
            }
            result
        }
        val connection = HermesChatGateway(
            origin = com.unsupportedpastels.hermesandroid.connection.ServerOrigin.parse("https://example.invalid"),
            accessToken = "",
            ticketClient = object : WsTicketClient {
                override suspend fun mintTicket(origin: com.unsupportedpastels.hermesandroid.connection.ServerOrigin, accessToken: String) = WsTicket("fixture", 30)
            },
            socketFactory = object : ChatWebSocketFactory {
                override suspend fun connect(url: String) = socket
            },
            parentScope = backgroundScope,
        ).connect()

        val usage = connection.loadSessionUsage(RuntimeSessionId(sessionId))
        val context = connection.loadContextBreakdown(RuntimeSessionId(sessionId))
        val expectedUsage = corpus["insights"]!!.jsonObject["usage"]!!.jsonObject["expected"]!!.jsonObject
        val expectedContext = corpus["insights"]!!.jsonObject["context"]!!.jsonObject["expected"]!!.jsonObject

        assertEquals(expectedUsage.longOrNull("input_tokens"), usage.inputTokens)
        assertEquals(expectedUsage.longOrNull("output_tokens"), usage.outputTokens)
        assertEquals(expectedUsage.longOrNull("total_tokens"), usage.totalTokens)
        assertEquals(expectedUsage.longOrNull("context_used_tokens"), usage.contextUsedTokens)
        assertEquals(expectedUsage.longOrNull("context_max_tokens"), usage.contextMaxTokens)
        assertEquals(expectedUsage.doubleOrNull("context_percent"), usage.contextPercent)
        assertEquals(expectedUsage.longOrNull("calls"), usage.calls)
        assertEquals(expectedUsage["credits_lines"]!!.jsonArray.map { it.jsonPrimitive.content }, usage.creditsLines)
        assertEquals(expectedUsage.stringOrNull("raw_info"), usage.rawInfo)
        assertEquals(expectedContext["categories"]!!.jsonArray.size, context.categories.size)
        for ((category, expectedCategory) in context.categories.zip(expectedContext["categories"]!!.jsonArray)) {
            val value = expectedCategory.jsonObject
            assertEquals(value.stringOrNull("name"), category.name)
            assertEquals(value.longOrNull("tokens"), category.tokens)
            assertEquals(value.doubleOrNull("percent"), category.percent)
        }
        assertEquals(expectedContext.longOrNull("used_tokens"), context.usedTokens)
        assertEquals(expectedContext.longOrNull("max_tokens"), context.maxTokens)
        assertEquals(expectedContext.doubleOrNull("percent"), context.percent)
        connection.close()
    }

    private fun decode(record: JsonObject): ChatEvent = checkNotNull(decodeOrNull(record))

    private fun decodeOrNull(record: JsonObject): ChatEvent? {
        val type = record["type"]!!.jsonPrimitive.content
        val id = record["session_id"]?.jsonPrimitive?.content ?: sessionId
        val payload = record["payload_json"]?.jsonPrimitive?.content ?: record["payload"]!!.toString()
        return ChatEventDecoder.decode(type, id, payload)
    }

    private fun loadCorpus(): JsonObject =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("adapter-parity/chat-event-corpus.json")) {
            "canonical adapter parity corpus is missing"
        }.use { input -> json.parseToJsonElement(input.readBytes().decodeToString()).jsonObject }

    private fun JsonObject.stringOrNull(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.longOrNull(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.doubleOrNull(name: String): Double? =
        this[name]?.jsonPrimitive?.doubleOrNull
}

private class FixtureSocket(
    private val resultFor: suspend (String) -> String,
) : HermesChatSocket {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    val sentMethods = mutableListOf<String>()

    override suspend fun sendText(text: String) {
        val request = Json.parseToJsonElement(text).jsonObject
        val method = request["method"]!!.jsonPrimitive.content
        sentMethods += method
        val result = resultFor(method)
        incoming.send("""{"jsonrpc":"2.0","id":${request["id"]!!.jsonPrimitive.content},"result":$result}""")
    }

    override suspend fun receiveText(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun close() {
        incoming.close()
    }
}
