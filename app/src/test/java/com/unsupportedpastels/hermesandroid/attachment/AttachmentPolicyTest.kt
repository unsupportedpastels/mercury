package com.unsupportedpastels.hermesandroid.attachment

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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
        val sanitized = AttachmentPolicy.sanitizeDisplayName(longName)
        assertEquals(AttachmentPolicy.MAX_DISPLAY_NAME_LENGTH, sanitized.length)
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

    private fun attachment(
        name: String,
        mime: String? = "text/plain",
        size: Long = 100,
    ) = ComposerAttachment(id = name, uri = "content://provider/$name", displayName = name, mimeType = mime, sizeBytes = size)

    @Test
    fun checkAddAcceptsWithinCaps() {
        val result = AttachmentPolicy.checkAdd(emptyList(), attachment("a.txt"))
        assertTrue(result is AttachmentAddResult.Accepted)
    }

    @Test
    fun checkAddRejectsBeyondCountCap() {
        val existing = (1..AttachmentPolicy.MAX_ATTACHMENTS).map { attachment("f$it.txt") }
        val result = AttachmentPolicy.checkAdd(existing, attachment("extra.txt"))
        assertTrue(result is AttachmentAddResult.Rejected)
        assertTrue((result as AttachmentAddResult.Rejected).reason.contains("attachments"))
    }

    @Test
    fun checkAddRejectsOversizedImage() {
        val result = AttachmentPolicy.checkAdd(
            emptyList(),
            attachment("big.png", mime = "image/png", size = AttachmentPolicy.MAX_IMAGE_BYTES + 1),
        )
        assertTrue(result is AttachmentAddResult.Rejected)
    }

    @Test
    fun checkAddRejectsOversizedFile() {
        val result = AttachmentPolicy.checkAdd(
            emptyList(),
            attachment("big.pdf", mime = "application/pdf", size = AttachmentPolicy.MAX_FILE_BYTES + 1),
        )
        assertTrue(result is AttachmentAddResult.Rejected)
    }

    @Test
    fun checkAddRejectsAggregateOverflow() {
        val big = attachment("big.pdf", size = AttachmentPolicy.MAX_FILE_BYTES)
        val second = attachment("second.pdf", size = AttachmentPolicy.MAX_FILE_BYTES)
        val third = attachment("third.pdf", size = AttachmentPolicy.MAX_FILE_BYTES)
        val fourth = attachment("fourth.pdf", size = AttachmentPolicy.MAX_FILE_BYTES)
        val result = AttachmentPolicy.checkAdd(listOf(big, second, third), fourth)
        assertTrue(result is AttachmentAddResult.Rejected)
    }

    @Test
    fun checkAddAllowsUnknownSize() {
        val result = AttachmentPolicy.checkAdd(emptyList(), attachment("unknown.bin", size = -1))
        assertTrue(result is AttachmentAddResult.Accepted)
    }

    @Test
    fun checkAddRejectsTheSameContentUriTwice() {
        val candidate = attachment("same.pdf", mime = "application/pdf")
        val result = AttachmentPolicy.checkAdd(listOf(candidate), candidate.copy(displayName = "renamed.pdf"))
        assertTrue(result is AttachmentAddResult.Rejected)
        assertTrue((result as AttachmentAddResult.Rejected).reason.contains("already attached"))
    }

    // --- readBounded ----------------------------------------------------------

    @Test
    fun readBoundedReturnsBytesUnderCap() {
        val bytes = "hello".toByteArray()
        val out = AttachmentIo.readBounded(ByteArrayInputStream(bytes), capBytes = 1024)
        assertEquals("hello", String(out))
    }

    @Test
    fun readBoundedAllowsExactlyCap() {
        val bytes = ByteArray(1024) { 1 }
        val out = AttachmentIo.readBounded(ByteArrayInputStream(bytes), capBytes = 1024)
        assertEquals(1024, out.size)
    }

    @Test
    fun readBoundedRejectsOversizedStream() {
        val bytes = ByteArray(2048) { 1 }
        val error = assertThrows(AttachmentTooLargeException::class.java) {
            AttachmentIo.readBounded(ByteArrayInputStream(bytes), capBytes = 1024)
        }
        assertTrue(error.message.orEmpty().contains("1024"))
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
        assertEquals(
            "@file:notes.txt",
            AttachmentPolicy.composePromptText("", listOf("@file:notes.txt"), emptyList()),
        )
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
        val text = AttachmentPolicy.composePromptText(
            "read both",
            listOf("@file:a.txt", "@file:b.txt"),
            emptyList(),
        )
        assertTrue(text.startsWith("@file:a.txt\n@file:b.txt"))
        assertTrue(text.endsWith("\n\nread both"))
        assertFalse(text.contains("[User attached"))
    }
}
