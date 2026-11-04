package com.unsupportedpastels.hermesandroid.ssh

import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local forward is the half of "is the tunnel usable" that a resume gets wrong: the SSH session
 * survives a freeze, the forwarded port does not.
 */
class LocalForwardProbeTest {

    @Test
    fun aListeningPortAnswers() {
        ServerSocket(0).use { server ->
            assertTrue(localForwardAnswers(server.localPort, timeoutMillis = 500))
        }
    }

    @Test
    fun aClosedPortDoesNotAnswer() {
        // Bind then close: the port is now free, so nothing accepts a connection on it.
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(localForwardAnswers(port, timeoutMillis = 500))
    }

    @Test
    fun aMissingPortIsNotAlive() {
        assertFalse(localForwardAnswers(null, timeoutMillis = 500))
        assertFalse(localForwardAnswers(0, timeoutMillis = 500))
    }
}
