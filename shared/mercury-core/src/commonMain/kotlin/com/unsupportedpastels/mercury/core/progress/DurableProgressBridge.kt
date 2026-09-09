package com.unsupportedpastels.mercury.core.progress

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Foundation/Swift boundary only; all decoding and reduction uses the canonical policy. */
object DurableProgressBridge {
    fun initial(): DurableProgress = DurableProgress()

    fun parseJson(rowsJson: String): DurableProgress {
        val rows = runCatching { Json.parseToJsonElement(rowsJson) as? JsonArray }.getOrNull()
        return DurableProgressParser.parse(rows?.mapNotNull { it as? JsonObject }.orEmpty())
    }

    fun liveSnapshotJson(payloadJson: String): DurableProgress? =
        runCatching { Json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
            ?.let(DurableProgressParser::liveSnapshot)
}
