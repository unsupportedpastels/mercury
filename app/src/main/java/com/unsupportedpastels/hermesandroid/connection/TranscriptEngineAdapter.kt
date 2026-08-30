package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.toSharedEvent
import com.unsupportedpastels.mercury.core.transcript.TranscriptEngine
import com.unsupportedpastels.mercury.core.transcript.TranscriptRow
import com.unsupportedpastels.mercury.core.transcript.TranscriptSnapshot

/**
 * Android facade over the shared transcript reducer. Run-event interactions,
 * notification delivery, transport ownership, and lifecycle remain native.
 */
internal fun ChatSessionSnapshot.applyTranscriptEvent(event: HermesChatEvent): ChatSessionSnapshot {
    val shared = event.toSharedEvent() ?: return this
    if (event !is HermesChatEvent.MessageStart &&
        event !is HermesChatEvent.MessageDelta &&
        event !is HermesChatEvent.MessageComplete &&
        event !is HermesChatEvent.ReasoningDelta &&
        event !is HermesChatEvent.MessageInterim &&
        event !is HermesChatEvent.Error
    ) {
        return this
    }

    val state = TranscriptSnapshot(
        rows = messages.mapIndexed { index, message ->
            TranscriptRow(
                id = index.toLong() + 1,
                role = message.role.toWireRole(),
                text = message.text,
                completed = !message.isStreaming,
                reasoningText = message.reasoningText,
            )
        },
        nextRowId = messages.size.toLong() + 1,
    )
    val reduced = TranscriptEngine.apply(state, shared)
    return copy(
        messages = reduced.rows.map { row ->
            ChatMessage(
                role = row.role.toAndroidRole() ?: ChatMessageRole.Assistant,
                text = row.text,
                isStreaming = !row.completed,
                reasoningText = row.reasoningText,
            )
        },
    )
}

private fun ChatMessageRole.toWireRole(): String = when (this) {
    ChatMessageRole.User -> "user"
    ChatMessageRole.Assistant -> "assistant"
    ChatMessageRole.System -> "system"
    ChatMessageRole.Tool -> "tool"
}

private fun String.toAndroidRole(): ChatMessageRole? = when (lowercase()) {
    "user" -> ChatMessageRole.User
    "assistant" -> ChatMessageRole.Assistant
    "system" -> ChatMessageRole.System
    "tool" -> ChatMessageRole.Tool
    else -> null
}
