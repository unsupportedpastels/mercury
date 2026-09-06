package com.unsupportedpastels.mercury.core.attachment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ported from the Android suite (AttachmentPolicyTest.kt) and the behavior the
 * iOS AttachmentPolicyTests.swift mirrored line-by-line. Error and rejection
 * strings are asserted verbatim: they are user-visible contract on both
 * platforms and the historical drift risk this migration exists to remove.
 */
class AttachmentPolicyTest {

    // --- sanitizeDisplayName -------------------------------------------------

    @Test
    fun sanitizeKeepsPlainFilenames() {
        assertEquals("report.pdf", AttachmentPolicy.sanitizeDisplayName("report.pdf"))
        assertEquals("notes v2.txt", AttachmentPolicy.sanitizeDisplayName("notes v2.txt"))
    }

    @Test
    fun sanitizeReducesToBasename() {
        assertEquals("report.txt", AttachmentPolicy.sanitizeDisplayName("/Users/alice/Downloads/report.txt"))
        assertEquals("passwd", AttachmentPolicy.sanitizeDisplayName("..\\..\\etc\\passwd"))
        assertEquals("photo.png", AttachmentPolicy.sanitizeDisplayName("C:\\Users\\alice\\Pictures\\photo.png"))
    }

    @Test
    fun sanitizeStripsInvalidAndControlCharacters() {
        assertEquals("abcdefgh", AttachmentPolicy.sanitizeDisplayName("a<b>c:d\"e|f?g*h"))
        assertEquals("badname.txt", AttachmentPolicy.sanitizeDisplayName("bad\u0000name\u001F.txt"))
    }

    @Test
    fun sanitizeStripsLeadingDotsAndFallsBackWhenEmpty() {
        assertEquals("hidden", AttachmentPolicy.sanitizeDisplayName(".hidden"))
        assertEquals("attachment", AttachmentPolicy.sanitizeDisplayName(".."))
        assertEquals("attachment", AttachmentPolicy.sanitizeDisplayName(""))
        assertEquals("attachment", AttachmentPolicy.sanitizeDisplayName("   "))
    }

    @Test
    fun sanitizeCapsLength() {
        val longName = "a".repeat(300) + ".txt"
        assertEquals(AttachmentPolicy.MAX_DISPLAY_NAME_LENGTH, AttachmentPolicy.sanitizeDisplayName(longName).length)
    }

    // --- kindOf --------------------------------------------------------------

    @Test
    fun kindRoutesByMimeType() {
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf("image/png", "photo.png"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf("image/jpeg", "photo"))
        assertEquals(AttachmentKind.FILE, AttachmentPolicy.kindOf("application/pdf", "doc.pdf"))
        assertEquals(AttachmentKind.FILE, AttachmentPolicy.kindOf("text/plain", "notes.txt"))
    }

    @Test
    fun kindFallsBackToExtensionForUnknownMime() {
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf("application/octet-stream", "scan.png"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf(null, "photo.jpg"))
        assertEquals(AttachmentKind.IMAGE, AttachmentPolicy.kindOf(null, "anim.webp"))
        assertEquals(AttachmentKind.FILE, AttachmentPolicy.kindOf("application/octet-stream", "report.txt"))
        assertEquals(AttachmentKind.FILE, AttachmentPolicy.kindOf(null, "archive.zip"))
    }

    // --- checkAdd (metadata-time caps) ----------------------------------------

    private fun candidate(
        name: String,
        mime: String? = "text/plain",
        size: Long = 100,
    ) = AttachmentCandidate(dedupKey = "content://provider/$name", displayName = name, mimeType = mime, sizeBytes = size)

    @Test
    fun checkAddAcceptsWithinCaps() {
        assertIs<AttachmentAddResult.Accepted>(AttachmentPolicy.checkAdd(emptyList(), candidate("a.txt")))
    }

    @Test
    fun checkAddRejectsBeyondCountCapWithExactReason() {
        val existing = (1..AttachmentPolicy.MAX_ATTACHMENTS).map { candidate("f$it.txt") }
        val result = AttachmentPolicy.checkAdd(existing, candidate("extra.txt"))
        assertIs<AttachmentAddResult.Rejected>(result)
        assertEquals("Maximum of 5 attachments", result.reason)
    }

    @Test
    fun checkAddRejectsOversizedImageWithExactReason() {
        val result = AttachmentPolicy.checkAdd(
            emptyList(),
            candidate("big.png", mime = "image/png", size = AttachmentPolicy.MAX_IMAGE_BYTES + 1),
        )
        assertIs<AttachmentAddResult.Rejected>(result)
        assertEquals("big.png exceeds the 24 MB limit for images", result.reason)
    }

    @Test
    fun checkAddRejectsOversizedFileWithExactReason() {
        val result = AttachmentPolicy.checkAdd(
            emptyList(),
            candidate("big.pdf", mime = "application/pdf", size = AttachmentPolicy.MAX_FILE_BYTES + 1),
        )
        assertIs<AttachmentAddResult.Rejected>(result)
        assertEquals("big.pdf exceeds the 10 MB limit for files", result.reason)
    }

    @Test
    fun checkAddRejectsAggregateOverflowWithExactReason() {
        val existing = listOf(
            candidate("a.pdf", size = AttachmentPolicy.MAX_FILE_BYTES),
            candidate("b.pdf", size = AttachmentPolicy.MAX_FILE_BYTES),
            candidate("c.pdf", size = AttachmentPolicy.MAX_FILE_BYTES),
        )
        val result = AttachmentPolicy.checkAdd(existing, candidate("d.pdf", size = AttachmentPolicy.MAX_FILE_BYTES))
        assertIs<AttachmentAddResult.Rejected>(result)
        assertEquals("Total attachment size exceeds the limit", result.reason)
    }

    @Test
    fun checkAddAllowsUnknownSize() {
        assertIs<AttachmentAddResult.Accepted>(
            AttachmentPolicy.checkAdd(emptyList(), candidate("unknown.bin", size = -1)),
        )
    }

    @Test
    fun checkAddRejectsTheSameDedupKeyTwiceWithExactReason() {
        val first = candidate("same.pdf", mime = "application/pdf")
        val result = AttachmentPolicy.checkAdd(listOf(first), first.copy(displayName = "renamed.pdf"))
        assertIs<AttachmentAddResult.Rejected>(result)
        assertEquals("renamed.pdf is already attached", result.reason)
    }

    // --- staging-time re-checks -----------------------------------------------

    @Test
    fun stagedSizeWithinCapPasses() {
        AttachmentPolicy.checkStagedSize("ok.pdf", AttachmentKind.FILE, AttachmentPolicy.MAX_FILE_BYTES)
        AttachmentPolicy.checkStagedSize("ok.png", AttachmentKind.IMAGE, AttachmentPolicy.MAX_IMAGE_BYTES)
    }

    @Test
    fun stagedSizeOverCapThrowsWithExactMessage() {
        val error = assertFailsWith<AttachmentTooLargeException> {
            AttachmentPolicy.checkStagedSize("big.pdf", AttachmentKind.FILE, AttachmentPolicy.MAX_FILE_BYTES + 1)
        }
        assertEquals("Attachment 'big.pdf' is 10485761 bytes; cap is 10485760 bytes", error.message)
    }

    @Test
    fun stagedAggregateOverCapThrowsWithExactMessage() {
        AttachmentPolicy.checkStagedAggregate(AttachmentPolicy.MAX_AGGREGATE_BYTES)
        val error = assertFailsWith<AttachmentTooLargeException> {
            AttachmentPolicy.checkStagedAggregate(AttachmentPolicy.MAX_AGGREGATE_BYTES + 1)
        }
        assertEquals("Attachment 'Total attachments' is 31457281 bytes; cap is 31457280 bytes", error.message)
    }

    // --- composePromptText ----------------------------------------------------

    @Test
    fun composePrependsFileRefsToTypedText() {
        assertEquals(
            "@file:.hermes/desktop-attachments/report.txt\n\nsummarize",
            AttachmentPolicy.composePromptText("summarize", listOf("@file:.hermes/desktop-attachments/report.txt"), emptyList()),
        )
    }

    @Test
    fun composeUsesRefsAloneWhenTextIsBlank() {
        assertEquals("@file:notes.txt", AttachmentPolicy.composePromptText("", listOf("@file:notes.txt"), emptyList()))
    }

    @Test
    fun composeUsesServerStyleNoteForImagesOnly() {
        assertEquals(
            "[User attached image: photo.png]",
            AttachmentPolicy.composePromptText("", emptyList(), listOf("photo.png")),
        )
    }

    @Test
    fun composePassesThroughPlainTextWithoutAttachments() {
        assertEquals("hello", AttachmentPolicy.composePromptText("hello", emptyList(), emptyList()))
        assertEquals("", AttachmentPolicy.composePromptText("", emptyList(), emptyList()))
    }

    @Test
    fun composeJoinsMultipleRefsAndKeepsTypedTextLast() {
        val text = AttachmentPolicy.composePromptText("read both", listOf("@file:a.txt", "@file:b.txt"), emptyList())
        assertTrue(text.startsWith("@file:a.txt\n@file:b.txt"))
        assertTrue(text.endsWith("\n\nread both"))
        assertFalse(text.contains("[User attached"))
    }
}
