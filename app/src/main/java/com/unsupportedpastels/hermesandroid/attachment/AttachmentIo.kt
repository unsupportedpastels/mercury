package com.unsupportedpastels.hermesandroid.attachment

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import java.io.InputStream

/** Raised when a staged file's bytes cannot be read at all. */
class AttachmentReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads a staged attachment's bytes on the client (the remote host cannot see content:// URIs). */
fun interface AttachmentByteReader {
    suspend fun readBytes(attachment: ComposerAttachment): ByteArray
}

/**
 * Platform I/O half of attachment staging, split from the pure
 * [AttachmentPolicy] so the policy can move to the shared KMP core.
 */
object AttachmentIo {
    /**
     * Bounded streaming read: never materializes more than [capBytes] + 1 so an
     * unknown-size or dishonest provider cannot exhaust the Android heap.
     */
    fun readBounded(input: InputStream, capBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > capBytes) {
                throw AttachmentTooLargeException("attachment", capBytes, total)
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
