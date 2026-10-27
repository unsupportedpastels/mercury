package ai.hermes.ssh.android

import ai.hermes.ssh.SshErrorKind
import ai.hermes.ssh.SshErrors
import ai.hermes.ssh.SshException
import ai.hermes.ssh.SshTarget
import ai.hermes.ssh.SshTransport
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.File
import java.util.Base64

/**
 * Library-based transport — the shape an **Android** build must use, because
 * Android ships no system `ssh` binary.
 *
 * Every desktop CLI behaviour it stands in for:
 *
 * | desktop flag                       | library equivalent                        |
 * |------------------------------------|-------------------------------------------|
 * | `-M -N -f` + ControlMaster         | one long-lived `Session` reused by all channels |
 * | `-O forward -L …`                  | `Session.setPortForwardingL`              |
 * | `ExitOnForwardFailure=yes`         | `setPortForwardingL` throws on bind failure |
 * | `BatchMode=yes`                    | no interactive `UserInfo` prompts         |
 * | `StrictHostKeyChecking=accept-new` | `TofuHostKeyRepository` (below)           |
 * | `ServerAliveInterval/CountMax`     | `setServerAliveInterval/CountMax`         |
 * | `ssh host '<cmd>'`                 | `ChannelExec`, stderr demultiplexed       |
 */

/** Identity label JSch associates with an in-memory private key. */
private const val IN_MEMORY_IDENTITY = "hermes-ssh"
class JschSshTransport(
    private val target: SshTarget,
    private val knownHostsFile: String,
    private val connectTimeoutMs: Int = 15_000,
    private val password: String? = null,
    /**
     * In-memory private key (PEM or OpenSSH format). This is the Android route:
     * there is no key *file path* to hand to a binary, and the user's key lives
     * in app storage. [SshTarget.keyPath] remains for the desktop-style flow.
     */
    private val privateKeyPem: ByteArray? = null,
    /** Passphrase for an encrypted [privateKeyPem]; null when it is not encrypted. */
    private val passphrase: ByteArray? = null,
) : SshTransport {

    private var session: Session? = null

    override fun openMaster() {
        if (session?.isConnected == true) return
        val jsch = JSch()
        try {
            when {
                privateKeyPem != null ->
                    jsch.addIdentity(IN_MEMORY_IDENTITY, privateKeyPem, null, passphrase)
                target.keyPath != null -> jsch.addIdentity(target.keyPath)
            }
        } catch (error: Exception) {
            // JSch's key parser throws bare runtime exceptions on malformed
            // input — a truncated OpenSSH blob gives an
            // ArrayIndexOutOfBoundsException — so a pasted public key or a
            // half-copied private key must not surface as an unchecked crash.
            throw SshException(
                kind = SshErrorKind.KEY_INVALID,
                message = SshErrors.message(SshErrorKind.KEY_INVALID, target),
                stderr = error.message,
            )
        }
        jsch.hostKeyRepository = TofuHostKeyRepository(File(knownHostsFile))

        val s = jsch.getSession(target.user ?: System.getProperty("user.name"), target.host, target.port)
        s.setConfig("StrictHostKeyChecking", "ask")
        s.timeout = connectTimeoutMs
        s.serverAliveInterval = 15_000
        s.serverAliveCountMax = 3
        password?.let { s.setPassword(it) }
        // Pin the order to what was actually supplied: a key-only connection must
        // not silently fall through to password auth (and vice versa), so a
        // failure reports the real reason instead of a generic auth error.
        val keyPresent = privateKeyPem != null || target.keyPath != null
        when {
            keyPresent && password != null ->
                s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
            keyPresent -> s.setConfig("PreferredAuthentications", "publickey")
            password != null -> s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        }
        s.userInfo = NoPromptUserInfo
        try {
            s.connect(connectTimeoutMs)
        } catch (error: JSchException) {
            // JSch speaks its own dialect ("Auth fail", "socket is not
            // established"); map it into the shared error taxonomy so callers
            // get an actionable reason instead of a library-specific string.
            val text = error.message ?: ""
            val kind = SshErrors.classify(text, timedOut = text.contains("timeout", ignoreCase = true))
            throw SshException(
                kind = kind,
                message = SshErrors.message(kind, target),
                stderr = text,
            )
        }
        session = s
    }

    override fun check(): Boolean = session?.isConnected == true

    override fun exec(command: String, timeoutMs: Int): String {
        val s = session ?: error("SSH session is not open")
        val channel = s.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val out = channel.inputStream
        val err = channel.errStream
        channel.connect(timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(4096)
        try {
            while (true) {
                if (System.currentTimeMillis() > deadline) {
                    throw SshException(SshErrorKind.TIMEOUT, "SSH exec timed out after ${timeoutMs}ms")
                }
                if (out.available() > 0) {
                    val n = out.read(buf)
                    if (n > 0) stdout.append(String(buf, 0, n))
                }
                if (err.available() > 0) {
                    val n = err.read(buf)
                    if (n > 0) stderr.append(String(buf, 0, n))
                }
                if (channel.isClosed && out.available() == 0 && err.available() == 0) break
                Thread.sleep(20)
            }
        } finally {
            channel.disconnect()
        }
        val exit = channel.exitStatus
        if (exit != 0) {
            val text = stderr.toString()
            val kind = if (exit == 137) SshErrorKind.TIMEOUT else SshErrors.classify(text)
            throw SshException(
                kind,
                "Remote command failed (exit $exit): ${text.trim().take(300)}",
                text,
                stdout.toString()
            )
        }
        return stdout.toString()
    }

    override fun forward(localPort: Int, remotePort: Int, remoteHost: String) {
        val s = session ?: error("SSH session is not open")
        // Throws on bind failure — the ExitOnForwardFailure analogue.
        s.setPortForwardingL(localPort, remoteHost, remotePort)
    }

    override fun cancelForward(localPort: Int, remotePort: Int, remoteHost: String) {
        runCatching { session?.delPortForwardingL(localPort) }
    }

    override fun exitMaster() {
        runCatching { session?.disconnect() }
        session = null
    }

    /** No interactive prompts: a key needing a passphrase or 2FA must fail, like BatchMode. */
    private object NoPromptUserInfo : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}
    }
}

/**
 * Trust-on-first-use host keys with changed-key detection — the library-level
 * replacement for `StrictHostKeyChecking=accept-new`.
 *
 * Unknown host: persist and accept. Known host presenting a *different* key:
 * CHANGED, which is what `Host key verification failed` means on the CLI path.
 *
 * Note the JSch contract, which differs from expectation: `check` receives the
 * raw key bytes, `add` receives a `HostKey` whose key is base64 text, and
 * `remove` has two overloads — all four abstract members must be implemented.
 */
class TofuHostKeyRepository(private val store: File) : HostKeyRepository {

    override fun check(host: String, key: ByteArray): Int {
        if (key.isEmpty()) return HostKeyRepository.OK
        val encoded = Base64.getEncoder().encodeToString(key)
        val entries = read()
        val existing = entries[host]
        if (existing == null) {
            entries[host] = encoded
            write(entries)
            return HostKeyRepository.OK
        }
        return if (existing == encoded) HostKeyRepository.OK else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        val entries = read()
        entries[hostkey.host] = hostkey.getKey()
        write(entries)
    }

    override fun remove(host: String?, type: String?) {
        val entries = read()
        if (host == null) entries.clear() else entries.remove(host)
        write(entries)
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        val entries = read()
        if (host == null) {
            entries.clear()
        } else if (key == null || entries[host] == Base64.getEncoder().encodeToString(key)) {
            entries.remove(host)
        }
        write(entries)
    }

    override fun getKnownHostsRepositoryID(): String = "tofu:$store"

    override fun getHostKey(): Array<HostKey> = read().entries.mapNotNull { (host, key) -> hostKey(host, key) }.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey>? {
        val key = read()[host ?: return null] ?: return null
        return hostKey(host, key)?.let { arrayOf(it) }
    }

    private fun hostKey(host: String, base64Key: String): HostKey? = try {
        HostKey(host, Base64.getDecoder().decode(base64Key))
    } catch (e: JSchException) {
        null
    }

    private fun read(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        if (!store.isFile) return map
        store.readLines().forEach { line ->
            val idx = line.indexOf(' ')
            if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1).trim()
        }
        return map
    }

    private fun write(entries: Map<String, String>) {
        store.parentFile?.mkdirs()
        store.writeText(entries.entries.joinToString("\n") { (host, key) -> "$host $key" })
    }
}