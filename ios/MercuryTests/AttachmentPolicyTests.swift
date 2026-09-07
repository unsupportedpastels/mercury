import Foundation
import XCTest
@testable import Mercury

/// Bridge tests for the Swift attachment facade. The policy itself is decided
/// and fully tested in the shared core (`AttachmentPolicyTest.kt`); these
/// cases cover the Swift-side conversions, the thrown-error bridging and one
/// representative rule per entry point.
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

    // MARK: - kindOf (image-vs-file routing)

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
