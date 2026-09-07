package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectSessionsResult
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectTreeResult
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

const val DEFAULT_PROJECT_PREVIEW_LIMIT = 3
const val DEFAULT_PROJECT_SESSION_LIMIT = 500

interface HermesChatSession {
    val events: Flow<HermesChatEvent>
    val relayLeaseSnapshot: com.unsupportedpastels.hermesandroid.relay.RelayLeaseSnapshot? get() = null
    fun acknowledgeRelayEvent(eventId: String?) {}

    /** Relay-owned metadata RPCs carried over the same admitted Hermes channel. */
    suspend fun relayRequest(method: String, params: JsonObject): JsonObject =
        throw HermesChatMethodNotFoundException(method)

    /** Profiles visible to this ordinary gateway client. */
    suspend fun loadProfiles(): List<String> =
        throw HermesChatMethodNotFoundException("profiles.list")

    suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String? = null,
    ): ResumedChatSession

    /** Read-only project metadata; this never resumes or creates a runtime. */
    suspend fun loadProjectTree(
        profile: String? = null,
        previewLimit: Int = DEFAULT_PROJECT_PREVIEW_LIMIT,
        sessionLimit: Int = DEFAULT_PROJECT_SESSION_LIMIT,
    ): ProjectTreeResult = throw HermesChatMethodNotFoundException("projects.tree")

    /** Read-only durable sessions for one project; this never activates a runtime. */
    suspend fun loadProjectSessions(
        projectId: ProjectId,
        profile: String? = null,
        sessionLimit: Int = DEFAULT_PROJECT_SESSION_LIMIT,
    ): ProjectSessionsResult = throw HermesChatMethodNotFoundException("projects.project_sessions")

    /** Process-local delegated children from the authoritative gateway registry. */
    suspend fun loadDelegationStatus(): DelegationStatus =
        throw HermesChatMethodNotFoundException("delegation.status")

    /**
     * Read-only observer presence from the official `session.active_list`
     * snapshot: durable session IDs currently running a turn in the connected
     * gateway process. Enumerates without transport rebinding; the result
     * never implies runtime ownership and must not gate resume affordances.
     */
    suspend fun loadActiveSessionPresence(): Set<DurableSessionId> =
        throw HermesChatMethodNotFoundException("session.active_list")

    /** Session-scoped background processes owned by this exact runtime. */
    suspend fun loadProcessList(runtimeSessionId: RuntimeSessionId): List<ProcessRow> =
        throw HermesChatMethodNotFoundException("process.list")

    /** Creates and activates a project rooted at an existing host directory. */
    suspend fun createProject(
        name: String,
        path: String,
        profile: String? = null,
    ): ProjectSummary = throw HermesChatMethodNotFoundException("projects.create")

    /**
     * Creates a fresh runtime (gateway `session.create`). The server persists the
     * durable row lazily on the first prompt; [durableSessionId] is the client-side
     * draft identity used until that row exists.
     */
    suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String? = null,
        workspacePath: String? = null,
    ): ResumedChatSession = throw HermesChatProtocolException("Session creation is not available")

    suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission

    /**
     * Submits a prompt with the voice barge-in annotation. The released gateway
     * latches `params.interrupted` so the turn's model message carries the
     * interruption note; it is sent only when true, so older servers see an
     * unchanged request. Implementations without the live transport just drop
     * the flag.
     */
    suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
        interrupted: Boolean,
    ): PromptSubmission = submitPrompt(runtimeSessionId, text)

    /** Relay's existing lease-local correlation contract; never implies replay safety. */
    suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
        interrupted: Boolean,
        submissionId: String?,
    ): PromptSubmission = submitPrompt(runtimeSessionId, text, interrupted)

    /** Adds bounded steering text to the currently running turn. */
    suspend fun steer(runtimeSessionId: RuntimeSessionId, text: String): SessionSteerResult =
        throw HermesChatMethodNotFoundException("session.steer")

    suspend fun loadSessionUsage(runtimeSessionId: RuntimeSessionId): SessionUsage =
        throw HermesChatMethodNotFoundException("session.usage")

    suspend fun loadContextBreakdown(runtimeSessionId: RuntimeSessionId): SessionContextBreakdown =
        throw HermesChatMethodNotFoundException("session.context_breakdown")

    suspend fun compressSession(runtimeSessionId: RuntimeSessionId, focusTopic: String? = null): SessionCompressResult =
        throw HermesChatMethodNotFoundException("session.compress")

    suspend fun undoSession(runtimeSessionId: RuntimeSessionId): SessionUndoResult =
        throw HermesChatMethodNotFoundException("session.undo")

    suspend fun branchSession(
        runtimeSessionId: RuntimeSessionId,
        count: Int? = null,
        name: String? = null,
    ): SessionBranchResult = throw HermesChatMethodNotFoundException("session.branch")

    suspend fun pauseDelegation(paused: Boolean): DelegationPauseResult =
        throw HermesChatMethodNotFoundException("delegation.pause")

    suspend fun interruptSubagent(subagentId: String): SubagentInterruptResult =
        throw HermesChatMethodNotFoundException("subagent.interrupt")

    suspend fun steerSubagent(
        runtimeSessionId: RuntimeSessionId,
        subagentId: String,
        text: String,
    ): SubagentSteerResult = throw HermesChatMethodNotFoundException("subagent.steer")

    /** include_disabled is intentionally true so paused jobs are visible. */
    suspend fun loadCronJobs(): List<CronJob> =
        throw HermesChatMethodNotFoundException("cron.manage")

    /** Profile-aware overload; legacy fakes and gateways remain source-compatible. */
    suspend fun loadCronJobsForProfile(profile: String): List<CronJob> = loadCronJobs()

    suspend fun manageCronJob(jobId: String, action: CronJobAction): Unit =
        throw HermesChatMethodNotFoundException("cron.manage")

    suspend fun respondToClarification(
        requestId: String,
        answer: String,
    ): HermesChatResponse = throw HermesChatProtocolException("Clarification response is not available")

    /** Batch clarify: answers one question of the request by its `qid`. */
    suspend fun respondToClarification(
        requestId: String,
        questionId: String?,
        answer: String,
    ): HermesChatResponse = respondToClarification(requestId, answer)

    suspend fun respondToApproval(
        runtimeSessionId: RuntimeSessionId,
        choice: String,
        all: Boolean = false,
        requestId: String? = null,
    ): HermesChatResponse = throw HermesChatProtocolException("Approval response is not available")

    suspend fun respondToBlockingPrompt(
        kind: UnsupportedBlockingKind,
        requestId: String,
        value: String,
    ): HermesChatResponse = throw HermesChatProtocolException("Blocking prompt response is not available")

    suspend fun interruptSession(
        runtimeSessionId: RuntimeSessionId,
    ): HermesChatResponse = throw HermesChatProtocolException("Session interrupt is not available")

    /**
     * Stage a non-image file on the remote host via `file.attach` and return its
     * `@file:` ref text to prepend to the submitted prompt.
     */
    suspend fun attachFile(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        mimeType: String,
        base64Content: String,
    ): String = throw HermesChatProtocolException("File attachment is not available")

    /** Stage an image on the remote host via `image.attach_bytes`; it rides the next prompt. */
    suspend fun attachImage(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        base64Content: String,
    ): Unit = throw HermesChatProtocolException("Image attachment is not available")

    /** Live slash-command completion from the connected host; never a static local list. */
    suspend fun completeSlash(text: String): SlashCompletionResult =
        throw HermesChatProtocolException("Slash completion is not available")

    suspend fun loadModelOptions(runtimeSessionId: RuntimeSessionId): ModelOptions =
        throw HermesChatProtocolException("Model selection is not available")

    /** Profile-default model options without creating or resuming a runtime. */
    suspend fun loadProfileModelOptions(): ModelOptions =
        throw HermesChatMethodNotFoundException("model.options")

    suspend fun setModel(
        runtimeSessionId: RuntimeSessionId,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean = false,
    ): ModelSwitchResult = throw HermesChatProtocolException("Model selection is not available")

    suspend fun setReasoning(
        runtimeSessionId: RuntimeSessionId,
        effort: String,
    ): Unit = throw HermesChatProtocolException("Reasoning selection is not available")

    suspend fun setFast(
        runtimeSessionId: RuntimeSessionId,
        fast: Boolean,
    ): Unit = throw HermesChatProtocolException("Fast mode selection is not available")

    suspend fun close()
}
