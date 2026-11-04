package com.unsupportedpastels.hermesandroid.ssh

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The status an authenticated endpoint returns through the tunnel: the client-side truth. */
internal suspend fun authenticatedStatusOf(
    localPort: Int,
    token: String,
    path: String,
    timeoutMillis: Int = 5_000,
): String {
    if (token.isBlank() || localPort <= 0) return "no-token"
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("http://127.0.0.1:$localPort$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
            }
            try {
                connection.responseCode.toString()
            } finally {
                connection.disconnect()
            }
        }.getOrDefault("unreachable")
    }
}

/**
 * Does the backend behind `localPort` accept this session token?
 *
 * A reused SSH backend keeps the session token it started with, so the token the
 * lifecycle adopts has to be the one that backend actually serves. When the adoption
 * read fails it falls back to a freshly minted token, and a client that then presents
 * that token gets `Hermes session listing returned HTTP 401` — a failure that looks
 * like bad credentials but is really "we never knew the right token".
 *
 * Asking `/api/auth/me` through the freshly opened forward turns that into a fact the
 * connect flow can act on (re-adopt, or fail loudly) before any origin is registered.
 * Measured against a spawned backend: no credential → 401, correct token → 200.
 */
internal suspend fun backendAcceptsSessionToken(
    localPort: Int,
    token: String,
    timeoutMillis: Int = 5_000,
): Boolean {
    if (token.isBlank() || localPort <= 0) return false
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("http://127.0.0.1:$localPort/api/auth/me").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
            }
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}