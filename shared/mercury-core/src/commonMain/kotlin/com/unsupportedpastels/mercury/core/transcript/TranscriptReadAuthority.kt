package com.unsupportedpastels.mercury.core.transcript

/** Authority for one durable read, not a sticky preference for a session's longest transcript. */
enum class TranscriptReadAuthority(val publishesTranscript: Boolean) {
    /** Initial history, explicit refresh/rewind, or fallback after an empty resume. */
    History(true),
    /** Official resume already supplied display rows; read only progress and pagination metadata. */
    Resume(false),
}

object TranscriptReadPolicy {
    fun afterResume(hasDisplayRows: Boolean): TranscriptReadAuthority =
        if (hasDisplayRows) TranscriptReadAuthority.Resume else TranscriptReadAuthority.History
}
