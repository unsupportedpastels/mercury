import Foundation
import XCTest
@testable import Mercury

/// Unit-test matrix for the attachment policy port. Mirrors the semantics of
/// Android's `AttachmentPolicy` / `AttachmentStager` (the Kotlin sources carry
/// no dedicated test classes, so each rule is exercised directly against the
/// behavior specified in AttachmentPolicy.kt and AttachmentStager.kt).
final class AttachmentPolicyTests: XCTestCase {
    private let mib: Int64 = 1024 * 1024

    private func attachment(
        _ id: String,
        _ displayName: String,
        mimeType: String?,
        sizeBytes: Int64
    ) -> StagedAttachment {
        StagedAttachment(
            id: id,
            displayName: displayName,
            mimeType: mimeType,
            sizeBytes: sizeBytes
        )
    }

    // MARK: - sanitizeDisplayName (hostile/qualified provider names)

    func testSanitizeStripsWindowsDirectoryTraversal() {
        XCTAssertEqual(
            AttachmentPolicy.sanitizeDisplayName("C:\\Users\\evil\\..\\..\\report.pdf"),
            "report.pdf"
        )
    }

    func testSanitizeStripsUnixDirectoryTraversal() {
        XCTAssertEqual(
            AttachmentPolicy.sanitizeDisplayName("../../etc/passwd"),
            "passwd"
        )
    }

    func testSanitizeHandlesMixedSeparatorsAndBlankSegments() {
        XCTAssertEqual(
            AttachmentPolicy.sanitizeDisplayName("/var/log//app log.txt\\"),
            "app log.txt"
        )
    }

    func testSanitizeUsesLastNonBlankSegmentWhenTrailingSeparator() {
        // Kotlin: split('/', '\\').lastOrNull { it.isNotBlank() } — the last
        // NON-blank segment wins, so "folder/" keeps "folder".
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName("folder/"), "folder")
    }

    func testSanitizeRemovesPlatformInvalidCharacters() {
        XCTAssertEqual(
            AttachmentPolicy.sanitizeDisplayName("a<b>c:\"d|e?f*g.pdf"),
            "abcdefg.pdf"
        )
    }

    func testSanitizeRemovesControlCharactersAndDelete() {
        XCTAssertEqual(
            AttachmentPolicy.sanitizeDisplayName("\u{0007}bell\u{001F}\u{007F}note.txt"),
            "bellnote.txt"
        )
    }

    func testSanitizeStripsLeadingDots() {
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName("...hidden.png"), "hidden.png")
    }

    func testSanitizeTrimsSurroundingWhitespaceBeforeDotStrip() {
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName("  spaced name.pdf  "), "spaced name.pdf")
    }

    func testSanitizeDotsOnlyNameFallsBackToAttachment() {
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName("..."), "attachment")
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName("."), "attachment")
    }

    func testSanitizeEmptyAndBlankNamesFallBackToAttachment() {
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName(""), "attachment")
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName("   "), "attachment")
    }

    func testSanitizeAllInvalidCharactersFallBackToAttachment() {
        // Splits into segments; every surviving character is stripped, so the
        // result blanks out to the fallback name.
        XCTAssertEqual(
            AttachmentPolicy.sanitizeDisplayName("<>:|?*"),
            "attachment"
        )
    }

    func testSanitizeCapsLengthAtOneHundredTwentyCharacters() {
        let longName = String(repeating: "x", count: 250) + ".pdf"
        let sanitized = AttachmentPolicy.sanitizeDisplayName(longName)
        XCTAssertEqual(sanitized.count, AttachmentPolicy.maxDisplayNameLength)
        XCTAssertEqual(sanitized, String(repeating: "x", count: 120))
    }

    func testSanitizeKeepsNameOfExactlyTheCap() {
        let exact = String(repeating: "y", count: 120)
        XCTAssertEqual(AttachmentPolicy.sanitizeDisplayName(exact), exact)
    }

    // MARK: - kindOf (image-vs-file routing)

    func testKindOfImageMimePrefixRoutesToImage() {
        XCTAssertEqual(AttachmentPolicy.kindOf("image/png", displayName: "x.bin"), .image)
        XCTAssertEqual(AttachmentPolicy.kindOf("image/svg+xml", displayName: "x.svg"), .image)
    }

    func testKindOfImageMimeIsCaseInsensitive() {
        XCTAssertEqual(AttachmentPolicy.kindOf("IMAGE/PNG", displayName: "x"), .image)
    }

    func testKindOfAbsentMimeFallsBackToKnownExtensions() {
        XCTAssertEqual(AttachmentPolicy.kindOf(nil, displayName: "photo.PNG"), .image)
        XCTAssertEqual(AttachmentPolicy.kindOf(nil, displayName: "shot.jpeg"), .image)
        XCTAssertEqual(AttachmentPolicy.kindOf(nil, displayName: "anim.webp"), .image)
        XCTAssertEqual(AttachmentPolicy.kindOf(nil, displayName: "pic.bmp"), .image)
    }

    func testKindOfUnknownExtensionOrNoExtensionIsFile() {
        XCTAssertEqual(AttachmentPolicy.kindOf(nil, displayName: "archive.tar.gz"), .file)
        XCTAssertEqual(AttachmentPolicy.kindOf(nil, displayName: "README"), .file)
        XCTAssertEqual(AttachmentPolicy.kindOf("video/mp4", displayName: "clip.mp4"), .file)
    }

    func testKindOfNonImageMimeFallsThroughToExtensionCheck() {
        // Android only short-circuits on image/*; any other MIME still falls
        // through to the conservative extension check.
        XCTAssertEqual(AttachmentPolicy.kindOf("text/plain", displayName: "x.gif"), .image)
        XCTAssertEqual(AttachmentPolicy.kindOf("application/octet-stream", displayName: "x.png"), .image)
    }

    func testKindOfDotFileWithKnownExtensionIsImage() {
        // ".png": substringAfterLast('.') yields "png", matching Android.
        XCTAssertEqual(AttachmentPolicy.kindOf(nil, displayName: ".png"), .image)
    }

    // MARK: - checkAdd (metadata-time admission)

    func testCheckAddRejectsDuplicateIdEvenWhenUnderAllOtherCaps() {
        let existing = [attachment("a1", "photo.png", mimeType: "image/png", sizeBytes: 100)]
        let duplicate = attachment("a1", "renamed.png", mimeType: "image/png", sizeBytes: 100)
        // Android dedupes by identity (uri there, id here), not by name, and
        // the rejection message uses the CANDIDATE's display name.
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: existing, candidate: duplicate),
            .rejected("renamed.png is already attached")
        )
    }

    func testCheckAddAllowsSameDisplayNameWithDistinctIds() {
        let existing = [attachment("a1", "photo.png", mimeType: "image/png", sizeBytes: 100)]
        let candidate = attachment("a2", "photo.png", mimeType: "image/png", sizeBytes: 100)
        XCTAssertEqual(AttachmentPolicy.checkAdd(existing: existing, candidate: candidate), .accepted)
    }

    func testCheckAddEnforcesMaximumAttachmentCount() {
        let existing = (0..<5).map {
            attachment("id\($0)", "f\($0).png", mimeType: "image/png", sizeBytes: 10)
        }
        let sixth = attachment("id5", "sixth.png", mimeType: "image/png", sizeBytes: 10)
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: existing, candidate: sixth),
            .rejected("Maximum of \(AttachmentPolicy.maxAttachments) attachments")
        )

        var fourItems = existing
        _ = fourItems.popLast()
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: fourItems, candidate: sixth),
            .accepted
        )
    }

    func testCheckAddRejectsOversizeImageAtMetadataTime() {
        let candidate = attachment("big", "huge.png", mimeType: "image/png", sizeBytes: 24 * mib + 1)
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: [], candidate: candidate),
            .rejected("huge.png exceeds the 24 MB limit for images")
        )
    }

    func testCheckAddRejectsOversizeFileAtMetadataTime() {
        let candidate = attachment("big", "huge.zip", mimeType: "application/zip", sizeBytes: 10 * mib + 1)
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: [], candidate: candidate),
            .rejected("huge.zip exceeds the 10 MB limit for files")
        )
    }

    func testCheckAddAcceptsFilesExactlyAtPerKindCap() {
        let image = attachment("i", "edge.png", mimeType: "image/png", sizeBytes: 24 * mib)
        XCTAssertEqual(AttachmentPolicy.checkAdd(existing: [], candidate: image), .accepted)

        let file = attachment("f", "edge.zip", mimeType: nil, sizeBytes: 10 * mib)
        XCTAssertEqual(AttachmentPolicy.checkAdd(existing: [], candidate: file), .accepted)
    }

    func testCheckAddAppliesImageCapBasedOnClassificationNotDeclaredMime() {
        // A .png name classifies as an image even under a non-image MIME, so
        // the 24 MiB image cap applies rather than the 10 MiB file cap.
        let sneaky = attachment(
            "sneaky", "innocent.png", mimeType: "application/octet-stream", sizeBytes: 12 * mib
        )
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: [], candidate: sneaky),
            .accepted
        )
        let overImageCap = attachment(
            "sneaky2", "innocent.png", mimeType: "application/octet-stream", sizeBytes: 25 * mib
        )
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: [], candidate: overImageCap),
            .rejected("innocent.png exceeds the 24 MB limit for images")
        )
    }

    func testCheckAddRejectsAggregateOverflow() {
        let existing = [
            attachment("a", "one.zip", mimeType: nil, sizeBytes: 15 * mib),
            attachment("b", "two.zip", mimeType: nil, sizeBytes: 14 * mib),
        ]
        let candidate = attachment("c", "three.zip", mimeType: nil, sizeBytes: 2 * mib) // 31 MiB total
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: existing, candidate: candidate),
            .rejected("Total attachment size exceeds the limit")
        )
    }

    func testCheckAddAcceptsAggregateExactlyAtCap() {
        let existing = [
            attachment("a", "one.zip", mimeType: nil, sizeBytes: 15 * mib),
            attachment("b", "two.zip", mimeType: nil, sizeBytes: 15 * mib),
        ]
        let candidate = attachment("c", "three.zip", mimeType: nil, sizeBytes: 0)
        XCTAssertEqual(AttachmentPolicy.checkAdd(existing: existing, candidate: candidate), .accepted)
    }

    func testCheckAddCoercesNegativeSizesToZeroForAggregate() {
        // Android sums sizeBytes.coerceAtLeast(0); negative metadata must not
        // smuggle extra budget past the aggregate cap.
        let existing = [
            attachment("a", "weird.zip", mimeType: nil, sizeBytes: -50 * mib),
            attachment("b", "big.zip", mimeType: nil, sizeBytes: 29 * mib),
        ]
        let candidate = attachment("c", "two-more.zip", mimeType: nil, sizeBytes: 2 * mib)
        XCTAssertEqual(
            AttachmentPolicy.checkAdd(existing: existing, candidate: candidate),
            .rejected("Total attachment size exceeds the limit")
        )
    }

    // MARK: - validateStagedBytes (staging-time re-check, AttachmentStager)

    func testValidateStagedBytesAcceptsWithinCaps() throws {
        try AttachmentPolicy.validateStagedBytes(
            displayName: "ok.png",
            kind: .image,
            actualBytes: 24 * mib,
            cumulativeBytes: 24 * mib
        )
        try AttachmentPolicy.validateStagedBytes(
            displayName: "ok.zip",
            kind: .file,
            actualBytes: 6 * mib,
            cumulativeBytes: 30 * mib
        )
    }

    func testValidateStagedBytesThrowsOnPerKindOverflow() {
        XCTAssertThrowsError(
            try AttachmentPolicy.validateStagedBytes(
                displayName: "dishonest.png",
                kind: .image,
                actualBytes: 25 * mib,
                cumulativeBytes: 25 * mib
            )
        ) { error in
            XCTAssertEqual(
                error as? AttachmentTooLargeError,
                AttachmentTooLargeError(
                    attachmentName: "dishonest.png",
                    capBytes: 24 * mib,
                    actualBytes: 25 * mib
                )
            )
        }
    }

    func testValidateStagedBytesThrowsOnCumulativeOverflowWithTotalAttachmentsName() {
        XCTAssertThrowsError(
            try AttachmentPolicy.validateStagedBytes(
                displayName: "small.zip",
                kind: .file,
                actualBytes: 5 * mib,
                cumulativeBytes: 30 * mib + 1
            )
        ) { error in
            XCTAssertEqual(
                error as? AttachmentTooLargeError,
                AttachmentTooLargeError(
                    attachmentName: "Total attachments",
                    capBytes: AttachmentPolicy.maxAggregateBytes,
                    actualBytes: 30 * mib + 1
                )
            )
        }
    }

    func testValidateStagedBytesAcceptsCumulativeExactlyAtCap() throws {
        try AttachmentPolicy.validateStagedBytes(
            displayName: "last.zip",
            kind: .file,
            actualBytes: 1 * mib,
            cumulativeBytes: 30 * mib
        )
    }

    func testTooLargeErrorDescriptionMatchesKotlinExceptionMessage() {
        let error = AttachmentTooLargeError(
            attachmentName: "big.bin", capBytes: 1234, actualBytes: 5678
        )
        XCTAssertEqual(
            error.errorDescription,
            "Attachment 'big.bin' is 5678 bytes; cap is 1234 bytes"
        )
    }

    // MARK: - composePromptText (prompt assembly)

    func testComposePromptPrependsFileRefsThenBlankLineThenTypedText() {
        XCTAssertEqual(
            AttachmentPolicy.composePromptText(
                typedText: "hello",
                fileRefs: ["@file:notes.txt", "@file:data.csv"],
                attachedNames: []
            ),
            "@file:notes.txt\n@file:data.csv\n\nhello"
        )
    }

    func testComposePromptWithOnlyFileRefsOmitsTrailingBlankLine() {
        XCTAssertEqual(
            AttachmentPolicy.composePromptText(typedText: "", fileRefs: ["@file:a.txt"], attachedNames: []),
            "@file:a.txt"
        )
    }

    func testComposePromptWhitespaceOnlyTypedTextCountsAsBlank() {
        XCTAssertEqual(
            AttachmentPolicy.composePromptText(typedText: "   ", fileRefs: ["@file:a.txt"], attachedNames: []),
            "@file:a.txt"
        )
    }

    func testComposePromptWithOnlyTypedTextPassesThrough() {
        XCTAssertEqual(
            AttachmentPolicy.composePromptText(typedText: "just text", fileRefs: [], attachedNames: []),
            "just text"
        )
    }

    func testComposePromptImagesOnlyEmitsServerStyleNoteLines() {
        XCTAssertEqual(
            AttachmentPolicy.composePromptText(
                typedText: "",
                fileRefs: [],
                attachedNames: ["a.png", "b.jpg"]
            ),
            "[User attached image: a.png]\n[User attached image: b.jpg]"
        )
    }

    func testComposePromptEmptyInputsYieldEmptyPayload() {
        XCTAssertEqual(
            AttachmentPolicy.composePromptText(typedText: "", fileRefs: [], attachedNames: []),
            ""
        )
    }

    // MARK: - Constants parity

    func testConstantsMatchAndroidSourceVerbatim() {
        XCTAssertEqual(AttachmentPolicy.maxAttachments, 5)
        XCTAssertEqual(AttachmentPolicy.maxImageBytes, 24 * 1024 * 1024)
        XCTAssertEqual(AttachmentPolicy.maxFileBytes, 10 * 1024 * 1024)
        XCTAssertEqual(AttachmentPolicy.maxAggregateBytes, 30 * 1024 * 1024)
        XCTAssertEqual(AttachmentPolicy.maxDisplayNameLength, 120)
        XCTAssertEqual(AttachmentPolicy.perKindCapBytes(.image), AttachmentPolicy.maxImageBytes)
        XCTAssertEqual(AttachmentPolicy.perKindCapBytes(.file), AttachmentPolicy.maxFileBytes)
    }
}
