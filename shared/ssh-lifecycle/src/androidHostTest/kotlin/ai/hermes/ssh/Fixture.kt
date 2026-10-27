package ai.hermes.ssh

import java.io.File
import java.net.Socket

/**
 * Wiring for the throwaway sshd the integration cases talk to.
 *
 * Nothing here touches the system sshd or the operator's
 * `~/.ssh/authorized_keys`: the fixture is a separate user-owned sshd on
 * 127.0.0.1:2222 with its own host key and its own authorized_keys file
 * (see tools/ssh-lifecycle-verify/fixture-sshd.sh).
 *
 * Everything is overridable by environment so the same tests can run against a
 * real host:
 *   HERMES_SSH_FIXTURE_HOST / _PORT / _USER / _KEY, HERMES_FAKE_HERMES
 */
object Fixture {
    val host: String = System.getenv("HERMES_SSH_FIXTURE_HOST") ?: "127.0.0.1"
    val port: Int = System.getenv("HERMES_SSH_FIXTURE_PORT")?.toIntOrNull() ?: 2222
    val user: String = System.getenv("HERMES_SSH_FIXTURE_USER") ?: System.getProperty("user.name")
    private val keyPath: String = System.getenv("HERMES_SSH_FIXTURE_KEY")
        ?: "${System.getProperty("user.home")}/.ssh/id_ed25519"

    /** Exposed so tests can read the key material for the in-memory route. */
    val keyPathForTests: String get() = keyPath

    /** The fake `hermes` CLI, located by walking up from the working directory. */
    val fakeHermes: String = System.getenv("HERMES_FAKE_HERMES")
        ?: findUpwards("tools/ssh-lifecycle-verify/fixtures/fake-hermes")

    private val runtimeDir = File(System.getProperty("java.io.tmpdir"), "mercury-ssh-test")

    fun available(): Boolean = try {
        Socket(host, port).use { true }
    } catch (e: Exception) {
        false
    }

    fun target(): SshTarget = SshTarget.of(host, user, port, keyPath.takeIf { File(it).isFile })

    fun knownHosts(name: String): String {
        runtimeDir.mkdirs()
        val f = File(runtimeDir, "$name.known_hosts")
        f.delete()
        return f.absolutePath
    }

    private fun findUpwards(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
            dir = dir.parentFile
        }
        return ""
    }
}
