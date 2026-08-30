import Foundation
import MercuryCore

// MARK: - Staging byte-cap errors

/// Raised when a staged attachment exceeds its byte cap. Swift-native value
/// mirror of the shared core's `AttachmentTooLargeException`; the message
/// format is contract shared with Android and asserted in the common suite.
struct AttachmentTooLargeError: Error, Equatable, Sendable {
    let attachmentName: String
    let capBytes: Int64
    let actualBytes: Int64

    var errorDescription: String? {
        "Attachment '\(attachmentName)' is \(actualBytes) bytes; cap is \(capBytes) bytes"
    }
}

// MARK: - Attachment policy

/// Facade over the shared KMP core's `AttachmentPolicy`
/// (shared/mercury-core, docs/plans/kmp-shared-core.md): naming hygiene,
/// image-vs-file routing, byte/count caps, and prompt-text assembly are
/// decided once for both clients. This type keeps Mercury's existing
/// Swift-native API — call sites and tests are unchanged — and converts
/// to and from the framework's generated types at the boundary. Byte
/// streaming and upload stay in Mercury's I/O layer.
enum AttachmentPolicy {
    private static let core = MercuryCore.AttachmentPolicy.shared

    static let maxAttachments = Int(core.MAX_ATTACHMENTS)
    static let maxImageBytes: Int64 = core.MAX_IMAGE_BYTES
    static let maxFileBytes: Int64 = core.MAX_FILE_BYTES
    static let maxAggregateBytes: Int64 = core.MAX_AGGREGATE_BYTES
    static let maxDisplayNameLength = Int(core.MAX_DISPLAY_NAME_LENGTH)

    // MARK: Name hygiene

    /// Reduce a hostile/qualified provider name to a safe basename.
    static func sanitizeDisplayName(_ raw: String) -> String {
        core.sanitizeDisplayName(raw: raw)
    }

    // MARK: Image-vs-file routing

    /// Route by MIME type with a conservative extension fallback.
    static func kindOf(_ mimeType: String?, displayName: String) -> AttachmentKind {
        AttachmentKind(core.kindOf(mimeType: mimeType, displayName: displayName))
    }

    // MARK: Per-kind caps

    static func perKindCapBytes(_ kind: AttachmentKind) -> Int64 {
        core.perKindCapBytes(kind: kind.core)
    }

    // MARK: Metadata-time admission

    /// Admission check: duplicate id, count cap, known-size per-kind cap,
    /// aggregate cap. Rejection strings come verbatim from the shared core,
    /// so both clients show identical composer errors.
    static func checkAdd(
        existing: [StagedAttachment],
        candidate: StagedAttachment
    ) -> AttachmentAddResult {
        let result = core.checkAdd(
            existing: existing.map(\.coreCandidate),
            candidate: candidate.coreCandidate
        )
        if let rejected = result as? MercuryCore.AttachmentAddResultRejected {
            return .rejected(rejected.reason)
        }
        return .accepted
    }

    // MARK: Staging-time re-check

    /// Re-validate one staged attachment's actual byte count against its
    /// per-kind cap and the running aggregate total. `cumulativeBytes` must
    /// already include `actualBytes`. An unknown-size or dishonest provider
    /// cannot slip past metadata admission; this catches it before upload.
    static func validateStagedBytes(
        displayName: String,
        kind: AttachmentKind,
        actualBytes: Int64,
        cumulativeBytes: Int64
    ) throws {
        do {
            try core.checkStagedSize(displayName: displayName, kind: kind.core, actualBytes: actualBytes)
            try core.checkStagedAggregate(aggregateBytes: cumulativeBytes)
        } catch {
            throw Self.asTooLargeError(error)
        }
    }

    // MARK: Prompt assembly

    /// Assemble the submitted prompt text: file `@file:` refs first, then the
    /// typed text, then a server-style note when only images were attached and
    /// nothing was typed — so `prompt.submit` never receives a blank payload
    /// with attachments.
    static func composePromptText(
        typedText: String,
        fileRefs: [String],
        attachedNames: [String]
    ) -> String {
        core.composePromptText(typedText: typedText, fileRefs: fileRefs, attachedNames: attachedNames)
    }

    // MARK: Boundary conversions

    /// The shared core's @Throws surfaces Kotlin exceptions as NSError with
    /// the original exception in userInfo; rebuild the Swift-native error.
    private static func asTooLargeError(_ error: Error) -> Error {
        let userInfo = (error as NSError).userInfo
        if let kotlin = userInfo["KotlinException"] as? MercuryCore.AttachmentTooLargeException {
            return AttachmentTooLargeError(
                attachmentName: kotlin.attachmentName,
                capBytes: kotlin.capBytes,
                actualBytes: kotlin.actualBytes
            )
        }
        return error
    }
}

private extension AttachmentKind {
    init(_ core: MercuryCore.AttachmentKind) {
        self = core == MercuryCore.AttachmentKind.image ? .image : .file
    }

    var core: MercuryCore.AttachmentKind {
        self == .image ? .image : .file
    }
}

private extension StagedAttachment {
    /// The policy-relevant view; the picker item id is the dedup key.
    var coreCandidate: MercuryCore.AttachmentCandidate {
        MercuryCore.AttachmentCandidate(
            dedupKey: id,
            displayName: displayName,
            mimeType: mimeType,
            sizeBytes: sizeBytes
        )
    }
}
