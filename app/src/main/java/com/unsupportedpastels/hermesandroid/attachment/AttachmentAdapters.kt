package com.unsupportedpastels.hermesandroid.attachment

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.mercury.core.attachment.AttachmentAddResult

/** The policy-relevant view of a composer attachment; the URI is the dedup key. */
fun ComposerAttachment.asCandidate(): AttachmentCandidate = AttachmentCandidate(
    dedupKey = uri,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
)

/** [AttachmentPolicy.checkAdd] over composer attachments; keeps call sites unchanged. */
fun AttachmentPolicy.checkAdd(
    existing: List<ComposerAttachment>,
    candidate: ComposerAttachment,
): AttachmentAddResult = checkAdd(existing.map { it.asCandidate() }, candidate.asCandidate())
