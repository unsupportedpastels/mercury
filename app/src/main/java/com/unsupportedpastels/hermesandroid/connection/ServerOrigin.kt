package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.mercury.core.origin.OriginParseResult
import com.unsupportedpastels.mercury.core.origin.ServerOriginPolicy

/**
 * Canonical server origin. Parsing and canonicalization decide in the shared
 * KMP core (AGENTS.md cross-platform rule): bare hosts
 * are accepted and [useTls] (the "Use HTTPS" checkbox, default on) picks the
 * scheme when the input carries none; an explicit scheme always wins.
 */
@JvmInline
value class ServerOrigin private constructor(val value: String) {
    companion object {
        fun parse(input: String, useTls: Boolean = true): ServerOrigin =
            when (val result = ServerOriginPolicy.canonicalize(input, useTls)) {
                is OriginParseResult.Valid -> ServerOrigin(result.origin)
                is OriginParseResult.Invalid -> throw IllegalArgumentException(result.reason)
            }
    }

    val webSocketValue: String
        get() = ServerOriginPolicy.webSocketValue(value)
            ?: error("Server origin must use HTTP or HTTPS")
}
