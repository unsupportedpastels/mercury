package com.unsupportedpastels.hermesandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerOriginTest {
    @Test
    fun canonicalizesHttpsOrigin() {
        assertEquals(
            "https://example.com",
            ServerOrigin.parse("  HTTPS://Example.COM:443/  ").value,
        )
    }

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
    fun bareHostsUseTheTlsFlag() {
        assertEquals("https://hermes.example.com", ServerOrigin.parse("hermes.example.com").value)
        assertEquals(
            "http://192.168.1.5:8080",
            ServerOrigin.parse("192.168.1.5:8080", useTls = false).value,
        )
        // An explicit scheme always wins over the flag.
        assertEquals(
            "http://192.168.1.5",
            ServerOrigin.parse("http://192.168.1.5", useTls = true).value,
        )
    }

    @Test
    fun preservesExplicitNonDefaultPort() {
        assertEquals(
            "https://example.com:8443",
            ServerOrigin.parse("https://example.com:8443").value,
        )
    }

    @Test
    fun canonicalizesInternationalizedDnsName() {
        assertEquals(
            "https://xn--r8jz45g.xn--zckzah",
            ServerOrigin.parse("https://例え.テスト/").value,
        )
        assertEquals("https://fass.de", ServerOrigin.parse("https://faß.de").value)
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
