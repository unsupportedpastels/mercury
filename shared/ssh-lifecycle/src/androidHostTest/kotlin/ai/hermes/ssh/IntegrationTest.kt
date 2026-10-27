package ai.hermes.ssh

import ai.hermes.ssh.android.AndroidClock
import ai.hermes.ssh.android.AndroidHasher
import ai.hermes.ssh.android.AndroidHttpFetcher
import ai.hermes.ssh.android.AndroidPortPicker
import ai.hermes.ssh.android.AndroidRandom
import ai.hermes.ssh.android.AndroidSleeper
import ai.hermes.ssh.android.JschSshTransport
import ai.hermes.ssh.android.TofuHostKeyRepository
import ai.hermes.ssh.host.ProcessSshTransport
import com.jcraft.jsch.HostKeyRepository
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Everything here runs against a REAL sshd — a user-owned throwaway one on
 * 127.0.0.1:2222 with its own host key and authorized_keys (never the system
 * sshd, never the operator's ~/.ssh/authorized_keys). Tests skip when it is
 * absent, so the suite stays portable.
 */
object FixtureKeys {

    /**
     * Bytes of the fixture private key — the Android route, where there is no
     * key *file path* for the app to read.
     */
    fun keyBytes(): ByteArray? = File(Fixture.keyPathForTests).takeIf { it.isFile }?.readBytes()

    /**
     * A passphrase-protected copy of the same key pair (produced with
     * ssh-keygen). The public half is unchanged, so the fixture sshd still
     * authorizes it — which is what makes this a real test of passphrase
     * handling rather than a synthetic one.
     */
    fun encryptedKeyBytes(passphrase: String): ByteArray? {
        val source = File(Fixture.keyPathForTests)
        val publicKey = File("${Fixture.keyPathForTests}.pub")
        if (!source.isFile || !publicKey.isFile) return null
        val dir = File(System.getProperty("java.io.tmpdir"), "mercury-ssh-keycopy").apply { mkdirs() }
        val copy = File(dir, "id_copy")
        copy.delete()
        copy.writeBytes(source.readBytes())
        File("$copy.pub").writeBytes(publicKey.readBytes())
        copy.setReadable(false, false)
        copy.setWritable(false, false)
        copy.setExecutable(false, false)
        copy.setReadable(true, true)
        copy.setWritable(true, true) // ssh-keygen -p rewrites the file in place: 0600, not read-only.
        val process = ProcessBuilder(
            "ssh-keygen", "-p", "-q", "-N", passphrase, "-f", copy.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        if (process.waitFor() != 0) {
            System.err.println("ssh-keygen could not encrypt the key copy: $output")
            return null
        }
        return copy.readBytes()
    }
}

class IntegrationTest {

    private fun localServer(): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hello") { exchange ->
            val body = "tunneled".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return server
    }

    @Test
    fun `process transport reuses one master and tunnels http`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val transport = ProcessSshTransport(
            Fixture.target(),
            ProcessSshTransport.defaultControlDir(),
            knownHostsFile = Fixture.knownHosts("proc")
        )
        transport.openMaster()
        assertTrue("ControlMaster should be running", transport.check())

        assertEquals("Linux", transport.execProbe(RemoteScripts.probePlatform()).trim())
        assertEquals("hello", transport.exec("echo hello").trim())

        val server = localServer()
        val localPort = AndroidPortPicker().pick()
        transport.forward(localPort, server.address.port)
        try {
            assertEquals("tunneled", AndroidHttpFetcher().getText("http://127.0.0.1:$localPort/hello", 5_000))
        } finally {
            transport.cancelForward(localPort, server.address.port)
            server.stop(0)
        }

        transport.exitMaster()
        assertFalse("master should be gone after -O exit", transport.check())
    }

    @Test
    fun `watchdog kills a hung remote command and reports a timeout`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val transport = ProcessSshTransport(
            Fixture.target(),
            ProcessSshTransport.defaultControlDir(),
            knownHostsFile = Fixture.knownHosts("wd")
        )
        transport.openMaster()
        try {
            val started = System.currentTimeMillis()
            val failure = assertThrows(SshException::class.java) {
                transport.execProbe("sleep 30", timeoutSecs = 2)
            }
            val elapsed = System.currentTimeMillis() - started
            assertEquals(SshErrorKind.TIMEOUT, failure.kind)
            assertTrue("watchdog should fire near 2s, took ${elapsed}ms", elapsed in 1_000..12_000)
        } finally {
            transport.exitMaster()
        }
    }

    @Test
    fun `a non-zero probe verdict is data, not an error`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val transport = ProcessSshTransport(
            Fixture.target(),
            ProcessSshTransport.defaultControlDir(),
            knownHostsFile = Fixture.knownHosts("probe")
        )
        transport.openMaster()
        try {
            // Exits 1 with no output: execProbe must return, not throw.
            assertEquals("", transport.execProbe("exit 1", timeoutSecs = 5).trim())
        } finally {
            transport.exitMaster()
        }
    }

    @Test
    fun `tofu host keys accept new, then reject a changed key`() {
        val store = File(File(System.getProperty("java.io.tmpdir"), "hermes-ssh-test"), "tofu.known_hosts")
        store.delete()
        val repo = TofuHostKeyRepository(store)
        val key = ByteArray(32) { 7 }
        assertEquals(HostKeyRepository.OK, repo.check("box:2222", key))
        assertEquals(HostKeyRepository.OK, repo.check("box:2222", ByteArray(32) { 7 }))
        assertEquals(HostKeyRepository.CHANGED, repo.check("box:2222", ByteArray(32) { 9 }))
    }

    /** The Android route: no `ssh` binary, an SSH library instead. */
    @Test
    fun `library transport matches the cli transport`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val transport = JschSshTransport(Fixture.target(), Fixture.knownHosts("jsch"))
        transport.openMaster()
        assertTrue(transport.check())
        assertEquals("Linux", transport.exec("uname -s").trim())

        val server = localServer()
        val localPort = AndroidPortPicker().pick()
        transport.forward(localPort, server.address.port)
        try {
            assertEquals("tunneled", AndroidHttpFetcher().getText("http://127.0.0.1:$localPort/hello", 5_000))
        } finally {
            transport.cancelForward(localPort, server.address.port)
            server.stop(0)
        }
        transport.exitMaster()
    }

    /**
     * The Android route: the private key arrives as **bytes**, because the app
     * has no key file path to read (and Android has no `ssh` binary at all).
     * This is the code path the app's "Private key" mode uses.
     */
    @Test
    fun `in-memory key bytes authenticate like a key file`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val pem = FixtureKeys.keyBytes()
        assumeTrue("fixture private key unreadable", pem != null)

        val transport = JschSshTransport(
            Fixture.target(),
            Fixture.knownHosts("keybytes"),
            privateKeyPem = pem,
        )
        transport.openMaster()
        assertTrue(transport.check())
        assertEquals("Linux", transport.exec("uname -s").trim())
        transport.exitMaster()
    }

    /**
     * A passphrase-protected key must work with the passphrase and fail without
     * it — otherwise the field the user types would be silently ignored.
     */
    @Test
    fun `passphrase protected key needs the right passphrase`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val passphrase = "fixture-passphrase"
        val pem = FixtureKeys.encryptedKeyBytes(passphrase)
        assumeTrue("ssh-keygen could not produce an encrypted key copy", pem != null)

        val wrong = JschSshTransport(
            Fixture.target(),
            Fixture.knownHosts("keyenc-wrong"),
            privateKeyPem = pem,
            passphrase = "not-the-passphrase".toByteArray(),
        )
        val failure = assertThrows(SshException::class.java) { wrong.openMaster() }
        assertEquals(SshErrorKind.AUTH_FAILED, failure.kind)

        val right = JschSshTransport(
            Fixture.target(),
            Fixture.knownHosts("keyenc-right"),
            privateKeyPem = pem,
            passphrase = passphrase.toByteArray(),
        )
        right.openMaster()
        assertTrue(right.check())
        assertEquals("Linux", right.exec("uname -s").trim())
        right.exitMaster()
    }

    /**
     * With only a key supplied the transport must fail with an auth error, never
     * quietly fall through to password auth: the user needs to know the key is
     * the problem. Also pins JSch's "Auth fail" into the shared taxonomy.
     */
    @Test
    fun `a bad key fails as an auth error instead of falling back`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val junk = "-----BEGIN OPENSSH PRIVATE KEY-----\nnope\n-----END OPENSSH PRIVATE KEY-----\n"
            .toByteArray()
        val transport = JschSshTransport(
            SshTarget(host = Fixture.host, user = Fixture.user, port = Fixture.port),
            Fixture.knownHosts("junkkey"),
            privateKeyPem = junk,
        )
        val failure = assertThrows(SshException::class.java) { transport.openMaster() }
        assertEquals(SshErrorKind.KEY_INVALID, failure.kind)
    }

    /**
     * Pasting the `.pub` file instead of the private key is the classic mistake.
     * Whatever JSch makes of it, the app must get a typed SSH error — never an
     * unchecked parser exception it would have to show raw.
     */
    @Test
    fun `a pasted public key yields a typed error, not a parser crash`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        val publicKey = File("${Fixture.keyPathForTests}.pub").takeIf { it.isFile }?.readBytes()
        assumeTrue("fixture public key unreadable", publicKey != null)

        val transport = JschSshTransport(
            SshTarget(host = Fixture.host, user = Fixture.user, port = Fixture.port),
            Fixture.knownHosts("pubkey-as-private"),
            privateKeyPem = publicKey,
        )
        val failure = assertThrows(SshException::class.java) { transport.openMaster() }
        assertTrue(
            "expected an invalid-key or auth failure, got ${failure.kind}",
            failure.kind in setOf(SshErrorKind.KEY_INVALID, SshErrorKind.AUTH_FAILED),
        )
    }

    @Test
    fun `lifecycle spawns adopts reuses and tears down a backend`() {
        assumeTrue("fixture sshd not running", Fixture.available())
        assumeTrue("fake-hermes fixture missing", Fixture.fakeHermes.isNotBlank() && File(Fixture.fakeHermes).canExecute())

        val target = Fixture.target()
        val hasher = AndroidHasher()
        val random = AndroidRandom()
        val transport = ProcessSshTransport(
            target,
            ProcessSshTransport.defaultControlDir(),
            knownHostsFile = Fixture.knownHosts("lifecycle")
        )
        transport.openMaster()
        val lifecycle = SshBackendLifecycle(
            target, transport, AndroidPortPicker(), AndroidHttpFetcher(), AndroidClock(), random, hasher, AndroidSleeper()
        ) { println("[ssh] $it") }

        val probe = lifecycle.probe(remoteHermesPath = Fixture.fakeHermes)
        assertEquals("Linux", probe.platform)
        assertTrue("capability gate must see both ownership flags", probe.supportsOwnership)
        assertTrue("located ${probe.hermesPath}", probe.hermesPath.endsWith("fake-hermes"))

        val ownership = SshBackendLifecycle.ownershipIdFor(target, "default", probe.hermesPath, hasher)
        val nonce = random.hex(8)

        val conn = lifecycle.connect(probe, ownership, nonce, readyTimeoutMs = 45_000)
        assertTrue("no pid reported", conn.backendPid > 0)
        assertTrue("no remote port scraped", conn.remotePort > 0)
        assertFalse(conn.reusedExisting)
        assertEquals("fixture serves the spawn token", conn.spawnToken, conn.servedToken)

        // The tunnel really reaches the spawned backend.
        val status = AndroidHttpFetcher().getText("${conn.baseUrl}/api/status", 5_000)
        assertTrue(status.contains("auth_required"))
        val index = AndroidHttpFetcher().getText("${conn.baseUrl}/", 5_000)
        assertEquals(conn.servedToken, DashboardToken.extractInjectedDashboardToken(index))

        // The lockfile was republished with the resolved port + served fingerprint.
        val lock = lifecycle.readLock(ownership)
        assertTrue("expected an owned lockfile, got $lock", lock is LockfileRead.Owned)
        lock as LockfileRead.Owned
        assertEquals(conn.remotePort, lock.port)
        assertEquals(fingerprintToken(hasher, conn.servedToken), lock.tokenFingerprint)

        // Second connect reuses the live backend instead of spawning another.
        val conn2 = lifecycle.connect(probe, ownership, random.hex(8), readyTimeoutMs = 45_000)
        assertTrue("second connect should reuse", conn2.reusedExisting)
        assertEquals(conn.backendPid, conn2.backendPid)

        lifecycle.disconnect(conn2, stopBackend = true, closeMaster = false)
        assertFalse("owned backend should be stopped", lifecycle.isBackendAlive(conn.backendPid))
        assertEquals("ownership dir should be gone", LockfileRead.Absent, lifecycle.readLock(ownership))
        transport.exitMaster()
    }
}
