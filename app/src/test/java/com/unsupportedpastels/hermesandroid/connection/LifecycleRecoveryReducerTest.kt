package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleRecoveryReducerTest {
    private val origin = ServerOrigin.parse("http://127.0.0.1:19119")
    private val otherOrigin = ServerOrigin.parse("http://127.0.0.1:19219")
    private val tunnel = ServerConnectionMode.ExternalSshTunnel

    private fun scope(generation: Long = 1L) = RecoveryScope(origin, generation, tunnel)

    private data class Case(
        val name: String,
        val initial: LifecycleRecoveryState,
        val event: LifecycleRecoveryEvent,
        val check: (LifecycleRecoveryDecision) -> Unit,
    )

    @Test
    fun tableDrivenPolicy() {
        val cases = listOf(
            Case(
                name = "configured starts a bootstrap",
                initial = LifecycleRecoveryState.Unconfigured,
                event = LifecycleRecoveryEvent.Configured(origin, 1L, tunnel, nowEpochMs = 0L),
            ) { decision ->
                val probing = decision.state as LifecycleRecoveryState.Probing
                assertEquals(scope(), probing.scope)
                assertTrue(probing.jobs.bootstrap)
                assertFalse(probing.keepReadyPresentation)
                assertEquals(ConnectionState.Connecting, probing.publishedConnectionState())
                assertEquals(
                    listOf(LifecycleRecoveryEffect.Bootstrap(scope())),
                    decision.effects,
                )
            },
            Case(
                name = "bootstrap success is ready with loopback auth",
                initial = LifecycleRecoveryState.Probing(
                    scope(),
                    RecoveryJobs(bootstrap = true),
                ),
                event = LifecycleRecoveryEvent.BootstrapSucceeded(
                    origin, 1L, AuthorizationKind.LoopbackSession,
                ),
            ) { decision ->
                assertEquals(
                    LifecycleRecoveryState.Ready(scope(), AuthorizationKind.LoopbackSession),
                    decision.state,
                )
                assertEquals(ConnectionState.Connected, decision.state.publishedConnectionState())
                assertTrue(decision.effects.isEmpty())
            },
            Case(
                name = "transport bootstrap failure waits with 1s backoff",
                initial = LifecycleRecoveryState.Probing(
                    scope(),
                    RecoveryJobs(bootstrap = true),
                ),
                event = LifecycleRecoveryEvent.BootstrapFailed(
                    origin, 1L, TunnelConnectionFailure.TunnelUnavailable, nowEpochMs = 10_000L,
                ),
            ) { decision ->
                val waiting = decision.state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(TunnelConnectionFailure.TunnelUnavailable, waiting.failure)
                assertEquals(1, waiting.attempt)
                assertEquals(11_000L, waiting.nextRetryAtEpochMs)
                assertEquals(10_000L, waiting.recoveryStartedAtEpochMs)
                assertFalse(waiting.budgetExhausted)
                assertTrue(waiting.jobs.retryTimer)
                assertEquals(ConnectionState.Recovering, waiting.publishedConnectionState())
                assertEquals(
                    listOf(LifecycleRecoveryEffect.ScheduleRetry(scope(), 11_000L)),
                    decision.effects,
                )
            },
            Case(
                name = "second transport failure uses 2s backoff",
                initial = waiting(attempt = 1, nextRetryAt = 11_000L, startedAt = 10_000L),
                event = LifecycleRecoveryEvent.BootstrapFailed(
                    origin, 1L, TunnelConnectionFailure.TunnelUnavailable, nowEpochMs = 11_000L,
                ),
            ) { decision ->
                val waiting = decision.state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(2, waiting.attempt)
                assertEquals(13_000L, waiting.nextRetryAtEpochMs)
                assertEquals(
                    listOf(
                        LifecycleRecoveryEffect.CancelRetry(scope()),
                        LifecycleRecoveryEffect.ScheduleRetry(scope(), 13_000L),
                    ),
                    decision.effects,
                )
            },
            Case(
                name = "backoff sequence reaches 5s then 10s then 30s cap",
                initial = waiting(attempt = 2, nextRetryAt = 13_000L, startedAt = 10_000L),
                event = LifecycleRecoveryEvent.BootstrapFailed(
                    origin, 1L, TunnelConnectionFailure.TunnelUnavailable, nowEpochMs = 13_000L,
                ),
            ) { decision ->
                val third = decision.state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(3, third.attempt)
                assertEquals(18_000L, third.nextRetryAtEpochMs)
                val fourth = reduceLifecycle(
                    third,
                    LifecycleRecoveryEvent.BootstrapFailed(
                        origin, 1L, TunnelConnectionFailure.TunnelUnavailable, nowEpochMs = 18_000L,
                    ),
                ).state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(4, fourth.attempt)
                assertEquals(28_000L, fourth.nextRetryAtEpochMs)
                val fifth = reduceLifecycle(
                    fourth,
                    LifecycleRecoveryEvent.BootstrapFailed(
                        origin, 1L, TunnelConnectionFailure.TunnelUnavailable, nowEpochMs = 28_000L,
                    ),
                ).state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(5, fifth.attempt)
                assertEquals(58_000L, fifth.nextRetryAtEpochMs)
                val sixth = reduceLifecycle(
                    fifth,
                    LifecycleRecoveryEvent.BootstrapFailed(
                        origin, 1L, TunnelConnectionFailure.TunnelUnavailable, nowEpochMs = 58_000L,
                    ),
                ).state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(6, sixth.attempt)
                assertEquals(88_000L, sixth.nextRetryAtEpochMs)
            },
            Case(
                name = "retry timer bootstraps while budget remains",
                initial = waiting(attempt = 1, nextRetryAt = 11_000L, startedAt = 10_000L),
                event = LifecycleRecoveryEvent.RetryTimerFired(origin, 1L, nowEpochMs = 11_000L),
            ) { decision ->
                val probing = decision.state as LifecycleRecoveryState.Probing
                assertTrue(probing.jobs.bootstrap)
                assertTrue(probing.keepReadyPresentation)
                assertEquals(ConnectionState.Recovering, probing.publishedConnectionState())
                assertEquals(
                    listOf(
                        LifecycleRecoveryEffect.CancelRetry(scope()),
                        LifecycleRecoveryEffect.Bootstrap(scope()),
                    ),
                    decision.effects,
                )
            },
            Case(
                name = "five minute budget stops automatic retries",
                initial = waiting(attempt = 8, nextRetryAt = 310_000L, startedAt = 0L),
                event = LifecycleRecoveryEvent.RetryTimerFired(origin, 1L, nowEpochMs = 300_000L),
            ) { decision ->
                val waiting = decision.state as LifecycleRecoveryState.WaitingForTunnel
                assertTrue(waiting.budgetExhausted)
                assertFalse(waiting.jobs.retryTimer)
                assertEquals(ConnectionState.Disconnected, waiting.publishedConnectionState())
                assertEquals(
                    listOf(LifecycleRecoveryEffect.CancelRetry(scope())),
                    decision.effects,
                )
            },
            Case(
                name = "manual retry is immediate and resets the budget",
                initial = waiting(
                    attempt = 8,
                    nextRetryAt = 310_000L,
                    startedAt = 0L,
                    exhausted = true,
                ),
                event = LifecycleRecoveryEvent.ManualRetry(nowEpochMs = 400_000L),
            ) { decision ->
                val probing = decision.state as LifecycleRecoveryState.Probing
                assertTrue(probing.jobs.bootstrap)
                assertTrue(probing.keepReadyPresentation)
                assertEquals(
                    listOf(
                        LifecycleRecoveryEffect.CancelRetry(scope()),
                        LifecycleRecoveryEffect.CancelDebounce(scope()),
                        LifecycleRecoveryEffect.Bootstrap(scope()),
                    ),
                    decision.effects,
                )
            },
            Case(
                name = "second credential rejection is terminal",
                initial = LifecycleRecoveryState.RefreshingCredential(
                    scope(),
                    RecoveryJobs(credentialRefresh = true),
                    AuthorizationKind.LoopbackSession,
                ),
                event = LifecycleRecoveryEvent.CredentialRejected(
                    origin, 1L, nowEpochMs = 5_000L, alreadyRefreshed = true,
                ),
            ) { decision ->
                val waiting = decision.state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(TunnelConnectionFailure.CredentialRejected, waiting.failure)
                assertTrue(waiting.budgetExhausted)
                assertEquals(ConnectionState.Disconnected, waiting.publishedConnectionState())
                assertTrue(waiting.isTerminalCredentialFailure())
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.ScheduleRetry })
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.Bootstrap })
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.Probe })
            },
            Case(
                name = "first credential rejection refreshes once",
                initial = LifecycleRecoveryState.Ready(scope(), AuthorizationKind.LoopbackSession),
                event = LifecycleRecoveryEvent.CredentialRejected(
                    origin, 1L, nowEpochMs = 5_000L, alreadyRefreshed = false,
                ),
            ) { decision ->
                val refreshing = decision.state as LifecycleRecoveryState.RefreshingCredential
                assertTrue(refreshing.jobs.credentialRefresh)
                assertEquals(ConnectionState.Recovering, refreshing.publishedConnectionState())
                assertEquals(
                    listOf(LifecycleRecoveryEffect.RefreshCredential(scope())),
                    decision.effects,
                )
            },
            Case(
                name = "foreground does not revive a terminal credential failure",
                initial = waiting(
                    attempt = 1,
                    nextRetryAt = 0L,
                    startedAt = 0L,
                    exhausted = true,
                    failure = TunnelConnectionFailure.CredentialRejected,
                    retry = false,
                ),
                event = LifecycleRecoveryEvent.Foreground(nowEpochMs = 50_000L, hasActiveTurn = false),
            ) { decision ->
                assertTrue(decision.state.isTerminalCredentialFailure())
                assertTrue(decision.effects.isEmpty())
            },
            Case(
                name = "foreground from ready schedules a debounced probe",
                initial = LifecycleRecoveryState.Ready(scope(), AuthorizationKind.LoopbackSession),
                event = LifecycleRecoveryEvent.Foreground(nowEpochMs = 1_000L, hasActiveTurn = false),
            ) { decision ->
                val ready = decision.state as LifecycleRecoveryState.Ready
                assertTrue(ready.jobs.debounce)
                assertEquals(ConnectionState.Connected, ready.publishedConnectionState())
                assertEquals(
                    listOf(LifecycleRecoveryEffect.ScheduleDebounce(scope(), 1_300L)),
                    decision.effects,
                )
            },
            Case(
                name = "debounce from ready starts one probe",
                initial = LifecycleRecoveryState.Ready(
                    scope(),
                    AuthorizationKind.LoopbackSession,
                    RecoveryJobs(debounce = true),
                ),
                event = LifecycleRecoveryEvent.DebounceFired(origin, 1L, nowEpochMs = 1_300L),
            ) { decision ->
                val probing = decision.state as LifecycleRecoveryState.Probing
                assertTrue(probing.keepReadyPresentation)
                assertTrue(probing.jobs.probe)
                assertEquals(AuthorizationKind.LoopbackSession, probing.authorization)
                assertEquals(ConnectionState.Recovering, probing.publishedConnectionState())
                assertEquals(
                    listOf(
                        LifecycleRecoveryEffect.CancelDebounce(scope()),
                        LifecycleRecoveryEffect.Probe(scope()),
                    ),
                    decision.effects,
                )
            },
            Case(
                name = "successful probe from ready leaves ready unchanged",
                initial = LifecycleRecoveryState.Probing(
                    scope(),
                    RecoveryJobs(probe = true),
                    authorization = AuthorizationKind.LoopbackSession,
                    keepReadyPresentation = true,
                ),
                event = LifecycleRecoveryEvent.ProbeSucceeded(
                    origin, 1L, AuthorizationKind.LoopbackSession,
                ),
            ) { decision ->
                assertEquals(
                    LifecycleRecoveryState.Ready(scope(), AuthorizationKind.LoopbackSession),
                    decision.state,
                )
                assertTrue(decision.effects.isEmpty())
            },
            Case(
                name = "failed probe from ready waits without dropping the generation",
                initial = LifecycleRecoveryState.Probing(
                    scope(),
                    RecoveryJobs(probe = true),
                    authorization = AuthorizationKind.LoopbackSession,
                    keepReadyPresentation = true,
                ),
                event = LifecycleRecoveryEvent.ProbeFailed(
                    origin, 1L, TunnelConnectionFailure.TunnelUnavailable, nowEpochMs = 2_000L,
                ),
            ) { decision ->
                val waiting = decision.state as LifecycleRecoveryState.WaitingForTunnel
                assertEquals(scope(), waiting.scope)
                assertEquals(AuthorizationKind.LoopbackSession, waiting.authorization)
                assertEquals(TunnelConnectionFailure.TunnelUnavailable, waiting.failure)
                assertFalse(waiting.budgetExhausted)
            },
            Case(
                name = "network hint from ready is only a debounce not a ready proof",
                initial = LifecycleRecoveryState.Ready(scope(), AuthorizationKind.LoopbackSession),
                event = LifecycleRecoveryEvent.NetworkHint(nowEpochMs = 4_000L),
            ) { decision ->
                val ready = decision.state as LifecycleRecoveryState.Ready
                assertEquals(AuthorizationKind.LoopbackSession, ready.authorization)
                assertEquals(ConnectionState.Connected, ready.publishedConnectionState())
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.Bootstrap })
                assertEquals(
                    listOf(LifecycleRecoveryEffect.ScheduleDebounce(scope(), 4_300L)),
                    decision.effects,
                )
            },
            Case(
                name = "background idle suspends and cancels timers",
                initial = waiting(attempt = 1, nextRetryAt = 11_000L, startedAt = 10_000L),
                event = LifecycleRecoveryEvent.Background(hasActiveTurn = false),
            ) { decision ->
                val suspended = decision.state as LifecycleRecoveryState.Suspended
                assertTrue(suspended.last is LifecycleRecoveryState.WaitingForTunnel)
                assertEquals(ConnectionState.Recovering, suspended.publishedConnectionState())
                assertTrue(decision.effects.any { it is LifecycleRecoveryEffect.CancelRetry })
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.Probe })
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.Bootstrap })
            },
            Case(
                name = "background with an active turn keeps recovering",
                initial = recoveringTurn(attempt = 1, nextRetryAt = 11_000L, startedAt = 10_000L),
                event = LifecycleRecoveryEvent.Background(hasActiveTurn = true),
            ) { decision ->
                assertTrue(decision.state is LifecycleRecoveryState.RecoveringTurn)
                assertTrue(decision.effects.isEmpty())
            },
            Case(
                name = "stale probe success after a generation change is discarded",
                initial = LifecycleRecoveryState.Probing(
                    scope(generation = 2L),
                    RecoveryJobs(bootstrap = true),
                ),
                event = LifecycleRecoveryEvent.ProbeSucceeded(
                    origin, 1L, AuthorizationKind.LoopbackSession,
                ),
            ) { decision ->
                val probing = decision.state as LifecycleRecoveryState.Probing
                assertEquals(2L, probing.scope.generation)
                assertTrue(probing.jobs.bootstrap)
                assertTrue(decision.effects.isEmpty())
            },
            Case(
                name = "origin change discards the previous generation's retry",
                initial = waiting(attempt = 1, nextRetryAt = 11_000L, startedAt = 10_000L),
                event = LifecycleRecoveryEvent.Configured(
                    otherOrigin, 2L, tunnel, nowEpochMs = 20_000L,
                ),
            ) { decision ->
                val probing = decision.state as LifecycleRecoveryState.Probing
                assertEquals(otherOrigin, probing.scope.origin)
                assertEquals(2L, probing.scope.generation)
                assertTrue(
                    decision.effects.contains(LifecycleRecoveryEffect.CancelRetry(scope(1L))),
                )
                assertTrue(
                    decision.effects.contains(
                        LifecycleRecoveryEffect.Bootstrap(
                            RecoveryScope(otherOrigin, 2L, tunnel),
                        ),
                    ),
                )
            },
            Case(
                name = "one probe job under concurrent foreground and network hint",
                initial = LifecycleRecoveryState.Ready(
                    scope(),
                    AuthorizationKind.LoopbackSession,
                    RecoveryJobs(debounce = true),
                ),
                event = LifecycleRecoveryEvent.NetworkHint(nowEpochMs = 9_000L),
            ) { decision ->
                val ready = decision.state as LifecycleRecoveryState.Ready
                assertTrue(ready.jobs.debounce)
                assertFalse(ready.jobs.probe)
                assertEquals(1, decision.effects.filterIsInstance<LifecycleRecoveryEffect.ScheduleDebounce>().size)
            },
            Case(
                name = "bootstrap in flight swallows a concurrent probe request",
                initial = LifecycleRecoveryState.Probing(
                    scope(),
                    RecoveryJobs(bootstrap = true),
                    keepReadyPresentation = true,
                ),
                event = LifecycleRecoveryEvent.Foreground(nowEpochMs = 3_000L, hasActiveTurn = false),
            ) { decision ->
                val probing = decision.state as LifecycleRecoveryState.Probing
                assertTrue(probing.jobs.bootstrap)
                assertFalse(probing.jobs.probe)
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.Probe })
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.Bootstrap })
            },
            Case(
                name = "turn recovery give-up waits for the tunnel",
                initial = recoveringTurn(attempt = 4, nextRetryAt = 300_000L, startedAt = 0L),
                event = LifecycleRecoveryEvent.RetryTimerFired(origin, 1L, nowEpochMs = 300_000L),
            ) { decision ->
                val waiting = decision.state as LifecycleRecoveryState.WaitingForTunnel
                assertTrue(waiting.budgetExhausted)
                assertEquals(TunnelConnectionFailure.TunnelUnavailable, waiting.failure)
                assertEquals(ConnectionState.Disconnected, waiting.publishedConnectionState())
                assertFalse(decision.effects.any { it is LifecycleRecoveryEffect.RecoverTurn })
            },
        )

        val failures = mutableListOf<String>()
        for (case in cases) {
            try {
                case.check(reduceLifecycle(case.initial, case.event))
            } catch (error: AssertionError) {
                failures += "${case.name}: ${error.message}"
            } catch (error: ClassCastException) {
                failures += "${case.name}: state was ${reduceLifecycle(case.initial, case.event).state} (${error.message})"
            }
        }
        assertTrue(failures.joinToString(separator = "\n"), failures.isEmpty())
    }

    @Test
    fun backoffScheduleIsExactlyOneTwoFiveTenThirty() {
        assertEquals(
            listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L),
            (1..6).map(::lifecycleBackoffMs),
        )
    }

    private fun waiting(
        attempt: Int,
        nextRetryAt: Long,
        startedAt: Long,
        exhausted: Boolean = false,
        failure: TunnelConnectionFailure = TunnelConnectionFailure.TunnelUnavailable,
        retry: Boolean = true,
        authorization: AuthorizationKind? = AuthorizationKind.LoopbackSession,
    ) = LifecycleRecoveryState.WaitingForTunnel(
        scope = scope(),
        failure = failure,
        attempt = attempt,
        nextRetryAtEpochMs = nextRetryAt,
        recoveryStartedAtEpochMs = startedAt,
        budgetExhausted = exhausted,
        jobs = RecoveryJobs(retryTimer = retry),
        authorization = authorization,
    )

    private fun recoveringTurn(
        attempt: Int,
        nextRetryAt: Long,
        startedAt: Long,
    ) = LifecycleRecoveryState.RecoveringTurn(
        scope = scope(),
        sessionId = "turn-1",
        attempt = attempt,
        nextRetryAtEpochMs = nextRetryAt,
        recoveryStartedAtEpochMs = startedAt,
        jobs = RecoveryJobs(retryTimer = true),
        authorization = AuthorizationKind.LoopbackSession,
    )
}
