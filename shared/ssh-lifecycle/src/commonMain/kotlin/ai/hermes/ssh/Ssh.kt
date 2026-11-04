package ai.hermes.ssh

/**
 * SSH transport primitives.
 *
 * Faithful Kotlin port of the behavioural contract in
 * `apps/desktop/electron/ssh-connection.ts` (Hermes Agent, MIT).
 *
 * The desktop shells out to the system OpenSSH binary (`ssh -M -N -f`,
 * `ssh -O forward`, `ssh -O exit`). Android has no such binary, so an Android
 * client must reproduce the same semantics through an SSH library
 * (JSch / MINA sshd / sshj). This file keeps the *semantics* — argument set,
 * forwarding rule, multiplexing policy, error taxonomy — independent of which
 * transport implements them.
 */

/** One SSH destination, as typed into the connection form (`user@host:22`). */
data class SshTarget(
    val host: String,
    val user: String? = null,
    val port: Int = 22,
    val keyPath: String? = null
) {
    val destination: String get() = if (user.isNullOrBlank()) host else "$user@$host"

    companion object {
        val CONTROL_CHARS = Regex("[\\u0000-\\u001f\\u007f]")
        val HOSTNAME = Regex("[A-Za-z0-9._-]+")
        val USERNAME = Regex("[A-Za-z0-9._-]+")

        /** Parse `host`, `host:2222`, `user@host`, `user@host:2222`. */
        fun parse(spec: String, defaultPort: Int = 22): SshTarget {
            val raw = spec.trim()
            require(raw.isNotEmpty()) { "SSH host is required." }
            val at = raw.lastIndexOf('@')
            val user = if (at >= 0) raw.substring(0, at).ifEmpty { null } else null
            val rest = if (at >= 0) raw.substring(at + 1) else raw
            val colon = rest.lastIndexOf(':')
            val host: String
            val port: Int
            if (colon >= 0) {
                host = rest.substring(0, colon)
                val p = rest.substring(colon + 1)
                require(p.isNotEmpty() && p.all { it.isDigit() }) { "SSH port is invalid." }
                port = p.toInt()
            } else {
                host = rest
                port = defaultPort
            }
            return of(host, user, port)
        }

        fun of(host: String, user: String?, port: Int = 22, keyPath: String? = null): SshTarget =
            SshTarget(host, user, port, keyPath).validate()
    }

    /** Mirrors `validateSshTarget` — a hostile target string never reaches argv. */
    fun validate(): SshTarget {
        require(!CONTROL_CHARS.containsMatchIn(host)) { "SSH host contains control characters." }
        require(HOSTNAME.matches(host)) { "SSH host is invalid." }
        require(!host.startsWith("-")) { "SSH host may not start with '-'." }
        if (user != null) {
            require(user.isNotEmpty()) { "SSH user is empty." }
            require(USERNAME.matches(user)) { "SSH user is invalid." }
        }
        require(port in 1..65535) { "SSH port is out of range: $port" }
        keyPath?.let { validateKeyPath(it) }
        return this
    }
}

/** Mirrors `validateKeyPath`. */
fun validateKeyPath(path: String): String {
    require(path.startsWith("/")) { "SSH identity file must be an absolute path." }
    require(!SshTarget.CONTROL_CHARS.containsMatchIn(path)) {
        "SSH identity file contains control characters."
    }
    return path
}

/**
 * Control-socket naming. Unix sockets cap near 104–108 bytes, so the desktop
 * keeps the socket directory short and refuses an over-long parent
 * (`checkShortControlParent`).
 */
object ControlPath {
    const val MAX_LENGTH = 100

    fun of(target: SshTarget, dir: String, identity: String = ""): String {
        val name = "cm-${target.user ?: "anon"}@${target.host}:${target.port}$identity"
        val path = "${dir.trimEnd('/')}/$name"
        check(path.length <= MAX_LENGTH) {
            "Control socket path is too long (${path.length} > $MAX_LENGTH); use a shorter directory."
        }
        return path
    }
}

/**
 * Argument construction, 1:1 with the desktop's builders.
 *
 * One rule is load-bearing: **every** invocation — including `-O` control
 * operations — must carry the same host args (port, identity) or `%p`/`%h`
 * expansion in `ControlPath` resolves against the *default* port and the
 * control socket silently misses (verified against a real sshd on :2222).
 */
object SshArgs {
    const val CONTROL_PERSIST_SECONDS = 300
    const val CONTROL_FORWARD_KEEPALIVE_MS = 60_000
    const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    const val DEFAULT_EXEC_TIMEOUT_MS = 20_000

    fun baseOptions(controlPath: String?, connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): List<String> {
        val secs = maxOf(1, connectTimeoutMs / 1000)
        val mux = if (controlPath != null) {
            listOf("-o", "ControlPath=$controlPath", "-o", "ControlMaster=auto", "-o", "ControlPersist=$CONTROL_PERSIST_SECONDS")
        } else {
            emptyList()
        }
        return mux + listOf(
            "-o", "BatchMode=yes",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "ExitOnForwardFailure=yes",
            "-o", "ConnectTimeout=$secs",
            "-o", "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=3",
            "-o", "TCPKeepAlive=yes"
        )
    }

    fun hostArgs(target: SshTarget): List<String> {
        val args = mutableListOf<String>()
        if (target.port != 22) args += listOf("-p", target.port.toString())
        target.keyPath?.let { args += listOf("-i", validateKeyPath(it)) }
        return args
    }

    /** `ssh -M -N -f … user@host` — background once the master is up. */
    fun master(target: SshTarget, controlPath: String?, connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): List<String> =
        listOf("-M", "-N", "-f") + baseOptions(controlPath, connectTimeoutMs) + hostArgs(target) +
            listOf("--", target.destination)

    /** `ssh … -- user@host '<command>'` */
    fun exec(target: SshTarget, controlPath: String?, command: String, connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS): List<String> =
        baseOptions(controlPath, connectTimeoutMs) + hostArgs(target) +
            listOf("--", target.destination, command)

    /** `ssh -O check|forward|exit … -- user@host` */
    fun control(
        target: SshTarget,
        controlPath: String?,
        op: String,
        extra: List<String> = emptyList(),
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): List<String> =
        listOf("-O", op) + extra + baseOptions(controlPath, connectTimeoutMs) + hostArgs(target) +
            listOf("--", target.destination)

    /** `ssh -tt … -- user@host` — reuses the master, never re-authenticates. */
    fun interactive(
        target: SshTarget,
        controlPath: String?,
        remoteCwd: String? = null,
        remoteCommand: String? = null,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS
    ): List<String> {
        val args = mutableListOf("-tt")
        args += baseOptions(controlPath, connectTimeoutMs)
        args += hostArgs(target)
        args += listOf("--", target.destination)
        if (remoteCommand != null) {
            args += remoteCommand
        } else if (!remoteCwd.isNullOrBlank()) {
            args += "cd ${shq(remoteCwd)} 2>/dev/null; exec \"\$SHELL\" -l"
        } else {
            args += "exec \"\$SHELL\" -l"
        }
        return args
    }

    /**
     * Bind the local end to 127.0.0.1 only — never 0.0.0.0 — so the tunnel never
     * re-exposes the remote dashboard to the client's own network.
     */
    fun forwardSpec(localPort: Int, remotePort: Int, remoteHost: String = "127.0.0.1"): String =
        "127.0.0.1:$localPort:$remoteHost:$remotePort"

    fun forwardArgs(localPort: Int, remotePort: Int, remoteHost: String = "127.0.0.1"): List<String> =
        listOf("-L", forwardSpec(localPort, remotePort, remoteHost))
}

/** POSIX single-quoting. */
fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Quote for the login shell sshd invokes (fish treats `\` inside quotes, POSIX does not). */
fun loginShellQuote(value: String): String =
    "'" + value.replace("\\", "\\\\").replace("'", "'\\''") + "'"

/** Error taxonomy mirrors `classifySshError`; order matters. */
enum class SshErrorKind { UNREACHABLE, AUTH_FAILED, INTERACTIVE_AUTH, HOST_KEY_CHANGED, TIMEOUT, KEY_INVALID, UNKNOWN }

class SshException(
    val kind: SshErrorKind,
    message: String,
    val stderr: String? = null,
    val stdout: String? = null
) : Exception(message)

object SshErrors {
    private val HOST_KEY_CHANGED = Regex(
        "REMOTE HOST IDENTIFICATION HAS CHANGED|Host key verification failed|Offending (?:key|ECDSA|RSA|ED25519)",
        RegexOption.IGNORE_CASE
    )
    private val INTERACTIVE_AUTH = Regex(
        "Tailscale SSH requires an additional check|To authenticate, visit:\\s*https://login\\.tailscale\\.com/",
        RegexOption.IGNORE_CASE
    )
    private val AUTH_FAILED = Regex(
        "Permission denied \\((?:publickey|password|keyboard-interactive)\\)|Authentication failed|" +
            "Too many authentication failures|Auth fail|Auth cancel|USERAUTH_FAILURE",
        RegexOption.IGNORE_CASE
    )
    private val UNREACHABLE = Regex(
        "Connection refused|No route to host|Could not resolve hostname|Network is unreachable|" +
            "Connection timed out|Operation timed out|Connection closed by remote host",
        RegexOption.IGNORE_CASE
    )

    /** Host-key change is checked before auth: that banner also contains "WARNING". */
    fun classify(stderr: String, timedOut: Boolean = false): SshErrorKind {
        val text = stderr
        return when {
            HOST_KEY_CHANGED.containsMatchIn(text) -> SshErrorKind.HOST_KEY_CHANGED
            INTERACTIVE_AUTH.containsMatchIn(text) -> SshErrorKind.INTERACTIVE_AUTH
            AUTH_FAILED.containsMatchIn(text) -> SshErrorKind.AUTH_FAILED
            UNREACHABLE.containsMatchIn(text) -> SshErrorKind.UNREACHABLE
            timedOut -> SshErrorKind.TIMEOUT
            else -> SshErrorKind.UNKNOWN
        }
    }

    /** True when the remote shell reported the probe was killed (watchdog fired). */
    fun wasKilled(stderr: String?): Boolean {
        val text = stderr ?: return false
        return text.contains("Killed") || text.contains("Terminated") || text.contains("exit: 137")
    }

    fun message(kind: SshErrorKind, target: SshTarget): String = when (kind) {
        SshErrorKind.HOST_KEY_CHANGED ->
            "The host key for ${target.host} changed since it was first seen. Verify the server, then remove the stale key."
        SshErrorKind.AUTH_FAILED ->
            "SSH authentication failed for ${target.destination}. Check the account, the key (and its " +
                "passphrase), or use a password if the server allows one."
        SshErrorKind.INTERACTIVE_AUTH ->
            "The SSH server needs an interactive login (Tailscale SSH?). Complete it once, outside the app."
        SshErrorKind.UNREACHABLE -> "Cannot reach ${target.destination} over SSH."
        SshErrorKind.TIMEOUT -> "SSH to ${target.destination} timed out."
        SshErrorKind.KEY_INVALID ->
            "The private key could not be read. Paste the private key itself (id_ed25519 / id_rsa), not the " +
                ".pub file, and check the passphrase."
        SshErrorKind.UNKNOWN -> "SSH to ${target.destination} failed."
    }
}