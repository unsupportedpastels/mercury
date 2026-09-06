package com.unsupportedpastels.mercury.core.origin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merged from Android ServerOriginTest and iOS ServerOriginTests, plus the
 * unified bare-host/"Use HTTPS" and elision rules decided 2026-08-30.
 */
class ServerOriginPolicyTest {

    private fun valid(input: String, useTls: Boolean = true): String =
        assertIs<OriginParseResult.Valid>(ServerOriginPolicy.canonicalize(input, useTls)).origin

    private fun reason(input: String, useTls: Boolean = true): String =
        assertIs<OriginParseResult.Invalid>(ServerOriginPolicy.canonicalize(input, useTls)).reason

    // --- bare hosts and the Use HTTPS flag -------------------------------------

    @Test
    fun bareHostUsesTlsFlagForScheme() {
        assertEquals("https://hermes.example.com", valid("hermes.example.com"))
        assertEquals("https://10.1.2.3:8080", valid("10.1.2.3:8080"))
        assertEquals("http://192.168.1.5:8080", valid("192.168.1.5:8080", useTls = false))
        assertEquals(
            "Plain HTTP is allowed only for local or private-network servers",
            reason("hermes.example.com", useTls = false),
        )
    }

    @Test
    fun explicitSchemeWinsOverTlsFlag() {
        assertEquals("http://192.168.1.5", valid("http://192.168.1.5", useTls = true))
        assertEquals("https://example.com", valid("https://example.com", useTls = false))
    }

    // --- canonicalization -----------------------------------------------------

    @Test
    fun lowercasesAndStripsTrailingSlash() {
        assertEquals("https://hermes.example.com", valid("  HTTPS://Hermes.Example.COM/ "))
    }

    @Test
    fun defaultPortsAreElided() {
        assertEquals("https://example.com", valid("HTTPS://Example.COM:443/"))
        assertEquals("http://10.0.1.2", valid("HTTP://10.0.1.2:80/"))
        assertEquals("https://example.com:8443", valid("https://example.com:8443"))
        assertEquals("http://192.168.1.5:443", valid("http://192.168.1.5:443"))
    }

    @Test
    fun unicodeHostsArePunycoded() {
        assertEquals("https://xn--r8jz45g.xn--zckzah", valid("https://例え.テスト/"))
        assertEquals("https://xn--bcher-kva.example", valid("bücher.example"))
    }

    @Test
    fun unicodeCompatibilityMatchesThePreviousAndroidCanonicalOrigin() {
        assertEquals("https://fass.de", valid("faß.de"))
        assertEquals("https://xn--4xa.gr", valid("ς.gr"))
        assertEquals("https://ab.example", valid("a\u200Cb.example"))
        assertEquals("https://foo.example", valid("ＦＯＯ.example"))
        assertEquals("https://foobar.example", valid("foo\u00ADbar.example"))
        assertEquals("https://foo.example", valid("foo\u3002example"))
        assertEquals("https://example.com.", valid("example.com."))
        assertIs<OriginParseResult.Invalid>(ServerOriginPolicy.canonicalize("ẞ.de"))
    }

    @Test
    fun bracketedIpv6IsAcceptedAndCanonicalized() {
        assertEquals("https://[::1]", valid("https://[::1]"))
        assertEquals("https://[2001:db8::1]:8443", valid("HTTPS://[2001:DB8::1]:8443"))
        assertEquals("https://[::1]", valid("https://[::1]:443"))
    }

    // --- rejections -----------------------------------------------------------

    @Test
    fun rejectionsCarryExactReasons() {
        assertEquals("Enter a valid HTTP or HTTPS server origin", reason(""))
        assertEquals("Server origin must use HTTP or HTTPS", reason("ftp://example.com"))
        assertEquals("Server origin must not include credentials", reason("https://user@example.com"))
        assertEquals("Server origin must not include a path", reason("https://example.com/api"))
        assertEquals("Server origin must not include a query or fragment", reason("https://example.com?x=1"))
        assertEquals("Server origin must not include a query or fragment", reason("https://example.com#frag"))
        assertEquals("Server origin contains an invalid port", reason("https://example.com:0"))
        assertEquals("Server origin contains an invalid port", reason("https://example.com:65536"))
        assertEquals("Server origin contains an invalid port", reason("https://example.com:12ab"))
        assertEquals("IPv6 server origins must use brackets", reason("https://2001:db8::1"))
        assertEquals("Server origin must include a valid host", reason("https://exa mple.com"))
        assertEquals("Server origin must include a valid host", reason("https://bad_host.example"))
        assertEquals("Server origin must include a valid host", reason("https://a..b"))
        assertEquals(
            "Plain HTTP is allowed only for local or private-network servers",
            reason("http://example.com"),
        )
    }

    // --- webSocketValue -------------------------------------------------------

    @Test
    fun webSocketValueMapsSchemesAndRequiresExplicitScheme() {
        assertEquals("wss://example.com", ServerOriginPolicy.webSocketValue("https://example.com"))
        assertEquals("ws://10.0.1.2:8080", ServerOriginPolicy.webSocketValue("http://10.0.1.2:8080"))
        assertEquals("wss://example.com", ServerOriginPolicy.webSocketValue("https://Example.com:443/"))
        assertNull(ServerOriginPolicy.webSocketValue("hermes.example.com"))
        assertNull(ServerOriginPolicy.webSocketValue("hermes.example.com/path"))
        assertNull(ServerOriginPolicy.webSocketValue("https://example.com/api"))
    }

    // --- loopback/private + cleartext -----------------------------------------

    @Test
    fun loopbackAndPrivateRangesClassify() {
        for (origin in listOf(
            "http://localhost", "http://localhost:9119", "https://svc.local",
            "http://127.0.0.1", "http://10.1.2.3", "http://172.16.0.1",
            "http://172.31.255.255", "http://192.168.1.5", "http://[::1]",
        )) {
            assertTrue(ServerOriginPolicy.isLoopbackOrPrivate(origin), origin)
        }
        for (origin in listOf(
            "https://example.com", "http://172.15.0.1", "http://172.32.0.1",
            "http://192.169.1.1", "http://11.0.0.1", "http://8.8.8.8",
        )) {
            assertFalse(ServerOriginPolicy.isLoopbackOrPrivate(origin), origin)
        }
    }

    @Test
    fun cleartextAllowedOnlyForPrivateHttp() {
        assertTrue(ServerOriginPolicy.allowsCleartextHttp("http://192.168.1.5:8080"))
        assertEquals("http://localhost.", valid("http://localhost."))
        assertEquals("http://127.0.0.1.", valid("http://127.0.0.1."))
        assertFalse(ServerOriginPolicy.allowsCleartextHttp("http://example.com"))
        assertFalse(ServerOriginPolicy.allowsCleartextHttp("https://192.168.1.5"))
    }
}
