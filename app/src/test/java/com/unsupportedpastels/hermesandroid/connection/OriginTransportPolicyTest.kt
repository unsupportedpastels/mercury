package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginTransportPolicyTest {
    @Test
    fun tunnelModeAcceptsOnlyNumericLoopbackHosts() {
        listOf("http://127.0.0.1:9119", "http://[::1]:9119").forEach { input ->
            val origin = ServerOrigin.parse(input)
            assertTrue(input, origin.isLoopback)
            assertEquals(input, OriginTransportDecision.Allowed, evaluateOriginTransport(origin, ServerConnectionMode.ExternalSshTunnel))
        }
    }

    @Test
    fun tunnelModeRejectsLocalhostWithIpv4Ipv6Explanation() {
        val origin = ServerOrigin.parse("http://localhost:9119")
        assertFalse(origin.isLoopback)
        val rejected = evaluateOriginTransport(origin, ServerConnectionMode.ExternalSshTunnel) as OriginTransportDecision.Rejected
        assertEquals(TunnelConnectionFailure.InvalidTunnelOrigin, rejected.failure)
        assertTrue(rejected.message.contains("IPv4"))
        assertTrue(rejected.message.contains("IPv6"))
        assertTrue(rejected.message.contains("127.0.0.1"))
        assertFalse(rejected.message.contains("identity", ignoreCase = true))
    }

    @Test
    fun tunnelModeRejectsNonLoopbackBeforeAnyRequest() {
        listOf(
            "http://10.0.1.2:9119",
            "https://hermes.example",
            "http://100.64.1.2",
            "http://127.0.0.2",
        ).forEach { input ->
            val rejected = evaluateOriginTransport(
                ServerOrigin.parse(input),
                ServerConnectionMode.ExternalSshTunnel,
            ) as OriginTransportDecision.Rejected
            assertEquals(input, TunnelConnectionFailure.InvalidTunnelOrigin, rejected.failure)
        }
    }

    @Test
    fun directModeStillAcceptsHttpsRemoteAndDoesNotUseTunnelLoopbackRule() {
        assertEquals(
            OriginTransportDecision.Allowed,
            evaluateOriginTransport(
                ServerOrigin.parse("https://hermes.example"),
                ServerConnectionMode.Direct,
            ),
        )
        assertEquals(
            OriginTransportDecision.Allowed,
            evaluateOriginTransport(
                ServerOrigin.parse("https://10.0.1.2"),
                ServerConnectionMode.Direct,
            ),
        )
        assertEquals(
            OriginTransportDecision.Allowed,
            evaluateOriginTransport(
                ServerOrigin.parse("https://localhost"),
                ServerConnectionMode.Direct,
            ),
        )
        val localhostHttp = evaluateOriginTransport(
            ServerOrigin.parse("http://localhost:8080"),
            ServerConnectionMode.Direct,
        ) as OriginTransportDecision.Rejected
        assertEquals(TunnelConnectionFailure.CleartextPolicyBlocked, localhostHttp.failure)
    }

    @Test
    fun productionCleartextAllowsHttpOnlyForTunnelNumericLoopback() {
        assertEquals(
            OriginTransportDecision.Allowed,
            evaluateOriginTransport(
                ServerOrigin.parse("http://127.0.0.1:9119"),
                ServerConnectionMode.ExternalSshTunnel,
            ),
        )
        assertEquals(
            OriginTransportDecision.Allowed,
            evaluateOriginTransport(
                ServerOrigin.parse("http://[::1]:9119"),
                ServerConnectionMode.ExternalSshTunnel,
            ),
        )
        listOf(
            "http://10.0.1.2" to ServerConnectionMode.Direct,
            "http://192.168.1.9" to ServerConnectionMode.Direct,
            "http://100.64.1.2" to ServerConnectionMode.Direct,
            "http://127.0.0.1:9119" to ServerConnectionMode.Direct,
        ).forEach { (input, mode) ->
            val rejected = evaluateOriginTransport(ServerOrigin.parse(input), mode) as OriginTransportDecision.Rejected
            assertEquals(input, TunnelConnectionFailure.CleartextPolicyBlocked, rejected.failure)
            assertFalse(rejected.message.contains("identity", ignoreCase = true))
        }
        assertNull(
            evaluateOriginTransport(
                ServerOrigin.parse("https://hermes.example"),
                ServerConnectionMode.Direct,
            ).let { it as? OriginTransportDecision.Rejected },
        )
    }
}
