package ai.hermes.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assertions on the exact POSIX payloads sent to the remote host. */
class ScriptsTest {

    @Test
    fun `watchdog uses job control and kills the process group`() {
        val script = RemoteScripts.withRemoteTimeout("sleep 30", 7)
        assertTrue(script.contains("set -m"))
        assertTrue(script.contains("sleep 7"))
        assertTrue(script.contains("kill -9 -- -"))
        assertTrue(script.contains("exit \$__htrc"))
    }

    @Test
    fun `remote paths expand against the remote HOME`() {
        val expanded = expandRemotePath("~/.hermes/desktop-ssh/abc")
        assertEquals("\"\$HOME\"'/.hermes/desktop-ssh/abc'", expanded)
        assertEquals("'/opt/hermes'", expandRemotePath("/opt/hermes"))
        assertEquals("\"\$HOME\"", expandRemotePath("~"))
    }

    @Test
    fun `spawn script carries the isolated serve contract`() {
        val ownership = "a".repeat(32)
        val nonce = "b".repeat(16)
        val plan = RemoteScripts.SpawnPlan(
            ownershipId = ownership,
            spawnNonce = nonce,
            hermesPath = "/opt/hermes/bin/hermes",
            metadata = buildLockJson(LockMetadata(ownershipId = ownership, profile = "default"))
        )
        val script = RemoteScripts.buildSpawnPayload(plan)

        // 1. the exact remote argv the desktop spawns
        assertTrue(script.contains("serve --isolated --host 127.0.0.1 --port 0"))
        assertTrue(script.contains("--ssh-session-token-file"))
        assertTrue(script.contains("--ssh-owner-nonce $nonce"))
        assertTrue(script.contains("exec env HERMES_DESKTOP=1"))
        // 2. fd limits raised for the child only
        assertTrue(script.contains("ulimit -n 65536"))
        // 3. detached: setsid with nohup fallback, stdio detached, merged log
        assertTrue(script.contains("command -v setsid || echo nohup"))
        assertTrue(script.contains("</dev/null >>"))
        assertTrue(script.contains("2>&1 & echo \$!"))
        // 4. ownership: atomic reservation + reuse judge + atomic publish
        assertTrue(script.contains("while ! mkdir \"\$reservation\""))
        assertTrue(script.contains("trap 'rm -rf \"\$reservation\"' EXIT"))
        assertTrue(script.contains("printf EXISTING"))
        assertTrue(script.contains("temporary_lock="))
        assertTrue(script.contains("mv -f \"\$temporary_lock\" \"\$lock\""))
    }

    @Test
    fun `update marker gate wraps the payload`() {
        val ownership = "a".repeat(32)
        val plan = RemoteScripts.SpawnPlan(
            ownershipId = ownership,
            spawnNonce = "b".repeat(16),
            hermesPath = "/opt/hermes/bin/hermes",
            metadata = buildLockJson(LockMetadata(ownershipId = ownership))
        )
        val gated = RemoteScripts.buildSpawnScript(plan)
        assertTrue(gated.contains("MARKER_PRESENT"))
        assertTrue(gated.contains("exit 75"))
        assertTrue(gated.contains("flock -w 60"))
        // The payload is passed through shq(), so its single quotes appear escaped.
        val escapedPayload = RemoteScripts.buildSpawnPayload(plan).replace("'", "'\\''")
        assertTrue("gate must wrap the whole payload", gated.contains(escapedPayload))
        assertTrue(gated.contains("serve --isolated --host 127.0.0.1 --port 0"))
    }

    @Test
    fun `spawn token filename matches the server-side validator`() {
        val ownership = "c".repeat(32)
        val nonce = "d".repeat(16)
        val plan = RemoteScripts.SpawnPlan(
            ownershipId = ownership,
            spawnNonce = nonce,
            hermesPath = "hermes",
            metadata = buildLockJson(LockMetadata(ownershipId = ownership))
        )
        val script = RemoteScripts.buildSpawnScript(plan)
        assertTrue(script.contains("$nonce.token"))
        assertFalse(script.contains("spawn-$nonce.token"))
    }

    @Test
    fun `capability gate greps both ownership flags from serve help`() {
        val gate = RemoteScripts.supportsSshOwnership("/opt/hermes")
        assertTrue(gate.contains("serve --help"))
        assertTrue(gate.contains("ssh-session-token-file"))
        assertTrue(gate.contains("ssh-owner-nonce"))
        assertTrue(gate.contains("echo YES || echo NO"))
    }

    @Test
    fun `lockfile rewrite is atomic`() {
        val cmd = RemoteScripts.writeLockfile("a".repeat(32), "{\"pid\":5}")
        assertTrue(cmd.contains("temporary_lock="))
        assertTrue(cmd.contains("mv -f"))
        assertTrue(cmd.contains("{\"pid\":5}"))
    }

    @Test
    fun `locate prefers an explicit path then PATH`() {
        val cmd = RemoteScripts.locateHermes("/opt/fake/hermes")
        assertTrue(cmd.contains("'/opt/fake/hermes'"))
        assertTrue(cmd.indexOf("command -v hermes") < cmd.indexOf("/opt/fake/hermes"))
    }

    @Test
    fun `validation rejects bad nonce and ownership shapes`() {
        assertThrows(IllegalArgumentException::class.java) { RemoteLayout.spawnNonce("zzzz") }
        assertThrows(IllegalArgumentException::class.java) { RemoteLayout.ownershipId("00") }
    }

    @Test
    fun `spawn log pruning keeps the newest and never fails the connect`() {
        val ownershipId = "a".repeat(32)
        val cmd = RemoteScripts.pruneSpawnLogs(ownershipId)
        // Newest-first listing, drop the first `keep` entries, remove the rest, best effort.
        assertTrue(cmd.contains("ls -1t"))
        assertTrue(cmd.contains("tail -n +${RemoteLayout.SPAWN_LOG_KEEP + 1}"))
        assertTrue(cmd.contains("rm -f"))
        assertTrue(cmd.endsWith("|| true"))
        assertTrue(cmd.contains(ownershipId))
        // Only this run's nonce-named logs: never the lockfile, the token or the reservation.
        assertFalse(cmd.contains("*.json"))
        assertFalse(cmd.contains(".token"))
        assertFalse(cmd.contains("reserve"))
    }
}
