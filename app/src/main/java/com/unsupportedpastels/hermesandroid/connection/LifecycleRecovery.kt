package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import com.unsupportedpastels.hermesandroid.gateway.requiresManualRecovery

/** Backoff for transport or bootstrap loss: 1s, 2s, 5s, 10s, then 30s capped. */
val LIFECYCLE_RECOVERY_BACKOFF_MS: LongArray = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

const val LIFECYCLE_RECOVERY_BUDGET_MS: Long = 5L * 60L * 1_000L
const val LIFECYCLE_PROBE_DEBOUNCE_MS: Long = 300L

fun lifecycleBackoffMs(attempt: Int): Long {
    val index = (attempt - 1).coerceAtLeast(0)
    return LIFECYCLE_RECOVERY_BACKOFF_MS.getOrElse(index) { 30_000L }
}

enum class AuthorizationKind {
    None,
    OAuth,
    LoopbackSession,
}

data class RecoveryScope(
    val origin: ServerOrigin,
    val generation: Long,
    val mode: ServerConnectionMode,
)

data class RecoveryJobs(
    val probe: Boolean = false,
    val bootstrap: Boolean = false,
    val retryTimer: Boolean = false,
    val credentialRefresh: Boolean = false,
    val debounce: Boolean = false,
    val turnRecovery: Boolean = false,
) {
    val hasExclusiveWork: Boolean
        get() = probe || bootstrap || credentialRefresh || turnRecovery
}

sealed interface LifecycleRecoveryState {
    data object Unconfigured : LifecycleRecoveryState

    data class Probing(
        val scope: RecoveryScope,
        val jobs: RecoveryJobs,
        val authorization: AuthorizationKind? = null,
        val keepReadyPresentation: Boolean = false,
        val preserveConnected: Boolean = false,
        val recoveryAttempt: Int = 0,
        val recoveryStartedAtEpochMs: Long? = null,
        val recoveryElapsedMs: Long = 0L,
    ) : LifecycleRecoveryState

    data class Ready(
        val scope: RecoveryScope,
        val authorization: AuthorizationKind,
        val jobs: RecoveryJobs = RecoveryJobs(),
    ) : LifecycleRecoveryState

    data class WaitingForTunnel(
        val scope: RecoveryScope,
        val failure: TunnelConnectionFailure,
        val attempt: Int,
        val nextRetryAtEpochMs: Long,
        val recoveryStartedAtEpochMs: Long,
        val budgetExhausted: Boolean,
        val jobs: RecoveryJobs,
        val authorization: AuthorizationKind? = null,
        val elapsedMs: Long = 0L,
    ) : LifecycleRecoveryState

    data class RefreshingCredential(
        val scope: RecoveryScope,
        val jobs: RecoveryJobs,
        val authorization: AuthorizationKind,
    ) : LifecycleRecoveryState

    data class RecoveringTurn(
        val scope: RecoveryScope,
        val sessionId: String?,
        val attempt: Int,
        val nextRetryAtEpochMs: Long,
        val recoveryStartedAtEpochMs: Long,
        val jobs: RecoveryJobs,
        val authorization: AuthorizationKind,
        val elapsedMs: Long = 0L,
    ) : LifecycleRecoveryState

    data class Suspended(
        val scope: RecoveryScope,
        val last: LifecycleRecoveryState,
    ) : LifecycleRecoveryState
}

sealed interface LifecycleRecoveryEvent {
    data class Configured(
        val origin: ServerOrigin,
        val generation: Long,
        val mode: ServerConnectionMode,
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent

    data object Cleared : LifecycleRecoveryEvent

    data class Foreground(
        val nowEpochMs: Long,
        val hasActiveTurn: Boolean,
    ) : LifecycleRecoveryEvent

    data class Background(
        val hasActiveTurn: Boolean,
    ) : LifecycleRecoveryEvent

    data class NetworkHint(
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent

    data class ProbeSucceeded(
        val origin: ServerOrigin,
        val generation: Long,
        val authorization: AuthorizationKind,
    ) : LifecycleRecoveryEvent

    data class ProbeFailed(
        val origin: ServerOrigin,
        val generation: Long,
        val failure: TunnelConnectionFailure,
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent

    data class BootstrapSucceeded(
        val origin: ServerOrigin,
        val generation: Long,
        val authorization: AuthorizationKind,
    ) : LifecycleRecoveryEvent

    data class BootstrapFailed(
        val origin: ServerOrigin,
        val generation: Long,
        val failure: TunnelConnectionFailure,
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent

    data class CredentialRejected(
        val origin: ServerOrigin,
        val generation: Long,
        val nowEpochMs: Long,
        val alreadyRefreshed: Boolean,
    ) : LifecycleRecoveryEvent

    data class CredentialRefreshSucceeded(
        val origin: ServerOrigin,
        val generation: Long,
        val authorization: AuthorizationKind,
    ) : LifecycleRecoveryEvent

    data class CredentialRefreshFailed(
        val origin: ServerOrigin,
        val generation: Long,
        val failure: TunnelConnectionFailure,
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent

    data class TransportLost(
        val origin: ServerOrigin,
        val generation: Long,
        val nowEpochMs: Long,
        val hasActiveTurn: Boolean,
        val sessionId: String? = null,
    ) : LifecycleRecoveryEvent

    data class TurnRecovered(
        val origin: ServerOrigin,
        val generation: Long,
        val authorization: AuthorizationKind,
    ) : LifecycleRecoveryEvent

    data class TurnRecoveryFailed(
        val origin: ServerOrigin,
        val generation: Long,
        val nowEpochMs: Long,
        val hasActiveTurn: Boolean,
        val sessionId: String? = null,
    ) : LifecycleRecoveryEvent

    data class RetryTimerFired(
        val origin: ServerOrigin,
        val generation: Long,
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent

    data class DebounceFired(
        val origin: ServerOrigin,
        val generation: Long,
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent

    data class ManualRetry(
        val nowEpochMs: Long,
    ) : LifecycleRecoveryEvent
}

sealed interface LifecycleRecoveryEffect {
    data class Probe(val scope: RecoveryScope) : LifecycleRecoveryEffect
    data class Bootstrap(val scope: RecoveryScope) : LifecycleRecoveryEffect
    data class RefreshCredential(val scope: RecoveryScope) : LifecycleRecoveryEffect
    data class RecoverTurn(
        val scope: RecoveryScope,
        val sessionId: String?,
    ) : LifecycleRecoveryEffect
    data class ScheduleRetry(
        val scope: RecoveryScope,
        val atEpochMs: Long,
        val waitMs: Long,
    ) : LifecycleRecoveryEffect
    data class CancelRetry(val scope: RecoveryScope) : LifecycleRecoveryEffect
    data class ScheduleDebounce(
        val scope: RecoveryScope,
        val atEpochMs: Long,
        val waitMs: Long,
    ) : LifecycleRecoveryEffect
    data class CancelDebounce(val scope: RecoveryScope) : LifecycleRecoveryEffect
    data class CancelProbe(val scope: RecoveryScope) : LifecycleRecoveryEffect
    data class CancelBootstrap(val scope: RecoveryScope) : LifecycleRecoveryEffect
}

data class LifecycleRecoveryDecision(
    val state: LifecycleRecoveryState,
    val effects: List<LifecycleRecoveryEffect> = emptyList(),
)

fun LifecycleRecoveryState.scopeOrNull(): RecoveryScope? = when (this) {
    LifecycleRecoveryState.Unconfigured -> null
    is LifecycleRecoveryState.Probing -> scope
    is LifecycleRecoveryState.Ready -> scope
    is LifecycleRecoveryState.WaitingForTunnel -> scope
    is LifecycleRecoveryState.RefreshingCredential -> scope
    is LifecycleRecoveryState.RecoveringTurn -> scope
    is LifecycleRecoveryState.Suspended -> scope
}

fun LifecycleRecoveryState.jobsOrNull(): RecoveryJobs? = when (this) {
    LifecycleRecoveryState.Unconfigured -> null
    is LifecycleRecoveryState.Probing -> jobs
    is LifecycleRecoveryState.Ready -> jobs
    is LifecycleRecoveryState.WaitingForTunnel -> jobs
    is LifecycleRecoveryState.RefreshingCredential -> jobs
    is LifecycleRecoveryState.RecoveringTurn -> jobs
    is LifecycleRecoveryState.Suspended -> null
}

fun LifecycleRecoveryState.isTerminalCredentialFailure(): Boolean =
    this is LifecycleRecoveryState.WaitingForTunnel &&
        failure == TunnelConnectionFailure.CredentialRejected

fun LifecycleRecoveryState.publishedConnectionState(): ConnectionState = when (this) {
    LifecycleRecoveryState.Unconfigured -> ConnectionState.Disconnected
    is LifecycleRecoveryState.Probing -> when {
        preserveConnected -> ConnectionState.Connected
        keepReadyPresentation -> ConnectionState.Recovering
        else -> ConnectionState.Connecting
    }
    is LifecycleRecoveryState.Ready -> ConnectionState.Connected
    is LifecycleRecoveryState.WaitingForTunnel ->
        if (budgetExhausted || failure.requiresManualRecovery()) {
            ConnectionState.Disconnected
        } else {
            ConnectionState.Recovering
        }
    is LifecycleRecoveryState.RefreshingCredential -> ConnectionState.Recovering
    is LifecycleRecoveryState.RecoveringTurn -> ConnectionState.Recovering
    is LifecycleRecoveryState.Suspended -> last.publishedConnectionState()
}

fun LifecycleRecoveryState.withScope(scope: RecoveryScope): LifecycleRecoveryState = when (this) {
    LifecycleRecoveryState.Unconfigured -> this
    is LifecycleRecoveryState.Probing -> copy(scope = scope)
    is LifecycleRecoveryState.Ready -> copy(scope = scope)
    is LifecycleRecoveryState.WaitingForTunnel -> copy(scope = scope)
    is LifecycleRecoveryState.RefreshingCredential -> copy(scope = scope)
    is LifecycleRecoveryState.RecoveringTurn -> copy(scope = scope)
    is LifecycleRecoveryState.Suspended -> copy(scope = scope, last = last.withScope(scope))
}

fun reduceLifecycle(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent,
): LifecycleRecoveryDecision {
    if (event is LifecycleRecoveryEvent.Configured) {
        return configured(state, event)
    }
    if (event is LifecycleRecoveryEvent.Cleared) {
        return cleared(state)
    }
    if (state is LifecycleRecoveryState.Suspended) {
        return reduceSuspended(state, event)
    }
    if (isStale(state, event)) {
        return LifecycleRecoveryDecision(state)
    }
    return when (event) {
        is LifecycleRecoveryEvent.Foreground -> foreground(state, event)
        is LifecycleRecoveryEvent.Background -> background(state, event)
        is LifecycleRecoveryEvent.NetworkHint -> networkHint(state, event)
        is LifecycleRecoveryEvent.ManualRetry -> manualRetry(state, event.nowEpochMs)
        is LifecycleRecoveryEvent.ProbeSucceeded -> probeSucceeded(state, event)
        is LifecycleRecoveryEvent.ProbeFailed -> probeFailed(state, event)
        is LifecycleRecoveryEvent.BootstrapSucceeded -> bootstrapSucceeded(state, event)
        is LifecycleRecoveryEvent.BootstrapFailed -> bootstrapFailed(state, event)
        is LifecycleRecoveryEvent.CredentialRejected -> credentialRejected(state, event)
        is LifecycleRecoveryEvent.CredentialRefreshSucceeded ->
            credentialRefreshSucceeded(state, event)
        is LifecycleRecoveryEvent.CredentialRefreshFailed ->
            credentialRefreshFailed(state, event)
        is LifecycleRecoveryEvent.TransportLost -> transportLost(state, event)
        is LifecycleRecoveryEvent.TurnRecovered -> turnRecovered(state, event)
        is LifecycleRecoveryEvent.TurnRecoveryFailed -> turnRecoveryFailed(state, event)
        is LifecycleRecoveryEvent.RetryTimerFired -> retryTimerFired(state, event)
        is LifecycleRecoveryEvent.DebounceFired -> debounceFired(state, event)
        is LifecycleRecoveryEvent.Configured,
        is LifecycleRecoveryEvent.Cleared,
        -> LifecycleRecoveryDecision(state)
    }
}

private fun configured(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.Configured,
): LifecycleRecoveryDecision {
    val nextScope = RecoveryScope(event.origin, event.generation, event.mode)
    return LifecycleRecoveryDecision(
        state = LifecycleRecoveryState.Probing(
            scope = nextScope,
            jobs = RecoveryJobs(bootstrap = true),
        ),
        effects = cancelTimers(state) + LifecycleRecoveryEffect.Bootstrap(nextScope),
    )
}

private fun cleared(state: LifecycleRecoveryState): LifecycleRecoveryDecision =
    LifecycleRecoveryDecision(
        state = LifecycleRecoveryState.Unconfigured,
        effects = cancelTimers(state),
    )

private fun reduceSuspended(
    state: LifecycleRecoveryState.Suspended,
    event: LifecycleRecoveryEvent,
): LifecycleRecoveryDecision = when (event) {
    is LifecycleRecoveryEvent.Foreground -> reduceLifecycle(state.last, event)
    is LifecycleRecoveryEvent.ManualRetry -> reduceLifecycle(state.last, event)
    is LifecycleRecoveryEvent.CredentialRejected -> {
        val inner = reduceLifecycle(state.last, event)
        LifecycleRecoveryDecision(
            LifecycleRecoveryState.Suspended(state.scope, inner.state),
            inner.effects,
        )
    }
    is LifecycleRecoveryEvent.CredentialRefreshSucceeded,
    is LifecycleRecoveryEvent.CredentialRefreshFailed,
    -> {
        val inner = reduceLifecycle(state.last, event)
        LifecycleRecoveryDecision(
            LifecycleRecoveryState.Suspended(state.scope, inner.state),
            inner.effects,
        )
    }
    is LifecycleRecoveryEvent.Background,
    is LifecycleRecoveryEvent.NetworkHint,
    is LifecycleRecoveryEvent.ProbeSucceeded,
    is LifecycleRecoveryEvent.ProbeFailed,
    is LifecycleRecoveryEvent.BootstrapSucceeded,
    is LifecycleRecoveryEvent.BootstrapFailed,
    is LifecycleRecoveryEvent.RetryTimerFired,
    is LifecycleRecoveryEvent.DebounceFired,
    is LifecycleRecoveryEvent.TransportLost,
    is LifecycleRecoveryEvent.TurnRecovered,
    is LifecycleRecoveryEvent.TurnRecoveryFailed,
    is LifecycleRecoveryEvent.Configured,
    is LifecycleRecoveryEvent.Cleared,
    -> LifecycleRecoveryDecision(state)
}

private fun foreground(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.Foreground,
): LifecycleRecoveryDecision {
    if (state.isTerminalCredentialFailure()) return LifecycleRecoveryDecision(state)
    if (state.jobsOrNull()?.hasExclusiveWork == true) return LifecycleRecoveryDecision(state)
    return requestDebouncedProbe(state, event.nowEpochMs)
}

private fun background(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.Background,
): LifecycleRecoveryDecision {
    if (event.hasActiveTurn) return LifecycleRecoveryDecision(state)
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.Suspended(scope, last = withoutIdleWork(state)),
        cancelTimers(state) + cancelInFlightIdleWork(state),
    )
}

private fun networkHint(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.NetworkHint,
): LifecycleRecoveryDecision {
    if (state is LifecycleRecoveryState.Unconfigured) return LifecycleRecoveryDecision(state)
    if (state.isTerminalCredentialFailure()) return LifecycleRecoveryDecision(state)
    if (state.jobsOrNull()?.hasExclusiveWork == true) return LifecycleRecoveryDecision(state)
    return requestDebouncedProbe(state, event.nowEpochMs)
}

private fun requestDebouncedProbe(
    state: LifecycleRecoveryState,
    nowEpochMs: Long,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    val at = nowEpochMs + LIFECYCLE_PROBE_DEBOUNCE_MS
    return LifecycleRecoveryDecision(
        state = withJobs(state, (state.jobsOrNull() ?: RecoveryJobs()).copy(debounce = true)),
        effects = listOf(LifecycleRecoveryEffect.ScheduleDebounce(scope, at, LIFECYCLE_PROBE_DEBOUNCE_MS)),
    )
}

private fun debounceFired(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.DebounceFired,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    val jobs = state.jobsOrNull() ?: return LifecycleRecoveryDecision(state)
    if (state.isTerminalCredentialFailure()) {
        return LifecycleRecoveryDecision(
            withJobs(state, jobs.copy(debounce = false)),
            listOf(LifecycleRecoveryEffect.CancelDebounce(scope)),
        )
    }
    if (jobs.hasExclusiveWork) {
        return LifecycleRecoveryDecision(
            withJobs(state, jobs.copy(debounce = false)),
            listOf(LifecycleRecoveryEffect.CancelDebounce(scope)),
        )
    }
    return when (state) {
        is LifecycleRecoveryState.Ready ->
            LifecycleRecoveryDecision(
                LifecycleRecoveryState.Probing(
                    scope = scope,
                    jobs = RecoveryJobs(probe = true),
                    authorization = state.authorization,
                    preserveConnected = true,
                ),
                listOf(
                    LifecycleRecoveryEffect.CancelDebounce(scope),
                    LifecycleRecoveryEffect.Probe(scope),
                ),
            )
        is LifecycleRecoveryState.WaitingForTunnel ->
            startBootstrap(
                scope = scope,
                authorization = state.authorization,
                keepReadyPresentation = true,
                recoveryAttempt = state.attempt,
                recoveryStartedAtEpochMs = state.recoveryStartedAtEpochMs,
                previous = state,
            )
        else -> LifecycleRecoveryDecision(
            withJobs(state, jobs.copy(debounce = false)),
            listOf(LifecycleRecoveryEffect.CancelDebounce(scope)),
        )
    }
}

private fun probeSucceeded(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.ProbeSucceeded,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    if (state is LifecycleRecoveryState.WaitingForTunnel) {
        return startBootstrap(
            scope = scope,
            authorization = event.authorization,
            keepReadyPresentation = true,
            recoveryAttempt = state.attempt,
            recoveryStartedAtEpochMs = state.recoveryStartedAtEpochMs,
            previous = state,
        )
    }
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.Ready(scope, event.authorization),
    )
}

private fun probeFailed(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.ProbeFailed,
): LifecycleRecoveryDecision {
    if (event.failure == TunnelConnectionFailure.CredentialRejected) {
        return credentialRejected(
            state,
            LifecycleRecoveryEvent.CredentialRejected(
                origin = event.origin,
                generation = event.generation,
                nowEpochMs = event.nowEpochMs,
                alreadyRefreshed = false,
            ),
        )
    }
    val probing = state as? LifecycleRecoveryState.Probing
    return waitForFailure(
        scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state),
        failure = event.failure,
        nowEpochMs = event.nowEpochMs,
        previousAttempt = probing?.recoveryAttempt ?: 0,
        startedAt = probing?.recoveryStartedAtEpochMs,
        authorization = authorizationOf(state),
        previous = state,
    )
}

private fun bootstrapSucceeded(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.BootstrapSucceeded,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.Ready(scope, event.authorization),
        cancelTimers(state),
    )
}

private fun bootstrapFailed(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.BootstrapFailed,
): LifecycleRecoveryDecision {
    if (event.failure == TunnelConnectionFailure.CredentialRejected) {
        return terminalCredential(state, event.nowEpochMs)
    }
    val waiting = state as? LifecycleRecoveryState.WaitingForTunnel
    val probing = state as? LifecycleRecoveryState.Probing
    val previousAttempt = waiting?.attempt
        ?: probing?.recoveryAttempt
        ?: 0
    val startedAt = waiting?.recoveryStartedAtEpochMs
        ?: probing?.recoveryStartedAtEpochMs
    return waitForFailure(
        scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state),
        failure = event.failure,
        nowEpochMs = event.nowEpochMs,
        previousAttempt = previousAttempt,
        startedAt = startedAt,
        authorization = authorizationOf(state),
        previous = state,
    )
}

private fun credentialRejected(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.CredentialRejected,
): LifecycleRecoveryDecision {
    if (event.alreadyRefreshed || state is LifecycleRecoveryState.RefreshingCredential) {
        return terminalCredential(state, event.nowEpochMs)
    }
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    val authorization = authorizationOf(state) ?: AuthorizationKind.LoopbackSession
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.RefreshingCredential(
            scope,
            RecoveryJobs(credentialRefresh = true),
            authorization,
        ),
        cancelTimers(state) + LifecycleRecoveryEffect.RefreshCredential(scope),
    )
}

private fun credentialRefreshSucceeded(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.CredentialRefreshSucceeded,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.Ready(scope, event.authorization),
        cancelTimers(state),
    )
}

private fun credentialRefreshFailed(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.CredentialRefreshFailed,
): LifecycleRecoveryDecision {
    if (event.failure == TunnelConnectionFailure.CredentialRejected) {
        return terminalCredential(state, event.nowEpochMs)
    }
    return waitForFailure(
        scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state),
        failure = event.failure,
        nowEpochMs = event.nowEpochMs,
        previousAttempt = 0,
        startedAt = null,
        authorization = authorizationOf(state),
        previous = state,
    )
}

private fun transportLost(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.TransportLost,
): LifecycleRecoveryDecision {
    if (state.isTerminalCredentialFailure()) return LifecycleRecoveryDecision(state)
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    if (event.hasActiveTurn) {
        if (state is LifecycleRecoveryState.RecoveringTurn) {
            return LifecycleRecoveryDecision(
                state.copy(
                    sessionId = event.sessionId,
                    jobs = RecoveryJobs(turnRecovery = true),
                ),
                listOf(
                    LifecycleRecoveryEffect.CancelRetry(scope),
                    LifecycleRecoveryEffect.RecoverTurn(scope, event.sessionId),
                ),
            )
        }
        val nextRetry = event.nowEpochMs + lifecycleBackoffMs(1)
        return LifecycleRecoveryDecision(
            LifecycleRecoveryState.RecoveringTurn(
                scope = scope,
                sessionId = event.sessionId,
                attempt = 1,
                nextRetryAtEpochMs = nextRetry,
                recoveryStartedAtEpochMs = event.nowEpochMs,
                jobs = RecoveryJobs(retryTimer = true),
                authorization = authorizationOf(state) ?: AuthorizationKind.LoopbackSession,
                elapsedMs = lifecycleBackoffMs(1),
            ),
            cancelTimers(state) + LifecycleRecoveryEffect.ScheduleRetry(scope, nextRetry, lifecycleBackoffMs(1)),
        )
    }
    if (state is LifecycleRecoveryState.Ready) {
        return requestDebouncedProbe(state, event.nowEpochMs)
    }
    return waitForFailure(
        scope = scope,
        failure = TunnelConnectionFailure.TunnelUnavailable,
        nowEpochMs = event.nowEpochMs,
        previousAttempt = 0,
        startedAt = null,
        authorization = authorizationOf(state),
        previous = state,
    )
}

private fun turnRecovered(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.TurnRecovered,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.Ready(scope, event.authorization),
        cancelTimers(state),
    )
}

private fun turnRecoveryFailed(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.TurnRecoveryFailed,
): LifecycleRecoveryDecision {
    val recovering = state as? LifecycleRecoveryState.RecoveringTurn
    if (event.hasActiveTurn && recovering != null) {
        return scheduleTurnRetry(
            recovering,
            event.nowEpochMs,
            recovering.attempt,
            recovering.recoveryStartedAtEpochMs,
        )
    }
    return waitForFailure(
        scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state),
        failure = TunnelConnectionFailure.TunnelUnavailable,
        nowEpochMs = event.nowEpochMs,
        previousAttempt = recovering?.attempt ?: 0,
        startedAt = recovering?.recoveryStartedAtEpochMs,
        authorization = authorizationOf(state),
        previous = state,
    )
}

private fun retryTimerFired(
    state: LifecycleRecoveryState,
    event: LifecycleRecoveryEvent.RetryTimerFired,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    when (state) {
        is LifecycleRecoveryState.WaitingForTunnel -> {
            if (state.failure.requiresManualRecovery() ||
                budgetElapsed(state.recoveryStartedAtEpochMs, event.nowEpochMs) ||
                state.budgetExhausted ||
                state.elapsedMs >= LIFECYCLE_RECOVERY_BUDGET_MS
            ) {
                return if (state.failure.requiresManualRecovery()) {
                    LifecycleRecoveryDecision(state, cancelTimers(state))
                } else {
                    exhaust(state, event.nowEpochMs)
                }
            }
            return startBootstrap(
                scope = scope,
                authorization = state.authorization,
                keepReadyPresentation = true,
                recoveryAttempt = state.attempt,
                recoveryStartedAtEpochMs = state.recoveryStartedAtEpochMs,
                previous = state,
            )
        }
        is LifecycleRecoveryState.RecoveringTurn -> {
            if (budgetElapsed(state.recoveryStartedAtEpochMs, event.nowEpochMs) ||
                state.elapsedMs >= LIFECYCLE_RECOVERY_BUDGET_MS
            ) {
                return exhaustTurn(state, event.nowEpochMs)
            }
            return LifecycleRecoveryDecision(
                state.copy(jobs = RecoveryJobs(turnRecovery = true)),
                listOf(
                    LifecycleRecoveryEffect.CancelRetry(scope),
                    LifecycleRecoveryEffect.RecoverTurn(scope, state.sessionId),
                ),
            )
        }
        else -> return LifecycleRecoveryDecision(state)
    }
}

private fun manualRetry(
    state: LifecycleRecoveryState,
    nowEpochMs: Long,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    return startBootstrap(
        scope = scope,
        authorization = authorizationOf(state),
        keepReadyPresentation = true,
        recoveryAttempt = 0,
        recoveryStartedAtEpochMs = nowEpochMs,
        previous = state,
        resetBudget = true,
    )
}

private fun startBootstrap(
    scope: RecoveryScope,
    authorization: AuthorizationKind?,
    keepReadyPresentation: Boolean,
    recoveryAttempt: Int,
    recoveryStartedAtEpochMs: Long?,
    previous: LifecycleRecoveryState,
    resetBudget: Boolean = false,
): LifecycleRecoveryDecision {
    val effects = if (resetBudget) {
        listOf(
            LifecycleRecoveryEffect.CancelRetry(scope),
            LifecycleRecoveryEffect.CancelDebounce(scope),
            LifecycleRecoveryEffect.Bootstrap(scope),
        )
    } else {
        cancelTimers(previous) + LifecycleRecoveryEffect.Bootstrap(scope)
    }
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.Probing(
            scope = scope,
            jobs = RecoveryJobs(bootstrap = true),
            authorization = authorization,
            keepReadyPresentation = keepReadyPresentation,
            recoveryAttempt = if (resetBudget) 0 else recoveryAttempt,
            recoveryStartedAtEpochMs = recoveryStartedAtEpochMs,
            recoveryElapsedMs = when {
                resetBudget -> 0L
                previous is LifecycleRecoveryState.WaitingForTunnel -> previous.elapsedMs
                previous is LifecycleRecoveryState.RecoveringTurn -> previous.elapsedMs
                previous is LifecycleRecoveryState.Probing -> previous.recoveryElapsedMs
                else -> 0L
            },
        ),
        effects,
    )
}

private fun waitForFailure(
    scope: RecoveryScope,
    failure: TunnelConnectionFailure,
    nowEpochMs: Long,
    previousAttempt: Int,
    startedAt: Long?,
    authorization: AuthorizationKind?,
    previous: LifecycleRecoveryState,
): LifecycleRecoveryDecision {
    if (failure == TunnelConnectionFailure.CredentialRejected) {
        return terminalCredential(previous, nowEpochMs)
    }
    if (failure.requiresManualRecovery()) {
        return waitForManual(previous, nowEpochMs, failure)
    }
    val attempt = previousAttempt + 1
    val recoveryStartedAt = startedAt ?: nowEpochMs
    val previousElapsed = when (previous) {
        is LifecycleRecoveryState.WaitingForTunnel -> previous.elapsedMs
        is LifecycleRecoveryState.RecoveringTurn -> previous.elapsedMs
        is LifecycleRecoveryState.Probing -> previous.recoveryElapsedMs
        else -> 0L
    }
    val wait = lifecycleBackoffMs(attempt)
    val nextElapsed = previousElapsed + wait
    if (budgetElapsed(recoveryStartedAt, nowEpochMs) ||
        nextElapsed >= LIFECYCLE_RECOVERY_BUDGET_MS
    ) {
        return LifecycleRecoveryDecision(
            LifecycleRecoveryState.WaitingForTunnel(
                scope = scope,
                failure = failure,
                attempt = attempt,
                nextRetryAtEpochMs = nowEpochMs,
                recoveryStartedAtEpochMs = recoveryStartedAt,
                budgetExhausted = true,
                jobs = RecoveryJobs(),
                authorization = authorization,
                elapsedMs = nextElapsed,
            ),
            cancelTimers(previous),
        )
    }
    val nextRetry = nowEpochMs + wait
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.WaitingForTunnel(
            scope = scope,
            failure = failure,
            attempt = attempt,
            nextRetryAtEpochMs = nextRetry,
            recoveryStartedAtEpochMs = recoveryStartedAt,
            budgetExhausted = false,
            jobs = RecoveryJobs(retryTimer = true),
            authorization = authorization,
            elapsedMs = nextElapsed,
        ),
        cancelTimers(previous) + LifecycleRecoveryEffect.ScheduleRetry(scope, nextRetry, wait),
    )
}

private fun scheduleTurnRetry(
    state: LifecycleRecoveryState.RecoveringTurn,
    nowEpochMs: Long,
    previousAttempt: Int,
    startedAt: Long,
): LifecycleRecoveryDecision {
    if (budgetElapsed(startedAt, nowEpochMs) ||
        state.elapsedMs + lifecycleBackoffMs(previousAttempt + 1) >= LIFECYCLE_RECOVERY_BUDGET_MS
    ) {
        return exhaustTurn(state, nowEpochMs)
    }
    val attempt = previousAttempt + 1
    val wait = lifecycleBackoffMs(attempt)
    val nextRetry = nowEpochMs + wait
    return LifecycleRecoveryDecision(
        state.copy(
            attempt = attempt,
            nextRetryAtEpochMs = nextRetry,
            jobs = RecoveryJobs(retryTimer = true),
            elapsedMs = state.elapsedMs + wait,
        ),
        cancelTimers(state) + LifecycleRecoveryEffect.ScheduleRetry(state.scope, nextRetry, wait),
    )
}

private fun terminalCredential(
    state: LifecycleRecoveryState,
    nowEpochMs: Long,
): LifecycleRecoveryDecision = waitForManual(
    state,
    nowEpochMs,
    TunnelConnectionFailure.CredentialRejected,
)

private fun waitForManual(
    state: LifecycleRecoveryState,
    nowEpochMs: Long,
    failure: TunnelConnectionFailure,
): LifecycleRecoveryDecision {
    val scope = state.scopeOrNull() ?: return LifecycleRecoveryDecision(state)
    return LifecycleRecoveryDecision(
        LifecycleRecoveryState.WaitingForTunnel(
            scope = scope,
            failure = failure,
            attempt = 1,
            nextRetryAtEpochMs = nowEpochMs,
            recoveryStartedAtEpochMs = nowEpochMs,
            budgetExhausted = true,
            jobs = RecoveryJobs(),
            authorization = authorizationOf(state),
        ),
        cancelTimers(state),
    )
}

private fun exhaust(
    state: LifecycleRecoveryState.WaitingForTunnel,
    nowEpochMs: Long,
): LifecycleRecoveryDecision = LifecycleRecoveryDecision(
    state.copy(
        budgetExhausted = true,
        nextRetryAtEpochMs = nowEpochMs,
        jobs = RecoveryJobs(),
    ),
    cancelTimers(state),
)

private fun exhaustTurn(
    state: LifecycleRecoveryState.RecoveringTurn,
    nowEpochMs: Long,
): LifecycleRecoveryDecision = LifecycleRecoveryDecision(
    LifecycleRecoveryState.WaitingForTunnel(
        scope = state.scope,
        failure = TunnelConnectionFailure.TunnelUnavailable,
        attempt = state.attempt,
        nextRetryAtEpochMs = nowEpochMs,
        recoveryStartedAtEpochMs = state.recoveryStartedAtEpochMs,
        budgetExhausted = true,
        jobs = RecoveryJobs(),
        authorization = state.authorization,
    ),
    cancelTimers(state),
)

private fun budgetElapsed(startedAt: Long, nowEpochMs: Long): Boolean =
    nowEpochMs - startedAt >= LIFECYCLE_RECOVERY_BUDGET_MS

private fun isStale(state: LifecycleRecoveryState, event: LifecycleRecoveryEvent): Boolean {
    val scope = state.scopeOrNull() ?: return false
    val stamped = stampedGeneration(event) ?: return false
    return stamped.origin != scope.origin || stamped.generation != scope.generation
}

private data class Stamped(val origin: ServerOrigin, val generation: Long)

private fun stampedGeneration(event: LifecycleRecoveryEvent): Stamped? = when (event) {
    is LifecycleRecoveryEvent.ProbeSucceeded -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.ProbeFailed -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.BootstrapSucceeded -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.BootstrapFailed -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.CredentialRejected -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.CredentialRefreshSucceeded -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.CredentialRefreshFailed -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.TransportLost -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.TurnRecovered -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.TurnRecoveryFailed -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.RetryTimerFired -> Stamped(event.origin, event.generation)
    is LifecycleRecoveryEvent.DebounceFired -> Stamped(event.origin, event.generation)
    else -> null
}

private fun authorizationOf(state: LifecycleRecoveryState): AuthorizationKind? = when (state) {
    is LifecycleRecoveryState.Probing -> state.authorization
    is LifecycleRecoveryState.Ready -> state.authorization
    is LifecycleRecoveryState.WaitingForTunnel -> state.authorization
    is LifecycleRecoveryState.RefreshingCredential -> state.authorization
    is LifecycleRecoveryState.RecoveringTurn -> state.authorization
    is LifecycleRecoveryState.Suspended -> authorizationOf(state.last)
    LifecycleRecoveryState.Unconfigured -> null
}

private fun cancelTimers(state: LifecycleRecoveryState): List<LifecycleRecoveryEffect> {
    val scope = state.scopeOrNull() ?: return emptyList()
    val jobs = state.jobsOrNull()
    return buildList {
        if (jobs?.retryTimer == true) {
            add(LifecycleRecoveryEffect.CancelRetry(scope))
        }
        if (jobs?.debounce == true) {
            add(LifecycleRecoveryEffect.CancelDebounce(scope))
        }
    }
}

private fun withoutTimers(state: LifecycleRecoveryState): LifecycleRecoveryState {
    val jobs = state.jobsOrNull() ?: return state
    return withJobs(state, jobs.copy(retryTimer = false, debounce = false))
}

private fun withoutIdleWork(state: LifecycleRecoveryState): LifecycleRecoveryState {
    val jobs = state.jobsOrNull() ?: return state
    return withJobs(
        state,
        jobs.copy(
            probe = false,
            bootstrap = false,
            retryTimer = false,
            debounce = false,
        ),
    )
}

private fun cancelInFlightIdleWork(state: LifecycleRecoveryState): List<LifecycleRecoveryEffect> {
    val scope = state.scopeOrNull() ?: return emptyList()
    val jobs = state.jobsOrNull() ?: return emptyList()
    return buildList {
        if (jobs.probe) add(LifecycleRecoveryEffect.CancelProbe(scope))
        if (jobs.bootstrap) add(LifecycleRecoveryEffect.CancelBootstrap(scope))
    }
}

private fun withJobs(
    state: LifecycleRecoveryState,
    jobs: RecoveryJobs,
): LifecycleRecoveryState = when (state) {
    is LifecycleRecoveryState.Probing -> state.copy(jobs = jobs)
    is LifecycleRecoveryState.Ready -> state.copy(jobs = jobs)
    is LifecycleRecoveryState.WaitingForTunnel -> state.copy(jobs = jobs)
    is LifecycleRecoveryState.RefreshingCredential -> state.copy(jobs = jobs)
    is LifecycleRecoveryState.RecoveringTurn -> state.copy(jobs = jobs)
    is LifecycleRecoveryState.Suspended,
    LifecycleRecoveryState.Unconfigured,
    -> state
}
