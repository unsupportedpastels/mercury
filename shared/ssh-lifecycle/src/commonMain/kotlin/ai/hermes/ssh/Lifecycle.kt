package ai.hermes.ssh

/**
 * The SSH backend lifecycle: probe → spawn (or reuse) → READY → forward →
 * adopt served token → keep alive → clean up.
 *
 * Port of the orchestration in `apps/desktop/electron/remote-lifecycle.ts`
 * (Hermes Agent, MIT), minus the update-mutex judge (see
 * `RemoteScripts.markerGate`) and minus the multi-connection/renderer layers.
 */

class SshBackendLifecycle(
    val target: SshTarget,
    val transport: SshTransport,
    val portPicker: PortPicker,
    val http: HttpFetcher,
    val clock: Clock,
    val random: RandomSource,
    val hasher: TokenHasher,
    val sleeper: Sleeper,
    val log: (String) -> Unit = {}
) {

    /** Step 1 — capability probe. Nothing is spawned before this passes. */
    fun probe(remoteHermesPath: String? = null, remoteHermesHome: String = "~/.hermes"): ProbeResult {
        val platform = transport.execProbe(RemoteScripts.probePlatform()).trim()
        val hermesPath = transport.execProbe(RemoteScripts.locateHermes(remoteHermesPath)).trim().lineSequence()
            .lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        require(hermesPath.isNotBlank()) {
            "No Hermes CLI found on ${target.destination}. Looked at PATH, ~/.local/bin, ~/.hermes/hermes-agent."
        }
        val version = transport.execProbe(RemoteScripts.probeHermesVersion(hermesPath)).trim()
        val ownership = transport.execProbe(RemoteScripts.supportsSshOwnership(hermesPath)).trim().endsWith("YES")
        val home = remoteHermesHome
        val installId = transport.execProbe(RemoteScripts.readRemoteInstallId(home)).trim()
        val profiles = transport.execProbe(RemoteScripts.listRemoteProfiles(hermesPath))
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        return ProbeResult(
            platform = platform.ifEmpty { "unknown" },
            hermesPath = hermesPath,
            hermesVersion = version,
            supportsOwnership = ownership,
            hermesHome = home,
            installId = installId,
            profiles = profiles
        )
    }

    /**
     * Step 2 — spawn or reuse a detached `hermes serve --isolated`, wait for the
     * READY line, open the tunnel, and adopt the token the backend serves.
     */
    fun connect(
        probe: ProbeResult,
        ownershipId: String,
        spawnNonce: String,
        profile: String = "",
        readyTimeoutMs: Int = RemoteLayout.DEFAULT_READY_TIMEOUT_MS
    ): BackendConnection {
        RemoteLayout.ownershipId(ownershipId)
        RemoteLayout.spawnNonce(spawnNonce)
        probe.assertUsable()

        val spawnToken = mintToken(random)
        val tokenPath = RemoteLayout.spawnTokenPath(ownershipId, spawnNonce)
        val logPath = RemoteLayout.spawnLogPath(ownershipId, spawnNonce)

        transport.exec(RemoteScripts.makeOwnershipDir(ownershipId))
        transport.exec("(umask 077 && printf '%s' ${shq(spawnToken)} > ${expandRemotePath(tokenPath)})")

        val meta = LockMetadata(
            ownershipId = ownershipId,
            profile = profile,
            tokenFingerprint = fingerprintToken(hasher, spawnToken),
            hermesPath = probe.hermesPath,
            hermesHome = probe.hermesHome,
            installId = probe.installId,
            host = target.host,
            user = target.user ?: "",
            port = target.port,
            createdAtMillis = clock.nowMillis()
        )
        val plan = RemoteScripts.SpawnPlan(
            ownershipId = ownershipId,
            spawnNonce = spawnNonce,
            hermesPath = probe.hermesPath,
            hermesHome = probe.hermesHome,
            profile = profile,
            metadata = buildLockJson(meta)
        )

        val spawnOut = transport.exec(RemoteScripts.buildSpawnScript(plan)).trim()
        val reused = spawnOut.contains("EXISTING")
        val lastLine = spawnOut.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

        val backendPid: Long
        var remotePort: Int
        if (reused) {
            // A healthy owned backend already holds the slot: its pid and its
            // port come from the lockfile, not from a fresh log scrape.
            when (val lock = readLock(ownershipId)) {
                is LockfileRead.Owned -> {
                    backendPid = lock.pid
                    remotePort = lock.port
                }
                is LockfileRead.Skew -> error("Lockfile skew while reusing: ${lock.reason}")
                LockfileRead.Absent -> error("Spawn reported EXISTING but no lockfile was published.")
            }
        } else {
            backendPid = lastLine.toLongOrNull()
                ?: error("Spawn did not report a pid (output: ${spawnOut.take(200)})")
            remotePort = 0
        }
        log("[ssh] ${if (reused) "reusing" else "spawned"} backend pid=$backendPid on ${target.destination}")

        // READY: the port is chosen by the backend (`--port 0`) and scraped from
        // the merged log; a reused backend already recorded it in the lockfile.
        if (remotePort <= 0) {
            remotePort = waitForReadyPort(ownershipId, spawnNonce, logPath, readyTimeoutMs, backendPid)
            log("[ssh] backend ready on remote port $remotePort")
        }

        val localPort = portPicker.pick()
        transport.forward(localPort, remotePort)

        // Housekeeping, deliberately off the critical path: spawn logs accumulate one per connect.
        runCatching { transport.exec(RemoteScripts.pruneSpawnLogs(ownershipId)) }
        val baseUrl = "http://127.0.0.1:$localPort"

        val servedToken = try {
            DashboardToken.adoptServedDashboardToken(
                fetcher = http,
                baseUrl = baseUrl,
                spawnToken = spawnToken,
                childAlive = { isBackendAlive(backendPid) },
                onLog = log
            )
        } catch (e: Exception) {
            transport.cancelForward(localPort, remotePort)
            throw e
        }

        // Publish the resolved port and the SERVED token's fingerprint: the
        // reuse probe later authenticates the credential that actually serves
        // the port, not the one we minted.
        val resolved = meta.copy(
            port = remotePort,
            tokenFingerprint = fingerprintToken(hasher, servedToken)
        )
        transport.exec(
            RemoteScripts.writeLockfile(
                ownershipId,
                buildLockJson(resolved).replace(LOCK_PID_PLACEHOLDER, backendPid.toString())
            )
        )

        return BackendConnection(
            ownershipId = ownershipId,
            spawnNonce = spawnNonce,
            backendPid = backendPid,
            reusedExisting = reused,
            remotePort = remotePort,
            localPort = localPort,
            spawnToken = spawnToken,
            servedToken = servedToken,
            profile = profile
        )
    }

    /** Poll the merged remote log for `HERMES_DASHBOARD_READY port=<n>`. */
    fun waitForReadyPort(
        ownershipId: String,
        spawnNonce: String,
        logPath: String,
        timeoutMs: Int,
        backendPid: Long
    ): Int {
        val budget = maxOf(timeoutMs, RemoteLayout.MIN_READY_TIMEOUT_MS)
        val deadline = clock.nowMillis() + budget
        while (clock.nowMillis() < deadline) {
            val tail = transport.exec(RemoteScripts.tailLog(ownershipId, spawnNonce))
            ReadySignal.parse(tail)?.let { return it }
            if (!isBackendAlive(backendPid)) {
                val exited = tail.lines().takeLast(12).joinToString("\n")
                throw IllegalStateException(
                    "The remote backend exited before announcing readiness.\n$exited"
                )
            }
            sleeper.sleep(RemoteLayout.READY_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Timed out after ${budget}ms waiting for HERMES_DASHBOARD_READY.")
    }

    /**
     * Teardown. Only a backend we can *prove* is ours is stopped: the live pid
     * must still match the lockfile, and a skew/foreign lockfile aborts instead
     * of reaping someone else's tunnel.
     */
    fun disconnect(conn: BackendConnection, stopBackend: Boolean = true, closeMaster: Boolean = true) {
        transport.cancelForward(conn.localPort, conn.remotePort)
        if (stopBackend) {
            when (val lock = readLock(conn.ownershipId)) {
                is LockfileRead.Owned -> {
                    if (lock.pid == conn.backendPid) {
                        transport.exec(RemoteScripts.stopBackend(conn.backendPid))
                    } else {
                        log("[ssh] lockfile pid ${lock.pid} != ours ${conn.backendPid}; leaving the backend alone")
                    }
                }
                is LockfileRead.Skew -> log("[ssh] lockfile skew (${lock.reason}); refusing to reap")
                LockfileRead.Absent -> log("[ssh] no lockfile; nothing to reap")
            }
        }
        transport.exec(RemoteScripts.removeLockfile(conn.ownershipId))
        transport.exec(RemoteScripts.removeTokenFile(conn.ownershipId, conn.spawnNonce))
        transport.exec(RemoteScripts.removeOwnershipDir(conn.ownershipId))
        if (closeMaster) transport.exitMaster()
    }

    fun isBackendAlive(pid: Long): Boolean =
        transport.exec(RemoteScripts.pidAlive(pid), 10_000).trim().endsWith("ALIVE")

    fun readLock(ownershipId: String): LockfileRead {
        val raw = transport.exec(RemoteScripts.readLockfile(ownershipId)).trim()
        if (raw.isEmpty() || raw == RemoteScripts.LOCKFILE_MISSING) return LockfileRead.Absent
        return readLockfile(raw, ownershipId)
    }

    fun readOwnedPid(ownershipId: String): Long = when (val lock = readLock(ownershipId)) {
        is LockfileRead.Owned -> lock.pid
        is LockfileRead.Skew -> error("Lockfile skew: ${lock.reason}")
        LockfileRead.Absent -> error("Spawn reported EXISTING but no lockfile was published.")
    }

    companion object {
        /**
         * Stable ownership id per (host, user, port, profile, hermesPath) — the
         * desktop hashes the same tuple as its bootstrap fingerprint.
         */
        fun ownershipIdFor(target: SshTarget, profile: String, hermesPath: String, hasher: TokenHasher): String =
            hasher.sha256Hex("${target.destination}|${target.port}|$profile|$hermesPath").take(32)
    }
}