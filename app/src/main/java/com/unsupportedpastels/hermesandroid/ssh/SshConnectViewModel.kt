package com.unsupportedpastels.hermesandroid.ssh

import ai.hermes.ssh.BackendConnection
import ai.hermes.ssh.SshBackendLifecycle
import ai.hermes.ssh.SshTarget
import ai.hermes.ssh.SshTransport
import ai.hermes.ssh.android.AndroidClock
import ai.hermes.ssh.android.AndroidHasher
import ai.hermes.ssh.android.AndroidHttpFetcher
import ai.hermes.ssh.android.AndroidPortPicker
import ai.hermes.ssh.android.AndroidRandom
import ai.hermes.ssh.android.AndroidSleeper
import ai.hermes.ssh.android.JschSshTransport
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unsupportedpastels.hermesandroid.connection.EncryptedNativeTokenStore
import com.unsupportedpastels.hermesandroid.connection.NativeTokenSet
import com.unsupportedpastels.hermesandroid.connection.SSH_SESSION_TOKEN_PROVIDER
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the SSH remote-backend lifecycle and hands the result to the *existing*
 * direct-connection path.
 *
 * Nothing about the app's REST/WebSocket stack changes: once the tunnel is up
 * the remote backend is reachable at `http://127.0.0.1:<localPort>`, so this
 * registers that origin (with the session token the backend actually serves)
 * exactly like a manually entered server, and the reactive connection flow in
 * [com.unsupportedpastels.hermesandroid.connection.HermesConnectionViewModel]
 * takes over — probe, ticket, chat, everything.
 *
 * The bundled workflow mirrors Hermes Desktop's SSH connection kind: open one
 * multiplexed SSH connection, spawn a detached `hermes serve --isolated` on the
 * remote host with an ownership token, forward its port to loopback, adopt the
 * served token, and tear down only the backend we can prove is ours.
 */
class SshConnectViewModel(
    private val appContext: Context,
    private val saveOrigin: suspend (ServerCatalogEntry) -> Result<Unit>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SshConnectState())
    val state: StateFlow<SshConnectState> = _state.asStateFlow()

    private val keyStore = EncryptedSshKeyStore(appContext)

    init {
        // Restarting the app should not cost the user a manual rebuild. A private-key target
        // restores itself; a password target is only pre-filled, because passwords are never
        // stored. Claimed by the process, so a navigation that recreates this ViewModel does not
        // rebuild the tunnel again, and a deliberate Disconnect is never undone.
        // The target itself is primed unconditionally: `claimAutoRestore()` answers true only once
        // per process, so a second entry into the app skipped the restore AND left the screen with
        // an empty target field and a disabled Connect button.
        viewModelScope.launch { primeRememberedTarget() }
        if (SshTunnelRuntime.claimAutoRestore()) {
            viewModelScope.launch { restoreOnLaunch() }
        } else {
            // The claim answers true only once per process, so a second entry into the app used to
            // do nothing at all: no tunnel, no attempt, and the user had to find "Rebuild tunnel".
            // One rebuild attempt here reuses a surviving backend and stays silent after a
            // deliberate Disconnect.
            viewModelScope.launch { restoreOnReentry() }
        }
        // The connection layer can hold a bearer this tunnel no longer serves — the host rebooted and
        // its backend was respawned with a new one-time token — and only this layer can read the new
        // one. Answer that request by re-establishing, which re-reads the token the backend serves and
        // announces the result to the connection layer.
        SshTunnelRuntime.registerTokenReAdopt { origin ->
            if (!servesOrigin(origin) || _state.value.phase == SshConnectPhase.Connecting) {
                return@registerTokenReAdopt
            }
            viewModelScope.launch { rebuildIfNeeded(force = true) }
        }
        watchForNetworks()
    }

    /** Single rebuild attempt for an app entry that the launch restore already claimed. */
    private suspend fun restoreOnReentry() {
        if (SshTunnelRuntime.hasUserDisconnected() || connection != null) return
        runCatching { rebuildIfNeeded() }
    }

    /** Fill the screen's target from the stored profile, without connecting to it. */
    private suspend fun primeRememberedTarget() {
        if (_state.value.target.isNotBlank()) return
        val remembered = withContext(io) { runCatching { keyStore.lastTarget() }.getOrNull() } ?: return
        val (target, mode) = remembered
        if (target.isBlank() || _state.value.target.isNotBlank()) return
        _state.value = _state.value.copy(
            target = target,
            detail = if (mode != SshAuthMode.PrivateKey.name) {
                "Tap Connect to restore the tunnel to $target (your password is never stored)."
            } else {
                _state.value.detail
            },
        )
    }

    /** Re-armed by every new network, so leaving 5G for the host's Wi-Fi heals the tunnel. */
    private var connectivityManager: ConnectivityManager? = null

    private fun watchForNetworks() {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SshTunnelRuntime.scope.launch { retryRestoreOnce() }
            }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
            connectivityManager = manager
            networkCallback = callback
        }
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * One extra attempt after a network appears. The launch restore's backoff covers the first
     * minute and a half; this covers the phone walking back onto the host's Wi-Fi later, which no
     * amount of up-front retrying can anticipate.
     */
    private suspend fun retryRestoreOnce() {
        if (SshTunnelRuntime.hasUserDisconnected() || connection != null) return
        val remembered = withContext(io) { runCatching { keyStore.lastTarget() }.getOrNull() } ?: return
        val (target, mode) = remembered
        if (mode != SshAuthMode.PrivateKey.name) return
        // Same trap as the launch restore: connect() refuses to run while the phase is Connecting,
        // so the phase is left for connect() itself to set.
        _state.value = _state.value.copy(
            phase = SshConnectPhase.Idle,
            detail = "Network available — restoring the tunnel to $target…",
            error = null,
        )
        runCatching { rebuildIfNeeded() }
    }

    /** True when this device already holds a key for [targetInput] (encrypted at rest). */
    suspend fun hasStoredKey(targetInput: String): Boolean = runCatching {
        val target = SshTarget.parse(targetInput)
        keyStore.load(keyId(target)) != null
    }.getOrDefault(false)

    /** Drops the remembered key for [targetInput]. */
    suspend fun forgetKey(targetInput: String) {
        runCatching { keyStore.clear(keyId(SshTarget.parse(targetInput))) }
    }

    private var transport: SshTransport? = null

    /** Serialises connect attempts so two of them cannot both replace the same transport. */
    /** Connect timeout for the local-forward probe: refused answers instantly, a stall must not. */
    private val LOCAL_FORWARD_PROBE_TIMEOUT_MS = 800

    private val establishLock = kotlinx.coroutines.sync.Mutex()
    private var lifecycle: SshBackendLifecycle? = null
    private var connection: BackendConnection? = null

    /** Watches the tunnel and its token while the app is in the foreground. */
    private var tunnelWatch: kotlinx.coroutines.Job? = null

    /** Holds the ownership WebSocket that stops a spawned backend from retiring itself. */
    private var keepAliveJob: kotlinx.coroutines.Job? = null

    /**
     * Keep a WebSocket open against the tunnel, as the desktop client does
     * (`ssh-isolated-keepalive.ts`).
     *
     * The spawned backend treats an accepted WebSocket as the ownership liveness signal, so a
     * backend nobody is connected to retires itself (`web_server_idle_exit`) while the phone
     * still believes it is connected — the forward then points at a port nobody listens on and
     * the app reports "Could not reach Hermes Serve — Connection refused" minutes after a
     * successful connect. Sticky spawn artifacts (owner nonce, token file, lockfile) are
     * deliberately *not* liveness, so holding this socket is the only way to say "still here".
     *
     * Reconnects with capped exponential backoff; while it is down the backend may retire, and
     * the tunnel watch rebuilds from that state.
     */
    private fun startKeepAliveSocket(localPort: Int, token: String, ownershipId: String?) {
        keepAliveJob?.cancel()
        if (token.isBlank()) return
        keepAliveJob = SshTunnelRuntime.scope.launch {
            var failures = 0
            while (true) {
                var openedAt = 0L
                try {
                    SshTunnelRuntime.keepAliveClient.webSocket("ws://127.0.0.1:$localPort/api/ws?token=$token") {
                        failures = 0
                        openedAt = System.currentTimeMillis()
                        reportToHost(
                            ownershipId = ownershipId,
                            transport = transport,
                            outcome = "keepalive=up port=$localPort ${SshTunnelService.lastStatus}",
                        )
                        // Holding the socket is the whole point; frames are irrelevant.
                        incoming.receiveAsFlow().collect { }
                    }
                    reportToHost(ownershipId, transport, "keepalive=closed after=${secondsSince(openedAt)}s")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += 1
                    // Why the socket died, and how long it lived, is the evidence that tells a
                    // freeze apart from a network drop or a server-side close.
                    val droppedLine = "keepalive=dropped after=${secondsSince(openedAt)}s " +
                        "err=${error.message ?: error::class.simpleName} ${SshTunnelService.lastStatus}"
                    reportToHost(ownershipId = ownershipId, transport = transport, outcome = droppedLine)
                }
                val backoff = KEEP_ALIVE_BACKOFF_MS shl minOf(failures, 4)
                kotlinx.coroutines.delay(minOf(backoff, KEEP_ALIVE_MAX_BACKOFF_MS))
            }
        }
    }

    private fun secondsSince(startMillis: Long): Long =
        if (startMillis == 0L) 0L else (System.currentTimeMillis() - startMillis) / 1_000L

    /** Report the connection layer's own view of the world, so its 401 text reaches the host. */
    fun reportConnectionState(text: String) {
        SshTunnelRuntime.scope.launch {
            reportToHost(lastOwnershipId, transport, "client-state=$text")
        }
    }

    /** The ownership directory of the most recent connect, for the host-side report. */
    private var lastOwnershipId: String? = null

    private val appVersion: String by lazy {
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    /**
     * Leave a one-line report in the ownership directory of the machine that runs the
     * backend.
     *
     * Everything that went wrong here was only observable from the phone: which build was
     * installed, which origin it registered, and whether the connection layer could read
     * the token back. Writing it where the host can read it removes the screenshot round
     * trip — and it is recorded for failures too, which is exactly when it is needed.
     */
    private suspend fun reportToHost(ownershipId: String?, transport: SshTransport?, outcome: String) {
        if (ownershipId.isNullOrBlank() || transport == null) return
        runCatching {
            val directory = ai.hermes.ssh.RemoteLayout.ownershipDirectory(ownershipId)
            val line = "app=$appVersion $outcome".replace('\n', ' ')
            val quoted = "'" + line.replace("'", "'\\''") + "'"
            transport.exec(
                "mkdir -p $directory && printf '%s\\n' $quoted >> $directory/client-report.txt",
            )
        }
    }

    fun connect(targetInput: String, credentials: SshCredentials) {
        if (_state.value.phase == SshConnectPhase.Connecting) return
        _state.value = SshConnectState(phase = SshConnectPhase.Connecting, target = targetInput, detail = "Reading target…")
        viewModelScope.launch {
            try {
                withContext(io) { establish(targetInput, credentials) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    phase = SshConnectPhase.Failed,
                    detail = "",
                    error = error.message ?: error.toString(),
                )
                withContext(io) {
                    reportToHost(
                        ownershipId = lastOwnershipId,
                        transport = transport,
                        outcome = "failed: ${error.message ?: error.toString()}",
                    )
                    teardown(closeMaster = true)
                }
                SshTunnelService.stop(appContext)
            }
        }
    }

    private suspend fun establish(targetInput: String, credentials: SshCredentials) {
        // One attempt at a time: the watch, the launch restore and a user tap all call this, and two
        // concurrent runs used to close the same session and each build a new one.
        if (!establishLock.tryLock()) return
        try {
            establishLocked(targetInput, credentials)
        } finally {
            establishLock.unlock()
        }
    }

    private suspend fun establishLocked(targetInput: String, credentials: SshCredentials) {
        // Replace, never stack. A transport used to be overwritten without being closed, so every
        // rebuild (the 15 s watch retries while a network flaps) left a live sshd session behind: one
        // phone accumulated 52 of them plus 107 sshd processes, and once sshd was that loaded, new
        // handshakes timed out. Closing first also frees the forwarded local port, which keeps the
        // origin stable and lets a rebuilt tunnel take the preserve path.
        withContext(io) { runCatching { transport?.exitMaster() } }
        transport = null
        val target = SshTarget.parse(targetInput)
        // The local port of the last successful tunnel, reused when it is still free: keeping the
        // origin stable is what stops a rebuild from adding another server entry.
        val preferredLocalPort = withContext(io) {
            runCatching { keyStore.lastLocalPort() }.getOrDefault(0)
        }
        require(!target.user.isNullOrBlank()) { "Use user@host — the SSH account on the remote machine." }

        val knownHosts = File(appContext.filesDir, "ssh_known_hosts").absolutePath
        val id = keyId(target)
        val remembered = if (credentials.mode == SshAuthMode.PrivateKey) keyStore.load(id) else null

        val ssh = when (credentials.mode) {
            SshAuthMode.Password -> {
                val password = credentials.password
                require(password.isNotBlank()) { "Enter the SSH password, or switch to key authentication." }
                JschSshTransport(target, knownHosts, password = password)
            }

            SshAuthMode.PrivateKey -> {
                // A key typed or picked now wins; otherwise fall back to the one
                // this device already holds for the target.
                val pem = credentials.privateKeyPem.trim().ifBlank { remembered?.privateKey.orEmpty() }
                require(pem.isNotBlank()) {
                    "Paste the private key, pick the key file, or switch to password authentication."
                }
                val passphrase = credentials.passphrase.ifBlank { remembered?.passphrase.orEmpty() }
                JschSshTransport(
                    target = target,
                    knownHostsFile = knownHosts,
                    privateKeyPem = pem.toByteArray(),
                    passphrase = passphrase.takeIf(String::isNotEmpty)?.toByteArray(),
                )
            }
        }
        // Own it from here on: a failure between this point and a successful connect used to leave
        // the session outside `transport`, so the failure path had nothing to close.
        transport = ssh
        val hasher = AndroidHasher()
        val random = AndroidRandom()
        val backend = SshBackendLifecycle(
            target = target,
            transport = ssh,
            portPicker = AndroidPortPicker(preferred = preferredLocalPort),
            http = AndroidHttpFetcher(),
            clock = AndroidClock(),
            random = random,
            hasher = hasher,
            sleeper = AndroidSleeper(),
        ) { line -> _state.value = _state.value.copy(detail = line.substringAfter("] ", line)) }

        _state.value = _state.value.copy(detail = "Opening SSH connection…")
        ssh.openMaster()

        // Only now that the credentials authenticate is the key worth remembering.
        if (credentials.mode == SshAuthMode.PrivateKey && credentials.privateKeyPem.isNotBlank()) {
            runCatching {
                keyStore.save(id, StoredSshKey(credentials.privateKeyPem, credentials.passphrase))
            }
        }

        _state.value = _state.value.copy(detail = "Probing the remote Hermes…")
        val probe = backend.probe()
        probe.assertUsable()

        _state.value = _state.value.copy(detail = "Starting the remote backend…")
        val ownershipId = SshBackendLifecycle.ownershipIdFor(target, "default", probe.hermesPath, hasher)
        lastOwnershipId = ownershipId
        var established = backend.connect(
            probe = probe,
            ownershipId = ownershipId,
            spawnNonce = random.hex(8),
        )
        // A reused backend keeps the session token it started with, so a token we adopted
        // while its adoption read was failing is one this backend will refuse — surfacing
        // later as "Hermes session listing returned HTTP 401" from a client that did send
        // a credential. Verify through the new forward and re-adopt (connect() re-reads
        // the token the dashboard serves) before the origin is registered, so the app
        // never publishes an origin it cannot authenticate against.
        var verified = backendAcceptsSessionToken(established.localPort, established.servedToken)
        var adoptAttempt = 0
        while (!verified && adoptAttempt < TOKEN_ADOPTION_ATTEMPTS) {
            adoptAttempt += 1
            _state.value = _state.value.copy(
                detail = "The backend refused the tunnel token — re-reading it (attempt $adoptAttempt)…",
            )
            established = backend.connect(
                probe = probe,
                ownershipId = ownershipId,
                spawnNonce = random.hex(8),
            )
            verified = backendAcceptsSessionToken(established.localPort, established.servedToken)
        }
        if (!verified) {
            throw IllegalStateException(
                "The backend rejected this tunnel's session token after " +
                    "${TOKEN_ADOPTION_ATTEMPTS + 1} attempts. Reconnect; if it persists, the remote " +
                    "backend was started by an older client session whose token cannot be re-read.",
            )
        }

        _state.value = _state.value.copy(detail = "Registering the tunneled server…")
        val origin = ServerOrigin.parse("127.0.0.1:${established.localPort}", useTls = false)
        // The served token is what authenticates /api/* and the WebSocket ticket,
        // so it is stored before the origin becomes active — the connection flow
        // reads it while probing.
        //
        // It is a bearer credential, not an OAuth pair, but the token store
        // validates what it is given: provider and userId must be non-blank, and
        // a non-"basic" provider must carry a positive expiry. A far-future
        // expiry also keeps refreshIfNeeded() from trying to exchange a refresh
        // token that does not exist.
        val sessionTokens = NativeTokenSet(
            accessToken = established.servedToken,
            refreshToken = "",
            expiresAt = System.currentTimeMillis() / 1_000L + SSH_TOKEN_LIFETIME_SECONDS,
            provider = SSH_SESSION_TOKEN_PROVIDER,
            userId = target.destination,
        )
        val sessionTokenStore = EncryptedNativeTokenStore(appContext)
        // What this origin held until now. A different value means the backend minted a new one-time
        // token (a host reboot respawns it), and the connection layer is still presenting the old one:
        // it has to hear about the new token even when nobody asked for a rebuild.
        val previousServedToken = runCatching { sessionTokenStore.load(origin)?.accessToken }.getOrNull()
        sessionTokenStore.save(origin, sessionTokens)
        // …and in the tunnel's own slot as well: the connection layer looks the token up
        // under the origin the server catalog hands back, which need not be the canonical
        // form the lifecycle computed here. A lookup that misses does not fail loudly — the
        // client declares "authentication not required", sends nothing and eats a 401.
        sessionTokenStore.saveSshSessionToken(sessionTokens)
        val tokenVisibleToTheConnectionLayer = runCatching {
            sessionTokenStore.hasSshSessionToken(origin)
        }.getOrDefault(false)
        // Anchor the process before reporting, so the report can say whether the anchor started:
        // this is the one fact that is only observable from the phone.
        SshTunnelService.start(appContext, "Tunnel ${origin.value}")
        val adoptedFingerprint = runCatching {
            sessionTokenStore.load(origin)?.accessToken?.let(::tokenFingerprint)
        }.getOrNull() ?: "none"
        // What the client itself gets through its own tunnel, with the token it just stored:
        // the one measurement that says whether a reported 401 comes from the backend or from
        // the app's own plumbing.
        val clientSideStatus = "me=${authenticatedStatusOf(established.localPort, established.servedToken, "/api/auth/me")} " +
            "sessions=${authenticatedStatusOf(established.localPort, established.servedToken, "/api/sessions")}"
        val readyLine = "ready origin=${origin.value} served=${tokenFingerprint(established.servedToken)} " +
            "adopted=$adoptedFingerprint store=${if (tokenVisibleToTheConnectionLayer) "ok" else "MISS"} " +
            "client=$clientSideStatus " + SshTunnelService.lastStatus
        // Remember the target AND the local port, but only once the tunnel is really up (the remote
        // backend outlives us: it is spawned detached). The port is reused on the next rebuild, so
        // the saved server entry stays valid instead of adding a new 127.0.0.1:<port> row each time.
        runCatching {
            keyStore.rememberLast(
                target = "${target.destination}:${target.port}",
                mode = credentials.mode.name,
                localPort = established.localPort,
            )
        }
        reportToHost(ownershipId = ownershipId, transport = ssh, outcome = readyLine)
        saveOrigin(
            ServerCatalogEntry(
                origin = origin,
                label = "SSH · ${target.destination}",
                lastUsedEpochSeconds = System.currentTimeMillis() / 1_000L,
            )
        )

        lifecycle = backend
        connection = established
        // Process-level release: when the user swipes the app away the tunnel service runs this before
        // it stops, so the backend spawned on the host is killed and its ownership directory removed
        // instead of being left behind to retire on its own.
        SshTunnelRuntime.registerRelease {
            withContext(io) { teardown(closeMaster = true) }
        }
        _state.value = SshConnectState(
            phase = SshConnectPhase.Ready,
            target = targetInput,
            detail = "Tunnel ready · ${origin.value} · token ${tokenFingerprint(established.servedToken)} " +
                "(accepted by the backend) · store ${if (tokenVisibleToTheConnectionLayer) "ok" else "MISS"} " +
                "· pid ${established.backendPid}",
            localPort = established.localPort,
            backendPid = established.backendPid,
        )
        startTunnelWatch()
        startKeepAliveSocket(established.localPort, established.servedToken, ownershipId)
        // Announce a REBUILD, and also a ROTATED token: in both cases the connection layer holds a
        // bearer that this backend no longer accepts, so it has to reconnect. A first connect (the
        // launch-time restore included) with an unchanged token stays silent: the connection layer
        // runs its own connect when settings become Ready, and an early nudge there raced that
        // attempt, bumped the generation under it and left the app sitting disconnected.
        val rotated = previousServedToken != null && previousServedToken != established.servedToken
        if (rebuildRequested || rotated) {
            rebuildRequested = false
            SshTunnelRuntime.notifyTunnelRestored(origin.value)
        }
    }

    /**
     * Rebuild the tunnel for the remembered target when it is no longer alive.
     *
     * A phone freezes or kills a backgrounded app, which closes the SSH socket without any
     * teardown running: the detached remote backend survives (that is what the desktop's
     * liveness design relies on too), but the local forwarded port is gone — so the saved
     * `127.0.0.1:<port>` server can no longer be reached and every probe fails. Reconnecting
     * reuses that surviving backend (the lifecycle reports EXISTING instead of spawning a
     * second one) and re-forwards its port.
     *
     * Passwords are never stored, so a password-authenticated tunnel can only be rebuilt by
     * the user; a key-authenticated one is rebuilt from the encrypted key store.
     */
    fun reconnect() {
        if (_state.value.phase == SshConnectPhase.Connecting) return
        viewModelScope.launch { rebuildIfNeeded() }
    }

    /** Set only when a rebuild is about to reconnect, so the recovered tunnel can announce itself. */
    private var rebuildRequested = false

    /**
     * Keep the tunnel and its token honest while the app is in the foreground.
     *
     * Two failures were observed on a real device, and both end as "the saved
     * `127.0.0.1:<port>` server cannot be used": the phone's forward disappears while the
     * detached backend survives ("Could not reach Hermes Serve — Connection refused"), and
     * the backend stops accepting the token the app holds ("… session listing returned
     * HTTP 401"). Rebuilding covers both, because connect() reuses the surviving backend,
     * re-forwards its port, re-reads the token the backend serves, verifies it and
     * re-registers the origin.
     */
    private fun startTunnelWatch() {
        tunnelWatch?.cancel()
        tunnelWatch = SshTunnelRuntime.scope.launch {
            while (true) {
                kotlinx.coroutines.delay(TUNNEL_WATCH_INTERVAL_MS)
                val established = connection ?: continue
                val sessionAlive = sshSessionAlive()
                val usable = sessionAlive && forwardAnswers() &&
                    backendAcceptsSessionToken(established.localPort, established.servedToken)
                if (usable) continue
                _state.value = _state.value.copy(
                    detail = if (sessionAlive) {
                        "The backend stopped accepting this tunnel's token — rebuilding…"
                    } else {
                        "The SSH session dropped — rebuilding…"
                    },
                )
                runCatching { rebuildIfNeeded(force = sessionAlive) }
            }
        }
    }

    /** True when the SSH transport itself still reports a live session. */
    private suspend fun sshSessionAlive(): Boolean =
        withContext(io) { runCatching { transport?.check() == true }.getOrDefault(false) }

    /**
     * True when the local forward actually answers.
     *
     * The forward lives in this process, so a frozen or killed app leaves the port closed even though
     * the SSH session object may still claim to be connected. Both halves are needed: the port alone
     * would rebuild while the session is fine, and the session alone is what produced a permanent
     * offline screen after a resume.
     */
    internal suspend fun forwardAnswers(): Boolean =
        withContext(io) { localForwardAnswers(connection?.localPort, LOCAL_FORWARD_PROBE_TIMEOUT_MS) }

    /**
     * Verify the tunnel now instead of waiting for the next watch tick.
     *
     * The watch is a 15 s poll, so a process that was frozen came back to a dead forward and stayed
     * broken for up to a full tick — long enough that sending a message in that window failed with a
     * ticket error, then started working again on its own ("it gets better after a while"). A return to
     * the foreground is exactly the moment to look.
     */
    fun checkTunnelNow() {
        if (connection == null) return
        SshTunnelRuntime.scope.launch {
            val established = connection ?: return@launch
            val sessionAlive = sshSessionAlive()
            val usable = sessionAlive && forwardAnswers() &&
                backendAcceptsSessionToken(established.localPort, established.servedToken)
            if (usable) return@launch
            _state.value = _state.value.copy(
                detail = if (sessionAlive) {
                    "The backend stopped accepting this tunnel's token — rebuilding…"
                } else {
                    "The SSH session dropped — rebuilding…"
                },
            )
            runCatching { rebuildIfNeeded(force = sessionAlive) }
        }
    }

    /** Does this ViewModel's live tunnel serve [origin]? */
    private fun servesOrigin(origin: String): Boolean {
        val port = connection?.localPort ?: return false
        return runCatching { ServerOrigin.parse("127.0.0.1:$port").value }.getOrNull() == origin
    }

    /** A short, non-reversible fingerprint of the token, for comparing with `tokenFingerprint`. */
    private fun tokenFingerprint(token: String): String = runCatching {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(12)
    }.getOrDefault("unavailable")

    /**
     * Bring the remembered tunnel back at launch, without the user retyping anything.
     *
     * A key-authenticated target is rebuilt automatically; a password-authenticated one can only
     * be pre-filled and left for one tap, since the password is deliberately not stored.
     *
     * Retries matter here: the app is routinely opened before the phone is back on the host's
     * network, and a single attempt then fails and leaves the app sitting offline until the user
     * rebuilds the tunnel by hand from the settings screen.
     */
    private suspend fun restoreOnLaunch() {
        if (SshTunnelRuntime.hasUserDisconnected()) return
        if (connection != null) return
        val remembered = withContext(io) { runCatching { keyStore.lastTarget() }.getOrNull() } ?: return
        val (target, mode) = remembered
        if (mode != SshAuthMode.PrivateKey.name) {
            _state.value = SshConnectState(
                phase = SshConnectPhase.Idle,
                target = target,
                detail = "Tap Connect to restore the tunnel to $target (your password is never stored).",
            )
            return
        }
        var attempt = 0
        while (attempt < AUTO_RESTORE_DELAYS_SECONDS.size) {
            if (SshTunnelRuntime.hasUserDisconnected() || connection != null) return
            attempt += 1
            // Deliberately NOT Connecting: connect() refuses to start when the phase is already
            // Connecting, so pre-setting it here made every automatic attempt a no-op that just
            // counted up (visible as "…(attempt 3)" and then a failure, while the Rebuild button
            // worked because it never touched the phase first).
            _state.value = _state.value.copy(
                phase = SshConnectPhase.Idle,
                target = target,
                detail = "Restoring the tunnel to $target… (attempt $attempt)",
                error = null,
            )
            runCatching { rebuildIfNeeded() }
            if (connection != null) return
            kotlinx.coroutines.delay(AUTO_RESTORE_DELAYS_SECONDS[attempt - 1] * 1_000L)
        }
        _state.value = SshConnectState(
            phase = SshConnectPhase.Failed,
            target = target,
            error = "Could not reach $target automatically. Check the network, then tap Connect.",
        )
    }

    /**
     * Rebuild the tunnel for the remembered target.
     *
     * A phone freezes or kills a backgrounded app, which closes the SSH socket without any
     * teardown running: the detached remote backend survives (that is what the desktop's
     * liveness design relies on too), but the local forwarded port is gone — so the saved
     * `127.0.0.1:<port>` server can no longer be reached and every probe fails. Reconnecting
     * reuses that surviving backend (the lifecycle reports EXISTING instead of spawning a
     * second one) and re-forwards its port.
     *
     * Passwords are never stored, so a password-authenticated tunnel can only be rebuilt by
     * the user; a key-authenticated one is rebuilt from the encrypted key store.
     */
    private suspend fun rebuildIfNeeded(force: Boolean = false) {
        // A live transport is not the same as a usable credential: the backend it points at can have
        // been respawned with a new one-time token (a host reboot, or a `hermes serve` restart under a
        // surviving sshd), and only a fresh re-forward + re-adopt produces the token that backend
        // actually serves. `force` is how the token-rejection path says "the session is alive but the
        // token is not" — without it this method reported "the SSH session is still alive" and the
        // caller retried forever against a dead bearer.
        // "The session is alive" is not "the tunnel is usable". A frozen process leaves the SSH
        // transport reporting connected while its local forward is gone, so this check passed on
        // resume, returned Ready, and never re-forwarded: the backend stayed healthy, the screen
        // stayed offline, and only a manual Rebuild recovered it ("it doesn't reconnect any more").
        val alive = !force && sshSessionAlive() && forwardAnswers()
        if (alive) {
            _state.value = _state.value.copy(
                phase = SshConnectPhase.Ready,
                error = null,
                detail = "The SSH session is still alive.",
            )
            return
        }
        val remembered = withContext(io) { runCatching { keyStore.lastTarget() }.getOrNull() }
        if (remembered == null) {
            _state.value = _state.value.copy(
                phase = SshConnectPhase.Failed,
                detail = "",
                error = "No SSH host remembered yet — connect once and this can rebuild the tunnel later.",
            )
            return
        }
        val (target, mode) = remembered
        if (mode != SshAuthMode.PrivateKey.name) {
            _state.value = _state.value.copy(
                phase = SshConnectPhase.Failed,
                detail = "",
                error = "The tunnel dropped. Passwords are never stored — re-enter it and connect.",
            )
            return
        }
        rebuildRequested = true
        connect(target, SshCredentials(mode = SshAuthMode.PrivateKey))
    }

    /** Stops the owned backend and closes the SSH connection. */
    fun disconnect() {
        SshTunnelRuntime.markUserDisconnected()
        SshTunnelRuntime.clearRelease()
        tunnelWatch?.cancel()
        tunnelWatch = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        viewModelScope.launch {
            withContext(io) { teardown(closeMaster = true) }
            _state.value = SshConnectState()
        }
        SshTunnelService.stop(appContext)
    }

    private fun teardown(closeMaster: Boolean) {
        val backend = lifecycle
        val established = connection
        if (backend != null && established != null) {
            runCatching { backend.disconnect(established, stopBackend = true, closeMaster = false) }
        }
        if (closeMaster) runCatching { transport?.exitMaster() }
        lifecycle = null
        connection = null
        transport = null
    }

    override fun onCleared() {
        // Deliberately NOT a teardown: the tunnel belongs to the app, not to a screen.
        // Android clears a ViewModel whenever the Activity is recreated (a navigation into
        // the session list does it), and tearing the tunnel down there killed a working
        // connection seconds after it was established — the host log shows a WebSocket
        // accepted and dropped 0.1-0.5s later, with no client-side reason recorded. The
        // desktop client has the same property for free: its tunnel is a detached process.
        // Release paths stay explicit: the Disconnect button, or process death.
        runCatching {
            val manager = connectivityManager
            val callback = networkCallback
            if (manager != null && callback != null) manager.unregisterNetworkCallback(callback)
        }
        // The hook holds this ViewModel: drop it with the screen. The next instance registers again
        // when the app shows the SSH screen, and until then a rejected token simply surfaces as a
        // disconnect instead of being re-read silently.
        SshTunnelRuntime.registerTokenReAdopt(null)
        super.onCleared()
    }

    class Factory(
        private val appContext: Context,
        private val saveOrigin: suspend (ServerCatalogEntry) -> Result<Unit>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SshConnectViewModel::class.java))
            return SshConnectViewModel(appContext, saveOrigin) as T
        }
    }
}

enum class SshConnectPhase { Idle, Connecting, Ready, Failed }

/** How this connection authenticates. */
enum class SshAuthMode { Password, PrivateKey }

/**
 * Credentials for one connect attempt. In [SshAuthMode.PrivateKey] an empty
 * [privateKeyPem] means "use the key this device already holds for the target".
 */
data class SshCredentials(
    val mode: SshAuthMode = SshAuthMode.Password,
    val password: String = "",
    val privateKeyPem: String = "",
    val passphrase: String = "",
)

/** Stable per-target identifier used as the key-store lookup key. */
private fun keyId(target: SshTarget): String = "${target.destination}:${target.port}"

data class SshConnectState(
    val phase: SshConnectPhase = SshConnectPhase.Idle,
    val detail: String = "",
    val target: String = "",
    val localPort: Int = 0,
    val backendPid: Long = 0,
    val error: String? = null,
)

/** Ten years: long enough that refreshIfNeeded() never tries an OAuth exchange. */
private const val SSH_TOKEN_LIFETIME_SECONDS = 10L * 365L * 24L * 3600L

/**
 * Backoff for the launch restore: the app is often opened before the phone is on the host's
 * network, and the first failure used to be the last word.
 */
private val AUTO_RESTORE_DELAYS_SECONDS = longArrayOf(5L, 10L, 20L, 40L)

/** How many extra attempts to re-read a token the backend refused before giving up. */
private const val TOKEN_ADOPTION_ATTEMPTS = 2

/** How often the foreground watch re-checks the tunnel and its token. */
private const val TUNNEL_WATCH_INTERVAL_MS = 15_000L

/** Keep-alive socket reconnect backoff: 2s doubling to 30s, as the desktop client does. */
private const val KEEP_ALIVE_BACKOFF_MS = 2_000L
private const val KEEP_ALIVE_MAX_BACKOFF_MS = 30_000L