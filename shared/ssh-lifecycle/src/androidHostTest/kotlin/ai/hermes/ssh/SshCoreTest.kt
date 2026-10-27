package ai.hermes.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests: no sshd, no network. */
class SshCoreTest {

    // ---- target parsing / validation ----------------------------------------

    @Test
    fun `parses bare host`() {
        val t = SshTarget.parse("box")
        assertEquals("box", t.host)
        assertNull(t.user)
        assertEquals(22, t.port)
        assertEquals("box", t.destination)
    }

    @Test
    fun `parses user host and port`() {
        val t = SshTarget.parse("alice@box:2222")
        assertEquals("box", t.host)
        assertEquals("alice", t.user)
        assertEquals(2222, t.port)
        assertEquals("alice@box", t.destination)
    }

    @Test
    fun `rejects hostile targets`() {
        assertThrows(IllegalArgumentException::class.java) { SshTarget.parse("box:abc") }
        assertThrows(IllegalArgumentException::class.java) { SshTarget.parse("bo x") }
        assertThrows(IllegalArgumentException::class.java) { SshTarget.parse("-oProxyCommand=evil") }
        assertThrows(IllegalArgumentException::class.java) { SshTarget.parse("box\u0000") }
        assertThrows(IllegalArgumentException::class.java) { SshTarget.parse("bo!x") }
        assertThrows(IllegalArgumentException::class.java) { SshTarget.parse("box:70000") }
        assertThrows(IllegalArgumentException::class.java) { SshTarget.of("box", "al ice") }
    }

    @Test
    fun `rejects relative identity file`() {
        assertThrows(IllegalArgumentException::class.java) { validateKeyPath("id_ed25519") }
        assertEquals("/home/x/.ssh/id_ed25519", validateKeyPath("/home/x/.ssh/id_ed25519"))
    }

    @Test
    fun `control socket path is length bounded`() {
        val t = SshTarget.parse("alice@box:2222")
        val path = ControlPath.of(t, "/tmp/hermes-ssh")
        assertTrue(path.endsWith("cm-alice@box:2222"))
        assertThrows(IllegalStateException::class.java) {
            ControlPath.of(t, "/" + "d".repeat(200))
        }
    }

    // ---- argv construction --------------------------------------------------

    @Test
    fun `master args carry multiplexing and keepalives`() {
        val args = SshArgs.master(SshTarget.parse("alice@box:2222"), "/tmp/hermes-ssh/cm-x")
        assertTrue(args.take(3) == listOf("-M", "-N", "-f"))
        assertTrue(args.contains("ControlMaster=auto"))
        assertTrue(args.contains("ControlPersist=300"))
        assertTrue(args.contains("BatchMode=yes"))
        assertTrue(args.contains("StrictHostKeyChecking=accept-new"))
        assertTrue(args.contains("ExitOnForwardFailure=yes"))
        assertTrue(args.contains("ServerAliveInterval=15"))
        assertTrue(args.contains("ServerAliveCountMax=3"))
        // host args present: non-default port
        assertTrue(args.containsAll(listOf("-p", "2222")))
        assertEquals("alice@box", args.last())
    }

    /**
     * The load-bearing rule found by running the real thing: control (`-O`)
     * invocations must repeat the host args, or `%p` expands against port 22 and
     * the control socket silently misses.
     */
    @Test
    fun `control args repeat host args`() {
        val t = SshTarget.parse("alice@box:2222")
        val fwd = SshArgs.control(t, "/tmp/cm", "forward", SshArgs.forwardArgs(1234, 9119))
        assertTrue(fwd.containsAll(listOf("-O", "forward")))
        assertTrue(fwd.containsAll(listOf("-p", "2222")))
        assertTrue(fwd.contains("127.0.0.1:1234:127.0.0.1:9119"))
        assertEquals("alice@box", fwd.last())
    }

    @Test
    fun `forward spec binds loopback only`() {
        assertEquals("127.0.0.1:8080:127.0.0.1:9119", SshArgs.forwardSpec(8080, 9119))
        assertFalse(SshArgs.forwardSpec(8080, 9119).startsWith("0.0.0.0"))
    }

    @Test
    fun `exec args separate options from destination`() {
        val args = SshArgs.exec(SshTarget.parse("alice@box"), "/tmp/cm", "uname -s")
        val dd = args.indexOf("--")
        assertTrue(dd > 0)
        assertEquals("alice@box", args[dd + 1])
        assertEquals("uname -s", args[dd + 2])
    }

    // ---- error taxonomy -----------------------------------------------------

    @Test
    fun `host key change wins over other markers`() {
        val banner = """
            @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
            @    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @
            @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@
            Permission denied (publickey).
        """.trimIndent()
        assertEquals(SshErrorKind.HOST_KEY_CHANGED, SshErrors.classify(banner))
        assertEquals(SshErrorKind.AUTH_FAILED, SshErrors.classify("user@box: Permission denied (publickey)."))
        assertEquals(SshErrorKind.INTERACTIVE_AUTH, SshErrors.classify("Tailscale SSH requires an additional check"))
        assertEquals(SshErrorKind.UNREACHABLE, SshErrors.classify("ssh: connect to host box port 22: Connection refused"))
        assertEquals(SshErrorKind.TIMEOUT, SshErrors.classify("", timedOut = true))
        assertEquals(SshErrorKind.UNKNOWN, SshErrors.classify("weird"))
    }

    // ---- lockfile -----------------------------------------------------------

    @Test
    fun `lockfile round trips and splits around the pid`() {
        val meta = LockMetadata(
            ownershipId = "a".repeat(32),
            profile = "default",
            tokenFingerprint = "f".repeat(32),
            hermesPath = "/opt/hermes",
            port = 0
        )
        val json = buildLockJson(meta)
        val (prefix, suffix) = splitLockJson(json)
        val published = prefix + "4242" + suffix
        assertTrue(published.contains("\"pid\":4242"))
        when (val read = readLockfile(published, meta.ownershipId)) {
            is LockfileRead.Owned -> {
                assertEquals(4242L, read.pid)
                assertEquals("default", read.profile)
                assertEquals("f".repeat(32), read.tokenFingerprint)
            }
            else -> error("expected Owned, got $read")
        }
    }

    @Test
    fun `lockfile skew is fail closed`() {
        val mine = "a".repeat(32)
        assertEquals(LockfileRead.Absent, readLockfile(null, mine))
        assertTrue(readLockfile("", mine) is LockfileRead.Skew)
        assertTrue(readLockfile("{\"pid\":1}", mine) is LockfileRead.Skew)
        assertTrue(
            readLockfile("{\"schemaVersion\":1,\"ownershipId\":\"$mine\",\"pid\":5}", mine) is LockfileRead.Skew
        )
        assertTrue(
            readLockfile("{\"schemaVersion\":2,\"ownershipId\":\"${"b".repeat(32)},\"pid\":5}", mine) is LockfileRead.Skew
        )
        assertTrue(readLockfile("{\"schemaVersion\":2,\"ownershipId\":\"$mine\"}", mine) is LockfileRead.Skew)
        val owned = readLockfile("{\"schemaVersion\":2,\"ownershipId\":\"$mine\",\"pid\":7,\"port\":9119}", mine)
        assertTrue(owned is LockfileRead.Owned && owned.port == 9119)
    }

    // ---- readiness ----------------------------------------------------------

    @Test
    fun `scrapes the ready port from a merged log`() {
        val log = "booting…\nHERMES_DASHBOARD_READY port=41337\nserving\n"
        assertEquals(41337, ReadySignal.parse(log))
        assertNull(ReadySignal.parse("nothing here"))
    }

    // ---- tokens -------------------------------------------------------------

    @Test
    fun `token minting and fingerprinting`() {
        val random = object : RandomSource {
            override fun hex(bytes: Int): String = "ab".repeat(bytes)
        }
        assertEquals(64, mintToken(random).length)
        val hasher = object : TokenHasher {
            override fun sha256Hex(value: String): String = value.repeat(16)
        }
        assertEquals(32, fingerprintToken(hasher, "xyz").length)
    }

    @Test
    fun `extracts injected session token from dashboard html`() {
        val html = "<script>window.__HERMES_SESSION_TOKEN__ = \"abc\\\"def\\u0041\";</script>"
        assertEquals("abc\"defA", DashboardToken.extractInjectedDashboardToken(html))
        assertNull(DashboardToken.extractInjectedDashboardToken("<html>no token</html>"))
        assertEquals("http://x:1/", DashboardToken.dashboardIndexUrl("http://x:1//"))
    }

    @Test
    fun `foreign backend tokens are refused only when our child is dead`() {
        assertTrue(DashboardToken.isForeignBackendToken("served", "spawn", childAlive = false))
        assertFalse(DashboardToken.isForeignBackendToken("served", "spawn", childAlive = true))
        assertFalse(DashboardToken.isForeignBackendToken("same", "same", childAlive = false))
        assertFalse(DashboardToken.isForeignBackendToken(null, "spawn", childAlive = false))
    }

    @Test
    fun `adoption picks up benign drift and refuses a foreign backend`() {
        val fetcher = object : HttpFetcher {
            override fun getText(url: String, timeoutMs: Int) =
                "<script>window.__HERMES_SESSION_TOKEN__ = \"served-token\";</script>"
        }
        val adopted = DashboardToken.adoptServedDashboardToken(fetcher, "http://127.0.0.1:9", "spawn-token", { true })
        assertEquals("served-token", adopted)

        val failure = assertThrows(IllegalStateException::class.java) {
            DashboardToken.adoptServedDashboardToken(fetcher, "http://127.0.0.1:9", "spawn-token", { false })
        }
        assertNotNull(failure.message)
        assertTrue(failure.message!!.contains("did not spawn"))
    }

    @Test
    fun `adoption falls back to the spawn token when the index is unreadable`() {
        val fetcher = object : HttpFetcher {
            override fun getText(url: String, timeoutMs: Int): String = throw RuntimeException("conn reset")
        }
        val adopted = DashboardToken.adoptServedDashboardToken(fetcher, "http://127.0.0.1:9", "spawn-token", { true })
        assertEquals("spawn-token", adopted)
    }

    // ---- paths (must match the server-side validator) -----------------------

    @Test
    fun `remote layout matches the server side validator`() {
        val ownership = "0123456789abcdef0123456789abcdef"
        val nonce = "0123456789abcdef"
        val token = RemoteLayout.spawnTokenPath(ownership, nonce)
        assertTrue(token.startsWith("~/.hermes/desktop-ssh/$ownership/"))
        assertTrue(token.endsWith("/$nonce.token"))
        assertEquals("~/.hermes/desktop-ssh/$ownership/backend.lock.json", RemoteLayout.lockfilePath(ownership))
        assertThrows(IllegalArgumentException::class.java) { RemoteLayout.ownershipId("tooshort") }
        assertThrows(IllegalArgumentException::class.java) { RemoteLayout.spawnNonce("ABCDEF0123456789") }
    }

    @Test
    fun `ownership id is stable per connection tuple`() {
        val hasher = object : TokenHasher {
            override fun sha256Hex(value: String): String = value.hashCode().toString().padStart(32, '0')
        }
        val a = SshBackendLifecycle.ownershipIdFor(SshTarget.parse("alice@box:2222"), "default", "/opt/hermes", hasher)
        val b = SshBackendLifecycle.ownershipIdFor(SshTarget.parse("alice@box:2222"), "default", "/opt/hermes", hasher)
        val c = SshBackendLifecycle.ownershipIdFor(SshTarget.parse("alice@box:2222"), "other", "/opt/hermes", hasher)
        assertEquals(a, b)
        assertFalse(a == c)
        assertEquals(32, a.length)
    }
}
