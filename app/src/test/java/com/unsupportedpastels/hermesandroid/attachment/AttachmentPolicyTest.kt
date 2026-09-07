package com.unsupportedpastels.hermesandroid.attachment

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.mercury.core.attachment.AttachmentAddResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class AttachmentPolicyTest {

    // --- sanitizeDisplayName -------------------------------------------------

    // --- kindOf --------------------------------------------------------------

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

}
