package com.unsupportedpastels.mercury.core.artifacts

/**
 * Extracts only explicit, bounded artifact references from transcript text,
 * decided once for both clients. This layer does not fetch, preview, or
 * interpret arbitrary prose. A source must be a standalone MEDIA directive, a
 * standalone HTTPS URL, or an explicit Markdown link/image link.
 *
 * The transcript budget counts UTF-16 code units on both platforms (Kotlin
 * String semantics), which resolves the previous Android/iOS divergence where
 * Swift truncated by grapheme cluster but consumed budget by UTF-16 unit.
 *
 * Where the Android original used `java.net.URI`/`URLDecoder`, this version
 * uses a strict structural HTTPS parse and explicit percent-decoding with the
 * same observable accept/reject behavior.
 */
object ArtifactExtractor {
    private const val MEDIA_PREFIX = "MEDIA:"
    private const val MANAGED_ID_PREFIX = "managed:"
    private const val REMOTE_ID_PREFIX = "remote:"
    private val markdownLinkPattern = Regex(
        """(!?)\[([^]\r\n]{1,512})\]\(\s*(<[^>\r\n]{1,4096}>|[^()\s\r\n]+)\s*\)""",
    )
    private val typePrefixPattern = Regex("(?i)^(image|audio|file|video)\\s*:\\s*")
    private val imageExtensions = setOf("bmp", "gif", "heic", "jpeg", "jpg", "png", "tif", "tiff", "webp")
    private val audioExtensions = setOf("aac", "flac", "m4a", "mp3", "oga", "ogg", "opus", "wav")

    /** Extract from every transcript message text, in message order. */
    fun extract(
        texts: List<String>,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> {
        val artifacts = ArrayList<Artifact>(limits.maxItems)
        val identities = HashSet<String>(limits.maxItems)
        var consumed = 0

        for (messageText in texts) {
            if (artifacts.size >= limits.maxItems || consumed >= limits.maxTranscriptChars) break
            val remaining = limits.maxTranscriptChars - consumed
            val text = messageText.take(remaining)
            consumed += text.length
            extractFromText(text, limits, artifacts, identities)
        }
        return artifacts
    }

    fun extract(
        text: String,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> = extract(listOf(text), limits)

    private fun extractFromText(
        text: String,
        limits: ArtifactExtractionLimits,
        artifacts: MutableList<Artifact>,
        identities: MutableSet<String>,
    ) {
        val candidates = ArrayList<Candidate>()
        // Line-oriented extraction mirrors the first-party TUI's standalone
        // MEDIA grammar while intentionally excluding its inline/prose form.
        var lineOffset = 0
        text.split('\n').forEach { rawLine ->
            val line = rawLine.removeSuffix("\r")
            val directive = standaloneMediaSource(line)
            if (directive != null) {
                candidates += Candidate(lineOffset, directive, null, null)
            } else {
                standaloneHttpsSource(line)?.let { source ->
                    candidates += Candidate(lineOffset, source, null, null)
                }
            }
            lineOffset += rawLine.length + 1
        }

        markdownLinkPattern.findAll(text).forEach { match ->
            val isImageLink = match.groupValues[1] == "!"
            val label = match.groupValues[2]
            val destination = match.groupValues[3].let { token ->
                if (token.length >= 2 && token.startsWith('<') && token.endsWith('>')) {
                    token.substring(1, token.length - 1)
                } else {
                    token
                }
            }
            candidates += Candidate(
                offset = match.range.first,
                source = destination,
                labelHint = label,
                forcedType = if (isImageLink) ArtifactType.Image else null,
            )
        }

        // Stable by construction: candidates are appended in scan order and
        // sortedBy is stable, so equal offsets keep line-candidates first.
        candidates.sortBy { it.offset }
        candidates.forEach { candidate ->
            if (artifacts.size < limits.maxItems) {
                addCandidate(
                    source = candidate.source,
                    labelHint = candidate.labelHint,
                    forcedType = candidate.forcedType,
                    limits = limits,
                    artifacts = artifacts,
                    identities = identities,
                )
            }
        }
    }

    private data class Candidate(
        val offset: Int,
        val source: String,
        val labelHint: String?,
        val forcedType: ArtifactType?,
    )

    /** Parses only the standalone directive grammar; callers must validate the returned source before fetching it. */
    fun standaloneMediaSource(line: String): String? {
        var body = line.trim(' ', '\t')
        if (body.isEmpty()) return null

        // The desktop/TUI grammar accepts an optional quote around the whole
        // tag as well as an optional quote/backtick around its value.
        val wrapper = body.firstOrNull()?.takeIf { it in QUOTES }
        if (wrapper != null) {
            if (body.length < 2 || body.last() != wrapper) return null
            body = body.substring(1, body.length - 1).trim(' ', '\t')
        }
        if (!body.startsWith(MEDIA_PREFIX)) return null

        var payload = body.removePrefix(MEDIA_PREFIX).trim(' ', '\t')
        if (payload.isEmpty()) return null
        val quote = payload.firstOrNull()?.takeIf { it in QUOTES }
        if (quote != null) {
            if (payload.length < 2 || payload.last() != quote) return null
            payload = payload.substring(1, payload.length - 1)
            if (payload.isEmpty()) return null
        } else {
            // Unquoted first-party values are one non-whitespace token. A
            // stray quote/backtick is malformed rather than a path.
            if (payload.any { it.isWhitespace() || it in QUOTES }) return null
        }
        return payload.takeIf { it.isNotBlank() }
    }

    private fun standaloneHttpsSource(line: String): String? {
        val trimmed = line.trim(' ', '\t')
        if (trimmed.isEmpty()) return null
        val source = if (trimmed.length >= 2 && trimmed.startsWith('<') && trimmed.endsWith('>')) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        if (source.any { it.isWhitespace() }) return null
        return source.takeIf { it.startsWith("https://", ignoreCase = true) }
    }

    private fun addCandidate(
        source: String,
        labelHint: String?,
        forcedType: ArtifactType?,
        limits: ArtifactExtractionLimits,
        artifacts: MutableList<Artifact>,
        identities: MutableSet<String>,
    ) {
        if (source.length > limits.maxSourceChars || source.length > limits.maxLocationChars) return
        if (source.any(::isHostileControl)) return

        val resolved = resolveSource(source) ?: return
        if (resolved.location.length > limits.maxLocationChars) return
        val identity = resolved.identity
        if (!identities.add(identity)) return

        val type = forcedType ?: inferType(resolved.location, labelHint)
        val displayName = displayName(resolved.location, labelHint, limits.maxDisplayNameChars)
        artifacts += Artifact(
            stableIdentity = identity,
            type = type,
            origin = resolved.origin,
            source = resolved.location,
            displayName = displayName,
        )
    }

    private data class ResolvedSource(
        val origin: ArtifactOrigin,
        val location: String,
        val identity: String,
    )

    private fun resolveSource(source: String): ResolvedSource? {
        if (isManagedPath(source)) {
            val canonical = canonicalManagedPath(source) ?: return null
            return ResolvedSource(
                origin = ArtifactOrigin.ManagedPath,
                location = canonical,
                identity = MANAGED_ID_PREFIX + canonical,
            )
        }
        val url = parseSafeHttpsUrl(source) ?: return null
        val canonical = canonicalRemoteUrl(url) ?: return null
        return ResolvedSource(
            origin = ArtifactOrigin.RemoteUrl,
            location = canonical,
            identity = REMOTE_ID_PREFIX + canonical,
        )
    }

    private fun isManagedPath(value: String): Boolean =
        value.startsWith('/') && !value.startsWith("//") && !value.contains('\\')

    private fun canonicalManagedPath(value: String): String? {
        val segments = value.split('/')
        if (segments.size <= 1 || segments.last().isEmpty()) return null
        val normalized = ArrayList<String>(segments.size)
        for (segment in segments.drop(1)) {
            when (segment) {
                "", "." -> Unit
                ".." -> return null
                else -> normalized += segment
            }
        }
        if (normalized.isEmpty()) return null
        return "/" + normalized.joinToString("/")
    }

    /** The accepted structural subset of an HTTPS URL. */
    private data class HttpsUrl(
        val host: String,
        val port: Int,
        val rawPath: String,
        val rawQuery: String?,
    )

    /**
     * Strict structural HTTPS parse with the same observable accept/reject
     * behavior as the original `java.net.URI` gate: https scheme only, no
     * userinfo, no fragment, valid percent-encoding, no whitespace/control,
     * numeric port, non-blank hostname.
     */
    private fun parseSafeHttpsUrl(value: String): HttpsUrl? {
        if (!value.startsWith("https://", ignoreCase = true)) return null
        if (value.containsEncodedControl() || value.any(::isHostileControl)) return null
        if (value.any { it.isWhitespace() }) return null
        if (value.contains('#')) return null
        if (!hasValidPercentEncoding(value)) return null

        val afterScheme = value.substring("https://".length)
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' }
            .let { if (it == -1) afterScheme.length else it }
        val authority = afterScheme.substring(0, authorityEnd)
        if (authority.isEmpty() || authority.contains('@')) return null

        val host: String
        var port = -1
        val lastColon = authority.lastIndexOf(':')
        if (lastColon >= 0 && authority.indexOf(':') == lastColon) {
            host = authority.substring(0, lastColon)
            val portText = authority.substring(lastColon + 1)
            if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return null
            port = portText.toIntOrNull() ?: return null
            if (port !in 0..65535) return null
        } else if (lastColon == -1) {
            host = authority
        } else {
            // Multiple colons (unbracketed IPv6 etc.) are rejected, matching
            // the previous host.contains(':') rejection.
            return null
        }
        if (host.isBlank()) return null

        val remainder = afterScheme.substring(authorityEnd)
        val queryStart = remainder.indexOf('?')
        val rawPath = if (queryStart == -1) remainder else remainder.substring(0, queryStart)
        val rawQuery = if (queryStart == -1) null else remainder.substring(queryStart + 1)

        val normalizedHost = host.lowercase()
        if (
            normalizedHost == "localhost" ||
            normalizedHost.endsWith(".localhost") ||
            normalizedHost.endsWith(".local") ||
            normalizedHost.all { it.isDigit() || it == '.' }
        ) return null
        if (normalizedHost.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        if (port != -1 && port != 443) return null
        return HttpsUrl(host = host, port = port, rawPath = rawPath, rawQuery = rawQuery)
    }

    private fun canonicalRemoteUrl(url: HttpsUrl): String? {
        val host = url.host.lowercase()
        val port = if (url.port == -1 || url.port == 443) "" else ":${url.port}"
        val path = url.rawPath.ifEmpty { "/" }
        if (path.split('/').any { it == ".." }) return null
        val query = url.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$host$port$path$query"
    }

    private fun inferType(source: String, labelHint: String?): ArtifactType {
        val name = pathName(source).ifBlank { labelHint.orEmpty() }
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension in imageExtensions -> ArtifactType.Image
            extension in audioExtensions -> ArtifactType.Audio
            extension in ManagedVideoPolicy.extensions -> ArtifactType.Video
            typePrefixPattern.find(labelHint.orEmpty())?.groupValues?.get(1)?.lowercase() == "video" -> ArtifactType.Video
            typePrefixPattern.find(labelHint.orEmpty())?.groupValues?.get(1)?.lowercase() == "image" -> ArtifactType.Image
            typePrefixPattern.find(labelHint.orEmpty())?.groupValues?.get(1)?.lowercase() == "audio" -> ArtifactType.Audio
            else -> ArtifactType.File
        }
    }

    private fun displayName(source: String, labelHint: String?, maxLength: Int): String {
        val sourceName = pathName(source)
        val hint = typePrefixPattern.replace(labelHint.orEmpty(), "")
        val candidate = sourceName.ifBlank { hint }.ifBlank { "artifact" }
        val sanitized = candidate
            .map { character ->
                when {
                    isHostileControl(character) || character == '/' || character == '\\' -> '_'
                    else -> character
                }
            }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .ifBlank { "artifact" }
        return sanitized.take(maxLength).ifBlank { "artifact" }
    }

    /**
     * Last path segment of a canonical location, percent-decoded the same way
     * the original `URI(source).path` + `URLDecoder.decode` pipeline did:
     * decode the path once, take the final segment, then form-decode it
     * (tolerantly — a malformed escape leaves the segment as-is).
     */
    private fun pathName(source: String): String {
        val rawPath = if (source.startsWith("https://", ignoreCase = true)) {
            val afterScheme = source.substring("https://".length)
            val start = afterScheme.indexOfFirst { it == '/' || it == '?' }
            if (start == -1 || afterScheme[start] == '?') "" else {
                afterScheme.substring(start).substringBefore('?')
            }
        } else {
            source.substringBefore('?').substringBefore('#')
        }
        val decodedPath = percentDecode(rawPath, plusIsSpace = false) ?: source.substringBefore('?').substringBefore('#')
        val name = decodedPath.substringAfterLast('/')
        return percentDecode(name, plusIsSpace = true) ?: name
    }

    private fun hasValidPercentEncoding(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length ||
                    !value[index + 1].isHexDigit() ||
                    !value[index + 2].isHexDigit()
                ) return false
                index += 3
            } else {
                index += 1
            }
        }
        return true
    }

    /** UTF-8 percent-decoding; null when an escape is malformed. */
    private fun percentDecode(value: String, plusIsSpace: Boolean): String? {
        if (!value.contains('%') && !(plusIsSpace && value.contains('+'))) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '%' -> {
                    if (index + 2 >= value.length) return null
                    val high = value[index + 1].hexValue() ?: return null
                    val low = value[index + 2].hexValue() ?: return null
                    bytes += ((high shl 4) or low).toByte()
                    index += 3
                }
                plusIsSpace && character == '+' -> {
                    bytes += ' '.code.toByte()
                    index += 1
                }
                else -> {
                    for (byte in character.toString().encodeToByteArray()) bytes += byte
                    index += 1
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.hexValue(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

    private fun String.containsEncodedControl(): Boolean =
        Regex("(?i)%0[0-9a-f]|%1[0-9a-f]").containsMatchIn(this)

    private fun isHostileControl(character: Char): Boolean = character.isISOControl()

    private val QUOTES = setOf('`', '"', '\'')
}
