package ai.hermes.ssh.host

import ai.hermes.ssh.ControlPath
import ai.hermes.ssh.SshArgs
import ai.hermes.ssh.SshErrorKind
import ai.hermes.ssh.SshErrors
import ai.hermes.ssh.SshException
import ai.hermes.ssh.SshTarget
import ai.hermes.ssh.SshTransport
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host-test oracle: drives the system OpenSSH binary exactly the way the
 * desktop does. Lives in the test source set on purpose — Android ships no
 * `ssh`, so the app uses [ai.hermes.ssh.android.JschSshTransport]; this class
 * exists to prove the Kotlin port reproduces the reference behaviour (argv
 * shape, ControlMaster reuse, `-O forward`, watchdog, error taxonomy).
 *
 * Two findings encoded here, both from running it against a real sshd:
 *  - every `-O` invocation must repeat the host args, or `%p`/`%h` in
 *    `ControlPath` expand against the default port and the socket silently
 *    misses;
 *  - injected `-o` options must be placed BEFORE `--`, since everything after
 *    the destination becomes remote-command arguments.
 */
class ProcessSshTransport(
    private val target: SshTarget,
    controlDir: String,
    private val sshBinary: String = System.getenv("SSH_BINARY") ?: "ssh",
    private val connectTimeoutMs: Int = SshArgs.DEFAULT_CONNECT_TIMEOUT_MS,
    private val knownHostsFile: String? = null,
    private val strictHostKeyChecking: String = "accept-new",
    private val extraOptions: List<String> = emptyList()
) : SshTransport {

    val controlPath: String = ControlPath.of(target, controlDir)

    override fun openMaster() {
        run(SshArgs.master(target, controlPath, connectTimeoutMs) + extraOptions, 30_000)
    }

    override fun check(): Boolean = try {
        run(SshArgs.control(target, controlPath, "check") + extraOptions, 15_000)
        // Success is the proof: OpenSSH prints "Master running (pid=N)" on
        // stderr, not stdout, so only the exit status is reliable.
        true
    } catch (e: SshException) {
        false
    }

    override fun exec(command: String, timeoutMs: Int): String =
        run(SshArgs.exec(target, controlPath, command, connectTimeoutMs) + extraOptions, timeoutMs)

    override fun forward(localPort: Int, remotePort: Int, remoteHost: String) {
        run(
            SshArgs.control(
                target, controlPath, "forward",
                SshArgs.forwardArgs(localPort, remotePort, remoteHost)
            ) + extraOptions,
            25_000
        )
    }

    override fun cancelForward(localPort: Int, remotePort: Int, remoteHost: String) {
        try {
            run(
                SshArgs.control(
                    target, controlPath, "cancel",
                    SshArgs.forwardArgs(localPort, remotePort, remoteHost)
                ) + extraOptions,
                15_000
            )
        } catch (e: SshException) {
            // Best effort: a dead master has nothing to cancel.
        }
    }

    override fun exitMaster() {
        try {
            run(SshArgs.control(target, controlPath, "exit") + extraOptions, 15_000)
        } catch (e: SshException) {
            // The master may already be gone.
        }
    }

    private fun run(args: List<String>, timeoutMs: Int): String {
        val injected = mutableListOf<String>()
        if (knownHostsFile != null) injected += listOf("-o", "UserKnownHostsFile=$knownHostsFile")
        // OpenSSH keeps the FIRST value for a keyword, so a later -o cannot
        // override baseOptions' accept-new; only a distinct keyword can be added.
        if (strictHostKeyChecking != "accept-new") {
            injected += listOf("-o", "StrictHostKeyChecking=$strictHostKeyChecking")
        }
        injected += extraOptions

        val separators = args.indexOf("--")
        val full = mutableListOf(sshBinary)
        if (separators >= 0) {
            full += args.take(separators)
            full += injected
            full += args.drop(separators)
        } else {
            full += args
            full += injected
        }

        val pb = ProcessBuilder(full)
        pb.redirectErrorStream(false)
        pb.environment()["SSH_ASKPASS"] = "/bin/false"
        val process = pb.start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw SshException(
                SshErrorKind.TIMEOUT,
                "SSH command timed out after ${timeoutMs}ms: ${full.joinToString(" ")}",
                stderr
            )
        }
        if (process.exitValue() != 0) {
            val exit = process.exitValue()
            val kind = if (exit == 137 || SshErrors.wasKilled(stderr)) {
                SshErrorKind.TIMEOUT
            } else {
                SshErrors.classify(stderr)
            }
            throw SshException(
                kind,
                SshErrors.message(kind, target) + " [${stderr.trim().take(300)}]",
                stderr,
                stdout
            )
        }
        return stdout
    }

    companion object {
        fun defaultControlDir(): String {
            val candidates = listOf(System.getenv("XDG_RUNTIME_DIR"), System.getProperty("java.io.tmpdir"), "/tmp")
            val dir = candidates.filterNotNull().firstOrNull { it.length <= 24 } ?: "/tmp"
            val path = File(dir, "mercury-ssh-test")
            path.mkdirs()
            return path.absolutePath
        }
    }
}