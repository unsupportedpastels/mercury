package com.unsupportedpastels.mercury.core.transcript

/**
 * The turn is already a disclosure. Preserve original reasoning/tool/prose
 * order inside it, coalescing adjacent tools but never adding another work-burst
 * disclosure. Native row IDs need not sort in history order after pagination.
 */
fun activityTranscriptEntries(rows: List<TranscriptRow>): List<TranscriptEntry> = buildList {
    var tools = mutableListOf<TranscriptRow>()
    fun flushTools() {
        if (tools.isNotEmpty()) add(TranscriptEntry.ToolRun(tools.toList()))
        tools = mutableListOf()
    }
    rows.forEach { row ->
        if (row.role.lowercase() == "tool") tools += row
        else {
            flushTools()
            add(TranscriptEntry.Message(row))
        }
    }
    flushTools()
}
