import Foundation

// MARK: - Attachment kind

/// Image attachments ride the session's queued-image list; everything else is
/// a `@file:` ref. Mirrors Android's `AttachmentKind` (IMAGE, FILE).
enum AttachmentKind: String, Sendable, Equatable {
    case image
    case file

    /// Lowercased Kotlin-style enum name used inside policy rejection messages
    /// ("images" / "files").
    var policyName: String { rawValue }
}

// MARK: - Staged attachment

/// A file the user staged in the composer. The `id` is a client-local
/// identity the remote host can never read directly — bytes are read and
/// uploaded (staged) at send time via `image.attach_bytes` / `file.attach`.
///
/// Value-type port of Android's `ComposerAttachment`, with the image-vs-file
/// routing decision materialized as [kind] exactly as `AttachmentPolicy.kindOf`
/// classifies it on Android.
struct StagedAttachment: Identifiable, Equatable, Sendable {
    let id: String
    var displayName: String
    var mimeType: String?
    var sizeBytes: Int64
    var kind: AttachmentKind

    init(
        id: String,
        displayName: String,
        mimeType: String?,
        sizeBytes: Int64,
        kind: AttachmentKind? = nil
    ) {
        self.id = id
        self.displayName = displayName
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.kind = kind ?? AttachmentPolicy.kindOf(mimeType, displayName: displayName)
    }
}

// MARK: - Admission result

/// Result of `AttachmentPolicy.checkAdd`. The rejection reason string matches
/// Android verbatim because it is surfaced to the user as-is.
enum AttachmentAddResult: Equatable, Sendable {
    case accepted
    case rejected(String)
}

// MARK: - Staging outcome

/// Result of staging the composer's attachments ahead of `prompt.submit`.
struct StagedAttachments: Equatable, Sendable {
    var refTexts: [String]
    var names: [String]
}
