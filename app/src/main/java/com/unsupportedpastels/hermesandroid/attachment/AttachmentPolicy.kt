package com.unsupportedpastels.hermesandroid.attachment

/** Raised when a staged file exceeds its byte cap while streaming. */
class AttachmentTooLargeException(
    val attachmentName: String,
    val capBytes: Long,
    actualBytes: Long,
) : Exception("Attachment '$attachmentName' is $actualBytes bytes; cap is $capBytes bytes")

/** Image attachments ride the session's queued-image list; everything else is a `@file:` ref. */
enum class AttachmentKind { IMAGE, FILE }

sealed interface AttachmentAddResult {
    data object Accepted : AttachmentAddResult
    data class Rejected(val reason: String) : AttachmentAddResult
}

/** Result of staging the composer's attachments ahead of `prompt.submit`. */
data class StagedAttachments(
    val refTexts: List<String>,
    val names: List<String>,
)

/**
 * The policy-relevant view of a staged attachment: platform-free so the policy
 * can move to the shared KMP core. [dedupKey] identifies the underlying source
 * (Android: the content:// URI) for duplicate rejection.
 */
data class AttachmentCandidate(
    val dedupKey: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
)

/**
 * Pure attachment policy: naming hygiene, image-vs-file routing, byte/count caps
 * (checked at metadata time AND again while staging), and prompt-text assembly.
 * No platform imports — this object is the Phase 1 candidate for `commonMain`
 * (docs/plans/kmp-shared-core.md); byte streaming lives in [AttachmentIo].
 */
object AttachmentPolicy {
    const val MAX_ATTACHMENTS = 5
    /** Under the gateway's 25 MiB per-image cap, leaving headroom for base64 framing. */
    const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
    const val MAX_FILE_BYTES = 10L * 1024 * 1024
    const val MAX_AGGREGATE_BYTES = 30L * 1024 * 1024
    const val MAX_DISPLAY_NAME_LENGTH = 120

    private val INVALID_NAME_CHARS = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F\\u007F]")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /**
     * Reduce a hostile/qualified provider name to a safe basename: split on both
     * path separators, drop control + platform-invalid characters, strip leading
     * dots, cap the length, and fall back to "attachment".
     */
    fun sanitizeDisplayName(raw: String): String {
        val basename = raw.split('/', '\\').lastOrNull { it.isNotBlank() } ?: ""
        val cleaned = basename
            .replace(INVALID_NAME_CHARS, "")
            .trim()
            .trimStart('.')
            .take(MAX_DISPLAY_NAME_LENGTH)
        return cleaned.ifBlank { "attachment" }
    }

    /** Route by MIME type with a conservative extension fallback for unknown/absent types. */
    fun kindOf(mimeType: String?, displayName: String): AttachmentKind {
        val mime = mimeType?.lowercase()
        if (mime?.startsWith("image/") == true) return AttachmentKind.IMAGE
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return if (extension in IMAGE_EXTENSIONS) AttachmentKind.IMAGE else AttachmentKind.FILE
    }

    fun perKindCapBytes(kind: AttachmentKind): Long = when (kind) {
        AttachmentKind.IMAGE -> MAX_IMAGE_BYTES
        AttachmentKind.FILE -> MAX_FILE_BYTES
    }

    /** Metadata-time admission: count cap, known-size per-kind cap, aggregate cap. */
    fun checkAdd(
        existing: List<AttachmentCandidate>,
        candidate: AttachmentCandidate,
    ): AttachmentAddResult {
        if (existing.any { it.dedupKey == candidate.dedupKey }) {
            return AttachmentAddResult.Rejected("${candidate.displayName} is already attached")
        }
        if (existing.size >= MAX_ATTACHMENTS) {
            return AttachmentAddResult.Rejected("Maximum of $MAX_ATTACHMENTS attachments")
        }
        val kind = kindOf(candidate.mimeType, candidate.displayName)
        val kindCap = perKindCapBytes(kind)
        if (candidate.sizeBytes > kindCap) {
            val mb = kindCap / (1024 * 1024)
            return AttachmentAddResult.Rejected("${candidate.displayName} exceeds the $mb MB limit for ${kind.name.lowercase()}s")
        }
        val aggregate = existing.sumOf { it.sizeBytes.coerceAtLeast(0) } + candidate.sizeBytes.coerceAtLeast(0)
        if (aggregate > MAX_AGGREGATE_BYTES) {
            return AttachmentAddResult.Rejected("Total attachment size exceeds the limit")
        }
        return AttachmentAddResult.Accepted
    }

    /**
     * Staging-time re-check of one attachment's actual byte count against its
     * per-kind cap (metadata sizes can be absent or dishonest).
     */
    fun checkStagedSize(displayName: String, kind: AttachmentKind, actualBytes: Long) {
        val capBytes = perKindCapBytes(kind)
        if (actualBytes > capBytes) {
            throw AttachmentTooLargeException(displayName, capBytes, actualBytes)
        }
    }

    /** Staging-time re-check of the running aggregate against the total cap. */
    fun checkStagedAggregate(aggregateBytes: Long) {
        if (aggregateBytes > MAX_AGGREGATE_BYTES) {
            throw AttachmentTooLargeException("Total attachments", MAX_AGGREGATE_BYTES, aggregateBytes)
        }
    }

    /**
     * Assemble the submitted prompt text: file `@file:` refs first, then the typed
     * text, then a server-style note when only images were attached and nothing was
     * typed — so `prompt.submit` never receives a blank payload with attachments.
     */
    fun composePromptText(
        typedText: String,
        fileRefs: List<String>,
        attachedNames: List<String>,
    ): String {
        if (fileRefs.isNotEmpty()) {
            val refs = fileRefs.joinToString("\n")
            return if (typedText.isNotBlank()) "$refs\n\n$typedText" else refs
        }
        if (typedText.isNotBlank()) return typedText
        if (attachedNames.isNotEmpty()) {
            return attachedNames.joinToString("\n") { "[User attached image: $it]" }
        }
        return ""
    }
}
