package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.mercury.core.transcript.ChatEvent
import com.unsupportedpastels.mercury.core.transcript.ChatEventDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedChatEventAdaptersTest {
    @Test fun fastModeSurvivesBothAdapterDirectionsIncludingAbsentPatch() {
        listOf(true, false, null).forEach { fast ->
            val payload = fast?.let { "{\"fast_mode\":$it}" } ?: "{}"
            val shared = ChatEventDecoder.decode("session.info", "runtime-test", payload) as ChatEvent.SessionInfo
            val roundTrip = shared.toAndroidEvent()!!.toSharedEvent() as ChatEvent.SessionInfo
            assertEquals("fast_mode=$fast", fast, roundTrip.fastMode)
        }
    }
}
