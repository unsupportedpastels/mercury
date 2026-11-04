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

    private var releaseHook: (suspend () -> Unit)? = null

    /**
     * Called when a tunnel that had to be rebuilt is ready again, with the origin it serves.
     *
     * A rebuild re-forwards the same local port and mints a fresh backend token, so the connection
     * layer keeps its origin but its live sockets are dead. Without this nudge the app stayed
     * `Disconnected` until the user backgrounded and reopened it (the only other reconnector),
     * which is what made a brief network blip look like a permanent outage.
     */
    private var restoredHook: ((String) -> Unit)? = null

    /**
     * Called when the connection layer's bearer was rejected by the backend it points at.
     *
     * A tunnel token is minted by the host's backend, not by the user: a relaunched backend — a host
     * reboot respawns it with a one-time token file — serves a different one, so a 401 means "the
     * backend this token came from is gone", not "sign in again". A tunnel origin has no interactive
     * sign-in to offer (`auth_required=false`, no providers), so the only exit is to re-establish the
     * tunnel and re-adopt whatever the current backend serves. The connection layer has no SSH
     * transport of its own, which is why it asks through this runtime.
     */
    private var tokenRejectedHook: ((String) -> Unit)? = null

    @Synchronized
    fun registerTokenReAdopt(block: ((String) -> Unit)?) {
        tokenRejectedHook = block
    }

    fun notifyTokenRejected(origin: String) {
        val hook = synchronized(this) { tokenRejectedHook }
        if (hook != null) runCatching { hook.invoke(origin) }
    }

    @Synchronized
    fun registerTunnelRestored(block: ((String) -> Unit)?) {
        restoredHook = block
    }

    fun notifyTunnelRestored(origin: String) {
        val hook = synchronized(this) { restoredHook }
        if (hook != null) runCatching { hook.invoke(origin) }
    }

    /**
     * Registers what an explicit process-level release must do: kill the backend this app spawned on
     * the host and remove its ownership directory, rather than leaving it to retire on its own.
     *
     * The connection layer registers this once a tunnel is ready and clears it on Disconnect; the
     * tunnel service runs it when the task is removed. Process death without it (a crash, a kill from
     * another app) still leaves no tunnel, only the host backend, which retires by itself.
     */
    @Synchronized
    fun registerRelease(block: suspend () -> Unit) {
        releaseHook = block
    }

    @Synchronized
    fun clearRelease() {
        releaseHook = null
    }

    /** Runs whatever was registered, at most once. Safe to call with nothing registered. */
    suspend fun runRelease() {
        val hook = synchronized(this) { releaseHook.also { releaseHook = null } }
        if (hook != null) runCatching { hook.invoke() }
    }
}