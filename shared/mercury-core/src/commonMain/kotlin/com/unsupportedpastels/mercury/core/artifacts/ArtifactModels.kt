package com.unsupportedpastels.mercury.core.artifacts

/** The safe presentation category of a transcript-delivered artifact. */
enum class ArtifactType {
    Image,
    Audio,
    Video,
    File,
}

/** Whether an artifact is a managed host path or an approved remote URL. */
enum class ArtifactOrigin {
    ManagedPath,
    RemoteUrl,
}

/** Native image decoder formats intentionally supported by each client. */
enum class ManagedImageFormatPolicy {
    Android,
    Ios,
}

/**
 * Bounded metadata for one transcript-delivered artifact.
 *
 * [source] is deliberately a path or URL only; it is never HTML, a data URI,
 * or a client-local file URI. [stableIdentity] is suitable for a per-session
 * browser key and is independent of the message in which the artifact first
 * appeared.
 */
data class Artifact(
    val stableIdentity: String,
    val type: ArtifactType,
    val origin: ArtifactOrigin,
    val source: String,
    val displayName: String,
) {
    /** Short alias for callers that use identity terminology. */
    val identity: String
        get() = stableIdentity

    /** Short alias for callers that use location terminology. */
    val location: String
        get() = source
}

/**
 * One validated explicit Markdown image occurrence backed by a managed host path.
 * Offsets are UTF-16 indices into the input string. Duplicate occurrences remain
 * present so renderers can remove their syntax while [shouldRender] preserves the
 * artifact extractor's first-identity-wins policy.
 */
data class ExplicitLocalMarkdownImage(
    val source: String,
    val stableIdentity: String,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val shouldRender: Boolean,
)

/** Input and output bounds for the pure transcript extractor. */
data class ArtifactExtractionLimits(
    val maxTranscriptChars: Int = 64 * 1024,
    val maxItems: Int = 64,
    val maxDisplayNameChars: Int = 128,
    val maxSourceChars: Int = 4 * 1024,
    val maxLocationChars: Int = 4 * 1024,
) {
    init {
        require(maxTranscriptChars > 0)
        require(maxItems > 0)
        require(maxDisplayNameChars > 0)
        require(maxSourceChars > 0)
        require(maxLocationChars > 0)
    }
}
