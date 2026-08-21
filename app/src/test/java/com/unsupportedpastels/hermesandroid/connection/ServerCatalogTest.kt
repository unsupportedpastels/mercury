package com.unsupportedpastels.hermesandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerCatalogTest {
    @Test
    fun directModeRemainsDefaultAndAcceptsRemoteOrigins() {
        val entry = ServerCatalogEntry(ServerOrigin.parse("https://hermes.example"))

        assertEquals(ServerConnectionMode.Direct, entry.connectionMode)
    }

    @Test
    fun externalSshTunnelModeRequiresLoopbackOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerCatalogEntry(
                origin = ServerOrigin.parse("https://hermes.example"),
                connectionMode = ServerConnectionMode.ExternalSshTunnel,
            )
        }

        assertEquals(
            ServerConnectionMode.ExternalSshTunnel,
            ServerCatalogEntry(
                origin = ServerOrigin.parse("http://127.0.0.1:8080"),
                connectionMode = ServerConnectionMode.ExternalSshTunnel,
            ).connectionMode,
        )
    }
}