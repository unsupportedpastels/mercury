package ai.hermes.ssh

/**
 * Transports the lifecycle talks to.
 *
 * Two implementations are verified in this repo:
 *  - `ProcessSshTransport` (JVM): shells out to the system `ssh` binary — the
 *    desktop's exact mechanism; this is the behavioural oracle.
 *  - `JschSshTransport` (JVM): uses an SSH *library* — the shape an Android
 *    build must use, because Android ships no `ssh` binary.
 */

interface SshTransport {
    /** `ssh -M -N -f` — one authenticated connection, reused by everything else. */
    fun openMaster()

    /** `ssh -O check` */
    fun check(): Boolean

    fun exec(command: String, timeoutMs: Int = SshArgs.DEFAULT_EXEC_TIMEOUT_MS): String

    /**
     * Run a probe wrapped in the POSIX watchdog.
     *
     * A probe that exits non-zero with output is *data* (e.g. `locate hermes`
     * exits 1 when the CLI is absent), so its stdout is returned. A probe the
     * watchdog had to kill is a timeout and is surfaced as such.
     */
    fun execProbe(
        command: String,
        timeoutSecs: Int = RemoteLayout.REMOTE_PROBE_TIMEOUT_SECS
    ): String = try {
        exec(RemoteScripts.withRemoteTimeout(command, timeoutSecs), (timeoutSecs + 5) * 1000)
    } catch (e: SshException) {
        if (e.kind == SshErrorKind.TIMEOUT) throw e
        e.stdout ?: ""
    }

    fun forward(localPort: Int, remotePort: Int, remoteHost: String = "127.0.0.1")

    fun cancelForward(localPort: Int, remotePort: Int, remoteHost: String = "127.0.0.1")

    fun exitMaster()
}

/** Picks a free local port for the tunnel's client end. */
interface PortPicker {
    fun pick(): Int
}

/** Injected so the lifecycle never calls a platform sleep directly (KMP). */
interface Sleeper {
    fun sleep(millis: Long)
}

data class ProbeResult(
    val platform: String,
    val hermesPath: String,
    val hermesVersion: String,
    val supportsOwnership: Boolean,
    val hermesHome: String,
    val installId: String,
    val profiles: List<String>
) {
    fun assertUsable() {
        require(RemoteLayout.SUPPORTED_REMOTE_OS.contains(platform)) {
            "SSH backends are supported on Linux and macOS only (remote reports '$platform')."
        }
        require(hermesPath.isNotBlank()) { "No Hermes CLI found on the remote host." }
        require(supportsOwnership) {
            "The remote Hermes is too old for SSH ownership (no --ssh-session-token-file/--ssh-owner-nonce)."
        }
    }
}

data class BackendConnection(
    val ownershipId: String,
    val spawnNonce: String,
    val backendPid: Long,
    val reusedExisting: Boolean,
    val remotePort: Int,
    val localPort: Int,
    val spawnToken: String,
    val servedToken: String,
    val profile: String = ""
) {
    val baseUrl: String get() = "http://127.0.0.1:$localPort"
}