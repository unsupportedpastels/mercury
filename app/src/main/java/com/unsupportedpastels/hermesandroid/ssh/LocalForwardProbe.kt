package com.unsupportedpastels.hermesandroid.ssh

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Answers "does the local forward still work?" for a tunnel owned by this process.
 *
 * A frozen or killed app closes the forwarded port while the SSH session object can still claim to be
 * connected, so asking the transport is not enough: the only reliable witness is the port itself.
 * Refused answers at once; a stalled stack gives up after [timeoutMillis] rather than hanging a resume.
 */
internal fun localForwardAnswers(port: Int?, timeoutMillis: Int): Boolean {
    if (port == null || port <= 0) return false
    return runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMillis)
            true
        }
    }.getOrDefault(false)
}
