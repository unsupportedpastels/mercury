package ai.hermes.ssh

/**
 * Remote backend lifecycle values.
 *
 * Faithful port of `apps/desktop/electron/remote-lifecycle.ts` (Hermes Agent, MIT).
 *
 * Layout on the remote host (verified against the server-side validator in
 * `hermes_cli/main_dashboard.py`, which requires exactly
 * `~/.hermes/desktop-ssh/<32 hex>/<16 hex>.token`):
 *
 * ```
 * ~/.hermes/desktop-ssh/<ownershipId:32hex>/
 *     <spawnNonce:16hex>.token      --ssh-session-token-file points here
 *     <spawnNonce:16hex>.log        backend stdout+stderr (">> log 2>&1")
 *     backend.lock.json             {schemaVersion, pid, profile, …}   (tmp + mv -f)
 *     .connect.lock/                atomic mkdir reservation (owner file inside)
 * ```
 */
object RemoteLayout {
    const val REMOTE_LOCK_DIR = "~/.hermes/desktop-ssh"
    const val LOCKFILE_SCHEMA_VERSION = 2
    const val PROTOCOL_VERSION = 1
    val SUPPORTED_REMOTE_OS = setOf("Linux", "Darwin")

    /** Desktop cold boots can take 60–150 s; floor at 45 s, poll fast. */
    const val DEFAULT_READY_TIMEOUT_MS = 120_000
    const val MIN_READY_TIMEOUT_MS = 45_000
    const val READY_POLL_INTERVAL_MS = 750L

    /** macOS sshd gives non-interactive shells a 256-FD limit; raise per child. */
    const val REMOTE_NOFILE_SOFT_LIMIT = 65_536

    const val PROBE_VERDICT_ATTEMPTS = 3
    const val PROBE_VERDICT_RETRY_MS = 500L
    const val REMOTE_PROBE_TIMEOUT_SECS = 15
    const val TOKEN_FETCH_TIMEOUT_MS = 3_000

    /** How many `<nonce>.log` spawn logs an ownership directory keeps before pruning the rest. */
    const val SPAWN_LOG_KEEP = 5

    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX16 = Regex("[0-9a-f]{16}")

    fun ownershipId(value: String): String {
        require(HEX32.matches(value)) { "SSH ownership ID is invalid." }
        return value
    }

    fun spawnNonce(value: String): String {
        require(HEX16.matches(value)) { "SSH spawn nonce is invalid." }
        return value
    }

    fun ownershipDirectory(ownershipId: String): String = "$REMOTE_LOCK_DIR/${ownershipId(ownershipId)}"
    fun lockfilePath(ownershipId: String): String = "${ownershipDirectory(ownershipId)}/backend.lock.json"
    fun reservationPath(ownershipId: String): String = "${ownershipDirectory(ownershipId)}/.connect.lock"
    fun spawnLogPath(ownershipId: String, spawnNonce: String): String =
        "${ownershipDirectory(ownershipId)}/${spawnNonce(spawnNonce)}.log"
    fun spawnTokenPath(ownershipId: String, spawnNonce: String): String =
        "${ownershipDirectory(ownershipId)}/${spawnNonce(spawnNonce)}.token"
}

/** Randomness/clock/hash are injected so this module stays multiplatform-pure. */
interface RandomSource {
    fun hex(bytes: Int): String
}

interface Clock {
    fun nowMillis(): Long
}

interface TokenHasher {
    fun sha256Hex(value: String): String
}

/** 32 random bytes, hex — the *spawn* credential (desktop `mintToken`). */
fun mintToken(random: RandomSource): String = random.hex(32)

/** SHA-256 hex truncated to 32 chars — the lockfile never stores the raw secret. */
fun fingerprintToken(hasher: TokenHasher, token: String): String = hasher.sha256Hex(token).take(32)

/** Metadata published in `backend.lock.json`. */
data class LockMetadata(
    val ownershipId: String,
    val profile: String = "",
    val tokenFingerprint: String = "",
    val hermesPath: String = "",
    val hermesHome: String = "~/.hermes",
    val installId: String = "",
    val host: String = "",
    val user: String = "",
    val port: Int = 22,
    val createdAtMillis: Long = 0L
)

/**
 * The lockfile body, built in two halves around an unquoted pid placeholder.
 *
 * The desktop substitutes the PID with a remote `sed`, which needs two levels
 * of quoting inside a shell payload. Splitting instead lets the spawn script do
 * `printf '%s%s%s' '<prefix>' "$child" '<suffix>'` with plain single-quoted
 * literals — no sed, nothing to mis-escape.
 */
fun buildLockJson(meta: LockMetadata): String {
    val fields = listOf(
        "\"schemaVersion\":${RemoteLayout.LOCKFILE_SCHEMA_VERSION}",
        "\"protocolVersion\":${RemoteLayout.PROTOCOL_VERSION}",
        "\"ownershipId\":\"${jsonEscape(meta.ownershipId)}\"",
        "\"pid\":__PID__",
        "\"profile\":\"${jsonEscape(meta.profile)}\"",
        "\"tokenFingerprint\":\"${jsonEscape(meta.tokenFingerprint)}\"",
        "\"hermesPath\":\"${jsonEscape(meta.hermesPath)}\"",
        "\"hermesHome\":\"${jsonEscape(meta.hermesHome)}\"",
        "\"installId\":\"${jsonEscape(meta.installId)}\"",
        "\"host\":\"${jsonEscape(meta.host)}\"",
        "\"user\":\"${jsonEscape(meta.user)}\"",
        "\"port\":${meta.port}",
        "\"createdAt\":${meta.createdAtMillis}"
    )
    return "{${fields.joinToString(",")}}"
}

const val LOCK_PID_PLACEHOLDER = "__PID__"

/** Split the lockfile body into the text before and after the pid placeholder. */
fun splitLockJson(json: String): Pair<String, String> {
    val at = json.indexOf(LOCK_PID_PLACEHOLDER)
    require(at >= 0) { "lockfile body has no pid placeholder" }
    return json.substring(0, at) to json.substring(at + LOCK_PID_PLACEHOLDER.length)
}

/**
 * Fail-closed skew: a lockfile that exists but is unparseable/foreign belongs
 * to some other build — never treat it as "no lockfile", never reap on it.
 */
sealed class LockfileRead {
    object Absent : LockfileRead()
    data class Owned(
        val pid: Long,
        val profile: String,
        val tokenFingerprint: String,
        val port: Int
    ) : LockfileRead()

    data class Skew(val reason: String) : LockfileRead()
}

fun readLockfile(json: String?, expectedOwnershipId: String, rawPid: Long? = null): LockfileRead {
    if (json == null) return LockfileRead.Absent
    val text = json.trim()
    if (text.isEmpty()) return LockfileRead.Skew("empty")
    val schema = Regex("\"schemaVersion\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: return LockfileRead.Skew("no schemaVersion")
    if (schema != RemoteLayout.LOCKFILE_SCHEMA_VERSION) return LockfileRead.Skew("schemaVersion=$schema")
    val ownership = Regex("\"ownershipId\"\\s*:\\s*\"([0-9a-f]{32})\"").find(text)?.groupValues?.get(1)
        ?: return LockfileRead.Skew("missing/foreign ownershipId")
    if (ownership != expectedOwnershipId) return LockfileRead.Skew("ownership mismatch")
    val pid = rawPid ?: Regex("\"pid\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()
        ?: return LockfileRead.Skew("no pid")
    val profile = Regex("\"profile\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""
    val tokenFp = Regex("\"tokenFingerprint\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""
    // The port is written back after readiness (0 until then); reuse reads it here.
    val port = Regex("\"port\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return LockfileRead.Owned(pid, profile, tokenFp, port)
}

/** The readiness line a freshly spawned `hermes serve --isolated` prints. */
object ReadySignal {
    val READY_RE = Regex("HERMES_DASHBOARD_READY port=(\\d+)")

    fun parse(logText: String?): Int? =
        READY_RE.find(logText ?: "")?.groupValues?.get(1)?.toIntOrNull()
}

private fun jsonEscape(value: String): String {
    val sb = StringBuilder()
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u").append(hex4(ch.code)) else sb.append(ch)
        }
    }
    return sb.toString()
}

private fun hex4(code: Int): String {
    val digits = "0123456789abcdef"
    return buildString {
        append(digits[(code shr 12) and 0xf])
        append(digits[(code shr 8) and 0xf])
        append(digits[(code shr 4) and 0xf])
        append(digits[code and 0xf])
    }
}