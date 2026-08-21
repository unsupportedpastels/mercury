package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

const val HERMES_SESSION_TOKEN_HEADER = "X-Hermes-Session-Token"
internal const val MAX_HERMES_CREDENTIAL_CHARS = 512
private const val MAX_NATIVE_BEARER_CHARS = 16 * 1024

/**
 * Process-memory-only authorization for one Hermes request origin.
 *
 * Secret values are deliberately private, have redacted debug text, and can
 * only be applied to an HTTP request through [applyTo]. Loopback credentials
 * carry their normalized origin so they cannot be sent to another server.
 */
sealed interface HermesCredential {
    data object None : HermesCredential

    class NativeBearer private constructor(
        private val token: String,
    ) : HermesCredential {
        internal fun apply(builder: HttpRequestBuilder) = builder.bearerAuth(token)

        override fun toString(): String = "HermesCredential.NativeBearer(***)"

        companion object {
            fun create(token: String): NativeBearer = NativeBearer(
                validSecret(token, MAX_NATIVE_BEARER_CHARS),
            )
        }
    }

    class LoopbackSession private constructor(
        val origin: ServerOrigin,
        private val token: String,
    ) : HermesCredential {
        internal fun apply(builder: HttpRequestBuilder, requestOrigin: ServerOrigin) {
            requireMatchingOrigin(requestOrigin)
            builder.header(HERMES_SESSION_TOKEN_HEADER, token)
        }

        internal fun encodedWebSocketToken(requestOrigin: ServerOrigin): String {
            requireMatchingOrigin(requestOrigin)
            return URLEncoder.encode(token, StandardCharsets.UTF_8.name())
        }

        private fun requireMatchingOrigin(requestOrigin: ServerOrigin) {
            require(requestOrigin == origin) { "Loopback session credential belongs to another origin" }
        }

        override fun toString(): String = "HermesCredential.LoopbackSession(${origin.value}, ***)"

        companion object {
            fun create(origin: ServerOrigin, token: String): LoopbackSession {
                require(origin.isLoopback) { "Loopback session credentials require a loopback origin" }
                return LoopbackSession(origin, validSecret(token, MAX_HERMES_CREDENTIAL_CHARS))
            }
        }
    }
}

/** Adapts the legacy optional-token boundary; null/blank means auth-free. */
internal fun String?.toHermesCredential(): HermesCredential =
    this?.takeIf(String::isNotBlank)?.let(HermesCredential.NativeBearer::create)
        ?: HermesCredential.None

internal fun HttpRequestBuilder.applyHermesCredential(
    credential: HermesCredential,
    requestOrigin: ServerOrigin,
) {
    when (credential) {
        HermesCredential.None -> Unit
        is HermesCredential.NativeBearer -> credential.apply(this)
        is HermesCredential.LoopbackSession -> credential.apply(this, requestOrigin)
    }
}

private fun validSecret(value: String, maxChars: Int): String {
    require(value.isNotBlank()) { "Hermes credential must not be blank" }
    require(value.length <= maxChars) { "Hermes credential is too long" }
    require(value.none(Char::isISOControl)) { "Hermes credential contains control characters" }
    return value
}
