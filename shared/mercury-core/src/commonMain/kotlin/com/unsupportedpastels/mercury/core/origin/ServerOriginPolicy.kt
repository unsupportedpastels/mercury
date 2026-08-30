package com.unsupportedpastels.mercury.core.origin

/** Outcome of canonicalizing user-entered server input. */
sealed interface OriginParseResult {
    data class Valid(val origin: String) : OriginParseResult
    data class Invalid(val reason: String) : OriginParseResult
}

/**
 * Server-origin canonicalization decided once for both clients.
 *
 * Unified rules (divergences resolved 2026-08-30, see plan doc):
 * - Bare hosts are accepted; [useTls] (the "Use HTTPS" checkbox, default on)
 *   picks https vs http. An explicit `http://`/`https://` in the input wins.
 * - Default ports are elided (`:443` on https, `:80` on http) so the same
 *   server always canonicalizes to the same string on both platforms.
 * - Credentials, paths (beyond one trailing slash), queries, and fragments
 *   are rejected; unbracketed IPv6 is rejected, bracketed accepted.
 * - Non-ASCII hosts are punycoded (pure-Kotlin RFC 3492) with STD3 label
 *   rules, matching the previous Android `java.net.IDN` behavior on both
 *   platforms.
 *
 * Rejection reasons are user-visible contract (Android's dialog shows them).
 */
object ServerOriginPolicy {
    private const val INVALID_INPUT = "Enter a valid HTTP or HTTPS server origin"
    private const val INVALID_SCHEME = "Server origin must use HTTP or HTTPS"
    private const val MISSING_HOST = "Server origin must include a host"
    private const val INVALID_HOST = "Server origin must include a valid host"
    private const val HAS_CREDENTIALS = "Server origin must not include credentials"
    private const val HAS_QUERY_OR_FRAGMENT = "Server origin must not include a query or fragment"
    private const val HAS_PATH = "Server origin must not include a path"
    private const val INVALID_PORT = "Server origin contains an invalid port"
    private const val IPV6_NEEDS_BRACKETS = "IPv6 server origins must use brackets"

    fun canonicalize(input: String, useTls: Boolean = true): OriginParseResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return OriginParseResult.Invalid(INVALID_INPUT)

        var scheme = if (useTls) "https" else "http"
        var remainder = trimmed
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator >= 0) {
            val candidate = trimmed.substring(0, schemeSeparator).lowercase()
            if (candidate != "http" && candidate != "https") {
                return OriginParseResult.Invalid(INVALID_SCHEME)
            }
            scheme = candidate
            remainder = trimmed.substring(schemeSeparator + 3)
        }

        if (remainder.isEmpty()) return OriginParseResult.Invalid(MISSING_HOST)
        if (remainder.endsWith("/")) {
            remainder = remainder.dropLast(1)
            if (remainder.isEmpty()) return OriginParseResult.Invalid(MISSING_HOST)
        }
        if (remainder.contains('?') || remainder.contains('#')) {
            return OriginParseResult.Invalid(HAS_QUERY_OR_FRAGMENT)
        }
        if (remainder.contains('/')) return OriginParseResult.Invalid(HAS_PATH)
        if (remainder.contains('@')) return OriginParseResult.Invalid(HAS_CREDENTIALS)
        if (remainder.contains('\\') || remainder.any { it.isWhitespace() || it.isISOControl() }) {
            return OriginParseResult.Invalid(INVALID_HOST)
        }

        val hostAndPort = parseAuthority(remainder)
        val host: String
        val port: Int
        when (hostAndPort) {
            is AuthorityResult.Invalid -> return OriginParseResult.Invalid(hostAndPort.reason)
            is AuthorityResult.Valid -> {
                host = hostAndPort.host
                port = hostAndPort.port
            }
        }

        val defaultPort = if (scheme == "https") 443 else 80
        val portSuffix = if (port == -1 || port == defaultPort) "" else ":$port"
        return OriginParseResult.Valid("$scheme://$host$portSuffix")
    }

    /**
     * Converts an explicit HTTP(S) origin to the matching WebSocket scheme.
     * A scheme is required; bare-host input must be canonicalized first.
     * Returns null when the input is not a valid schemed origin.
     */
    fun webSocketValue(origin: String): String? {
        val trimmed = origin.trim()
        if (!trimmed.contains("://")) return null
        val result = canonicalize(trimmed)
        val canonical = (result as? OriginParseResult.Valid)?.origin ?: return null
        return when {
            canonical.startsWith("https://") -> "wss://" + canonical.removePrefix("https://")
            else -> "ws://" + canonical.removePrefix("http://")
        }
    }

    /**
     * True when the host of a normalized origin is loopback or RFC1918-private,
     * i.e. it never leaves the user's network.
     */
    fun isLoopbackOrPrivate(origin: String): Boolean {
        val host = hostOf(origin) ?: return false
        return hostIsLoopbackOrPrivate(host)
    }

    /** Cleartext HTTP is acceptable only for loopback/RFC1918 hosts. */
    fun allowsCleartextHttp(origin: String): Boolean =
        origin.startsWith("http://") && isLoopbackOrPrivate(origin)

    // MARK: authority parsing

    private sealed interface AuthorityResult {
        data class Valid(val host: String, val port: Int) : AuthorityResult
        data class Invalid(val reason: String) : AuthorityResult
    }

    private fun parseAuthority(authority: String): AuthorityResult {
        if (authority.startsWith("[")) {
            val closingBracket = authority.indexOf(']')
            if (closingBracket <= 1) return AuthorityResult.Invalid(INVALID_HOST)
            val host = authority.substring(1, closingBracket).lowercase()
            val suffix = authority.substring(closingBracket + 1)
            val port = when {
                suffix.isEmpty() -> -1
                suffix.startsWith(":") -> parsePort(suffix.drop(1)) ?: return AuthorityResult.Invalid(INVALID_PORT)
                else -> return AuthorityResult.Invalid(INVALID_HOST)
            }
            return AuthorityResult.Valid("[$host]", port)
        }

        if (authority.count { it == ':' } > 1) {
            return AuthorityResult.Invalid(IPV6_NEEDS_BRACKETS)
        }
        val separator = authority.lastIndexOf(':')
        val hostInput = if (separator >= 0) authority.substring(0, separator) else authority
        val port = if (separator >= 0) {
            parsePort(authority.substring(separator + 1)) ?: return AuthorityResult.Invalid(INVALID_PORT)
        } else {
            -1
        }
        if (hostInput.isBlank()) return AuthorityResult.Invalid(MISSING_HOST)
        val host = idnToAscii(hostInput) ?: return AuthorityResult.Invalid(INVALID_HOST)
        return AuthorityResult.Valid(host, port)
    }

    private fun parsePort(value: String): Int? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
        val port = value.toIntOrNull() ?: return null
        return port.takeIf { it in 1..65535 }
    }

    // MARK: IDN / punycode (RFC 3492 + STD3 label rules)

    private fun idnToAscii(host: String): String? {
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) return null
        val encoded = labels.map { label ->
            val ascii = if (label.all { it.code < 0x80 }) {
                label.lowercase()
            } else {
                "xn--" + (Punycode.encode(label.lowercase()) ?: return null)
            }
            if (ascii.isEmpty() || ascii.length > 63) return null
            // STD3: letters, digits, hyphen only; no leading/trailing hyphen.
            if (ascii.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }) return null
            if (ascii.startsWith('-') || ascii.endsWith('-')) return null
            ascii
        }
        val joined = encoded.joinToString(".")
        return joined.takeIf { it.length <= 253 }
    }

    private object Punycode {
        private const val BASE = 36
        private const val T_MIN = 1
        private const val T_MAX = 26
        private const val SKEW = 38
        private const val DAMP = 700
        private const val INITIAL_BIAS = 72
        private const val INITIAL_N = 128

        fun encode(label: String): String? {
            val input = label.toCodePoints()
            val output = StringBuilder()
            val basic = input.filter { it < 0x80 }
            basic.forEach { output.append(it.toChar()) }
            var handled = basic.size
            val basicLength = handled
            if (basicLength > 0) output.append('-')

            var n = INITIAL_N
            var delta = 0
            var bias = INITIAL_BIAS
            while (handled < input.size) {
                val m = input.filter { it >= n }.minOrNull() ?: return null
                val widen = (m - n).toLong() * (handled + 1).toLong()
                if (widen > Int.MAX_VALUE - delta) return null
                delta += widen.toInt()
                n = m
                for (codePoint in input) {
                    if (codePoint < n) {
                        delta += 1
                        if (delta == Int.MAX_VALUE) return null
                    }
                    if (codePoint == n) {
                        var q = delta
                        var k = BASE
                        while (true) {
                            val threshold = when {
                                k <= bias -> T_MIN
                                k >= bias + T_MAX -> T_MAX
                                else -> k - bias
                            }
                            if (q < threshold) break
                            output.append(digitToChar(threshold + (q - threshold) % (BASE - threshold)))
                            q = (q - threshold) / (BASE - threshold)
                            k += BASE
                        }
                        output.append(digitToChar(q))
                        bias = adapt(delta, handled + 1, handled == basicLength)
                        delta = 0
                        handled += 1
                    }
                }
                delta += 1
                n += 1
            }
            return output.toString()
        }

        private fun adapt(rawDelta: Int, numPoints: Int, firstTime: Boolean): Int {
            var delta = if (firstTime) rawDelta / DAMP else rawDelta / 2
            delta += delta / numPoints
            var k = 0
            while (delta > ((BASE - T_MIN) * T_MAX) / 2) {
                delta /= BASE - T_MIN
                k += BASE
            }
            return k + (((BASE - T_MIN + 1) * delta) / (SKEW + delta))
        }

        private fun digitToChar(digit: Int): Char =
            if (digit < 26) ('a' + digit) else ('0' + digit - 26)

        private fun String.toCodePoints(): List<Int> {
            val points = mutableListOf<Int>()
            var index = 0
            while (index < length) {
                val character = this[index]
                if (character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
                    points += (((character.code - 0xD800) shl 10) or (this[index + 1].code - 0xDC00)) + 0x10000
                    index += 2
                } else {
                    points += character.code
                    index += 1
                }
            }
            return points
        }
    }

    // MARK: host helpers

    private fun hostOf(origin: String): String? {
        val schemeEnd = origin.indexOf("://")
        if (schemeEnd < 0) return null
        var rest = origin.substring(schemeEnd + 3)
        rest = rest.substringBefore('/')
        if (rest.startsWith("[")) {
            val closing = rest.indexOf(']')
            if (closing <= 1) return null
            return rest.substring(1, closing).lowercase()
        }
        val host = rest.substringBefore(':')
        return host.ifEmpty { null }?.lowercase()
    }

    private fun hostIsLoopbackOrPrivate(rawHost: String): Boolean {
        val host = rawHost.lowercase().trim('[', ']')
        if (host == "localhost" || host == "::1" ||
            host.endsWith(".localhost") || host.endsWith(".local")
        ) {
            return true
        }

        // IPv4 dotted-quad checks: 127/8, 10/8, 172.16/12, 192.168/16.
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return when (octets[0]) {
            127, 10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            else -> false
        }
    }
}
