package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableProgress
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.mercury.core.progress.DurableProgressParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Raw metadata is consumed before conversion to display-only messages; never written to disk here. */
data class TranscriptEnvelope(
    val messages: List<ChatMessage>,
    val progress: DurableProgress = DurableProgress(),
)

internal fun parseRelayTranscriptEnvelope(result: JsonObject) = TranscriptEnvelope(
    messages = parseRelayTranscriptRows(result),
    progress = DurableProgressParser.parse(
        (result["messages"] as? JsonArray).orEmpty().take(100).mapNotNull { it as? JsonObject },
    ),
)
