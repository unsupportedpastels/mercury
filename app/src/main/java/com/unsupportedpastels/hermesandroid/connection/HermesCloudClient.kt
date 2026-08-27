package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

import kotlinx.serialization.Serializable

/**
 * A Nous Portal OAuth token set — distinct from a per-agent dashboard
 * [NativeTokenSet]. This authenticates Hermes Cloud discovery (`GET /api/agents`)
 * and is minted via the Portal device-code flow (RFC 8628), the same flow the
 * `hermes` CLI uses (`hermes_cli/auth.py`, `client_id = "hermes-cli"`,
 * scope `inference:invoke`).
 */
@Serializable
data class PortalTokenSet(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val scope: String,
    /** Epoch seconds; 0 when the server did not supply an expiry. */
    val expiresAt: Long,
)

/** A pending device authorization, from `POST /api/oauth/device/code`. */
data class PortalDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/** Raised when the Portal session is absent/expired and a fresh sign-in is needed. */
class HermesCloudSignInRequiredException(
    message: String = "Sign in to Hermes Cloud to continue.",
    cause: Throwable? = null,
) : HermesConnectionException(message, cause)

/** The Portal contract HAM depends on: device-code sign-in, refresh, discovery. */
interface HermesCloudClient {
    /** Start a device authorization; returns the code + verification URL to open. */
    suspend fun requestDeviceCode(portalOrigin: ServerOrigin): PortalDeviceCode

    /**
     * Poll the token endpoint until the user approves the [deviceCode], honoring
     * the server's `interval`/`slow_down` backoff and the code's expiry. Throws
     * [HermesCloudSignInRequiredException] on denial/expiry.
     */
    suspend fun awaitDeviceToken(
        portalOrigin: ServerOrigin,
        deviceCode: PortalDeviceCode,
    ): PortalTokenSet

    /**
     * Exchange a persisted refresh token for a fresh access token. The Portal
     * rotates refresh tokens with reuse detection, so the returned set's
     * [PortalTokenSet.refreshToken] MUST replace the stored one immediately.
     * Throws [HermesCloudSignInRequiredException] on a terminal grant rejection.
     */
    suspend fun refreshToken(
        portalOrigin: ServerOrigin,
        refreshToken: String,
    ): PortalTokenSet

    /**
     * Discover the Hermes Cloud agents visible to the signed-in user. Pass [org]
     * (a slug or id) to scope a multi-org account. A 401 surfaces as
     * [HermesCloudSignInRequiredException]; a 409 returns
     * [CloudDiscoverResult.NeedsOrgSelection].
     */
    suspend fun discoverAgents(
        portalOrigin: ServerOrigin,
        accessToken: String,
        org: String? = null,
    ): CloudDiscoverResult
}

class HttpHermesCloudClient(
    private val client: HttpClient,
    private val clientId: String = PORTAL_CLIENT_ID,
    private val scope: String = PORTAL_SCOPE,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : HermesCloudClient {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * CIO on Android intermittently throws [UnresolvedAddressException] /
     * [UnknownHostException] on the first request after the app is backgrounded
     * — exactly what happens when the sign-in browser opens over this flow. The
     * existing native token exchanger ([HttpHermesNativeAuthClient]) retries the
     * same class of transient DNS failure; mirror it so a blip on the token poll
     * or discovery call doesn't abort an otherwise-successful sign-in.
     */
    private suspend fun httpWithDnsRetry(
        url: String,
        method: HttpMethod,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                return client.request(url) {
                    this.method = method
                    block()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!error.isTransientNetworkFailure() || attempt >= MAX_DNS_ATTEMPTS) throw error
                delayMillis(DNS_RETRY_BASE_MILLIS * attempt)
            }
        }
    }

    private fun Throwable.isTransientNetworkFailure(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { it is UnknownHostException || it is UnresolvedAddressException }

    /**
     * Device-token polling is explicitly replayable until the RFC 8628 code's
     * deadline. Once the initial device-code request has established Portal TLS,
     * an I/O failure here means Android tore down the background response stream,
     * not that the user's authorization was rejected. Keep this broader policy
     * local to polling: refresh-token POSTs rotate credentials and must not be
     * replayed after an ambiguous response failure.
     */
    private fun Throwable.isTransientDevicePollFailure(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any { it is IOException || it is UnknownHostException || it is UnresolvedAddressException }

    override suspend fun requestDeviceCode(portalOrigin: ServerOrigin): PortalDeviceCode = try {
        val response = httpWithDnsRetry(
            "${portalOrigin.value}/api/oauth/device/code",
            HttpMethod.Post,
        ) {
            header("Accept", "application/json")
            setBody(
                TextContent(
                    formEncode(
                        "client_id" to clientId,
                        "scope" to scope,
                    ),
                    ContentType.Application.FormUrlEncoded,
                ),
            )
        }
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException(
                "Hermes Cloud device authorization returned HTTP ${response.status.value}",
            )
        }
        val root = json.parseToJsonElement(body).jsonObject
        val deviceCode = root.string("device_code")
        val userCode = root.string("user_code")
        val verificationUri = root.string("verification_uri")
        val verificationUriComplete = root["verification_uri_complete"]
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: verificationUri
        if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
            throw HermesConnectionException("Hermes Cloud device authorization was incomplete")
        }
        PortalDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = verificationUriComplete,
            expiresInSeconds = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 600L,
            intervalSeconds = (root["interval"]?.jsonPrimitive?.longOrNull ?: 5L)
                .coerceIn(1L, 30L),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException(
            "Could not start Hermes Cloud sign-in (${error.javaClass.simpleName})",
            error,
        )
    }

    override suspend fun awaitDeviceToken(
        portalOrigin: ServerOrigin,
        deviceCode: PortalDeviceCode,
    ): PortalTokenSet {
        var interval = deviceCode.intervalSeconds.coerceIn(1L, 30L)
        val deadline = nowSeconds() + deviceCode.expiresInSeconds.coerceAtLeast(1L)
        while (nowSeconds() < deadline) {
            delayMillis(interval * 1000L)
            // The user is approving in the browser, so this app is backgrounded
            // and Android may tear the network down: CIO then throws
            // UnresolvedAddressException/UnknownHostException. During a device
            // flow that is NOT terminal — it's the same "keep waiting" case as
            // authorization_pending. Swallow transient network failures and keep
            // polling until the code's own deadline, or the sign-in dies the
            // instant the browser steals focus.
            val (response, body) = try {
                val response = httpWithDnsRetry(
                    "${portalOrigin.value}/api/oauth/token",
                    HttpMethod.Post,
                ) {
                    header("Accept", "application/json")
                    setBody(
                        TextContent(
                            formEncode(
                                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                                "client_id" to clientId,
                                "device_code" to deviceCode.deviceCode,
                            ),
                            ContentType.Application.FormUrlEncoded,
                        ),
                    )
                }
                response to response.readBodyTextBounded()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error.isTransientDevicePollFailure()) continue
                throw error
            }
            if (response.status.isSuccess()) {
                return parseTokenBody(body)
            }
            when (errorCode(body)) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = (interval + 5L).coerceAtMost(30L)
                else -> throw HermesCloudSignInRequiredException(
                    "Hermes Cloud sign-in was not approved.",
                )
            }
        }
        throw HermesCloudSignInRequiredException(
            "Hermes Cloud sign-in timed out. Start again to get a new code.",
        )
    }

    override suspend fun refreshToken(
        portalOrigin: ServerOrigin,
        refreshToken: String,
    ): PortalTokenSet {
        val response = httpWithDnsRetry(
            "${portalOrigin.value}/api/oauth/token",
            HttpMethod.Post,
        ) {
            header("Accept", "application/json")
            header("x-nous-refresh-token", refreshToken)
            setBody(
                TextContent(
                    formEncode(
                        "grant_type" to "refresh_token",
                        "client_id" to clientId,
                    ),
                    ContentType.Application.FormUrlEncoded,
                ),
            )
        }
        val body = response.readBodyTextBounded()
        if (response.status.isSuccess()) {
            val refreshed = parseTokenBody(body)
            // The Portal rotates refresh tokens; carry the previous one forward
            // only if the response omitted a replacement.
            return if (refreshed.refreshToken.isBlank()) {
                refreshed.copy(refreshToken = refreshToken)
            } else {
                refreshed
            }
        }
        val code = errorCode(body)
        if (code in TERMINAL_REFRESH_ERRORS) {
            throw HermesCloudSignInRequiredException(
                "Your Hermes Cloud session expired. Sign in again.",
            )
        }
        throw HermesConnectionException(
            "Hermes Cloud token refresh returned HTTP ${response.status.value}",
        )
    }

    override suspend fun discoverAgents(
        portalOrigin: ServerOrigin,
        accessToken: String,
        org: String?,
    ): CloudDiscoverResult {
        val response = httpWithDnsRetry(
            "${portalOrigin.value}/api/agents",
            HttpMethod.Get,
        ) {
            header("Accept", "application/json")
            bearerAuth(accessToken)
            if (!org.isNullOrBlank()) parameter("org", org)
        }
        val body = response.readBodyTextBounded()
        if (response.status.isSuccess()) {
            return HermesCloudParsing.parseAgentsBody(body)
        }
        when (response.status.value) {
            401, 403 -> throw HermesCloudSignInRequiredException(
                "Your Hermes Cloud session expired. Sign in again.",
            )
            409 -> {
                val orgs = HermesCloudParsing.parseOrgSelection(body)
                if (orgs != null) return CloudDiscoverResult.NeedsOrgSelection(orgs)
            }
        }
        throw HermesConnectionException(
            "Could not load your Hermes Cloud agents (HTTP ${response.status.value})",
        )
    }

    private fun parseTokenBody(body: String): PortalTokenSet {
        val root = json.parseToJsonElement(body).jsonObject
        val accessToken = root.string("access_token")
        if (accessToken.isBlank()) {
            throw HermesConnectionException("Hermes Cloud token response was incomplete")
        }
        val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull
        return PortalTokenSet(
            accessToken = accessToken,
            refreshToken = root.string("refresh_token"),
            tokenType = root["token_type"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: "Bearer",
            scope = root["scope"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: scope,
            expiresAt = if (expiresIn != null && expiresIn > 0) nowSeconds() + expiresIn else 0L,
        )
    }

    private fun errorCode(body: String): String? =
        runCatching { (json.parseToJsonElement(body) as? JsonObject)?.string("error") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

    private fun formEncode(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${k.encodeFormComponent()}=${v.encodeFormComponent()}"
        }

    private fun String.encodeFormComponent(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        const val PORTAL_CLIENT_ID = "hermes-cli"
        const val PORTAL_SCOPE = "inference:invoke"
        private const val MAX_DNS_ATTEMPTS = 3
        private const val DNS_RETRY_BASE_MILLIS = 500L
        private val TERMINAL_REFRESH_ERRORS = setOf(
            "invalid_grant",
            "invalid_token",
            "refresh_token_reused",
        )
    }
}
