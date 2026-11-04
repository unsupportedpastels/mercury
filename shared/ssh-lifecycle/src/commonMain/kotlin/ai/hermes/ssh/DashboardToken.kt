package ai.hermes.ssh

/**
 * Session-token discovery.
 *
 * Port of `apps/desktop/electron/dashboard-token.ts` (Hermes Agent, MIT).
 *
 * Why this exists: the desktop can pass `HERMES_DASHBOARD_SESSION_TOKEN` when it
 * spawns a backend, but the *dashboard* is the source of truth for the token it
 * serves. If they drift, HTTP readiness probes still pass while `/api/ws`
 * rejects the client's token. So after readiness the client reads the token the
 * backend actually serves and uses that one — and stores only its fingerprint.
 */

/** Injected so the module stays transport-agnostic (JVM/Android/iOS fetch). */
interface HttpFetcher {
    /** GET a public URL as text; throws on non-2xx or timeout. */
    fun getText(url: String, timeoutMs: Int): String
}

object DashboardToken {
    val TOKEN_RE = Regex("window\\.__HERMES_SESSION_TOKEN__\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\")")

    /** Trim trailing slashes, then append one — the dashboard index. */
    fun dashboardIndexUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/"

    /** Pull the injected session token out of the dashboard HTML. */
    fun extractInjectedDashboardToken(html: String?): String? {
        val match = TOKEN_RE.find(html ?: "") ?: return null
        return jsonStringLiteral(match.groupValues[1])
    }

    /** Decode a JSON string literal (`"a\"b"` → `a"b`); null when malformed. */
    fun jsonStringLiteral(literal: String): String? {
        if (literal.length < 2 || !literal.startsWith("\"") || !literal.endsWith("\"")) return null
        val body = literal.substring(1, literal.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            if (ch != '\\') {
                sb.append(ch); i++; continue
            }
            if (i + 1 >= body.length) return null
            when (val esc = body[i + 1]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000c')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 5 >= body.length) return null
                    val hex = body.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16) ?: return null
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * A served token that differs from our spawn token while our child is DEAD
     * came from a process we did not spawn (an orphan squatting the port that
     * still satisfied the public readiness probe). With a live child the
     * mismatch is benign: our own backend regenerated it.
     */
    fun isForeignBackendToken(servedToken: String?, spawnToken: String, childAlive: Boolean): Boolean =
        !servedToken.isNullOrEmpty() && servedToken != spawnToken && !childAlive

    fun resolveServedDashboardToken(
        fetcher: HttpFetcher,
        baseUrl: String,
        fallbackToken: String,
        timeoutMs: Int = RemoteLayout.TOKEN_FETCH_TIMEOUT_MS
    ): String {
        val html = fetcher.getText(dashboardIndexUrl(baseUrl), timeoutMs)
        return extractInjectedDashboardToken(html) ?: fallbackToken
    }

    /**
     * Adopt the served token, refusing loudly on a foreign backend.
     * `childAlive` is a thunk so liveness is sampled *after* the fetch.
     */
    fun adoptServedDashboardToken(
        fetcher: HttpFetcher,
        baseUrl: String,
        spawnToken: String,
        childAlive: () -> Boolean,
        timeoutMs: Int = RemoteLayout.TOKEN_FETCH_TIMEOUT_MS,
        onLog: (String) -> Unit = {}
    ): String {
        val served = try {
            resolveServedDashboardToken(fetcher, baseUrl, spawnToken, timeoutMs)
        } catch (e: Exception) {
            onLog("[boot] could not read served dashboard token: ${e.message}")
            spawnToken
        }
        if (isForeignBackendToken(served, spawnToken, childAlive())) {
            throw IllegalStateException(
                "The backend exited and ${dashboardIndexUrl(baseUrl)} is served by a process we did not spawn; refusing its session token."
            )
        }
        return served
    }
}