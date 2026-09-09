package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals

class PendingInputResolutionTest {
    @Test fun acknowledgementResolvesOnlyItsCapturedRequest() {
        val first = TranscriptEngine.apply(TranscriptEngine.initial(), ChatEvent.ClarifyRequest("runtime", "q1", "First?", emptyList(), false))
        val second = TranscriptEngine.apply(first, ChatEvent.ClarifyRequest("runtime", "q2", "Second?", emptyList(), false))
        assertNull(TranscriptEngine.resolvePendingRequest(first, first.pendingRequest).pendingRequest)
        assertEquals(second, TranscriptEngine.resolvePendingRequest(second, first.pendingRequest))
        assertEquals(second, TranscriptEngine.resolvePendingRequest(second, null))
    }

    @Test fun terminalResponseCannotLeaveAttentionPinned() {
        val pending = TranscriptEngine.apply(TranscriptEngine.initial(), ChatEvent.ClarifyRequest("runtime", "q", "Continue?", emptyList(), false))
        assertNull(TranscriptEngine.apply(pending, ChatEvent.MessageComplete("runtime", text = "Done")).pendingRequest)
        assertNull(TranscriptEngine.apply(pending, ChatEvent.Error("runtime", "Stopped")).pendingRequest)
    }
}
