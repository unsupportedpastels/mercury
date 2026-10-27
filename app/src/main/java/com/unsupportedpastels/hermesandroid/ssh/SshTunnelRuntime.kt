package com.unsupportedpastels.hermesandroid.ssh

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped home for the tunnel's background work.
 *
 * A tunnel is not a screen: Android clears a ViewModel every time an Activity is recreated
 * (navigating into the session list is enough), and a tunnel whose keep-alive socket and
 * watchdog live in that scope is torn down seconds after it is established — the host log
 * shows a WebSocket accepted and dropped 0.1-0.5s later with nothing recorded on the client
 * side. The desktop client has this property for free because its tunnel is a detached
 * process; this object plus the foreground service are the Android equivalent.
 *
 * Jobs are cancelled only on an explicit release (Disconnect) or process death.
 */
internal object SshTunnelRuntime {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val keepAliveClient: HttpClient by lazy {
        HttpClient(CIO) { install(WebSockets) }
    }

    private var autoRestoreClaimed = false
    private var userDisconnected = false

    /**
     * True at most once per process, and never after a deliberate Disconnect.
     *
     * A ViewModel is recreated whenever the Activity is, so "restore on launch" has to be
     * claimed by the process, not by the screen — otherwise the tunnel would be rebuilt on every
     * navigation.
     */
    @Synchronized
    fun claimAutoRestore(): Boolean {
        if (autoRestoreClaimed || userDisconnected) return false
        autoRestoreClaimed = true
        return true
    }

    @Synchronized
    fun markUserDisconnected() {
        userDisconnected = true
    }

    @Synchronized
    fun hasUserDisconnected(): Boolean = userDisconnected
}