package com.unsupportedpastels.hermesandroid.attachment

import android.content.Context
import android.net.Uri
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a staged attachment's bytes from its client-local content:// URI,
 * enforcing the per-kind byte cap while streaming so an unknown-size or
 * dishonest provider cannot exhaust the heap.
 */
class ContentAttachmentByteReader(private val context: Context) : AttachmentByteReader {
    override suspend fun readBytes(attachment: ComposerAttachment): ByteArray = withContext(Dispatchers.IO) {
        val kind = AttachmentPolicy.kindOf(attachment.mimeType, attachment.displayName)
        val capBytes = AttachmentPolicy.perKindCapBytes(kind)
        val stream = try {
            context.contentResolver.openInputStream(Uri.parse(attachment.uri))
        } catch (error: Exception) {
            throw AttachmentReadException("Could not open ${attachment.displayName}", error)
        }
        stream?.use { input ->
            try {
                AttachmentIo.readBounded(input, capBytes)
            } catch (error: AttachmentTooLargeException) {
                throw error
            } catch (error: Exception) {
                throw AttachmentReadException("Could not read ${attachment.displayName}", error)
            }
        } ?: throw AttachmentReadException("Could not open ${attachment.displayName}")
    }
}
