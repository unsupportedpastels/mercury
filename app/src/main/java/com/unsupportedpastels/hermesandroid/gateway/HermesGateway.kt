package com.unsupportedpastels.hermesandroid.gateway

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.MAX_PROCESS_ROWS
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.HermesAuthProvider
import com.unsupportedpastels.hermesandroid.connection.SessionBulkDeleteCapability
import com.unsupportedpastels.hermesandroid.connection.SessionSearchResult
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.CurrentModelInfo
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import kotlinx.coroutines.flow.StateFlow

@JvmInline
value class RuntimeSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Runtime session ID must not be blank" }
    }
}

enum class ConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Recovering,
}

enum class RuntimeAccess {
    Observer,
    Controller,
}

enum class AuthenticationState {
    Unknown,
    NotRequired,
    SignInRequired,
    SigningIn,
    Authenticated,
}

/** Bounded external-tunnel failure classification for actionable UI. */
enum class TunnelConnectionFailure {
    TunnelUnavailable,
    BootstrapRejected,
    CredentialRejected,
}

enum class CacheSource {
    Live,
    Cached,
}

data class ActiveRuntimeSession(
    val runtimeSessionId: RuntimeSessionId,
    val durableSessionId: DurableSessionId? = null,
    val title: String,
    val access: RuntimeAccess = RuntimeAccess.Observer,
)

enum class ChatMessageRole {
    User,
    Assistant,
    System,
    Tool,
}

data class ChatMessage(
    val role: ChatMessageRole,
    val text: String,
    val isStreaming: Boolean = false,
    val reasoningText: String = "",
)

data class ChatBillingNotice(
    val provider: String? = null,
    val billingUrl: String? = null,
    val isNous: Boolean = false,
    val message: String? = null,
)

data class ChatSessionSnapshot(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isStopping: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val billingNotice: ChatBillingNotice? = null,
    val runState: RunEventState = RunEventState(),
    val processRows: List<ProcessRow> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
    val modelCapabilities: ModelCapabilities? = null,
    val fastMode: String? = null,
    val reasoningEffort: String? = null,
    val draftDefaultsLoaded: Boolean = false,
    val sessionUsage: SessionUsage? = null,
    val contextBreakdown: SessionContextBreakdown? = null,
    val insightsLoading: Boolean = false,
    val insightsError: String? = null,
    val maintenanceLoading: Boolean = false,
    val maintenanceError: String? = null,
    val transcriptSource: CacheSource = CacheSource.Live,
) {
    init {
        require(processRows.size <= MAX_PROCESS_ROWS) { "Process rows exceed the bounded limit" }
    }
}

data class HermesGatewaySnapshot(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val authenticationState: AuthenticationState = AuthenticationState.Unknown,
    val serverVersion: String? = null,
    val nativeOAuthSupported: Boolean = false,
    val authProviders: List<HermesAuthProvider> = emptyList(),
    val connectionError: String? = null,
    val tunnelConnectionFailure: TunnelConnectionFailure? = null,
    val durableSessions: List<SessionSummary> = emptyList(),
    val recentSessions: RecentSessionsState = RecentSessionsState(),
    val sessionMetadataSource: CacheSource = CacheSource.Live,
    val projects: List<ProjectSummary> = emptyList(),
    val projectState: ProjectLoadState = ProjectLoadState.Loaded(emptyList()),
    val activeProjectId: ProjectId? = null,
    val scopedSessionIds: Set<DurableSessionId> = emptySet(),
    val projectSessions: Map<ProjectId, List<SessionSummary>> = emptyMap(),
    val projectSessionStates: Map<ProjectId, ProjectSessionLoadState> = emptyMap(),
    val activeRuntimes: List<ActiveRuntimeSession> = emptyList(),
    val chatSessions: Map<DurableSessionId, ChatSessionSnapshot> = emptyMap(),
    val delegationStatus: DelegationStatus = DelegationStatus(),
    val delegationStatusAvailable: Boolean = false,
    val operationalStatusState: OperationalStatusState = OperationalStatusState.Unavailable,
    val cronJobsState: CronJobsState = CronJobsState.Idle,
    val cronJobActionJobId: String? = null,
    val cronJobActionError: String? = null,
    val cronTriggerCapability: CronRestCapability = CronRestCapability.Unknown,
    val cronHistoryCapability: CronRestCapability = CronRestCapability.Unknown,
    val cronRunLoadingScopes: Set<CronJobScope> = emptySet(),
    val cronRunErrors: Map<CronJobScope, String> = emptyMap(),
    val cronRunsByScope: Map<CronJobScope, CronJobRunsState> = emptyMap(),
    val bulkDeleteCapability: SessionBulkDeleteCapability = SessionBulkDeleteCapability.Unknown,
    val profiles: List<String> = emptyList(),
    val selectedProfile: String = "default",
    val defaultModelOptions: ModelOptions? = null,
    val currentModelInfo: CurrentModelInfo? = null,
    /** Effective effort for the selected model, including its override when present. */
    val profileReasoningEffort: String? = null,
    /** Profile-wide fallback used by models without a per-model override. */
    val profileReasoningDefault: String? = null,
    /** Per-model reasoning effort overrides for the selected profile, keyed by
     *  the picker's ModelSelection. Empty when none are configured. */
    val profileModelReasoningOverrides: Map<ModelSelection, String> = emptyMap(),
    val managementLoading: Boolean = false,
    val managementError: String? = null,
    val searchQuery: String = "",
    val transcriptSearchResults: List<SessionSearchResult> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: String? = null,
    val lastBranchedSessionId: DurableSessionId? = null,
)

data class RecentSessionsState(
    val sessions: List<SessionSummary> = emptyList(),
    val total: Int? = null,
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)

interface HermesGateway {
    val snapshots: StateFlow<HermesGatewaySnapshot>

    suspend fun refresh(): HermesGatewaySnapshot
}
