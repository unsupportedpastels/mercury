package com.unsupportedpastels.hermesandroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Servers screen seeds its target field once, from a snapshot that is usually still empty (the
 * remembered target is read from the Keystore asynchronously). Falling back to the restored value is
 * what keeps the Connect button usable; the field must never overwrite what the user typed.
 */
class SshConnectPanelTargetTest {

    @Test
    fun anEmptyFieldTakesTheRestoredTarget() {
        assertEquals("lute@192.168.101.16", mergeTargetField(typed = "", restored = "lute@192.168.101.16"))
    }

    @Test
    fun whatTheUserTypedWins() {
        assertEquals("lute@10.42.0.1", mergeTargetField(typed = "lute@10.42.0.1", restored = "lute@192.168.101.16"))
    }

    @Test
    fun anEmptyRestoreLeavesTheFieldAlone() {
        assertEquals("", mergeTargetField(typed = "", restored = ""))
    }
}