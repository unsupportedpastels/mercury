package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.TranscriptPresentation
import com.unsupportedpastels.hermesandroid.gateway.toSharedEvent
import com.unsupportedpastels.mercury.core.transcript.TranscriptEngine
import com.unsupportedpastels.mercury.core.transcript.TranscriptRow
import com.unsupportedpastels.mercury.core.transcript.TranscriptSnapshot
import java.util.IdentityHashMap

/**
 * Retains the shared reducer state for each session. Native history replacement
 * remains supported by the façade: only that boundary reseeds the shared rows.
 * Transport ownership, interactions, notifications, and lifecycle stay native.
 */
internal fun ChatSessionSnapshot.applyTranscriptEvent(event: HermesChatEvent): ChatSessionSnapshot {
    if (event !is HermesChatEvent.MessageStart &&
        event !is HermesChatEvent.MessageDelta &&
        event !is HermesChatEvent.MessageComplete &&
        event !is HermesChatEvent.ReasoningDelta &&
        event !is HermesChatEvent.MessageInterim &&
        event !is HermesChatEvent.Error
    ) return this
    val shared = event.toSharedEvent() ?: return this
    val previous = transcriptPresentation
    val state = if (previous != null && previous.messages === messages) {
        previous.state
    } else {
        seedTranscript(messages, previous)
    }
    val reduced = TranscriptEngine.apply(state, shared)
    if (previous != null && previous.messages === messages && reduced === state) return this

    // The reducer preserves untouched row objects. Keep their Android presentation
    // objects too, rather than allocating the entire history on each token.
    val projected = reduced.rows.mapIndexed { index, row ->
        if (state.rows.getOrNull(index) === row) messages[index] else row.toMessage()
    }
    return copy(
        messages = projected,
        transcriptPresentation = TranscriptPresentation(reduced, projected),
    )
}

private fun seedTranscript(messages: List<ChatMessage>, previous: TranscriptPresentation?): TranscriptSnapshot {
    val known = IdentityHashMap<ChatMessage, ArrayDeque<TranscriptRow>>()
    previous?.messages?.forEachIndexed { index, message ->
        known.getOrPut(message) { ArrayDeque() }.addLast(previous.state.rows[index])
    }
    var nextId = previous?.state?.nextRowId ?: 1L
    val rows = messages.map { message ->
        known[message]?.removeFirstOrNull() ?: TranscriptRow(
            id = nextId++,
            role = message.role.toWireRole(),
            text = message.text,
            completed = !message.isStreaming,
            reasoningText = message.reasoningText,
            displayKind = message.displayKind,
        )
    }
    return TranscriptSnapshot(rows = rows, nextRowId = nextId)
}

private fun TranscriptRow.toMessage() = ChatMessage(
    role = when (role) {
        "user" -> ChatMessageRole.User
        "system" -> ChatMessageRole.System
        "tool" -> ChatMessageRole.Tool
        else -> ChatMessageRole.Assistant
    },
    text = text,
    isStreaming = !completed,
    reasoningText = reasoningText,
    displayKind = displayKind,
)

private fun ChatMessageRole.toWireRole(): String = when (this) {
    ChatMessageRole.User -> "user"
    ChatMessageRole.Assistant -> "assistant"
    ChatMessageRole.System -> "system"
    ChatMessageRole.Tool -> "tool"
}
