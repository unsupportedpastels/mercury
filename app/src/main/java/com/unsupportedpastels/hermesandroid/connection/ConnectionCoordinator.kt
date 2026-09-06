package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

/** Token state owned by the connection generation that obtained it. */
internal data class ActiveTokenRecord(
    val origin: ServerOrigin,
    val generation: Long,
    val tokens: NativeTokenSet,
)

/** Identity returned when a connection scope is replaced. */
internal data class ConnectionTransition(
    val previousOrigin: ServerOrigin?,
    val origin: ServerOrigin?,
    val generation: Long,
    val profileGeneration: Long,
)

/**
 * Native owner for connection identity and its connection-scoped jobs.
 *
 * The ViewModel remains the public façade and owns publication, but it does not
 * own the mutable connection identity. Every transition increments [generation]
 * before cancelling work, which keeps non-cooperative completions from adopting
 * results into a later origin or relay connection.
 */
internal class ConnectionCoordinator {
    var activeOrigin: ServerOrigin? = null
        internal set
    var activeRelayTarget: RelayPairedTarget? = null
        internal set
    var activeTokens: ActiveTokenRecord? = null
    var generation: Long = 0L
        internal set
    var profileGeneration: Long = 0L
        internal set
    var lastReadySettings: ServerSettingsState.Ready? = null
    var consecutiveAuthProviderUnavailable: Int = 0

    var cacheLoadJob: Job? = null
    var connectionJob: Job? = null
    var projectLoadJob: Job? = null
    var refreshHomeJob: Job? = null
    var recentSessionsJob: Job? = null
    var recentSessionsScopeKey: String? = null
    var managementJob: Job? = null
    var managementRequestGeneration: Long = 0L
    var operationalStatusJob: Job? = null
    var searchJob: Job? = null
    var foregroundReconnectJob: Job? = null
    var signInJob: Job? = null
    val tokenRefreshMutex = Mutex()

    private val projectSessionJobs = mutableMapOf<ProjectId, Job>()
    private val projectSessionGenerations = mutableMapOf<ProjectId, Long>()
    private var nextProjectSessionGeneration = 0L

    /** Starts the same full direct-origin reset used by settings changes. */
    fun beginDirectTransition(nextOrigin: ServerOrigin?): ConnectionTransition {
        val transition = transitionTo(nextOrigin = nextOrigin, relayTarget = null)
        cancelDirectScopeJobs()
        return transition
    }

    /** Starts an explicit relay transition without letting direct settings take over. */
    fun beginRelayTransition(target: RelayPairedTarget, relayOrigin: ServerOrigin): ConnectionTransition {
        val previous = activeOrigin
        val nextGeneration = generation + 1L
        generation = nextGeneration
        connectionJob?.cancel()
        cacheLoadJob?.cancel()
        projectLoadJob?.cancel()
        activeTokens = null
        activeRelayTarget = target
        activeOrigin = relayOrigin
        profileGeneration += 1L
        return ConnectionTransition(previous, relayOrigin, generation, profileGeneration)
    }

    /** Invalidates relay work and returns to the empty direct-selection state. */
    fun leaveRelayTransition(): ConnectionTransition? {
        if (activeRelayTarget == null) return null
        val previous = activeOrigin
        generation += 1L
        activeRelayTarget = null
        activeOrigin = null
        connectionJob?.cancel()
        activeTokens = null
        return ConnectionTransition(previous, null, generation, profileGeneration)
    }

    /** Invalidates the current connection attempt for manual/foreground retry. */
    fun beginReconnect(): Long {
        generation += 1L
        connectionJob?.cancel()
        return generation
    }

    fun advanceProfileGeneration(): Long {
        profileGeneration += 1L
        return profileGeneration
    }

    fun currentScope(profile: String, authenticated: Boolean): ConnectionOperationScope? {
        val origin = activeOrigin ?: return null
        if (!authenticated) return null
        return ConnectionOperationScope(
            origin = origin,
            relayTargetId = activeRelayTarget?.id,
            profile = profile,
            generation = generation,
            profileGeneration = profileGeneration,
        )
    }

    fun isCurrent(origin: ServerOrigin, expectedGeneration: Long): Boolean =
        activeOrigin == origin && generation == expectedGeneration

    fun nextProjectSessionGeneration(projectId: ProjectId): Long {
        projectSessionJobs[projectId]?.cancel()
        val requestGeneration = ++nextProjectSessionGeneration
        projectSessionGenerations[projectId] = requestGeneration
        return requestGeneration
    }

    fun projectSessionGeneration(projectId: ProjectId): Long? = projectSessionGenerations[projectId]

    fun projectSessionJob(projectId: ProjectId): Job? = projectSessionJobs[projectId]

    fun setProjectSessionJob(projectId: ProjectId, job: Job) {
        projectSessionJobs[projectId] = job
    }

    fun finishProjectSession(projectId: ProjectId, requestGeneration: Long) {
        if (projectSessionGenerations[projectId] == requestGeneration) {
            projectSessionJobs.remove(projectId)
            projectSessionGenerations.remove(projectId)
        }
    }

    fun cancelProjectSessionJobs() {
        projectSessionJobs.values.forEach(Job::cancel)
        projectSessionJobs.clear()
        projectSessionGenerations.clear()
    }

    /** Cancels only jobs whose results are invalid after a direct-origin switch. */
    fun cancelDirectScopeJobs() {
        cacheLoadJob?.cancel()
        connectionJob?.cancel()
        projectLoadJob?.cancel()
        projectLoadJob = null
        refreshHomeJob?.cancel()
        refreshHomeJob = null
        recentSessionsJob?.cancel()
        recentSessionsJob = null
        recentSessionsScopeKey = null
        managementJob?.cancel()
        managementJob = null
        managementRequestGeneration += 1L
        operationalStatusJob?.cancel()
        operationalStatusJob = null
        searchJob?.cancel()
        searchJob = null
        cancelProjectSessionJobs()
    }

    /** Clears connection-scoped jobs during ViewModel teardown. */
    fun cancelAll() {
        cacheLoadJob?.cancel()
        connectionJob?.cancel()
        projectLoadJob?.cancel()
        refreshHomeJob?.cancel()
        recentSessionsJob?.cancel()
        managementJob?.cancel()
        operationalStatusJob?.cancel()
        searchJob?.cancel()
        foregroundReconnectJob?.cancel()
        signInJob?.cancel()
        cancelProjectSessionJobs()
        activeTokens = null
    }

    private fun transitionTo(
        nextOrigin: ServerOrigin?,
        relayTarget: RelayPairedTarget?,
    ): ConnectionTransition {
        val previous = activeOrigin
        generation += 1L
        activeTokens = null
        activeRelayTarget = relayTarget
        activeOrigin = nextOrigin
        profileGeneration += 1L
        return ConnectionTransition(previous, nextOrigin, generation, profileGeneration)
    }
}
