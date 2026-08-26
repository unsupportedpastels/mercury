import Foundation

// MARK: - Staging byte-cap errors

/// Raised when a staged attachment exceeds its byte cap. Mirrors Android's
/// `AttachmentTooLargeException` (AttachmentPolicy.kt lines 7–11 and
/// AttachmentStager.kt lines 31–41); the streaming-read half of the Android
/// check lives in the I/O layer, this port carries only the pure decision.
struct AttachmentTooLargeError: Error, Equatable, Sendable {
    let attachmentName: String
    let capBytes: Int64
    let actualBytes: Int64

    var errorDescription: String? {
        "Attachment '\(attachmentName)' is \(actualBytes) bytes; cap is \(capBytes) bytes"
    }
}

// MARK: - Attachment policy

/// Pure attachment policy: naming hygiene, image-vs-file routing, byte/count
/// caps (checked at metadata time AND again while staging), and prompt-text
/// assembly. Exact port of Android's `AttachmentPolicy` object
/// (app/src/main/java/com/unsupportedpastels/hermesandroid/attachment/AttachmentPolicy.kt)
/// plus the byte-cap re-check from `AttachmentStager.stage`
/// (AttachmentStager.kt lines 27–43). All constants and rejection strings are
/// copied verbatim; the session/base64/I/O halves of the stager stay on the
/// platform-specific layer.
enum AttachmentPolicy {
    /// AttachmentPolicy.kt line 40.
    static let maxAttachments = 5

    /// AttachmentPolicy.kt lines 41–42 — under the gateway's 25 MiB per-image
    /// cap, leaving headroom for base64 framing.
    static let maxImageBytes: Int64 = 24 * 1024 * 1024

    /// AttachmentPolicy.kt line 43.
    static let maxFileBytes: Int64 = 10 * 1024 * 1024

    /// AttachmentPolicy.kt line 44.
    static let maxAggregateBytes: Int64 = 30 * 1024 * 1024

    /// AttachmentPolicy.kt line 45.
    static let maxDisplayNameLength = 120

    private static let invalidNameCharacters: Set<Character> = {
        var characters: Set<Character> = ["<", ">", ":", "\"", "/", "\\", "|", "?", "*"]
        // Kotlin regex class [<>:"/\|?*\u0000-\u001F\u007F] (line 47).
        for codeUnit in UInt32(0x00)...UInt32(0x1F) {
            guard let scalar = Unicode.Scalar(codeUnit) else { continue }
            characters.insert(Character(scalar))
        }
        if let delete = Unicode.Scalar(0x7F) {
            characters.insert(Character(delete))
        }
        return characters
    }()

    /// AttachmentPolicy.kt line 48.
    private static let imageExtensions: Set<String> = [
        "png", "jpg", "jpeg", "gif", "webp", "bmp",
    ]

    // MARK: Name hygiene (AttachmentPolicy.kt lines 50–63)

    /// Reduce a hostile/qualified provider name to a safe basename: split on
    /// both path separators, drop control + platform-invalid characters,
    /// strip leading dots, cap the length, and fall back to "attachment".
    ///
    /// Kotlin's pipeline is replace → trim → trimStart('.') → take(120) →
    /// ifBlank("attachment"); the last NON-BLANK separator segment wins.
    static func sanitizeDisplayName(_ raw: String) -> String {
        let segments = raw.split { $0 == "/" || $0 == "\\" }.map(String.init)
        let basename = segments.last { !isBlank($0) } ?? ""
        let cleaned = String(basename.filter { !invalidNameCharacters.contains($0) })
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .drop { $0 == "." }
        let capped = String(cleaned.prefix(maxDisplayNameLength))
        return isBlank(capped) ? "attachment" : capped
    }

    // MARK: Image-vs-file routing (AttachmentPolicy.kt lines 65–71)

    /// Route by MIME type with a conservative extension fallback for unknown/
    /// absent types. Only an `image/*` MIME short-circuits to image; any other
    /// MIME falls through to the extension check exactly as on Android.
    static func kindOf(_ mimeType: String?, displayName: String) -> AttachmentKind {
        if let mime = mimeType?.lowercased(), mime.hasPrefix("image/") {
            return .image
        }
        let lowered = displayName.lowercased()
        guard let dot = lowered.lastIndex(of: ".") else { return .file }
        let fileExtension = String(lowered[lowered.index(after: dot)...])
        return imageExtensions.contains(fileExtension) ? .image : .file
    }

    // MARK: Per-kind caps (AttachmentPolicy.kt lines 73–76)

    static func perKindCapBytes(_ kind: AttachmentKind) -> Int64 {
        switch kind {
        case .image: return maxImageBytes
        case .file: return maxFileBytes
        }
    }

    // MARK: Metadata-time admission (AttachmentPolicy.kt lines 78–100)

    /// Admission check: duplicate id, count cap, known-size per-kind cap,
    /// aggregate cap. Rejection strings match Android character-for-character
    /// so both clients show identical composer errors.
    static func checkAdd(
        existing: [StagedAttachment],
        candidate: StagedAttachment
    ) -> AttachmentAddResult {
        if existing.contains(where: { $0.id == candidate.id }) {
            return .rejected("\(candidate.displayName) is already attached")
        }
        if existing.count >= maxAttachments {
            return .rejected("Maximum of \(maxAttachments) attachments")
        }
        let kind = kindOf(candidate.mimeType, displayName: candidate.displayName)
        let kindCap = perKindCapBytes(kind)
        if candidate.sizeBytes > kindCap {
            let megabytes = kindCap / (1024 * 1024)
            return .rejected(
                "\(candidate.displayName) exceeds the \(megabytes) MB limit for \(kind.policyName)s"
            )
        }
        let aggregate = existing.reduce(Int64(0)) { $0 + max($1.sizeBytes, 0) }
            + max(candidate.sizeBytes, 0)
        if aggregate > maxAggregateBytes {
            return .rejected("Total attachment size exceeds the limit")
        }
        return .accepted
    }

    // MARK: Staging-time re-check (AttachmentStager.kt lines 27–43)

    /// Re-validate one staged attachment's actual byte count against its
    /// per-kind cap and the running aggregate total — the same two throws the
    /// Android stager performs after reading each file. `cumulativeBytes` must
    /// already include `actualBytes`. An unknown-size or dishonest provider
    /// cannot slip past metadata admission; this catches it before upload.
    static func validateStagedBytes(
        displayName: String,
        kind: AttachmentKind,
        actualBytes: Int64,
        cumulativeBytes: Int64
    ) throws {
        let capBytes = perKindCapBytes(kind)
        if actualBytes > capBytes {
            throw AttachmentTooLargeError(
                attachmentName: displayName,
                capBytes: capBytes,
                actualBytes: actualBytes
            )
        }
        if cumulativeBytes > maxAggregateBytes {
            throw AttachmentTooLargeError(
                attachmentName: "Total attachments",
                capBytes: maxAggregateBytes,
                actualBytes: cumulativeBytes
            )
        }
    }

    // MARK: Prompt assembly (AttachmentPolicy.kt lines 102–141)

    /// Assemble the submitted prompt text: file `@file:` refs first, then the
    /// typed text, then a server-style note when only images were attached and
    /// nothing was typed — so `prompt.submit` never receives a blank payload
    /// with attachments.
    static func composePromptText(
        typedText: String,
        fileRefs: [String],
        attachedNames: [String]
    ) -> String {
        if !fileRefs.isEmpty {
            let refs = fileRefs.joined(separator: "\n")
            return isBlank(typedText) ? refs : "\(refs)\n\n\(typedText)"
        }
        if !isBlank(typedText) { return typedText }
        if !attachedNames.isEmpty {
            return attachedNames.map { "[User attached image: \($0)]" }
                .joined(separator: "\n")
        }
        return ""
    }

    // MARK: Helpers

    /// Kotlin `String.isBlank`: empty or whitespace-only.
    private static func isBlank(_ value: String) -> Bool {
        value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
