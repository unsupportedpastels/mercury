package com.unsupportedpastels.hermesandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerOriginTest {
    @Test
    fun canonicalizesHttpOriginForLocalServers() {
        assertEquals(
            "http://10.0.1.2",
            ServerOrigin.parse("  HTTP://10.0.1.2:80/  ").value,
        )
        assertEquals(
            "http://10.0.1.2:8080",
            ServerOrigin.parse("http://10.0.1.2:8080").value,
        )
    }

    @Test
    fun webSocketValueMapsScheme() {
        assertEquals("wss://example.com", ServerOrigin.parse("https://example.com").webSocketValue)
        assertEquals("ws://10.0.1.2:8080", ServerOrigin.parse("http://10.0.1.2:8080").webSocketValue)
    }

    @Test
    fun rejectsUnsupportedSchemesAndNonOriginUrls() {
        listOf(
            "",
            "ftp://example.com",
            "https://user@example.com",
            "https://example.com/api",
            "https://example.com?ticket=secret",
            "https://example.com#fragment",
            "http://example.com",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerOrigin.parse(input)
            }
        }
    }
}
