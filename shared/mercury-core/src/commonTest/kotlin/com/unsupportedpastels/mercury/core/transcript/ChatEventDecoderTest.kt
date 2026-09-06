package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ChatEventDecoderTest {
    @Test
    fun decodesKnownEventAndIgnoresUnknownFields() {
        val event = ChatEventDecoder.decode(
            type = "message.complete",
            sessionId = "runtime-1",
            payloadJson = """{
                "text":"done","status":"error","reasoning":"why","recoverable":true,
                "billing":{"provider":"nous","billing_url":"https://billing.example","is_nous":true},
                "future":{"ignored":true}
            }""".trimIndent(),
        )
        val complete = assertIs<ChatEvent.MessageComplete>(event)
        assertEquals("done", complete.text)
        assertEquals("why", complete.reasoning)
        assertEquals("nous", complete.billing?.provider)
        assertEquals(true, complete.recoverable)
    }

    @Test
    fun terminalErrorWithoutUsableMessageStillDecodesWithFallback() {
        val fallback = ChatEvent.Error("runtime-1", ChatEventDecoder.ERROR_MESSAGE_FALLBACK)
        assertEquals(fallback, ChatEventDecoder.decode("error", "runtime-1", "{}"))
        assertEquals(fallback, ChatEventDecoder.decode("error", "runtime-1", "{\"message\":null}"))
        assertEquals(fallback, ChatEventDecoder.decode("error", "runtime-1", "{\"message\":\"\"}"))
        assertEquals(fallback, ChatEventDecoder.decode("error", "runtime-1", "{\"message\":\"   \"}"))
        assertEquals(
            ChatEvent.Error("runtime-1", "boom"),
            ChatEventDecoder.decode("error", "runtime-1", "{\"message\":\"boom\"}"),
        )
    }

    @Test
    fun unknownOrMalformedEventsAreIgnoredWithoutThrowing() {
        assertNull(ChatEventDecoder.decode("future.event", "runtime-1", "42"))
        assertNull(ChatEventDecoder.decode("message.delta", "runtime-1", "42"))
        assertNull(ChatEventDecoder.decode("message.delta", "runtime-1", "{not-json"))
        assertNull(ChatEventDecoder.decode("message.delta", "", "{\"text\":\"x\"}"))
    }

    @Test
    fun preservesStreamingWhitespaceAndBoundsMetadata() {
        val delta = assertIs<ChatEvent.MessageDelta>(
            ChatEventDecoder.decode("message.delta", "runtime-1", "{\"text\":\" leading\"}"),
        )
        assertEquals(" leading", delta.text)

        val emptyComplete = assertIs<ChatEvent.MessageComplete>(
            ChatEventDecoder.decode("message.complete", "runtime-1", "{\"text\":\"\"}"),
        )
        assertEquals("", emptyComplete.text)

        val tooLongId = "x".repeat(ChatEventDecoder.MAX_EVENT_ID_CHARS + 1)
        assertNull(ChatEventDecoder.decode("message.delta", tooLongId, "{\"text\":\"x\"}"))
        val tooLongType = "x".repeat(ChatEventDecoder.MAX_EVENT_NAME_CHARS + 1)
        assertNull(ChatEventDecoder.decode(tooLongType, "runtime-1", "{\"text\":\"x\"}"))
    }
}