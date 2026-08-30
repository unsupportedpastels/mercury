package com.unsupportedpastels.hermesandroid.share

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.attachment.AttachmentAddResult
import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import com.unsupportedpastels.hermesandroid.attachment.checkAdd

/** Pure admission policy for untrusted Android share metadata. */
object SharePayloadPolicy {
    const val MAX_TEXT_CHARS = 32_768

    fun build(
        text: String?,
        candidates: List<SharedAttachmentCandidate>,
        requestId: Long = 0,
    ): SharePayloadBuildResult {
        val accepted = mutableListOf<ComposerAttachment>()
        val rejections = mutableListOf<String>()
        candidates.forEach { candidate ->
            if (!candidate.uri.startsWith("content://", ignoreCase = true)) {
                rejections += "One shared item was not a readable document"
                return@forEach
            }
            val attachment = ComposerAttachment(
                id = candidate.uri,
                uri = candidate.uri,
                displayName = AttachmentPolicy.sanitizeDisplayName(candidate.displayName),
                mimeType = candidate.mimeType?.take(256)?.takeIf(String::isNotBlank),
                sizeBytes = candidate.sizeBytes,
            )
            when (val result = AttachmentPolicy.checkAdd(accepted, attachment)) {
                AttachmentAddResult.Accepted -> accepted += attachment
                is AttachmentAddResult.Rejected -> rejections += result.reason
            }
        }
        val boundedText = text.orEmpty().take(MAX_TEXT_CHARS)
        if (boundedText.isBlank() && accepted.isEmpty()) {
            rejections += "Nothing readable was shared"
        }
        val payload = SharePayload(
            requestId = requestId,
            text = boundedText,
            attachments = accepted,
            rejections = rejections.toList(),
        )
        return SharePayloadBuildResult(payload, rejections.toList())
    }
}
