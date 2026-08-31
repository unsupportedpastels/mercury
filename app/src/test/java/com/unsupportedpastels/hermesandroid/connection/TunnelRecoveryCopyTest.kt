package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRecoveryCopyTest {
    @Test
    fun credentialRejectedUsesAnswersDocumentWordingAndTerminalActions() {
        val copy = tunnelRecoveryCopy(
            failure = TunnelConnectionFailure.CredentialRejected,
            connectionState = ConnectionState.Disconnected,
            connectionError = CREDENTIAL_REJECTED_USER_MESSAGE,
        )
        assertNotNull(copy)
        assertEquals(CREDENTIAL_REJECTED_USER_MESSAGE, copy!!.body)
        assertEquals(
            listOf(
                TunnelRecoveryAction.Retry,
                TunnelRecoveryAction.ConnectionSetup,
                TunnelRecoveryAction.Cancel,
            ),
            copy.actions,
        )
    }

    @Test
    fun tunnelUnavailableDoesNotClaimThisAppCanRestoreTheTunnel() {
        val copy = tunnelRecoveryCopy(
            failure = TunnelConnectionFailure.TunnelUnavailable,
            connectionState = ConnectionState.Recovering,
            connectionError = "SSH tunnel unavailable",
        )
        assertNotNull(copy)
        assertTrue(copy!!.body.contains("cannot start the tunnel", ignoreCase = true))
        assertTrue(copy.body.contains("SSH app"))
        assertTrue(copy.body.contains("Retry reconnects this app only"))
        assertFalse(copy.body.contains("restore the tunnel", ignoreCase = true))
        assertEquals(listOf(TunnelRecoveryAction.Retry), copy.actions)
    }

    @Test
    fun wrongServiceSuggestsAnotherLocalPort() {
        val copy = tunnelRecoveryCopy(
            failure = TunnelConnectionFailure.NotHermesEndpoint,
            connectionState = ConnectionState.Disconnected,
            connectionError = NOT_HERMES_ENDPOINT_MESSAGE,
        )
        assertNotNull(copy)
        assertTrue(copy!!.body.contains("another local port", ignoreCase = true))
        assertEquals(TunnelConnectionFailure.NotHermesEndpoint, copy.failure)
    }

    @Test
    fun bootstrapUnavailableAndGatedServerAreDistinct() {
        val bootstrap = tunnelRecoveryCopy(
            failure = TunnelConnectionFailure.BootstrapRejected,
            connectionState = ConnectionState.Disconnected,
            connectionError = BOOTSTRAP_REJECTED_USER_MESSAGE,
        )
        val gated = tunnelRecoveryCopy(
            failure = TunnelConnectionFailure.BootstrapRejected,
            connectionState = ConnectionState.Disconnected,
            connectionError = GATED_TUNNEL_TARGET_MESSAGE,
        )
        assertNotNull(bootstrap)
        assertNotNull(gated)
        assertTrue(bootstrap!!.title != gated!!.title)
        assertTrue(bootstrap.body != gated.body)
        assertFalse(bootstrap.body.contains("OAuth", ignoreCase = true))
        assertTrue(gated.body.contains("OAuth", ignoreCase = true))
    }

    @Test
    fun protocolIncompatibleIsItsOwnCopy() {
        val copy = tunnelRecoveryCopy(
            failure = TunnelConnectionFailure.ProtocolIncompatible,
            connectionState = ConnectionState.Disconnected,
            connectionError = PROTOCOL_INCOMPATIBLE_MESSAGE,
        )
        assertNotNull(copy)
        assertEquals("Protocol incompatible", copy!!.title)
        assertTrue(copy.body.contains(MINIMUM_HERMES_VERSION))
    }

    @Test
    fun recoveringSnapshotStillShowsInstallationChangedAcceptAndCancel() {
        val copy = tunnelRecoveryCopy(
            failure = TunnelConnectionFailure.InstallationChanged,
            connectionState = ConnectionState.Recovering,
            connectionError = INSTALLATION_CHANGED_MESSAGE,
        )
        assertNotNull(copy)
        assertEquals(TunnelConnectionFailure.InstallationChanged, copy!!.failure)
        assertFalse(copy.body.contains("identity", ignoreCase = true))
        assertFalse(copy.body.contains("reconnect", ignoreCase = true))
        assertEquals(
            listOf(TunnelRecoveryAction.AcceptNewServer, TunnelRecoveryAction.Cancel),
            copy.actions,
        )
    }

    @Test
    fun recoveringWithoutAClassifiedFailureDoesNotInventTunnelCopy() {
        val copy = tunnelRecoveryCopy(
            failure = null,
            connectionState = ConnectionState.Recovering,
            connectionError = null,
        )
        assertEquals(null, copy)
    }

    @Test
    fun loopbackWarningNeverCallsAndroidLoopbackPrivateOrSandboxed() {
        assertFalse(LOOPBACK_SECURITY_WARNING.contains("private", ignoreCase = true))
        assertFalse(LOOPBACK_SECURITY_WARNING.contains("sandbox", ignoreCase = true))
        assertTrue(LOOPBACK_SECURITY_WARNING.contains("any other app", ignoreCase = true))
    }
}
