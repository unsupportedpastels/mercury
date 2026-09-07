package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface NativeRefreshClient {
    suspend fun refresh(
        serverOrigin: ServerOrigin,
        refreshToken: String,
        provider: String,
    ): NativeTokenSet
}

open class NativeRefreshException(message: String) : Exception(message)

class NativeRefreshExpiredException : NativeRefreshException(
    "Hermes native refresh token expired",
)

class NativeRefreshTransientException : NativeRefreshException(
    "Hermes native refresh provider is temporarily unavailable",
)

private const val MAX_REFRESH_FIELD_BYTES = 16 * 1024

@Serializable
private data class NativeRefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    val provider: String,
)

@Serializable
private data class NativeRefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    val provider: String,
    @SerialName("user_id") val userId: String,
)

class HttpHermesNativeRefreshClient(
    private val client: HttpClient,
) : NativeRefreshClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun refresh(
        serverOrigin: ServerOrigin,
        refreshToken: String,
        provider: String,
    ): NativeTokenSet = try {
        requireBoundedField(refreshToken)
        requireBoundedField(provider)
        require(refreshToken.isNotBlank() && provider.isNotBlank()) {
            "Native refresh request was incomplete"
        }
        val requestBody = json.encodeToString(
            NativeRefreshRequest(refreshToken = refreshToken, provider = provider),
        )
        val response = client.post("${serverOrigin.value}/auth/native/refresh") {
            setBody(TextContent(requestBody, ContentType.Application.Json))
        }
        val responseBody = response.readBodyTextBounded()
        when {
            response.status.value == 401 -> throw NativeRefreshExpiredException()
            response.status.value == 503 -> throw NativeRefreshTransientException()
            !response.status.isSuccess() -> throw NativeRefreshException(
                "Hermes native refresh returned HTTP ${response.status.value}",
            )
        }

        val decoded = json.decodeFromString<NativeRefreshResponse>(responseBody)
        val tokens = NativeTokenSet(
            accessToken = decoded.accessToken,
            refreshToken = decoded.refreshToken,
            expiresAt = decoded.expiresAt,
            provider = decoded.provider,
            userId = decoded.userId,
        )
        validate(tokens)
        tokens
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: NativeRefreshException) {
        throw error
    } catch (_: Exception) {
        throw NativeRefreshException("Hermes native token refresh failed")
    }

    private fun validate(tokens: NativeTokenSet) {
        require(tokens.accessToken.isNotBlank())
        require(tokens.refreshToken.isNotBlank())
        require(tokens.provider.isNotBlank())
        require(tokens.userId.isNotBlank())
        require(tokens.expiresAt > 0)
        requireBoundedField(tokens.accessToken)
        requireBoundedField(tokens.refreshToken)
        requireBoundedField(tokens.provider)
        requireBoundedField(tokens.userId)
    }

    private fun requireBoundedField(value: String) {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_REFRESH_FIELD_BYTES)
    }
}
