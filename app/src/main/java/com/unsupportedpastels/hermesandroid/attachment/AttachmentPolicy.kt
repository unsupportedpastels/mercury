package com.unsupportedpastels.hermesandroid.attachment

/**
 * The attachment policy now lives in the shared KMP core (Phase 1 of
 * AGENTS.md cross-platform rule) so Android and iOS decide identically.
 * These aliases keep existing call sites and imports unchanged; byte
 * streaming stays platform-local in [AttachmentIo].
 */
typealias AttachmentPolicy = com.unsupportedpastels.mercury.core.attachment.AttachmentPolicy
typealias AttachmentCandidate = com.unsupportedpastels.mercury.core.attachment.AttachmentCandidate
typealias AttachmentKind = com.unsupportedpastels.mercury.core.attachment.AttachmentKind
typealias AttachmentTooLargeException = com.unsupportedpastels.mercury.core.attachment.AttachmentTooLargeException
typealias StagedAttachments = com.unsupportedpastels.mercury.core.attachment.StagedAttachments
