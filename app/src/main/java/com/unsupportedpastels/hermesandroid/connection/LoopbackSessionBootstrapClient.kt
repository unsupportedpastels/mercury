package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal const val MAX_LOOPBACK_BOOTSTRAP_BODY_BYTES = 64 * 1024
private const val SESSION_ASSIGNMENT = "window.__HERMES_SESSION_TOKEN__="
private val SESSION_ASSIGNMENT_PATTERN = Regex(
    Regex.escape(SESSION_ASSIGNMENT) + "(\"(?:\\\\.|[^\"\\\\])*\");",
)

enum class LoopbackSessionBootstrapFailure {
    NonLoopbackOrigin,
    RedirectRejected,
    HttpRejected,
    BodyTooLarge,
    TokenAbsent,
    TokenMalformed,
    TransportFailure,
}

internal fun LoopbackSessionBootstrapFailure.asTunnelOutcome(): Pair<TunnelConnectionFailure, String> =
    if (this == LoopbackSessionBootstrapFailure.TransportFailure) {
        TunnelConnectionFailure.TunnelUnavailable to TUNNEL_UNAVAILABLE_BODY
    } else {
        TunnelConnectionFailure.BootstrapRejected to BOOTSTRAP_REJECTED_USER_MESSAGE
    }

/**
 * The loopback session bootstrap could not produce a credential. This is never a
 * credential rejection: [reason] separates a tunnel that stopped forwarding
 * ([LoopbackSessionBootstrapFailure.TransportFailure]) from a shell that no
 * longer looks like the Hermes dashboard, so a dead port cannot clear a
 * credential or masquerade as a sign-in prompt.
 */
class HermesLoopbackBootstrapException(
    val reason: LoopbackSessionBootstrapFailure,
) : HermesConnectionException("Hermes tunnel authorization bootstrap did not complete")

sealed interface LoopbackSessionBootstrapResult {
    class Success(
        val credential: HermesCredential.LoopbackSession,
    ) : LoopbackSessionBootstrapResult {
        override fun toString(): String = "LoopbackSessionBootstrapResult.Success(***)"
    }

    data class Failure(
        val reason: LoopbackSessionBootstrapFailure,
    ) : LoopbackSessionBootstrapResult
}

interface LoopbackSessionBootstrapClient {
    suspend fun bootstrap(origin: ServerOrigin): LoopbackSessionBootstrapResult
}

/** Fetches and parses the unchanged Hermes loopback dashboard shell. */
class HttpLoopbackSessionBootstrapClient(
    client: HttpClient,
) : LoopbackSessionBootstrapClient {
    // Redirect policy is intrinsic: callers cannot accidentally pass a default
    // Ktor client that follows a token-bootstrap redirect.
    private val bootstrapClient = client.config { followRedirects = false }

    override suspend fun bootstrap(origin: ServerOrigin): LoopbackSessionBootstrapResult {
        if (!origin.isLoopback) return failure(LoopbackSessionBootstrapFailure.NonLoopbackOrigin)

        return try {
            val response = bootstrapClient.get(origin.value + "/") {
                // Request revalidation even when an injected engine has a cache;
                // the server also responds with no-store/no-cache.
                header(HttpHeaders.CacheControl, "no-cache, no-store, max-age=0")
                header(HttpHeaders.Pragma, "no-cache")
            }
            if (response.status.value in 300..399) {
                return response.discardAndFail(LoopbackSessionBootstrapFailure.RedirectRejected)
            }
            val responseOrigin = runCatching {
                ServerOrigin.parse(response.call.request.url.toString())
            }.getOrNull()
            if (responseOrigin != origin || !responseOrigin.isLoopback) {
                return response.discardAndFail(LoopbackSessionBootstrapFailure.RedirectRejected)
            }
            if (!response.status.isSuccess()) {
                return response.discardAndFail(LoopbackSessionBootstrapFailure.HttpRejected)
            }
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_LOOPBACK_BOOTSTRAP_BODY_BYTES) {
                return response.discardAndFail(LoopbackSessionBootstrapFailure.BodyTooLarge)
            }
            val html = try {
                response.readBodyTextBounded(MAX_LOOPBACK_BOOTSTRAP_BODY_BYTES)
            } catch (_: HermesResponseBodyTooLargeException) {
                return failure(LoopbackSessionBootstrapFailure.BodyTooLarge)
            }
            parseShell(origin, html)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(LoopbackSessionBootstrapFailure.TransportFailure)
        }
    }

    private fun parseShell(
        origin: ServerOrigin,
        html: String,
    ): LoopbackSessionBootstrapResult {
        val assignments = SESSION_ASSIGNMENT_PATTERN.findAll(html).toList()
        if (assignments.isEmpty()) {
            return failure(
                if (SESSION_ASSIGNMENT in html) {
                    LoopbackSessionBootstrapFailure.TokenMalformed
                } else {
                    LoopbackSessionBootstrapFailure.TokenAbsent
                },
            )
        }
        val prefixRepeats =
            html.indexOf(SESSION_ASSIGNMENT) != html.lastIndexOf(SESSION_ASSIGNMENT)
        if (assignments.size != 1 || prefixRepeats) {
            return failure(LoopbackSessionBootstrapFailure.TokenMalformed)
        }
        val token = runCatching {
            Json.decodeFromString<String>(assignments.single().groupValues[1])
        }.getOrNull() ?: return failure(LoopbackSessionBootstrapFailure.TokenMalformed)
        val credential = runCatching {
            HermesCredential.LoopbackSession.create(origin, token)
        }.getOrNull() ?: return failure(LoopbackSessionBootstrapFailure.TokenMalformed)
        return LoopbackSessionBootstrapResult.Success(credential)
    }

    private fun failure(reason: LoopbackSessionBootstrapFailure) =
        LoopbackSessionBootstrapResult.Failure(reason)

    /** Drops the body of a rejected shell response so the connection is not left mid-read. */
    private suspend fun HttpResponse.discardAndFail(
        reason: LoopbackSessionBootstrapFailure,
    ): LoopbackSessionBootstrapResult {
        bodyAsChannel().cancel(null)
        return failure(reason)
    }
}
