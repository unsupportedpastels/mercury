package com.unsupportedpastels.mercury.core.composer

/** Wire acknowledgement, not a server capability advertisement. */
enum class QueueAcknowledgement(val accepted: Boolean, val notice: String) {
    Queued(true, "Message queued for after the active turn."),
    Streaming(true, "Message accepted and started immediately."),
    OtherAccepted(true, "Message accepted by the server, but not queued as a separate turn."),
    Unknown(false, "Queue acknowledgement unavailable — check the transcript before sending again."),
}

object QueueAcknowledgementPolicy {
    /** An idle-claim race legitimately returns streaming for queued:true. Never replay it. */
    fun classify(status: String?): QueueAcknowledgement = when (status) {
        "queued" -> QueueAcknowledgement.Queued
        "streaming" -> QueueAcknowledgement.Streaming
        "steered", "redirected" -> QueueAcknowledgement.OtherAccepted
        else -> QueueAcknowledgement.Unknown
    }
}
