package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

private const val MAX_CHAT_RECOVERIES_PER_OPERATION = 2

/** State for one accepted turn's bounded transport recovery. */
internal class ChatRecoveryState(
    val operationGeneration: Long,
    var remaining: Int = MAX_CHAT_RECOVERIES_PER_OPERATION,
    var activeAttempt: ChatRecoveryAttempt? = null,
)

internal class ChatRecoveryAttempt(
    val state: ChatRecoveryState,
)

/**
 * The durable-session keyed owner of one live runtime and its event/recovery work.
 * Durable and runtime IDs intentionally remain separate: replacing a controller
 * never makes an old runtime eligible to publish into a newer controller.
 */
internal data class PerSessionController(
    val durableSessionId: DurableSessionId,
    val session: HermesChatSession,
    val runtimeSessionId: RuntimeSessionId,
    var operationGeneration: Long,
    var eventJob: Job? = null,
    /** Registry liveness polling for unresolved delegated children of this runtime. */
    var registryJob: Job? = null,
    var recoveryState: ChatRecoveryState? = null,
)

/** A snapshot of controller identity captured before a suspending operation. */
internal data class ControllerOperation(
    val durableSessionId: DurableSessionId,
    val session: HermesChatSession,
    val runtimeSessionId: RuntimeSessionId,
    val origin: ServerOrigin,
    val originGeneration: Long,
    val chatOperationGeneration: Long,
    val requestId: String? = null,
    val advertisedChoices: List<String> = emptyList(),
    val blockingKind: UnsupportedBlockingKind? = null,
)

/**
 * Registry/owner for all durable-session runtime lifecycle state.
 *
 * This is deliberately transport-agnostic. The ViewModel supplies the close
 * operation and publication callbacks, while this owner keeps per-session
 * generations, jobs, controllers, recovery attempts, and turn membership
 * together. No selected-session UI state is used to decide ownership.
 */
internal class PerSessionControllerRegistry {
    val lock = Any()
    val controllers = mutableMapOf<DurableSessionId, PerSessionController>()
    val chatJobs = mutableMapOf<DurableSessionId, Job>()
    private val promptSubmissionLifecycles = mutableMapOf<DurableSessionId, PromptSubmissionLifecycle>()
    // Recovery can outlive removal of its failed controller and event-job slot.
    private val recoveryJobs = mutableMapOf<DurableSessionId, Job>()

    fun setRecoveryJob(durableSessionId: DurableSessionId, job: Job) {
        recoveryJobs[durableSessionId] = job
    }

    fun clearRecoveryJob(durableSessionId: DurableSessionId, job: Job) {
        if (recoveryJobs[durableSessionId] === job) recoveryJobs.remove(durableSessionId)
    }

    private fun cancelRecoveryJobs() {
        recoveryJobs.values.toList().forEach(Job::cancel)
        recoveryJobs.clear()
    }
    val chatOperationGenerations = mutableMapOf<DurableSessionId, Long>()
    val sessionInsightsJobs = mutableMapOf<DurableSessionId, Job>()
    val sessionInsightsGenerations = mutableMapOf<DurableSessionId, Long>()
    val slashCompletionJobs = mutableMapOf<DurableSessionId, Job>()
    val slashCompletionGenerations = mutableMapOf<DurableSessionId, Long>()
    val activeTurnIds = mutableSetOf<DurableSessionId>()

    var nextChatOperationGeneration = 0L
    var lastPublishedActiveTurnCount = 0

    fun nextOperationGeneration(durableSessionId: DurableSessionId): Long {
        recoveryJobs.remove(durableSessionId)?.cancel()
        chatJobs.remove(durableSessionId)?.cancel()
        val operationGeneration = ++nextChatOperationGeneration
        chatOperationGenerations[durableSessionId] = operationGeneration
        return operationGeneration
    }

    fun operationGeneration(durableSessionId: DurableSessionId): Long? =
        chatOperationGenerations[durableSessionId]

    fun cancelOperationJob(durableSessionId: DurableSessionId) {
        recoveryJobs.remove(durableSessionId)?.cancel()
        chatJobs.remove(durableSessionId)?.cancel()
    }

    fun setOperationJob(durableSessionId: DurableSessionId, job: Job) {
        chatJobs[durableSessionId] = job
    }

    fun beginPromptSubmission(
        durableSessionId: DurableSessionId,
        draft: String,
        attachmentIds: List<String>,
    ): PromptSubmissionLifecycle.Attempt? = promptSubmissionLifecycles
        .getOrPut(durableSessionId, ::PromptSubmissionLifecycle)
        .begin(draft, attachmentIds)

    fun observePromptTerminal(durableSessionId: DurableSessionId): PromptSubmissionLifecycle.TerminalEffect =
        promptSubmissionLifecycles[durableSessionId]?.observeTerminal()
            ?: PromptSubmissionLifecycle.TerminalEffect.Ignored

    fun resolvePromptSubmission(
        durableSessionId: DurableSessionId,
        attempt: PromptSubmissionLifecycle.Attempt,
        accepted: Boolean,
    ): PromptSubmissionLifecycle.Resolution {
        val lifecycle = promptSubmissionLifecycles[durableSessionId]
            ?: return PromptSubmissionLifecycle.Resolution.Stale
        val resolution = lifecycle.resolve(attempt, accepted)
        if (resolution !is PromptSubmissionLifecycle.Resolution.Stale) {
            promptSubmissionLifecycles.remove(durableSessionId)
        }
        return resolution
    }

    fun controller(durableSessionId: DurableSessionId): PerSessionController? =
        controllers[durableSessionId]

    fun activateController(controller: PerSessionController) {
        controllers[controller.durableSessionId] = controller
    }

    fun setEventJob(
        durableSessionId: DurableSessionId,
        session: HermesChatSession,
        job: Job,
    ) {
        controllers[durableSessionId]
            ?.takeIf { it.session === session }
            ?.let { controller ->
                controller.eventJob?.cancel()
                controller.eventJob = job
            }
            ?: job.cancel()
    }

    fun isCurrentChatOperation(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        currentOrigin: ServerOrigin?,
        originGeneration: Long,
        currentGeneration: Long,
        operationGeneration: Long,
    ): Boolean =
        currentOrigin == origin &&
            currentGeneration == originGeneration &&
            chatOperationGenerations[durableSessionId] == operationGeneration

    fun isExactControllerRuntime(
        durableSessionId: DurableSessionId,
        session: HermesChatSession,
        runtimeSessionId: RuntimeSessionId,
        isPublishedControllerRuntime: (DurableSessionId, RuntimeSessionId) -> Boolean,
    ): Boolean {
        val controller = controllers[durableSessionId] ?: return false
        return controller.session === session &&
            controller.runtimeSessionId == runtimeSessionId &&
            isPublishedControllerRuntime(durableSessionId, runtimeSessionId)
    }

    fun prepareExistingController(
        durableSessionId: DurableSessionId,
        operationGeneration: Long,
    ): PerSessionController? {
        val controller = controllers[durableSessionId] ?: return null
        controller.operationGeneration = operationGeneration
        controller.recoveryState = ChatRecoveryState(operationGeneration)
        return controller
    }

    fun startRecovery(
        durableSessionId: DurableSessionId,
        operationGeneration: Long,
    ): ChatRecoveryAttempt? {
        val state = controllers[durableSessionId]?.recoveryState
            ?.takeIf { it.operationGeneration == operationGeneration }
            ?: return null
        if (state.activeAttempt != null || state.remaining <= 0) return null
        state.remaining -= 1
        return ChatRecoveryAttempt(state).also { state.activeAttempt = it }
    }

    fun finishRecovery(attempt: ChatRecoveryAttempt) {
        if (controllers.values.any { it.recoveryState === attempt.state } &&
            attempt.state.activeAttempt === attempt
        ) {
            attempt.state.activeAttempt = null
        }
    }

    fun isRecoveryInProgress(
        durableSessionId: DurableSessionId,
        operationGeneration: Long,
    ): Boolean =
        controllers[durableSessionId]?.recoveryState
            ?.takeIf { it.operationGeneration == operationGeneration }
            ?.activeAttempt != null

    /** Invalidates active prompt jobs when the transport changes to relay. */
    fun invalidateChatOperations() {
        nextChatOperationGeneration += 1L
        cancelRecoveryJobs()
        chatJobs.values.forEach(Job::cancel)
        chatJobs.clear()
        chatOperationGenerations.clear()
        promptSubmissionLifecycles.clear()
    }

    /** Cancels operation work while leaving close/adoption to [detachAll]. */
    fun invalidateOperations() {
        nextChatOperationGeneration += 1L
        cancelRecoveryJobs()
        chatJobs.values.forEach(Job::cancel)
        chatJobs.clear()
        chatOperationGenerations.clear()
        promptSubmissionLifecycles.clear()
        sessionInsightsJobs.values.forEach(Job::cancel)
        sessionInsightsJobs.clear()
        sessionInsightsGenerations.clear()
    }

    /** Removes one exact controller; the caller decides whether to close its session. */
    fun detachController(
        durableSessionId: DurableSessionId,
        expectedSession: HermesChatSession,
    ): PerSessionController? {
        val controller = controllers[durableSessionId]
            ?.takeIf { it.session === expectedSession }
            ?: return null
        controllers.remove(durableSessionId)
        chatOperationGenerations.remove(durableSessionId)
        chatJobs.remove(durableSessionId)
        return controller
    }

    /** Removes a runtime for recovery while retaining the durable operation generation. */
    fun replaceControllerForRecovery(
        durableSessionId: DurableSessionId,
        expectedSession: HermesChatSession,
    ): PerSessionController? {
        val controller = controllers[durableSessionId]
            ?.takeIf { it.session === expectedSession }
            ?: return null
        controllers.remove(durableSessionId)
        return controller
    }

    fun detachAll(): List<PerSessionController> {
        val detached = controllers.values.toList()
        controllers.clear()
        chatOperationGenerations.clear()
        promptSubmissionLifecycles.clear()
        cancelRecoveryJobs()
        chatJobs.values.forEach(Job::cancel)
        chatJobs.clear()
        sessionInsightsJobs.values.forEach(Job::cancel)
        sessionInsightsJobs.clear()
        sessionInsightsGenerations.clear()
        slashCompletionJobs.values.forEach(Job::cancel)
        slashCompletionJobs.clear()
        slashCompletionGenerations.clear()
        detached.forEach { it.eventJob?.cancel(); it.registryJob?.cancel() }
        activeTurnIds.clear()
        lastPublishedActiveTurnCount = 0
        return detached
    }

    fun syncActiveTurns(
        isSending: (DurableSessionId) -> Boolean,
        onCountChanged: (Int) -> Unit,
    ) {
        activeTurnIds.retainAll(isSending)
        if (activeTurnIds.size != lastPublishedActiveTurnCount) {
            lastPublishedActiveTurnCount = activeTurnIds.size
            onCountChanged(activeTurnIds.size)
        }
    }

    fun markTurnActive(
        durableSessionId: DurableSessionId,
        title: String,
        onStarted: (DurableSessionId, String, Int) -> Unit,
    ) {
        if (!activeTurnIds.add(durableSessionId)) return
        lastPublishedActiveTurnCount = activeTurnIds.size
        onStarted(durableSessionId, title, activeTurnIds.size)
    }
}
