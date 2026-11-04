package ai.hermes.ssh.android

import ai.hermes.ssh.Clock
import ai.hermes.ssh.HttpFetcher
import ai.hermes.ssh.PortPicker
import ai.hermes.ssh.RandomSource
import ai.hermes.ssh.Sleeper
import ai.hermes.ssh.TokenHasher
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Platform services for the lifecycle on Android.
 *
 * Everything here uses JDK APIs only — deliberately, so the whole module is
 * compilable and testable on a plain JVM (see tools/ssh-lifecycle-verify).
 * Notes for app wiring:
 *  - the lifecycle's calls are blocking; run `connect`/`disconnect` on a
 *    background dispatcher (Dispatchers.IO) from the ViewModel layer;
 *  - `AndroidHttpFetcher` is only used for the 3-second, loopback-only
 *    dashboard-index read that yields the session token; swapping it for the
 *    app's Ktor client is straightforward (interface is one method).
 */

class AndroidClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class AndroidRandom(private val secureRandom: SecureRandom = SecureRandom()) : RandomSource {
    override fun hex(bytes: Int): String {
        val buf = ByteArray(bytes)
        secureRandom.nextBytes(buf)
        return buf.joinToString("") { b -> "%02x".format(b) }
    }
}

class AndroidHasher : TokenHasher {
    override fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }
}

class AndroidPortPicker(private val preferred: Int? = null) : PortPicker {
    override fun pick(): Int {
        // Reuse the port the previous tunnel used whenever it is still free: the local origin then
        // stays identical across rebuilds, so a saved server entry never goes stale. Falls back to
        // an ephemeral port when something else has taken it.
        val candidate = preferred?.takeIf { it in MIN_PORT..MAX_PORT }
        if (candidate != null && isFree(candidate)) return candidate
        return ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    private fun isFree(port: Int): Boolean = try {
        ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65_535
    }
}

class AndroidSleeper : Sleeper {
    override fun sleep(millis: Long) = Thread.sleep(millis)
}

class AndroidHttpFetcher : HttpFetcher {
    override fun getText(url: String, timeoutMs: Int): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = "GET"
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) error("$code: $body")
            body
        } finally {
            conn.disconnect()
        }
    }
}