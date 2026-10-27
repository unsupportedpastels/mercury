package ai.hermes.ssh

/**
 * Remote command payloads — POSIX sh only.
 *
 * Port of the remote-side strings in `apps/desktop/electron/remote-lifecycle.ts`
 * (Hermes Agent, MIT). Everything here must run under `dash` on Linux and under
 * macOS `sh`: no bashisms, no GNU-only tools.
 */

/** Expand `~/.hermes/...` into a shell fragment that keeps `$HOME` dynamic. */
fun expandRemotePath(path: String): String = when {
    path == "~" -> "\"\$HOME\""
    path.startsWith("~/") -> "\"\$HOME\"'" + path.substring(1).replace("'", "'\\''") + "'"
    path.startsWith("/") -> shq(path)
    else -> shq(path)
}

object RemoteScripts {

    /**
     * Wrap a remote command in a POSIX watchdog so a hung remote CLI is killed
     * REMOTELY (macOS ships no GNU `timeout`). The wrapped command must be a
     * single command: the watchdog kills its direct child.
     */
    fun withRemoteTimeout(command: String, timeoutSecs: Int = RemoteLayout.REMOTE_PROBE_TIMEOUT_SECS): String {
        val secs = if (timeoutSecs > 0) timeoutSecs else RemoteLayout.REMOTE_PROBE_TIMEOUT_SECS
        return "[ -n \"\${ZSH_VERSION-}\" ] || set -m 2>/dev/null; " +
            "($command) </dev/null & __htp=\$!; set +m 2>/dev/null; " +
            "(sleep $secs </dev/null >/dev/null 2>&1; kill -9 -- -\$__htp 2>/dev/null; kill -9 \$__htp 2>/dev/null) & __htw=\$!; " +
            "wait \$__htp; __htrc=\$?; " +
            "kill \$__htw 2>/dev/null; wait \$__htw 2>/dev/null; " +
            "exit \$__htrc"
    }

    fun probePlatform(): String = "uname -s 2>/dev/null"

    fun probeHermesVersion(hermesPath: String): String =
        withRemoteTimeout("${expandRemotePath(hermesPath)} --version 2>&1")

    fun locateHermes(remoteHermesPath: String? = null): String {
        val literalCandidates = mutableListOf<String>()
        remoteHermesPath?.let {
            literalCandidates += if (it.startsWith("/") || it.startsWith("~/")) expandRemotePath(it) else shq(it)
        }
        literalCandidates += listOf(
            "\"\$HOME\"/.local/bin/hermes",
            "\"\$HOME\"/.hermes/hermes-agent/hermes",
            "/usr/local/bin/hermes",
            "/usr/bin/hermes"
        )
        val body = literalCandidates.joinToString("; ") { c ->
            "if [ -x $c ]; then printf '%s' $c; exit 0; fi"
        }
        return "if [ -n \"\$(command -v hermes 2>/dev/null)\" ]; then command -v hermes; exit 0; fi; $body; exit 1"
    }

    /**
     * Version capability gate: the remote `hermes serve` must advertise both
     * ownership flags, otherwise this lifecycle cannot prove ownership and the
     * desktop refuses the SSH route.
     */
    fun supportsSshOwnership(hermesPath: String): String {
        val hermes = expandRemotePath(hermesPath)
        return "help=\"\$( ${withRemoteTimeout("$hermes serve --help 2>&1")} )\"; " +
            "printf '%s' \"\$help\" | grep -q ssh-session-token-file && " +
            "printf '%s' \"\$help\" | grep -q ssh-owner-nonce && echo YES || echo NO"
    }

    fun probeRemoteHermesHome(): String = "printf '%s' \"\$HOME/.hermes\""

    fun assertSafeRemoteHome(home: String): String =
        "printf '%s' ${expandRemotePath(home)}"

    fun listRemoteProfiles(hermesPath: String): String =
        withRemoteTimeout("${expandRemotePath(hermesPath)} profiles list 2>/dev/null || true")

    fun readRemoteInstallId(hermesHome: String): String {
        val home = expandRemotePath(hermesHome)
        return "for f in $home/install_id $home/.install_id; do [ -f \"\$f\" ] && { cat \"\$f\"; exit 0; }; done; exit 0"
    }

    fun pidAlive(pid: Long): String = "kill -0 $pid 2>/dev/null && echo ALIVE || echo DEAD"

    fun readLockfile(ownershipId: String): String {
        val path = expandRemotePath(RemoteLayout.lockfilePath(ownershipId))
        // Distinguish "absent" from "present but empty": an empty file is skew
        // (some other build's/corrupt state), absence is simply no record.
        return "if [ -f $path ]; then cat $path; else echo ${shq(LOCKFILE_MISSING)}; fi"
    }

    /** Sentinel printed when the lockfile is absent (vs present-but-empty). */
    const val LOCKFILE_MISSING = "__HERMES_LOCK_MISSING__"

    /**
     * Rewrite the lockfile after readiness so it carries the resolved port and
     * the fingerprint of the token the backend actually serves — that is what
     * the reuse probe later authenticates against. Atomic: tmp + mv -f.
     */
    fun writeLockfile(ownershipId: String, json: String): String {
        val lock = expandRemotePath(RemoteLayout.lockfilePath(ownershipId))
        val tmp = "\"\$lock\".$$" + ".tmp"
        return "lock=$lock; temporary_lock=$tmp; printf '%s' ${shq(json)} > \"\$temporary_lock\" && mv -f \"\$temporary_lock\" \"\$lock\""
    }

    fun removeLockfile(ownershipId: String): String =
        "rm -f ${expandRemotePath(RemoteLayout.lockfilePath(ownershipId))}"

    fun removeTokenFile(ownershipId: String, spawnNonce: String): String =
        "rm -f ${expandRemotePath(RemoteLayout.spawnTokenPath(ownershipId, spawnNonce))}"

    fun removeOwnershipDir(ownershipId: String): String =
        "rm -rf ${expandRemotePath(RemoteLayout.ownershipDirectory(ownershipId))}"

    fun tailLog(ownershipId: String, spawnNonce: String): String {
        val path = expandRemotePath(RemoteLayout.spawnLogPath(ownershipId, spawnNonce))
        return "tail -n 200 $path 2>/dev/null || true"
    }

    fun makeOwnershipDir(ownershipId: String): String =
        "(umask 077 && mkdir -p ${expandRemotePath(RemoteLayout.ownershipDirectory(ownershipId))})"

    /**
     * Keep a bounded number of spawn logs.
     *
     * One `<nonce>.log` is written per spawn and nothing ever removes it — a single ownership
     * directory held eight of them after one debugging day (measured). Everything but the newest
     * few is debris; the log being written right now is the newest by construction, so it is never
     * a candidate. Best effort: housekeeping must not fail a connect.
     */
    fun pruneSpawnLogs(ownershipId: String, keep: Int = RemoteLayout.SPAWN_LOG_KEEP): String {
        val dir = expandRemotePath(RemoteLayout.ownershipDirectory(ownershipId))
        return "ls -1t $dir/[0-9a-f]*.log 2>/dev/null | tail -n +${keep + 1} | " +
            "while read -r f; do rm -f \"\$f\"; done || true"
    }

    fun stopBackend(pid: Long): String =
        "kill $pid 2>/dev/null || true; i=0; while kill -0 $pid 2>/dev/null; do i=\$((i+1)); " +
            "[ \"\$i\" -ge 40 ] && break; sleep 0.25; done; kill -0 $pid 2>/dev/null && echo STILL_ALIVE || echo STOPPED"

    /**
     * Marker gate.
     *
     * Divergence from the desktop, recorded deliberately: Hermes ships a
     * 257-line `remote-update-marker-programs.ts` whose Python judge proves a
     * live updater by (pid, creation-time) identity with a corpus of verdicts
     * (LIVE / CLEAR / UNCERTAIN). We implement the *gate* — one exclusive
     * holder at a time, refuse while the marker exists — with `flock`, which is
     * enough to keep two clients from spawning concurrently. Porting the real
     * judge is a follow-up (it matters only during an install update).
     */
    fun markerGate(command: String, markerPath: String, hermesPath: String): String {
        val marker = expandRemotePath(markerPath)
        val lock = expandRemotePath("$markerPath.gate")
        // `--close` matters: util-linux flock hands the lock fd to the command's
        // children, so a long-lived spawned backend would hold the gate forever
        // and every later spawn would sit out the -w timeout (observed: a 60s
        // stall on the second connection).
        return "if [ -e $marker ]; then echo MARKER_PRESENT; exit 75; fi; " +
            "if command -v flock >/dev/null 2>&1; then " +
            "  if flock --help 2>&1 | grep -q -- '--close'; then " +
            "    flock -w 60 --close $lock sh -c ${shq(command)}; " +
            "  else flock -w 60 $lock sh -c ${shq(command)}; fi; " +
            "else sh -c ${shq(command)}; fi"
    }

    /** A plan for one detached `hermes serve --isolated` spawn. */
    data class SpawnPlan(
        val ownershipId: String,
        val spawnNonce: String,
        val hermesPath: String,
        val hermesHome: String = "~/.hermes",
        val profile: String = "",
        val metadata: String,
        val withOwnership: Boolean = true
    )

    /**
     * Detached spawn: `setsid` on Linux, `nohup` on macOS, stdio detached, log
     * merged (`>> log 2>&1`), pid echoed. Under ownership: atomic `mkdir`
     * reservation, reuse judge, lockfile published with tmp + `mv -f`.
     */
    fun buildSpawnScript(plan: SpawnPlan): String = markerGate(
        buildSpawnPayload(plan),
        "${plan.hermesHome.trimEnd('/')}/.hermes-update-in-progress",
        plan.hermesPath
    )

    /** The ungated payload (exposed so tests can assert on it directly). */
    fun buildSpawnPayload(plan: SpawnPlan): String {
        val hermes = expandRemotePath(plan.hermesPath)
        val profileArg = if (plan.profile.isBlank()) "" else "--profile ${shq(plan.profile)} "
        val logPath = expandRemotePath(RemoteLayout.spawnLogPath(plan.ownershipId, plan.spawnNonce))
        val tokenPath = expandRemotePath(RemoteLayout.spawnTokenPath(plan.ownershipId, plan.spawnNonce))
        val marker = expandRemotePath("${plan.hermesHome.trimEnd('/')}/.hermes-update-in-progress")

        val subCmd = "serve --isolated --host 127.0.0.1 --port 0" +
            " --ssh-session-token-file $tokenPath" +
            " --ssh-owner-nonce ${RemoteLayout.spawnNonce(plan.spawnNonce)}"

        val dashCmd = "ulimit -n ${RemoteLayout.REMOTE_NOFILE_SOFT_LIMIT} 2>/dev/null || true; " +
            "exec env HERMES_DESKTOP=1 $hermes $profileArg$subCmd"

        val detachedShell = "$dashCmd </dev/null >> $logPath 2>&1 & echo \$!"
        // Note: the desktop prefixes this with `eval "exec $1>&-"` to drop an fd
        // its Python gate passes in. With no such gate `$1` is empty, so that
        // becomes `exec >&-` — it closes stdout, the inner `echo $!` dies with an
        // I/O error, and the pid comes back empty (verified the hard way).
        // The child already detaches all three stdio fds, so nothing is needed.
        val detachedSpawn = "child=\$(\"\$(command -v setsid || echo nohup)\" sh -c ${shq(detachedShell)})"
        // Never publish a lockfile around an empty pid.
        val assertPid = "case \"\$child\" in ''|*[!0-9]*) echo SPAWN_FAILED >&2; exit 77;; esac; "

        if (!plan.withOwnership) {
            return "mkdir -p \"\$(dirname $logPath)\" && $detachedSpawn; $assertPid" +
                "[ ! -e $marker ] || { kill \"\$child\" 2>/dev/null || true; wait \"\$child\" 2>/dev/null || true; exit 75; }; " +
                "echo \"\$child\""
        }

        val reservation = expandRemotePath(RemoteLayout.reservationPath(plan.ownershipId))
        val lock = expandRemotePath(RemoteLayout.lockfilePath(plan.ownershipId))
        val ownerFile = "$reservation/owner"
        val (lockPrefix, lockSuffix) = splitLockJson(plan.metadata)

        val payload = buildString {
            append("(umask 077 && mkdir -p \"\$(dirname $reservation)\"); ")
            append("reservation=$reservation; lock=$lock; owner_file=$ownerFile; ")
            append("reservation_nonce=${shq(plan.spawnNonce)}; ")
            // Atomic reservation: a live owner keeps the slot, a dead one is reclaimed.
            append("i=0; while ! mkdir \"\$reservation\" 2>/dev/null; do ")
            append("owner_data=\$(cat \"\$owner_file\" 2>/dev/null || true); owner_pid=\${owner_data%%:*}; ")
            append("case \"\$owner_pid\" in ''|*[!0-9]*) ;; *) kill -0 \"\$owner_pid\" 2>/dev/null || { rm -rf \"\$reservation\"; continue; };; esac; ")
            append("i=\$((i+1)); [ \"\$i\" -ge 600 ] && exit 75; sleep 0.05; done; ")
            append("printf '%s:%s' \"\$\$\" \"\$reservation_nonce\" > \"\$owner_file\"; ")
            append("trap 'rm -rf \"\$reservation\"' EXIT; ")
            // Reuse judge: an existing lock with a live pid means a healthy owned backend.
            append("if [ -f \"\$lock\" ]; then ")
            append("existing_pid=\$(sed -n 's/.*\"pid\":\\([0-9][0-9]*\\).*/\\1/p' \"\$lock\" | head -n 1); ")
            append("case \"\$existing_pid\" in ''|*[!0-9]*) rm -f \"\$lock\";; *) ")
            append("if kill -0 \"\$existing_pid\" 2>/dev/null; then rm -f $tokenPath; printf EXISTING; exit 0; fi; rm -f \"\$lock\";; esac; fi; ")
            append("mkdir -p \"\$(dirname $logPath)\" && $detachedSpawn; ")
            append(assertPid)
            append("[ ! -e $marker ] || { kill \"\$child\" 2>/dev/null || true; wait \"\$child\" 2>/dev/null || true; exit 75; }; ")
            // Publish atomically; the pid goes in as a JSON number between the halves.
            append("lock_json=\$(printf '%s%s%s' ${shq(lockPrefix)} \"\$child\" ${shq(lockSuffix)}); ")
            append("temporary_lock=\"\${lock}.\${reservation_nonce}.tmp\"; ")
            append("printf '%s' \"\$lock_json\" > \"\$temporary_lock\" && mv -f \"\$temporary_lock\" \"\$lock\" || ")
            append("{ kill \"\$child\" 2>/dev/null || true; wait \"\$child\" 2>/dev/null || true; exit 76; }; ")
            append("echo \"\$child\"")
        }

        return payload
    }
}