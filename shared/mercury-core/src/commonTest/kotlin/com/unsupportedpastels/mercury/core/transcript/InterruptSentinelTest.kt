package com.unsupportedpastels.mercury.core.transcript

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterruptSentinelTest {
    @Test
    fun matchesOnlyTheExactHermesPrefixAfterTrimming() {
        assertTrue(InterruptSentinel.isInterruptSentinel("Operation interrupted: waiting for model response (12s)"))
        assertTrue(InterruptSentinel.isInterruptSentinel("  \nOperation interrupted: waiting for model response (x)"))
        assertFalse(InterruptSentinel.isInterruptSentinel("Operation interrupted: waiting for model response"))
        assertFalse(InterruptSentinel.isInterruptSentinel("operation interrupted: waiting for model response ("))
        assertFalse(InterruptSentinel.isInterruptSentinel("The Operation interrupted: waiting for model response ("))
    }
}
