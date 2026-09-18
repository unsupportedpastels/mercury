package com.unsupportedpastels.mercury.core.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionResponseLimitsTest {
    @Test
    fun sessionListLimitIsFiveMebibytes() {
        assertEquals(5 * 1024 * 1024, SessionResponseLimits.SESSION_LIST_MAX_BYTES)
    }
}
