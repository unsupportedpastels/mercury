package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogTest {
    @Test
    fun directModeRemainsDefaultAndAcceptsRemoteOrigins() {
        val entry = ServerCatalogEntry(ServerOrigin.parse("https://hermes.example"))

        assertEquals(ServerConnectionMode.Direct, entry.connectionMode)
    }

    @Test
    fun externalSshTunnelModeCanRecordNumericLoopback() {
        assertEquals(
            ServerConnectionMode.ExternalSshTunnel,
            ServerCatalogEntry(
                origin = ServerOrigin.parse("http://127.0.0.1:8080"),
                connectionMode = ServerConnectionMode.ExternalSshTunnel,
            ).connectionMode,
        )
    }

    @Test
    fun externalSshTunnelModeRejectsLocalhostWithIpv4Ipv6Explanation() {
        val rejected = evaluateOriginTransport(
            ServerOrigin.parse("http://localhost:9119"),
            ServerConnectionMode.ExternalSshTunnel,
        ) as OriginTransportDecision.Rejected
        assertEquals(TunnelConnectionFailure.InvalidTunnelOrigin, rejected.failure)
        assertTrue(rejected.message.contains("IPv4"))
        assertTrue(rejected.message.contains("IPv6"))
        assertTrue(rejected.message.contains("127.0.0.1"))
    }

    @Test
    fun externalSshTunnelModeRejectsNonLoopbackOrigins() {
        val rejected = evaluateOriginTransport(
            ServerOrigin.parse("https://hermes.example"),
            ServerConnectionMode.ExternalSshTunnel,
        ) as OriginTransportDecision.Rejected
        assertEquals(TunnelConnectionFailure.InvalidTunnelOrigin, rejected.failure)
        assertTrue(rejected.message.contains("127.0.0.1"))
    }
}
