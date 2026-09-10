import Foundation
import MercuryCore

// MARK: - Models (Swift-native mirrors of the shared core types)

/// The safe presentation category of a transcript-delivered artifact.
enum ArtifactType: Sendable, Equatable {
    case image
    case audio
    case video
    case file
}

/// Whether an artifact is a managed host path or an approved remote URL.
enum ArtifactOrigin: Sendable, Equatable {
    case managedPath
    case remoteURL
}

/// Bounded metadata for one transcript-delivered artifact.
///
/// `source` is deliberately a path or URL only; it is never HTML, a data URI,
/// or a client-local file URI. `stableIdentity` is suitable for a per-session
/// browser key and is independent of the message in which the artifact first
/// appeared.
struct Artifact: Sendable, Equatable {
    let stableIdentity: String
    let type: ArtifactType
    let origin: ArtifactOrigin
    let source: String
    let displayName: String

    /// Short alias for callers that use identity terminology.
    var identity: String { stableIdentity }

    /// Short alias for callers that use location terminology.
    var location: String { source }
}

/// Input and output bounds for the pure transcript extractor.
struct ArtifactExtractionLimits: Sendable, Equatable {
    let maxTranscriptChars: Int
    let maxItems: Int
    let maxDisplayNameChars: Int
    let maxSourceChars: Int
    let maxLocationChars: Int

    init(
        maxTranscriptChars: Int = 64 * 1024,
        maxItems: Int = 64,
        maxDisplayNameChars: Int = 128,
        maxSourceChars: Int = 4 * 1024,
        maxLocationChars: Int = 4 * 1024
    ) {
        precondition(maxTranscriptChars > 0, "maxTranscriptChars must be positive")
        precondition(maxItems > 0, "maxItems must be positive")
        precondition(maxDisplayNameChars > 0, "maxDisplayNameChars must be positive")
        precondition(maxSourceChars > 0, "maxSourceChars must be positive")
        precondition(maxLocationChars > 0, "maxLocationChars must be positive")
        self.maxTranscriptChars = maxTranscriptChars
        self.maxItems = maxItems
        self.maxDisplayNameChars = maxDisplayNameChars
        self.maxSourceChars = maxSourceChars
        self.maxLocationChars = maxLocationChars
    }
}

// MARK: - Message input

/// Minimal transcript-message input for the pure extractor. Only `text` is
/// ever read; `reasoningText` is carried for call-site fidelity and
/// intentionally never extracted from.
struct MediaExtractionMessage: Sendable, Equatable {
    let text: String
    let reasoningText: String?

    init(text: String, reasoningText: String? = nil) {
        self.text = text
        self.reasoningText = reasoningText
    }
}

// MARK: - Extractor (facade over the shared KMP core)

/// Facade over the shared core's `ArtifactExtractor`
/// (shared/mercury-core, AGENTS.md cross-platform rule): the extraction
/// grammar, canonicalization, and bounds are decided once for both clients.
/// The previous hand-written Swift port (including its re-implementations of
/// `java.net.URI` behavior) is gone; this file only converts types.
enum MediaDirectiveExtractor {
    static func extract(
        messages: [MediaExtractionMessage],
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
    ) -> [Artifact] {
        MercuryCore.ArtifactExtractor.shared
            .extract(texts: messages.map(\.text), limits: limits.core)
            .map(Artifact.init)
    }

    static func extract(
        message: MediaExtractionMessage,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
    ) -> [Artifact] {
        extract(messages: [message], limits: limits)
    }

    static func extract(
        _ text: String,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
    ) -> [Artifact] {
        extract(messages: [MediaExtractionMessage(text: text)], limits: limits)
    }

    static func managedImageArtifacts(
        _ text: String,
        limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
    ) -> [Artifact] {
        MercuryCore.ArtifactExtractor.shared
            .managedImageArtifacts(
                text: text,
                formatPolicy: MercuryCore.ManagedImageFormatPolicy.ios,
                limits: limits.core
            )
            .map(Artifact.init)
    }
}

func extractArtifacts(
    messages: [MediaExtractionMessage],
    limits: ArtifactExtractionLimits = ArtifactExtractionLimits()
) -> [Artifact] {
    MediaDirectiveExtractor.extract(messages: messages, limits: limits)
}

// MARK: - Boundary conversions

private extension ArtifactExtractionLimits {
    var core: MercuryCore.ArtifactExtractionLimits {
        MercuryCore.ArtifactExtractionLimits(
            maxTranscriptChars: Int32(maxTranscriptChars),
            maxItems: Int32(maxItems),
            maxDisplayNameChars: Int32(maxDisplayNameChars),
            maxSourceChars: Int32(maxSourceChars),
            maxLocationChars: Int32(maxLocationChars)
        )
    }
}

private extension Artifact {
    init(_ core: MercuryCore.Artifact) {
        self.init(
            stableIdentity: core.stableIdentity,
            type: core.type == MercuryCore.ArtifactType.image
                ? .image
                : (core.type == MercuryCore.ArtifactType.video ? .video
                    : (core.type == MercuryCore.ArtifactType.audio ? .audio : .file)),
            origin: core.origin == MercuryCore.ArtifactOrigin.managedpath ? .managedPath : .remoteURL,
            source: core.source,
            displayName: core.displayName
        )
    }
}
