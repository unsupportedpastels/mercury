package com.unsupportedpastels.hermesandroid.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The process-level release hook must be a one-shot: the tunnel service runs it when the task is
 * removed, and a second run would hit an already-torn-down transport.
 */
class SshTunnelRuntimeReleaseTest {

    @Test
    fun releaseIsANoOpWithNothingRegistered() = runBlocking {
        SshTunnelRuntime.clearRelease()
        SshTunnelRuntime.runRelease()
    }

    @Test
    fun releaseRunsTheRegisteredHookExactlyOnce() = runBlocking {
        SshTunnelRuntime.clearRelease()
        var runs = 0
        SshTunnelRuntime.registerRelease { runs += 1 }

        SshTunnelRuntime.runRelease()
        SshTunnelRuntime.runRelease()

        assertEquals(1, runs)
    }

    @Test
    fun clearingReleasePreventsARegisteredHookFromRunning() = runBlocking {
        var runs = 0
        SshTunnelRuntime.registerRelease { runs += 1 }
        SshTunnelRuntime.clearRelease()

        SshTunnelRuntime.runRelease()

        assertEquals(0, runs)
    }
}
