package com.unsupportedpastels.hermesandroid.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    }

    @Test
    fun classifiesOnlyExactSupportedLoopbackHosts() {
        listOf(
            "http://127.0.0.1",
            "https://[::1]:8443",
        ).forEach { input ->
            assertTrue(input, ServerOrigin.parse(input).isLoopback)
        }

        listOf(
            "http://localhost:8080",
            "http://localhost",
            "http://127.0.0.2",
            "http://localhost.example",
            "http://localhost.",
            "http://[::2]",
            "http://[0:0:0:0:0:0:0:1]",
        ).forEach { input ->
            assertFalse(input, ServerOrigin.parse(input).isLoopback)
        }
        assertTrue(runCatching { ServerOrigin.parse("http://127.1") }.isFailure)
    }

    @Test
    fun numericLoopbackSpellingsRemainDistinctOrigins() {
        val origins = listOf(
            ServerOrigin.parse("http://127.0.0.1"),
            ServerOrigin.parse("http://[::1]"),
        )

        assertEquals(2, origins.distinct().size)
        assertFalse(ServerOrigin.parse("http://localhost").isLoopback)
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
            "example.com",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerOrigin.parse(input)
            }
        }
    }
}
