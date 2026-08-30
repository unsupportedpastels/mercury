package com.unsupportedpastels.hermesandroid.connection

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.net.URLDecoder
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object NativeOAuth {
    fun s256Challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizationUrl(
        serverOrigin: ServerOrigin,
        provider: String,
        challenge: String,
        redirectUri: String,
        state: String,
    ): Url = URLBuilder("${serverOrigin.value}/auth/native/authorize").apply {
        parameters.append("provider", provider)
        parameters.append("code_challenge", challenge)
        parameters.append("code_challenge_method", "S256")
        parameters.append("redirect_uri", redirectUri)
        parameters.append("state", state)
    }.build()

    fun parseCallback(requestTarget: String, expectedState: String): String {
        val callback = validateCallback(requestTarget, CALLBACK_PATH, expectedState)
            ?: throw HermesConnectionException("Hermes sign-in callback was malformed")
        if (callback.error != null) {
            throw HermesConnectionException("Hermes sign-in was rejected")
        }
        return requireNotNull(callback.code)
    }

    internal data class ValidCallback(val code: String?, val error: String?)

    internal fun validateCallback(
        requestTarget: String,
        expectedPath: String,
        expectedState: String,
    ): ValidCallback? {
        if (expectedState.isBlank() || !requestTarget.startsWith('/')) return null
        val uri = runCatching { URI(requestTarget) }.getOrNull() ?: return null
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null) return null
        if (uri.rawPath != expectedPath || uri.rawQuery.isNullOrEmpty()) return null

        val parameters = parseQuery(uri.rawQuery) ?: return null
        if (parameters["state"] != expectedState) return null

        val code = parameters["code"]
        val error = parameters["error"]
        if ((code == null) == (error == null)) return null
        val expectedKeys = if (code != null) {
            setOf("code", "state")
        } else {
            setOf("error", "state")
        }
        if (parameters.keys != expectedKeys) return null
        if (code != null && code.isBlank()) return null
        if (error != null && error.isBlank()) return null
        return ValidCallback(code = code, error = error)
    }

    private fun parseQuery(rawQuery: String): Map<String, String>? {
        val parameters = linkedMapOf<String, String>()
        for (component in rawQuery.split('&')) {
            val separator = component.indexOf('=')
            if (separator <= 0) return null
            val key = decodeQueryComponent(component.substring(0, separator)) ?: return null
            val value = decodeQueryComponent(component.substring(separator + 1)) ?: return null
            if (key.isBlank() || parameters.put(key, value) != null) return null
        }
        return parameters
    }

    private fun decodeQueryComponent(value: String): String? = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrNull()

    internal const val CALLBACK_PATH = "/callback"
}

internal fun readBoundedRequestLine(
    input: InputStream,
    maxBytes: Int,
    deadlineNanos: Long,
    nanoTime: () -> Long = System::nanoTime,
): String? {
    val bytes = ByteArray(maxBytes)
    var count = 0
    while (count < maxBytes) {
        if (nanoTime() >= deadlineNanos) return null
        val next = input.read()
        if (nanoTime() > deadlineNanos) return null
        if (next == -1) return null
        if (next == '\r'.code) {
            if (nanoTime() >= deadlineNanos || input.read() != '\n'.code) return null
            if (nanoTime() > deadlineNanos) return null
            return String(bytes, 0, count, StandardCharsets.US_ASCII)
        }
        if (next == '\n'.code || next < 0x20 || next > 0x7e) return null
        bytes[count++] = next.toByte()
    }
    return null
}

@Serializable
data class NativeTokenSet(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0,
    val provider: String = "",
    @SerialName("user_id") val userId: String = "",
) {
    /** Redacted like every other credential type in the connection layer. */
    override fun toString(): String = "NativeTokenSet(provider=$provider, ***)"
}

@Serializable
private data class NativeTokenRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
private data class NativeTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_at") val expiresAt: Long,
    val provider: String,
    @SerialName("user_id") val userId: String,
)

interface NativeTokenExchanger {
    suspend fun exchange(
        serverOrigin: ServerOrigin,
        code: String,
        verifier: String,
    ): NativeTokenSet
}

class HttpHermesNativeAuthClient(
    private val client: HttpClient,
) : NativeTokenExchanger {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun exchange(
        serverOrigin: ServerOrigin,
        code: String,
        verifier: String,
    ): NativeTokenSet = try {
        val payload = json.encodeToString(NativeTokenRequest(code, verifier))
        var attempt = 0
        var response: HttpResponse? = null
        while (response == null) {
            attempt += 1
            try {
                response = client.post("${serverOrigin.value}/auth/native/token") {
                    setBody(TextContent(payload, ContentType.Application.Json))
                }
            } catch (error: Exception) {
                val transientDnsFailure = generateSequence<Throwable>(error) { it.cause }
                    .any { it is UnknownHostException || it is UnresolvedAddressException }
                if (!transientDnsFailure || attempt >= 3) throw error
                delay(500L * attempt)
            }
        }
        val responseBody = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException(
                "Hermes token exchange returned HTTP ${response.status.value}",
            )
        }
        val responseTokens = json.decodeFromString<NativeTokenResponse>(responseBody)
        val tokens = NativeTokenSet(
            accessToken = responseTokens.accessToken,
            refreshToken = responseTokens.refreshToken,
            expiresAt = responseTokens.expiresAt,
            provider = responseTokens.provider,
            userId = responseTokens.userId,
        )
        if (tokens.accessToken.isBlank() ||
            tokens.provider.isBlank() ||
            tokens.userId.isBlank() ||
            tokens.expiresAt <= 0
        ) {
            throw HermesConnectionException("Hermes token response was incomplete")
        }
        tokens
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException(
            "Hermes token exchange failed (${error.javaClass.simpleName})",
            error,
        )
    }
}

interface NativeLogin {
    suspend fun signIn(
        serverOrigin: ServerOrigin,
        provider: String,
        openBrowser: suspend (String) -> Unit,
    ): NativeTokenSet
}

class HermesNativeLogin(
    private val exchanger: NativeTokenExchanger,
    private val randomBytes: (Int) -> ByteArray = { size ->
        ByteArray(size).also(SecureRandom()::nextBytes)
    },
    private val awaitExchangeReady: suspend () -> Unit = {},
) : NativeLogin {
    private companion object {
        const val CALLBACK_ACCEPT_POLL_MILLIS = 250
        const val CALLBACK_READ_TIMEOUT_MILLIS = 1_000
        const val CALLBACK_REQUEST_DEADLINE_MILLIS = 3_000
        const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        const val BAD_REQUEST_BODY = "<!doctype html><title>Bad Request</title>"
        const val SIGN_IN_FAILED_BODY = """
            <!doctype html><meta charset="utf-8"><title>Sign-in failed</title>
            <meta name="referrer" content="no-referrer">
            <script>history.replaceState(null,"","/complete")</script>
            <body><h2>Hermes sign-in failed</h2>
            <p>Return to the app and try again.</p></body>
        """
        const val RETURN_TO_APP_BODY = """
            <!doctype html><meta charset="utf-8"><title>Continue in Hermes</title>
            <meta name="referrer" content="no-referrer">
            <script>history.replaceState(null,"","/complete")</script>
            <body><h2>Return to Hermes</h2>
            <p>Sign-in will finish securely in the app.</p></body>
        """
    }

    override suspend fun signIn(
        serverOrigin: ServerOrigin,
        provider: String,
        openBrowser: suspend (String) -> Unit,
    ): NativeTokenSet = withTimeout(5 * 60 * 1_000L) {
        withContext(Dispatchers.IO) {
            ServerSocket(
                0,
                1,
                InetAddress.getByName("127.0.0.1"),
            ).use { serverSocket ->
                serverSocket.soTimeout = CALLBACK_ACCEPT_POLL_MILLIS
                val verifier = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(randomBytes(32))
                val challenge = NativeOAuth.s256Challenge(verifier)
                val state = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(randomBytes(24))
                val redirectUri = "http://127.0.0.1:${serverSocket.localPort}/callback"
                val authorizeUrl = NativeOAuth.authorizationUrl(
                    serverOrigin = serverOrigin,
                    provider = provider,
                    challenge = challenge,
                    redirectUri = redirectUri,
                    state = state,
                ).toString()

                coroutineScope {
                    val browserJob = launch { openBrowser(authorizeUrl) }
                    val code = awaitCallback(serverSocket, state)
                    awaitExchangeReady()
                    val tokens = exchanger.exchange(serverOrigin, code, verifier)
                    browserJob.join()
                    tokens
                }
            }
        }
    }

    private suspend fun awaitCallback(
        serverSocket: ServerSocket,
        expectedState: String,
    ): String {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                runInterruptible(Dispatchers.IO) { serverSocket.accept() }
            } catch (_: SocketTimeoutException) {
                continue
            }
            try {
                socket.use {
                    it.soTimeout = CALLBACK_READ_TIMEOUT_MILLIS
                    val requestLine = try {
                        runInterruptible(Dispatchers.IO) {
                            readBoundedRequestLine(
                                input = it.inputStream,
                                maxBytes = MAX_REQUEST_LINE_BYTES,
                                deadlineNanos = System.nanoTime() +
                                    TimeUnit.MILLISECONDS.toNanos(
                                        CALLBACK_REQUEST_DEADLINE_MILLIS.toLong(),
                                    ),
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        null
                    }
                    val callback = requestLine
                        ?.let { line -> parseCallbackRequestLine(line, expectedState) }
                    if (callback == null) {
                        writeHttpResponse(it, "400 Bad Request", BAD_REQUEST_BODY)
                        continue
                    }
                    if (callback.error != null) {
                        runCatching {
                            writeHttpResponse(it, "400 Bad Request", SIGN_IN_FAILED_BODY)
                        }
                        throw HermesConnectionException("Hermes sign-in was rejected")
                    }
                    runCatching { writeHttpResponse(it, "200 OK", RETURN_TO_APP_BODY) }
                    return requireNotNull(callback.code)
                }
            } catch (_: IOException) {
                // A client that disconnects or times out cannot consume the valid callback.
            }
        }
    }

    private fun parseCallbackRequestLine(
        requestLine: String,
        expectedState: String,
    ): NativeOAuth.ValidCallback? {
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts.any(String::isBlank)) return null
        if (parts[0] != "GET") return null
        if (parts[2] != "HTTP/1.0" && parts[2] != "HTTP/1.1") return null
        return NativeOAuth.validateCallback(parts[1], "/callback", expectedState)
    }


    private fun writeHttpResponse(socket: Socket, status: String, body: String) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val responseHeaders = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        socket.getOutputStream().apply {
            write(responseHeaders)
            write(bodyBytes)
            flush()
        }
    }

}
