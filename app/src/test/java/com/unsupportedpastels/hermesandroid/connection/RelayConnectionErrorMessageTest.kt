package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.relay.RelayConnectionException
import com.unsupportedpastels.hermesandroid.relay.RelayConnectionFailure
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each relay failure cause gets its own actionable message (iOS parity). */
class RelayConnectionErrorMessageTest {
    @Test
    fun messagesAreDistinctPerCause() {
        val rejected = relayConnectionErrorMessage(RelayConnectionException(RelayConnectionFailure.RoutingRejected))
        val noHost = relayConnectionErrorMessage(RelayConnectionException(RelayConnectionFailure.NoHost))
        val unapproved = relayConnectionErrorMessage(RelayConnectionException(RelayConnectionFailure.NotAuthorized))
        val offline = relayConnectionErrorMessage(IllegalStateException("socket"))
        assertTrue(rejected.contains("pair again"))
        assertTrue(noHost.contains("isn't connected"))
        assertTrue(unapproved.contains("approved"))
        assertTrue(offline.contains("unreachable"))
        assertTrue(setOf(rejected, noHost, unapproved, offline).size == 4)
        // The cause is found through wrapping exceptions too.
        assertTrue(relayConnectionErrorMessage(RuntimeException("wrapped", RelayConnectionException(RelayConnectionFailure.NoHost))).contains("isn't connected"))
    }
}
