package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.RunTodoItem
import com.unsupportedpastels.mercury.core.transcript.BillingInfo as SharedBillingInfo
import com.unsupportedpastels.mercury.core.transcript.ChatEvent as SharedChatEvent
import com.unsupportedpastels.mercury.core.transcript.UnsupportedBlockingKind as SharedBlockingKind

/** Native boundary around the shared event alphabet; transport ownership stays Android-local. */
internal fun SharedChatEvent.toAndroidEvent(todos: List<RunTodoItem>? = null): HermesChatEvent? {
    val runtimeId = runCatching { RuntimeSessionId(sessionId) }.getOrNull() ?: return null
    return when (this) {
        is SharedChatEvent.MessageStart -> HermesChatEvent.MessageStart(runtimeId, text)
        is SharedChatEvent.MessageDelta -> HermesChatEvent.MessageDelta(runtimeId, text)
        is SharedChatEvent.MessageComplete -> HermesChatEvent.MessageComplete(
            sessionId = runtimeId,
            text = text,
            status = status,
            error = error,
            reasoning = reasoning,
            warning = warning,
            failureReason = failureReason,
            recoverable = recoverable,
            billing = billing?.let {
                HermesChatEvent.BillingInfo(it.provider, it.billingUrl, it.isNous, it.message)
            },
        )
        is SharedChatEvent.ReasoningDelta -> HermesChatEvent.ReasoningDelta(runtimeId, text, replace)
        is SharedChatEvent.MessageInterim -> HermesChatEvent.MessageInterim(runtimeId, text, alreadyStreamed)
        is SharedChatEvent.ToolGenerating -> HermesChatEvent.ToolGenerating(runtimeId, name)
        is SharedChatEvent.SessionTitle -> HermesChatEvent.SessionTitle(runtimeId, title)
        is SharedChatEvent.SessionInfo -> HermesChatEvent.SessionInfo(
            sessionId = runtimeId,
            storedSessionId = storedSessionId?.let { runCatching { DurableSessionId(it) }.getOrNull() },
            model = model,
            provider = provider,
            reasoningEffort = reasoningEffort,
            title = title,
            running = running,
        )
        is SharedChatEvent.Error -> HermesChatEvent.Error(runtimeId, message)
        is SharedChatEvent.ToolStart -> HermesChatEvent.ToolStart(runtimeId, toolId, name, context, todos)
        is SharedChatEvent.ToolComplete -> HermesChatEvent.ToolComplete(runtimeId, toolId, name, summary, todos)
        is SharedChatEvent.StatusUpdate -> HermesChatEvent.StatusUpdate(runtimeId, kind, text)
        is SharedChatEvent.ClarifyRequest ->
            HermesChatEvent.ClarifyRequest(runtimeId, requestId, question, choices, multiSelect)
        is SharedChatEvent.ClarifyExpire -> HermesChatEvent.ClarifyExpire(runtimeId, requestId)
        is SharedChatEvent.ApprovalRequest ->
            HermesChatEvent.ApprovalRequest(runtimeId, requestId, command, description, choices)
        is SharedChatEvent.ApprovalExpire -> HermesChatEvent.ApprovalExpire(runtimeId, requestId)
        is SharedChatEvent.UnsupportedBlockingRequest -> HermesChatEvent.UnsupportedBlockingRequest(
            runtimeId,
            kind.toAndroidKind(),
            requestId,
            prompt,
        )
        is SharedChatEvent.UnsupportedBlockingExpire -> HermesChatEvent.UnsupportedBlockingExpire(
            runtimeId,
            kind.toAndroidKind(),
            requestId,
        )
    }
}

internal fun HermesChatEvent.toSharedEvent(): SharedChatEvent? = when (this) {
    is HermesChatEvent.MessageStart -> SharedChatEvent.MessageStart(sessionId.value, text)
    is HermesChatEvent.MessageDelta -> SharedChatEvent.MessageDelta(sessionId.value, text)
    is HermesChatEvent.MessageComplete -> SharedChatEvent.MessageComplete(
        sessionId = sessionId.value,
        text = text,
        status = status,
        error = error,
        reasoning = reasoning,
        warning = warning,
        failureReason = failureReason,
        recoverable = recoverable,
        billing = billing?.let { SharedBillingInfo(it.provider, it.billingUrl, it.isNous, it.message) },
    )
    is HermesChatEvent.ReasoningDelta -> SharedChatEvent.ReasoningDelta(sessionId.value, text, replace)
    is HermesChatEvent.MessageInterim -> SharedChatEvent.MessageInterim(sessionId.value, text, alreadyStreamed)
    is HermesChatEvent.ToolGenerating -> SharedChatEvent.ToolGenerating(sessionId.value, name)
    is HermesChatEvent.SessionTitle -> SharedChatEvent.SessionTitle(sessionId.value, title)
    is HermesChatEvent.SessionInfo -> SharedChatEvent.SessionInfo(
        sessionId.value,
        storedSessionId?.value,
        model,
        provider,
        reasoningEffort,
        title = title,
        running = running,
    )
    is HermesChatEvent.Error -> SharedChatEvent.Error(sessionId.value, message)
    is HermesChatEvent.ToolStart -> SharedChatEvent.ToolStart(sessionId.value, toolId, name, context)
    is HermesChatEvent.ToolComplete -> SharedChatEvent.ToolComplete(sessionId.value, toolId, name, summary)
    is HermesChatEvent.StatusUpdate -> SharedChatEvent.StatusUpdate(sessionId.value, kind, text)
    is HermesChatEvent.ClarifyRequest ->
        SharedChatEvent.ClarifyRequest(sessionId.value, requestId, question, choices, multiSelect)
    is HermesChatEvent.ClarifyExpire -> SharedChatEvent.ClarifyExpire(sessionId.value, requestId)
    is HermesChatEvent.ApprovalRequest ->
        SharedChatEvent.ApprovalRequest(sessionId.value, requestId, command, description, choices)
    is HermesChatEvent.ApprovalExpire -> SharedChatEvent.ApprovalExpire(sessionId.value, requestId)
    is HermesChatEvent.UnsupportedBlockingRequest -> SharedChatEvent.UnsupportedBlockingRequest(
        sessionId.value,
        kind.toSharedKind(),
        requestId,
        prompt,
    )
    is HermesChatEvent.UnsupportedBlockingExpire -> SharedChatEvent.UnsupportedBlockingExpire(
        sessionId.value,
        kind.toSharedKind(),
        requestId,
    )
    else -> null
}

private fun SharedBlockingKind.toAndroidKind(): UnsupportedBlockingKind = when (this) {
    SharedBlockingKind.Secret -> UnsupportedBlockingKind.Secret
    SharedBlockingKind.Sudo -> UnsupportedBlockingKind.Sudo
    SharedBlockingKind.TerminalRead -> UnsupportedBlockingKind.TerminalRead
    SharedBlockingKind.PreviewRead -> UnsupportedBlockingKind.PreviewRead
    SharedBlockingKind.WindowRead -> UnsupportedBlockingKind.WindowRead
}

private fun UnsupportedBlockingKind.toSharedKind(): SharedBlockingKind = when (this) {
    UnsupportedBlockingKind.Secret -> SharedBlockingKind.Secret
    UnsupportedBlockingKind.Sudo -> SharedBlockingKind.Sudo
    UnsupportedBlockingKind.TerminalRead -> SharedBlockingKind.TerminalRead
    UnsupportedBlockingKind.PreviewRead -> SharedBlockingKind.PreviewRead
    UnsupportedBlockingKind.WindowRead -> SharedBlockingKind.WindowRead
}
