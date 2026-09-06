package com.unsupportedpastels.mercury.core.transcript

/**
 * Hermes emits this as an interrupted turn's final text when a stop/steer
 * lands while the provider request is in flight
 * (`agent/conversation_loop.py` `INTERRUPT_WAITING_FOR_MODEL_PREFIX`). It is
 * cancellation metadata, not assistant prose; official surfaces suppress it
 * and so do both clients — live, from persisted history written by servers
 * that predate the upstream transcript fix, and in notification previews.
 */
object InterruptSentinel {
    const val PREFIX = "Operation interrupted: waiting for model response ("

    fun isInterruptSentinel(text: String): Boolean =
        text.trim().startsWith(PREFIX)
}
