package com.unsupportedpastels.hermesandroid.artifacts

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Extracts only explicit, bounded artifact references from transcript text.
 *
 * This class does not fetch, preview, or interpret arbitrary prose. A source
 * must be a standalone MEDIA directive, a standalone HTTPS URL, or an
 * explicit Markdown link/image link.
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
    // Mirrors ui/RemoteMediaImage.kt so the browser and the inline chat player
    // agree on which MEDIA sources count as video.
    private val videoExtensions = setOf("m4v", "mkv", "mov", "mp4", "webm")

    /** Extract from the text field of every transcript message, in message order. */
    fun extract(
        messages: List<ChatMessage>,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> {
        val artifacts = ArrayList<Artifact>(limits.maxItems)
        val identities = HashSet<String>(limits.maxItems)
        var consumed = 0

        for (message in messages) {
            if (artifacts.size >= limits.maxItems || consumed >= limits.maxTranscriptChars) break
            val remaining = limits.maxTranscriptChars - consumed
            val text = message.text.take(remaining)
            consumed += text.length
            extractFromText(text, limits, artifacts, identities)
        }
        return artifacts
    }

    fun extract(
        message: ChatMessage,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> = extract(listOf(message), limits)

    fun extract(
        text: String,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
    ): List<Artifact> = extract(
        listOf(ChatMessage(role = com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole.Assistant, text = text)),
        limits,
    )

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
                if (token.startsWith('<') && token.endsWith('>')) token.substring(1, token.length - 1) else token
            }
            candidates += Candidate(
                offset = match.range.first,
                source = destination,
                labelHint = label,
                forcedType = if (isImageLink) ArtifactType.Image else null,
            )
        }

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

    private fun standaloneMediaSource(line: String): String? {
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
        val source = if (trimmed.startsWith('<') && trimmed.endsWith('>')) {
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
        val uri = safeHttpsUri(source) ?: return null
        val canonical = canonicalRemoteUrl(uri) ?: return null
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
                "" , "." -> Unit
                ".." -> return null
                else -> normalized += segment
            }
        }
        if (normalized.isEmpty()) return null
        return "/" + normalized.joinToString("/")
    }

    private fun safeHttpsUri(value: String): URI? {
        if (!value.startsWith("https://", ignoreCase = true)) return null
        if (value.containsEncodedControl() || value.any(::isHostileControl)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (host.isBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val normalizedHost = host.lowercase(Locale.ROOT)
        if (
            normalizedHost == "localhost" ||
            normalizedHost.endsWith(".localhost") ||
            normalizedHost.endsWith(".local") ||
            normalizedHost.contains(':') ||
            normalizedHost.all { it.isDigit() || it == '.' }
        ) return null
        if (normalizedHost.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        return uri
    }

    private fun canonicalRemoteUrl(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
        if (path.split('/').any { it == ".." }) return null
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$host$port$path$query"
    }

    private fun inferType(source: String, labelHint: String?): ArtifactType {
        val name = pathName(source).ifBlank { labelHint.orEmpty() }
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension in imageExtensions -> ArtifactType.Image
            extension in videoExtensions -> ArtifactType.Video
            extension in audioExtensions -> ArtifactType.Audio
            typePrefixPattern.find(labelHint.orEmpty())?.groupValues?.get(1)?.lowercase(Locale.ROOT) == "image" -> ArtifactType.Image
            typePrefixPattern.find(labelHint.orEmpty())?.groupValues?.get(1)?.lowercase(Locale.ROOT) == "video" -> ArtifactType.Video
            typePrefixPattern.find(labelHint.orEmpty())?.groupValues?.get(1)?.lowercase(Locale.ROOT) == "audio" -> ArtifactType.Audio
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

    private fun pathName(source: String): String {
        val path = runCatching { URI(source).path }.getOrNull()
            ?: source.substringBefore('?').substringBefore('#')
        return path?.substringAfterLast('/').orEmpty()
            .let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it) }
    }

    private fun String.containsEncodedControl(): Boolean =
        Regex("(?i)%0[0-9a-f]|%1[0-9a-f]").containsMatchIn(this)

    private fun isHostileControl(character: Char): Boolean = character.isISOControl()

    private val QUOTES = setOf('`', '"', '\'')
}

fun extractArtifacts(
    messages: List<ChatMessage>,
    limits: ArtifactExtractionLimits = ArtifactExtractionLimits(),
): List<Artifact> = ArtifactExtractor.extract(messages, limits)
