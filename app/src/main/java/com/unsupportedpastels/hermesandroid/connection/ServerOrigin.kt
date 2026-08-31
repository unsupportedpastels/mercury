package com.unsupportedpastels.hermesandroid.connection

import java.net.IDN
import java.net.URI
import java.util.Locale

@JvmInline
value class ServerOrigin private constructor(val value: String) {
    companion object {
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1")

        fun parse(input: String): ServerOrigin {
            val candidate = input.trim()
            val uri = runCatching { URI(candidate) }
                .getOrElse { throw IllegalArgumentException("Enter a valid HTTP or HTTPS server origin") }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val authority = uri.rawAuthority

            require(scheme == "http" || scheme == "https") {
                "Server origin must use HTTP or HTTPS"
            }
            require(!authority.isNullOrBlank()) { "Server origin must include a host" }
            require(uri.rawUserInfo == null && '@' !in authority) {
                "Server origin must not include credentials"
            }
            require(uri.rawQuery == null && uri.rawFragment == null) {
                "Server origin must not include a query or fragment"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Server origin must not include a path"
            }

            val (host, port) = parseAuthority(authority)
            val defaultPort = if (scheme == "https") 443 else 80
            val canonicalPort = port.takeUnless { it == -1 || it == defaultPort } ?: -1
            val canonical = URI(scheme, null, host, canonicalPort, null, null, null)
                .toASCIIString()
            return ServerOrigin(canonical)
        }

        private fun parseAuthority(authority: String): Pair<String, Int> {
            if (authority.startsWith("[")) {
                val closingBracket = authority.indexOf(']')
                require(closingBracket > 1) { "Server origin must include a valid host" }
                val host = authority.substring(1, closingBracket).lowercase(Locale.ROOT)
                val suffix = authority.substring(closingBracket + 1)
                val port = when {
                    suffix.isEmpty() -> -1
                    suffix.startsWith(":") -> parsePort(suffix.drop(1))
                    else -> throw IllegalArgumentException("Server origin must include a valid host")
                }
                return host to port
            }

            require(authority.count { it == ':' } <= 1) {
                "IPv6 server origins must use brackets"
            }
            val separator = authority.lastIndexOf(':')
            val hostInput = if (separator >= 0) authority.substring(0, separator) else authority
            val port = if (separator >= 0) parsePort(authority.substring(separator + 1)) else -1
            require(hostInput.isNotBlank()) { "Server origin must include a host" }
            val host = runCatching {
                IDN.toASCII(hostInput, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }.getOrElse {
                throw IllegalArgumentException("Server origin must include a valid host")
            }
            require(host.isNotBlank()) { "Server origin must include a valid host" }
            return host to port
        }

        private fun parsePort(value: String): Int {
            val port = value.toIntOrNull()
            require(port != null && port in 1..65535) {
                "Server origin contains an invalid port"
            }
            return port
        }
    }

    val webSocketValue: String
        get() = when {
            value.startsWith("https://") -> value.replaceFirst("https://", "wss://")
            value.startsWith("http://") -> value.replaceFirst("http://", "ws://")
            else -> error("Server origin must use HTTP or HTTPS")
        }

    val isLoopback: Boolean
        get() = URI(value).host.removeSurrounding("[", "]") in LOOPBACK_HOSTS
}
