package com.unsupportedpastels.hermesandroid.attachment

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import java.util.Base64

/**
 * Stages the composer's attachments on the remote host ahead of `prompt.submit`.
 * Every local URI is read and validated before the first RPC because staging is
 * not transactional; this prevents a local read/size failure from leaving an
 * avoidable partial upload on the host.
 */
class AttachmentStager(
    private val session: HermesChatSession,
    private val runtimeSessionId: RuntimeSessionId,
    private val reader: AttachmentByteReader,
) {
    private data class PreparedAttachment(
        val attachment: ComposerAttachment,
        val kind: AttachmentKind,
        val bytes: ByteArray,
    )

    suspend fun stage(attachments: List<ComposerAttachment>): StagedAttachments {
        var aggregateBytes = 0L
        val prepared = attachments.map { attachment ->
            val kind = AttachmentPolicy.kindOf(attachment.mimeType, attachment.displayName)
            val bytes = reader.readBytes(attachment)
            AttachmentPolicy.checkStagedSize(attachment.displayName, kind, bytes.size.toLong())
            aggregateBytes += bytes.size
            AttachmentPolicy.checkStagedAggregate(aggregateBytes)
            PreparedAttachment(attachment, kind, bytes)
        }

        val refTexts = mutableListOf<String>()
        val imageNames = mutableListOf<String>()
        prepared.forEach { item ->
            val attachment = item.attachment
            val base64 = Base64.getEncoder().encodeToString(item.bytes)
            when (item.kind) {
                AttachmentKind.IMAGE -> {
                    session.attachImage(runtimeSessionId, attachment.displayName, base64)
                    imageNames += attachment.displayName
                }
                AttachmentKind.FILE -> {
                    refTexts += session.attachFile(
                        runtimeSessionId = runtimeSessionId,
                        filename = attachment.displayName,
                        mimeType = attachment.mimeType ?: "application/octet-stream",
                        base64Content = base64,
                    )
                }
            }
        }
        return StagedAttachments(refTexts = refTexts, names = imageNames)
    }
}
