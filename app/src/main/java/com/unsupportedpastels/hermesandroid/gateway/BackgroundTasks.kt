package com.unsupportedpastels.hermesandroid.gateway

import kotlinx.serialization.json.JsonObject

typealias BackgroundTaskStatus = com.unsupportedpastels.mercury.core.relay.BackgroundTaskStatus
typealias BackgroundTaskEventKind = com.unsupportedpastels.mercury.core.relay.BackgroundTaskEventKind
data class BackgroundTaskEvent(
    override val sessionId: RuntimeSessionId,
    val kind: BackgroundTaskEventKind,
    val childId: String?,
    val goal: String?,
    val action: String?,
    val terminalStatus: BackgroundTaskStatus = BackgroundTaskStatus.Unknown,
    val eventId: String? = null,
    val historical: Boolean = false,
) : HermesChatEvent

data class BackgroundTaskRow(
    val runtimeId: RuntimeSessionId,
    val id: String,
    val goal: String,
    val action: String?,
    val status: BackgroundTaskStatus,
    val observedAtMillis: Long,
    val available: Boolean = true,
    val identityKnown: Boolean = true,
    val registryConfirmedAtMillis: Long = 0L,
) {
    val terminal: Boolean get() = shared().terminal
    fun recentlyActive(now: Long): Boolean = shared().recentlyActive(now)
    fun label(now: Long): String = shared().label(now)
    fun isDismissible(now: Long): Boolean = shared().isDismissible(now)
    fun dismissalKey(): String = shared().dismissalKey()
    fun timeLabel(now: Long): String = shared().timeLabel(now)
    internal fun shared() = com.unsupportedpastels.mercury.core.relay.BackgroundTaskRow(
        runtimeId.value, id, goal, action, status, observedAtMillis, available, identityKnown, registryConfirmedAtMillis)
}

data class BackgroundTasks(
    val rows: List<BackgroundTaskRow> = emptyList(),
    private val processedEventIds: List<String> = emptyList(),
) {
    internal fun shared() = com.unsupportedpastels.mercury.core.relay.BackgroundTasks(
        rows.map { it.shared() }, processedEventIds)
    fun activeCount(now: Long): Int = shared().activeCount(now)
    fun presentation(now: Long): com.unsupportedpastels.mercury.core.relay.BackgroundTaskPresentation =
        presentation(rows, now)
    fun presentation(
        rows: List<BackgroundTaskRow>,
        now: Long,
    ): com.unsupportedpastels.mercury.core.relay.BackgroundTaskPresentation =
        com.unsupportedpastels.mercury.core.relay.BackgroundTaskPresentationPolicy
            .summarize(rows.map { it.shared() }, now)
    fun secondaryLabel(rows: List<BackgroundTaskRow>, now: Long): String =
        com.unsupportedpastels.mercury.core.relay.BackgroundTaskPresentationPolicy
            .secondaryLabel(rows.map { it.shared() }, now)
    fun unavailable(): BackgroundTasks = shared().unavailable().native()
    fun reconcile(
        status: com.unsupportedpastels.hermesandroid.app.DelegationStatus,
        runtime: RuntimeSessionId,
        previousRuntime: RuntimeSessionId = runtime,
        now: Long = 0L,
    ): BackgroundTasks = shared().reconcile(status.active.map {
        com.unsupportedpastels.mercury.core.relay.BackgroundTaskRegistryEntry(it.subagentId, it.status)
    }, runtime.value, previousRuntime.value, now).native()
    fun reduce(event: HermesChatEvent, expectedRuntime: RuntimeSessionId, now: Long): BackgroundTasks {
        if (event !is BackgroundTaskEvent) return this
        val current = shared()
        val next = current.reduce(event.shared(), expectedRuntime.value, now)
        return if (next === current) this else next.native()
    }
    companion object {
        internal fun fromShared(state: com.unsupportedpastels.mercury.core.relay.BackgroundTasks) = BackgroundTasks(
            state.rows.map { BackgroundTaskRow(RuntimeSessionId(it.runtimeId), it.id, it.goal,
                it.action, it.status, it.observedAtMillis, it.available, it.identityKnown, it.registryConfirmedAtMillis) },
            state.processedEventIds,
        )
    }
}

internal fun BackgroundTaskEvent.shared() = com.unsupportedpastels.mercury.core.relay.BackgroundTaskEvent(
    sessionId.value, kind, childId, goal, action, terminalStatus, eventId, historical)
internal fun com.unsupportedpastels.mercury.core.relay.BackgroundTasks.native() = BackgroundTasks.fromShared(this)
internal fun com.unsupportedpastels.mercury.core.relay.BackgroundTaskEvent.native() = BackgroundTaskEvent(
    RuntimeSessionId(sessionId), kind, childId, goal, action, terminalStatus, eventId, historical)
internal fun decodeBackgroundTaskEvent(type: String, runtime: String, payload: JsonObject): BackgroundTaskEvent? =
    com.unsupportedpastels.mercury.core.relay.RelayRecoveryDecoder.backgroundTaskEvent(type, runtime, payload.toString())?.native()
