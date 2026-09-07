package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionsResult
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectTreeResult
import com.unsupportedpastels.hermesandroid.app.ProcessListIdentity
import com.unsupportedpastels.hermesandroid.app.ProcessRowsState
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.reconcileProjectSession
import com.unsupportedpastels.hermesandroid.app.isNoProjectBucket
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.mercury.core.attachment.AttachmentAddResult
import com.unsupportedpastels.mercury.core.notifications.NotificationTextPolicy
import com.unsupportedpastels.mercury.core.relay.RelayAdmissionEnvelope
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import com.unsupportedpastels.hermesandroid.attachment.AttachmentByteReader
import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import com.unsupportedpastels.hermesandroid.attachment.AttachmentReadException
import com.unsupportedpastels.hermesandroid.attachment.AttachmentStager
import com.unsupportedpastels.hermesandroid.attachment.ContentAttachmentByteReader
import com.unsupportedpastels.hermesandroid.attachment.checkAdd
import com.unsupportedpastels.hermesandroid.cache.CacheScope
import com.unsupportedpastels.hermesandroid.cache.CachedSession
import com.unsupportedpastels.hermesandroid.cache.EncryptedOfflineCacheRepository
import com.unsupportedpastels.hermesandroid.cache.OfflineCacheRepository
import com.unsupportedpastels.hermesandroid.files.ManagedVideoCache
import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ChatBillingNotice
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.CacheSource
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnector
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnection
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatMethodNotFoundException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatResponseStatus
import com.unsupportedpastels.hermesandroid.gateway.HermesChatGateway
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatUnauthorizedException
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.RecentSessionsState
import com.unsupportedpastels.hermesandroid.gateway.HERMES_CHAT_MAX_FRAME_BYTES
import com.unsupportedpastels.hermesandroid.gateway.KtorChatWebSocketFactory
import com.unsupportedpastels.hermesandroid.notifications.AndroidTurnNotificationController
import com.unsupportedpastels.hermesandroid.notifications.NoOpTurnNotificationController
import com.unsupportedpastels.hermesandroid.notifications.TurnNotificationController
import com.unsupportedpastels.hermesandroid.gateway.KtorWsTicketClient
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.OperationalSnapshot
import com.unsupportedpastels.hermesandroid.gateway.OperationalStatusState
import com.unsupportedpastels.hermesandroid.voice.ElevenLabsVoice
import com.unsupportedpastels.hermesandroid.voice.KtorSpeechWebSocketFactory
import com.unsupportedpastels.hermesandroid.voice.SpeechAudio
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamConnector
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamSocket
import com.unsupportedpastels.hermesandroid.voice.StreamingSpeechTransport
import com.unsupportedpastels.hermesandroid.voice.TranscriptionResult
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import com.unsupportedpastels.hermesandroid.voice.VoiceServerConfig
import com.unsupportedpastels.hermesandroid.gateway.lastGoodOrNull
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.CronJobRunsState
import com.unsupportedpastels.hermesandroid.gateway.CronJobScope
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState
import com.unsupportedpastels.hermesandroid.gateway.CronRestCapability
import com.unsupportedpastels.hermesandroid.gateway.SessionBranchResult
import com.unsupportedpastels.hermesandroid.gateway.SessionContextBreakdown
import com.unsupportedpastels.hermesandroid.gateway.SessionUsage
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionResult
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.gateway.canonicalReasoningEffort
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.session.DataStoreSessionFilterRepository
import com.unsupportedpastels.hermesandroid.session.MAX_BULK_SELECTION
import com.unsupportedpastels.hermesandroid.session.SavedSessionFilter
import com.unsupportedpastels.hermesandroid.session.SessionFilterRepository
import com.unsupportedpastels.hermesandroid.session.SessionFilterScope
import com.unsupportedpastels.hermesandroid.session.evaluateBulkDeleteSelection
import com.unsupportedpastels.hermesandroid.session.SessionListFilter
import com.unsupportedpastels.hermesandroid.session.toggleBulkSelection
import com.unsupportedpastels.hermesandroid.relay.RelayFoldersClient
import com.unsupportedpastels.hermesandroid.relay.TlsRelayBinarySocketFactory
import com.unsupportedpastels.hermesandroid.relay.RelayConnector
import com.unsupportedpastels.hermesandroid.relay.RelayHermesChatSocket
import com.unsupportedpastels.hermesandroid.relay.RelayLeaseCheckpoint
import com.unsupportedpastels.hermesandroid.relay.RelayLeaseRecoverySocket
import com.unsupportedpastels.hermesandroid.relay.recoverRelayTasks
import com.unsupportedpastels.hermesandroid.ui.isSlashCommandContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class HermesConnectionViewModel(
    settingsStates: Flow<ServerSettingsState>,
    private val client: HermesConnectionClient,
    private val nativeLogin: NativeLogin? = null,
    private val passwordLogin: NativePasswordLogin? = null,
    private val closeResources: () -> Unit = {},
    private val tokenStore: NativeTokenStore? = null,
    private val refreshClient: NativeRefreshClient? = null,
    private val chatConnector: HermesChatConnector? = null,
    private val projectConnector: HermesChatConnector? = null,
    /**
     * Opens one admitted relay controller. The third argument is the lease
     * channel: null borrows the device's default channel (reads and metadata,
     * superseded by the next default open); chats pass a per-session channel
     * so several sessions stay open on one phone at once.
     */
    private val relaySessionFactory: (suspend (RelayPairedTarget, String, String?) -> HermesChatSession)? = null,
    private val cacheRepository: OfflineCacheRepository? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val attachmentReader: AttachmentByteReader =
        AttachmentByteReader { throw AttachmentReadException("Attachment reading is not available") },
    private val appForegroundStates: StateFlow<Boolean> = MutableStateFlow(true),
    private val relayNetworkAvailable: StateFlow<Boolean> = MutableStateFlow(true),
    private val notifications: TurnNotificationController = NoOpTurnNotificationController,
    private val sessionFilterRepository: SessionFilterRepository? = null,
    private val speechStreamConnector: SpeechStreamConnector? = null,
    private val videoCache: ManagedVideoCache? = null,
) : ViewModel() {
    // Serializes downloads targeting the same origin/path cache entry, so two
    // concurrent players of the same path never race on the shared `.part` file.
    private val videoDownloadLocks = ConcurrentHashMap<String, Mutex>()

    private val mutableSnapshots = MutableStateFlow(HermesGatewaySnapshot())
    val snapshots: StateFlow<HermesGatewaySnapshot> = mutableSnapshots.asStateFlow()

    private val mutableSavedSessionFilters = MutableStateFlow<List<SavedSessionFilter>>(emptyList())
    val savedSessionFilters: StateFlow<List<SavedSessionFilter>> = mutableSavedSessionFilters.asStateFlow()
    private val mutableSessionFilterScope = MutableStateFlow<SessionFilterScope?>(null)
    val sessionFilterScope: StateFlow<SessionFilterScope?> = mutableSessionFilterScope.asStateFlow()
    private var sessionFilterLoadJob: Job? = null
    private var sessionFilterScopeGeneration = 0L

    private val _homeRefreshing = MutableStateFlow(false)
    val homeRefreshing: StateFlow<Boolean> = _homeRefreshing.asStateFlow()

    val transcriptCachingEnabled: StateFlow<Boolean> = cacheRepository?.transcriptCachingEnabled
        ?: MutableStateFlow(false)

    private val connectionCoordinator = ConnectionCoordinator()
    private val sessionControllerRegistry = PerSessionControllerRegistry()
    // Fixed-size stripes bound lock retention; Relay admission is target-wide.
    private val controllerConnectionLocks = List(32) { kotlinx.coroutines.sync.Mutex() }
    private val relayConnectionLock = kotlinx.coroutines.sync.Mutex()

    private fun controllerConnectionLock(id: DurableSessionId) =
        if (activeRelayTarget != null) relayConnectionLock
        else controllerConnectionLocks[(id.hashCode() and Int.MAX_VALUE) % controllerConnectionLocks.size]

    // Compatibility façade for existing VM operations. The mutable values live in
    // the native owners above; callers still observe the same public snapshot API.
    private var cacheLoadJob: Job?
        get() = connectionCoordinator.cacheLoadJob
        set(value) { connectionCoordinator.cacheLoadJob = value }
    private var activeOrigin: ServerOrigin?
        get() = connectionCoordinator.activeOrigin
        set(value) { connectionCoordinator.activeOrigin = value }
    private var activeRelayTarget: RelayPairedTarget?
        get() = connectionCoordinator.activeRelayTarget
        set(value) { connectionCoordinator.activeRelayTarget = value }
    private var activeTokens: ActiveTokenRecord?
        get() = connectionCoordinator.activeTokens
        set(value) { connectionCoordinator.activeTokens = value }
    private var generation: Long
        get() = connectionCoordinator.generation
        set(value) { connectionCoordinator.generation = value }
    private var profileGeneration: Long
        get() = connectionCoordinator.profileGeneration
        set(value) { connectionCoordinator.profileGeneration = value }
    private var connectionJob: Job?
        get() = connectionCoordinator.connectionJob
        set(value) { connectionCoordinator.connectionJob = value }
    private var projectLoadJob: Job?
        get() = connectionCoordinator.projectLoadJob
        set(value) { connectionCoordinator.projectLoadJob = value }
    private var refreshHomeJob: Job?
        get() = connectionCoordinator.refreshHomeJob
        set(value) { connectionCoordinator.refreshHomeJob = value }
    private var recentSessionsJob: Job?
        get() = connectionCoordinator.recentSessionsJob
        set(value) { connectionCoordinator.recentSessionsJob = value }
    private var recentSessionsScopeKey: String?
        get() = connectionCoordinator.recentSessionsScopeKey
        set(value) { connectionCoordinator.recentSessionsScopeKey = value }
    private var managementJob: Job?
        get() = connectionCoordinator.managementJob
        set(value) { connectionCoordinator.managementJob = value }
    private var managementRequestGeneration: Long
        get() = connectionCoordinator.managementRequestGeneration
        set(value) { connectionCoordinator.managementRequestGeneration = value }
    private var operationalStatusJob: Job?
        get() = connectionCoordinator.operationalStatusJob
        set(value) { connectionCoordinator.operationalStatusJob = value }
    private var lastOperationalStatusFetch: OperationalStatusFetch? = null
    // The active Home filter's archived mode is publication state, not controller state.
    private var activeSessionArchivedFilter = false
    private var lastDurableSessionsFetchKey: DurableSessionsFetchKey? = null
    private val projectMetadataLock = Any()
    private var activeProjectMetadataSession: ProjectMetadataSessionRecord? = null
    private var searchJob: Job?
        get() = connectionCoordinator.searchJob
        set(value) { connectionCoordinator.searchJob = value }
    private var foregroundReconnectJob: Job?
        get() = connectionCoordinator.foregroundReconnectJob
        set(value) { connectionCoordinator.foregroundReconnectJob = value }
    private var signInJob: Job?
        get() = connectionCoordinator.signInJob
        set(value) { connectionCoordinator.signInJob = value }
    private val tokenRefreshMutex: Mutex
        get() = connectionCoordinator.tokenRefreshMutex
    private var lastReadySettings: ServerSettingsState.Ready?
        get() = connectionCoordinator.lastReadySettings
        set(value) { connectionCoordinator.lastReadySettings = value }
    private var consecutiveAuthProviderUnavailable: Int
        get() = connectionCoordinator.consecutiveAuthProviderUnavailable
        set(value) { connectionCoordinator.consecutiveAuthProviderUnavailable = value }

    private val liveControllers: MutableMap<DurableSessionId, PerSessionController>
        get() = sessionControllerRegistry.controllers
    private val activeTurnIds: MutableSet<DurableSessionId>
        get() = sessionControllerRegistry.activeTurnIds
    private val controllerLock: Any
        get() = sessionControllerRegistry.lock

    private val mutableSlashCompletions =
        MutableStateFlow<Map<DurableSessionId, SlashCompletionState>>(emptyMap())
    val slashCompletions: StateFlow<Map<DurableSessionId, SlashCompletionState>> =
        mutableSlashCompletions.asStateFlow()
    private val slashCompletionJobs: MutableMap<DurableSessionId, Job>
        get() = sessionControllerRegistry.slashCompletionJobs
    private val slashCompletionGenerations: MutableMap<DurableSessionId, Long>
        get() = sessionControllerRegistry.slashCompletionGenerations
    private val sessionInsightsJobs: MutableMap<DurableSessionId, Job>
        get() = sessionControllerRegistry.sessionInsightsJobs
    private val sessionInsightsGenerations: MutableMap<DurableSessionId, Long>
        get() = sessionControllerRegistry.sessionInsightsGenerations

    private val mutableModelPickerState = MutableStateFlow<ModelPickerState>(ModelPickerState.Closed)
    val modelPickerState: StateFlow<ModelPickerState> = mutableModelPickerState.asStateFlow()
    private var modelPickerJob: Job? = null
    private var modelPickerGeneration = 0L

    /** Composer attachments staged per session; uploaded to the host at send time. */
    private val mutableAttachments =
        MutableStateFlow<Map<DurableSessionId, List<ComposerAttachment>>>(emptyMap())
    val attachments: StateFlow<Map<DurableSessionId, List<ComposerAttachment>>> =
        mutableAttachments.asStateFlow()

    private val operationGuard = ConnectionOperationGuard(::currentConnectionOperationScope)
    private val voiceSettingsOwner = VoiceSettingsController(
        guard = operationGuard,
        probe = { scope -> withHermesRestOperation(scope) { origin, token ->
            val capabilities = client.probeVoiceCapabilities(origin, token.orEmpty(), scope.profile)
            operationGuard.ensureCurrent(scope)
            capabilities to client.loadVoiceServerConfig(origin, token.orEmpty(), scope.profile)
        } },
        write = { scope, payload -> withHermesRestOperation(scope) { origin, token ->
            client.updateServerConfig(origin, token.orEmpty(), scope.profile, payload)
        } },
        voices = { scope -> withHermesRestOperation(scope) { origin, token ->
            client.listElevenLabsVoices(origin, token.orEmpty(), scope.profile)
        } },
    )
    val voiceCapabilities: StateFlow<VoiceCapabilities> = voiceSettingsOwner.capabilities
    val voiceServerConfig: StateFlow<VoiceServerConfig> = voiceSettingsOwner.config

    /** Maps local draft IDs to the canonical durable IDs returned by session.create. */
    private val serverDurableIds = mutableMapOf<DurableSessionId, DurableSessionId>()

    /** Locally created chat drafts not yet persisted server-side (no durable row). */
    private val pendingDraftSessions = mutableSetOf<DurableSessionId>()
    private var draftCounter = 0L

    private fun setSessionFilterScope(serverOrigin: ServerOrigin?, profile: String?) {
        val nextScope = if (serverOrigin != null && !profile.isNullOrBlank()) {
            runCatching { SessionFilterScope(serverOrigin, profile.trim().take(64)) }.getOrNull()
        } else {
            null
        }
        if (mutableSessionFilterScope.value == nextScope) return
        sessionFilterScopeGeneration += 1
        sessionFilterLoadJob?.cancel()
        sessionFilterLoadJob = null
        mutableSessionFilterScope.value = nextScope
        mutableSavedSessionFilters.value = emptyList()
        val repository = sessionFilterRepository ?: return
        if (nextScope == null) return
        val expectedScopeGeneration = sessionFilterScopeGeneration
        sessionFilterLoadJob = viewModelScope.launch {
            val loaded = runCatching { repository.list(nextScope) }.getOrDefault(emptyList())
            if (sessionFilterScopeGeneration == expectedScopeGeneration &&
                mutableSessionFilterScope.value == nextScope
            ) {
                mutableSavedSessionFilters.value = loaded
            }
        }
    }

    suspend fun saveSessionFilter(filter: SavedSessionFilter) {
        val scope = mutableSessionFilterScope.value ?: return
        val repository = sessionFilterRepository ?: return
        repository.save(scope, filter)
        if (mutableSessionFilterScope.value == scope) {
            mutableSavedSessionFilters.value = repository.list(scope)
        }
    }

    suspend fun removeSessionFilter(name: String) {
        val scope = mutableSessionFilterScope.value ?: return
        val repository = sessionFilterRepository ?: return
        repository.remove(scope, name)
        if (mutableSessionFilterScope.value == scope) {
            mutableSavedSessionFilters.value = repository.list(scope)
        }
    }

    init {
        viewModelScope.launch {
            snapshots.collect { voiceSettingsOwner.onScopeChanged() }
        }
        viewModelScope.launch {
            var hasAppliedReadySettings = false
            settingsStates.collect { settingsState ->
                val nextOrigin = (settingsState as? ServerSettingsState.Ready)?.activeOrigin
                // Relay mode is an explicit transport selection. Direct settings remain passive
                // until a direct-server action calls leaveRelayMode() first; transient Loading /
                // Ready emissions must not clear the relay and start a competing direct connect.
                if (activeRelayTarget != null) {
                    if (settingsState is ServerSettingsState.Ready) {
                        lastReadySettings = settingsState
                    }
                    return@collect
                }
                // Label edits and catalog metadata updates must not tear down a live transport when
                // the active origin is unchanged. Only an actual active-origin transition resets
                // client state and starts a new connection generation.
                val directOriginUnchanged = settingsState is ServerSettingsState.Ready &&
                    nextOrigin == activeOrigin
                if (hasAppliedReadySettings && directOriginUnchanged) {
                    lastReadySettings = settingsState
                    return@collect
                }
                hasAppliedReadySettings = settingsState is ServerSettingsState.Ready
                val transition = connectionCoordinator.beginDirectTransition(nextOrigin)
                val currentGeneration = transition.generation
                val previousOrigin = transition.previousOrigin
                if (previousOrigin != null && previousOrigin != nextOrigin) {
                    viewModelScope.launch {
                        cacheRepository?.clearTranscriptTailsForOrigin(previousOrigin)
                    }
                }
                lastOperationalStatusFetch = null
                activeSessionArchivedFilter = false
                lastDurableSessionsFetchKey = null
                sessionControllerRegistry.invalidateOperations()
                signInJob?.cancel()
                modelPickerGeneration += 1
                modelPickerJob?.cancel()
                modelPickerJob = null
                mutableModelPickerState.value = ModelPickerState.Closed
                disconnectProjectMetadata()
                disconnectChat()

                pendingDraftSessions.clear()
                serverDurableIds.clear()
                mutableAttachments.value = emptyMap()
                setSessionFilterScope(null, null)
                lastReadySettings = settingsState as? ServerSettingsState.Ready
                when (settingsState) {
                    ServerSettingsState.Loading -> {
                        mutableSnapshots.value = HermesGatewaySnapshot()
                    }
                    ServerSettingsState.Unavailable -> {
                        mutableSnapshots.value = HermesGatewaySnapshot(
                            connectionError = "Server settings unavailable",
                        )
                    }
                    is ServerSettingsState.Ready -> {
                        mutableSnapshots.value = HermesGatewaySnapshot()
                        setSessionFilterScope(settingsState.activeOrigin, "default")
                        cacheLoadJob = viewModelScope.launch {
                            loadCachedMetadata(
                                serverOrigin = settingsState.serverOrigin,
                                profile = "default",
                                originGeneration = currentGeneration,
                                expectedProfileGeneration = profileGeneration,
                            )
                        }
                        connectionJob = viewModelScope.launch {
                            connect(settingsState.activeOrigin, currentGeneration)
                        }
                    }
                }
            }
        }
        // Self-healing on resume: when the app returns to the foreground and the
        // connection has dropped to a transient Disconnected state (e.g. the
        // process was backgrounded across a token expiry, or a network blip hit
        // the resume-time load), re-run connect() automatically. Deliberate
        // sign-in-required and healthy connected/connecting states are left alone.
        viewModelScope.launch {
            appForegroundStates.collect { foreground ->
                if (foreground) maybeReconnectOnForeground()
            }
        }
    }

    /**
     * Re-attempts the connection for the current Ready settings without waiting for
     * a settings change. Used by the manual "Retry" affordance and the automatic
     * foreground recovery. No-op when there is no configured origin or a connection
     * attempt is already in flight.
     */
    fun retryConnection(): Job {
        val relayTarget = activeRelayTarget
        return if (relayTarget != null) {
            connectRelay(relayTarget, mutableSnapshots.value.selectedProfile)
        } else {
            viewModelScope.launch { reconnectActiveOrigin() }
        }
    }

    /** Connects through one approved Mercury Relay target without probing its router as Hermes REST. */
    fun connectRelay(target: RelayPairedTarget, profile: String = "default"): Job {
        if (target.status != RelayTargetStatus.Approved) return viewModelScope.launch { }
        val factory = relaySessionFactory ?: return viewModelScope.launch { }
        val relayOrigin = runCatching { ServerOrigin.parse(target.relayOrigin) }.getOrNull()
            ?: return viewModelScope.launch { }
        val boundedProfile = profile.trim().take(64).ifEmpty { "default" }
        val transition = connectionCoordinator.beginRelayTransition(target, relayOrigin)
        val currentGeneration = transition.generation
        sessionControllerRegistry.invalidateChatOperations()
        val cleanupJob = viewModelScope.launch {
            disconnectProjectMetadata()
            disconnectChat()
        }
        mutableSnapshots.value = HermesGatewaySnapshot(
            connectionState = ConnectionState.Connecting,
            relayTargetId = target.id,
            relayTargetLabel = target.displayLabel,
            selectedProfile = boundedProfile,
        )
        return viewModelScope.launch {
            var session: HermesChatSession? = null
            var admissionLocked = false
            try {
                cleanupJob.join()
                relayConnectionLock.lock()
                admissionLocked = true
                if (generation != currentGeneration || activeRelayTarget?.id != target.id) return@launch
                session = factory(target, boundedProfile, null)
                val profiles = try {
                    session.loadProfiles()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: HermesChatMethodNotFoundException) {
                    emptyList()
                }
                val result = session.relayRequest(
                    "relay.sessions.list",
                    buildJsonObject {
                        put("profile", boundedProfile)
                        put("limit", 20)
                        put("offset", 0)
                    },
                )
                val durableSessions = parseRelaySessionRows(result)
                val projectSnapshot = loadRelayProjects(
                    session = session,
                    profile = boundedProfile,
                    durableSessions = durableSessions,
                )
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeRelayTarget?.id != target.id) return@launch
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.Authenticated,
                    relayTargetId = target.id,
                    relayTargetLabel = target.displayLabel,
                    durableSessions = durableSessions,
                    projects = projectSnapshot.projects,
                    projectState = projectSnapshot.state,
                    activeProjectId = projectSnapshot.activeProjectId,
                    scopedSessionIds = projectSnapshot.scopedSessionIds,
                    profiles = (profiles + boundedProfile).distinct(),
                    selectedProfile = boundedProfile,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != currentGeneration || activeRelayTarget?.id != target.id) return@launch
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Disconnected,
                    authenticationState = AuthenticationState.Unknown,
                    relayTargetId = target.id,
                    relayTargetLabel = target.displayLabel,
                    connectionError = relayConnectionErrorMessage(error),
                )
            } finally {
                try {
                    closeChatSessionNonCancellably(session)
                } finally {
                    if (admissionLocked) relayConnectionLock.unlock()
                }
            }
        }.also { connectionJob = it }
    }

    fun leaveRelayMode() {
        if (connectionCoordinator.leaveRelayTransition() == null) return
        viewModelScope.launch {
            disconnectProjectMetadata()
            disconnectChat()
        }
        mutableSnapshots.value = HermesGatewaySnapshot()
    }

    private fun refreshRelaySessions(target: RelayPairedTarget): Job {
        val expectedGeneration = generation
        val factory = relaySessionFactory ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            relayConnectionLock.lock()
            var session: HermesChatSession? = null
            val borrowed = liveControllers.values.firstOrNull()?.session
            try {
                if (generation != expectedGeneration || activeRelayTarget?.id != target.id) return@launch
                session = borrowed ?: factory(target, mutableSnapshots.value.selectedProfile, null)
                val result = session.relayRequest(
                    "relay.sessions.list",
                    buildJsonObject {
                        put("profile", mutableSnapshots.value.selectedProfile)
                        put("limit", MAX_RELAY_SESSIONS)
                        put("offset", 0)
                    },
                )
                val durableSessions = parseRelaySessionRows(result)
                val projectSnapshot = loadRelayProjects(
                    session = session,
                    profile = mutableSnapshots.value.selectedProfile,
                    durableSessions = durableSessions,
                )
                if (generation != expectedGeneration || activeRelayTarget?.id != target.id) return@launch
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    connectionState = ConnectionState.Connected,
                    connectionError = null,
                    durableSessions = durableSessions,
                    projects = projectSnapshot.projects,
                    projectState = projectSnapshot.state,
                    activeProjectId = projectSnapshot.activeProjectId,
                    scopedSessionIds = projectSnapshot.scopedSessionIds,
                    projectSessions = emptyMap(),
                    projectSessionStates = emptyMap(),
                    sessionMetadataSource = CacheSource.Live,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation != expectedGeneration || activeRelayTarget?.id != target.id) return@launch
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    connectionError = "The relay host could not be reached. Pull to retry.",
                )
            } finally {
                try {
                    if (borrowed == null) closeChatSessionNonCancellably(session)
                } finally {
                    relayConnectionLock.unlock()
                }
            }
        }
    }

    private fun maybeReconnectOnForeground() {
        // Only heal a dropped connection; never disturb a live session, an
        // in-progress attempt, or a deliberate sign-in prompt.
        if (mutableSnapshots.value.connectionState != ConnectionState.Disconnected) return
        if (mutableSnapshots.value.authenticationState == AuthenticationState.SignInRequired) return
        if (foregroundReconnectJob?.isActive == true) return
        val relayTarget = activeRelayTarget
        foregroundReconnectJob = if (relayTarget != null) {
            connectRelay(relayTarget, mutableSnapshots.value.selectedProfile)
        } else {
            viewModelScope.launch { reconnectActiveOrigin() }
        }
    }

    private suspend fun reconnectActiveOrigin() {
        val settings = lastReadySettings ?: return
        if (activeOrigin != settings.activeOrigin) return
        if (mutableSnapshots.value.connectionState == ConnectionState.Connecting) return
        // A deliberate sign-in prompt (e.g. after persistent auth-provider 503s)
        // must not be silently overwritten by an auto-reconnect: the user needs
        // to complete sign-in to mint a fresh token. Leave the sign-in state in
        // place; the sign-in flow drives the next connect.
        if (mutableSnapshots.value.authenticationState == AuthenticationState.SignInRequired) return
        val currentGeneration = connectionCoordinator.beginReconnect()
        val job = viewModelScope.launch {
            connect(settings.activeOrigin, currentGeneration)
        }
        connectionJob = job
        job.join()
    }

    private suspend fun loadCachedMetadata(
        serverOrigin: ServerOrigin?,
        profile: String,
        originGeneration: Long,
        expectedProfileGeneration: Long,
    ) {
        val repository = cacheRepository ?: return
        val origin = serverOrigin ?: return
        val cached = repository.read(CacheScope(origin, profile), nowEpochSeconds())
        currentCoroutineContext().ensureActive()
        val currentSnapshot = mutableSnapshots.value
        val liveMetadataIsAuthoritative =
            currentSnapshot.connectionState == ConnectionState.Connected &&
                currentSnapshot.sessionMetadataSource == CacheSource.Live &&
                currentSnapshot.authenticationState in setOf(
                    AuthenticationState.Authenticated,
                    AuthenticationState.NotRequired,
                )
        if (
            activeOrigin != origin || generation != originGeneration ||
            profileGeneration != expectedProfileGeneration || cached.sessions.isEmpty() ||
            liveMetadataIsAuthoritative
        ) return
        mutableSnapshots.value = currentSnapshot.copy(
            durableSessions = cached.sessions.map(CachedSession::summary),
            sessionMetadataSource = CacheSource.Cached,
            chatSessions = cached.sessions.fold(mutableSnapshots.value.chatSessions) { chats, session ->
                if (session.messages.isEmpty() || chats[session.summary.id]?.owningProfile != null) chats else chats + (
                    session.summary.id to ChatSessionSnapshot(
                        messages = session.messages,
                        transcriptSource = CacheSource.Cached,
                        owningProfile = session.summary.profile,
                    )
                )
            },
        )
    }

    fun setTranscriptCachingEnabled(enabled: Boolean): Job = viewModelScope.launch {
        cacheRepository?.setTranscriptCachingEnabled(enabled)
    }

    fun clearOfflineCache(): Job = viewModelScope.launch {
        val expectedOrigin = activeOrigin
        val expectedGeneration = generation
        cacheRepository?.clear(null)
        if (expectedOrigin != null && activeOrigin == expectedOrigin && generation == expectedGeneration) {
            val current = mutableSnapshots.value
            mutableSnapshots.value = current.copy(
                durableSessions = if (current.sessionMetadataSource == CacheSource.Cached) {
                    emptyList()
                } else {
                    current.durableSessions
                },
                sessionMetadataSource = if (current.sessionMetadataSource == CacheSource.Cached) {
                    CacheSource.Live
                } else {
                    current.sessionMetadataSource
                },
                chatSessions = current.chatSessions.filterValues { chat ->
                    chat.transcriptSource != CacheSource.Cached
                },
            )
        }
    }

    private fun persistCachedMetadata(
        origin: ServerOrigin,
        profile: String,
        sessions: List<SessionSummary>,
        expectedGeneration: Long,
    ) {
        viewModelScope.launch {
            cacheRepository?.writeMetadata(CacheScope(origin, profile), sessions, nowEpochSeconds())
            if (activeOrigin == origin && generation == expectedGeneration) {
                // The write is local bookkeeping; the server remains authoritative.
            }
        }
    }

    private fun persistCachedTranscript(
        origin: ServerOrigin,
        profile: String,
        summary: SessionSummary,
        messages: List<ChatMessage>,
        expectedGeneration: Long,
    ) {
        viewModelScope.launch {
            cacheRepository?.writeTranscript(
                CacheScope(origin, profile), summary, messages, nowEpochSeconds(),
            )
            if (activeOrigin == origin && generation == expectedGeneration) {
                updateChat(summary.id) { it.copy(transcriptSource = CacheSource.Live) }
            }
        }
    }

    private suspend fun connect(serverOrigin: ServerOrigin?, currentGeneration: Long) {
        if (serverOrigin == null) {
            mutableSnapshots.value = HermesGatewaySnapshot()
            return
        }

        val currentSnapshot = mutableSnapshots.value
        mutableSnapshots.value = if (currentSnapshot.sessionMetadataSource == CacheSource.Cached) {
            currentSnapshot.copy(
                connectionState = ConnectionState.Connecting,
                connectionError = null,
            )
        } else {
            HermesGatewaySnapshot(connectionState = ConnectionState.Connecting)
        }
        try {
            val store = tokenStore
            val startup = if (store != null) {
                probeAndLoadSavedTokenConcurrently(
                    probe = { client.probe(serverOrigin) },
                    loadSavedToken = { store.load(serverOrigin) },
                    needsSavedToken = { it.authRequired },
                )
            } else {
                client.probe(serverOrigin) to null
            }
            val info = startup.first
            val stored = startup.second
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration || activeOrigin != serverOrigin) return

            if (!info.authRequired) {
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.NotRequired,
                    serverVersion = info.version,
                    nativeOAuthSupported = info.nativeOAuthSupported,
                    authProviders = info.providers,
                    durableSessions = info.sessions,
                    sessionMetadataSource = CacheSource.Live,
                )
                persistCachedMetadata(serverOrigin, "default", info.sessions, currentGeneration)
                return
            }

            // Route the establishment refresh through the SAME serialization
            // (tokenRefreshMutex) every other caller uses. After an outage the app
            // wakes several drivers at once — foreground reconnect, live-chat
            // recovery, and this establishment connect — and refreshing here
            // OUTSIDE the lock let two of them each spend the single-use rotated
            // refresh token: one rotated RT1->RT2 and won, the loser presented the
            // now-consumed RT1 and got 401 session_expired, flip-flopping forever
            // (16.9k refresh failures / 3k successes observed). Seeding activeTokens
            // first lets refreshAndPublishTokensLocked refresh, persist, and publish
            // the rotation atomically under the mutex; a terminal expiry surfaces as
            // NativeRefreshExpiredException (handled below -> clean sign-in).
            val usableTokens = if (stored != null) {
                activeTokens = ActiveTokenRecord(serverOrigin, currentGeneration, stored)
                accessTokenForRequest(serverOrigin, currentGeneration)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return
                activeTokens
                    ?.takeIf { it.origin == serverOrigin && it.generation == currentGeneration }
                    ?.tokens
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            if (usableTokens != null) {
                // refreshAndPublishTokensLocked already persisted and published any
                // rotation under the mutex, so no extra store.save is needed here.
                val authenticated: AuthenticatedHermesConnection
                val prefetchedSession: HermesChatSession?
                if (projectConnector != null) {
                    val concurrentResult = authenticateAndPrefetchConcurrently(
                        authenticate = {
                            client.authenticate(serverOrigin, usableTokens.accessToken)
                        },
                        prefetchMetadata = {
                            connectProjectMetadataCandidate(
                                serverOrigin = serverOrigin,
                                originGeneration = currentGeneration,
                                accessToken = usableTokens.accessToken,
                            )
                        },
                        discardMetadata = { candidate ->
                            closeChatSessionNonCancellably(candidate)
                        },
                    )
                    authenticated = concurrentResult.first
                    prefetchedSession = concurrentResult.second.getOrNull()
                } else {
                    authenticated = client.authenticate(serverOrigin, usableTokens.accessToken)
                    prefetchedSession = null
                }
                try {
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    closeChatSessionNonCancellably(prefetchedSession)
                    throw cancelled
                }
                if (generation != currentGeneration || activeOrigin != serverOrigin) {
                    closeChatSessionNonCancellably(prefetchedSession)
                    return
                }
                activeTokens = ActiveTokenRecord(serverOrigin, currentGeneration, usableTokens)
                consecutiveAuthProviderUnavailable = 0
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.Authenticated,
                    serverVersion = info.version,
                    nativeOAuthSupported = info.nativeOAuthSupported,
                    authProviders = info.providers,
                    durableSessions = authenticated.sessions,
                    sessionMetadataSource = CacheSource.Live,
                )
                persistCachedMetadata(
                    serverOrigin,
                    mutableSnapshots.value.selectedProfile,
                    authenticated.sessions,
                    currentGeneration,
                )
                prefetchedSession?.let { candidate ->
                    adoptProjectMetadataSessionCandidate(
                        serverOrigin = serverOrigin,
                        originGeneration = currentGeneration,
                        accessToken = usableTokens.accessToken,
                        candidate = candidate,
                    )
                }
                startProjectTreeLoad(
                    serverOrigin = serverOrigin,
                    originGeneration = currentGeneration,
                    accessToken = usableTokens.accessToken,
                    durableSessions = authenticated.sessions,
                )
                refreshOperationalStatus(force = true)
            } else {
                mutableSnapshots.value = HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.SignInRequired,
                    serverVersion = info.version,
                    nativeOAuthSupported = info.nativeOAuthSupported,
                    authProviders = info.providers,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: NativeRefreshExpiredException) {
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            consecutiveAuthProviderUnavailable = 0
            tokenStore?.clear(serverOrigin)
            activeTokens = null
            disconnectChat()
            publishSignInRequired()
        } catch (_: HermesAuthenticationRejectedException) {
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            consecutiveAuthProviderUnavailable = 0
            tokenStore?.clear(serverOrigin)
            activeTokens = null
            disconnectChat()
            publishSignInRequired()
        } catch (error: Exception) {
            if (generation != currentGeneration || activeOrigin != serverOrigin) return
            val isAuthProviderUnavailable =
                error is HermesAuthProviderUnavailableException ||
                    error is NativeRefreshTransientException
            if (isAuthProviderUnavailable) {
                // The server's auth provider is unavailable (HTTP 503) — from
                // /api/auth/me (HermesAuthProviderUnavailableException) or the
                // token refresh (NativeRefreshTransientException). The transport
                // is healthy and the token may be valid, so a single 503 is a
                // transient blip we retry silently as a normal disconnect. But a
                // *persistent* 503 is, from the client's side, indistinguishable
                // from a server that will never validate this session's token
                // again (e.g. an upstream-IDP classification bug). Rather than
                // loop "reconnecting" forever with no escape, after
                // MAX_AUTH_503_BEFORE_SIGN_IN consecutive 503s we clear the
                // (possibly poisoned) token and surface a recoverable sign-in.
                consecutiveAuthProviderUnavailable += 1
                if (consecutiveAuthProviderUnavailable >= MAX_AUTH_503_BEFORE_SIGN_IN) {
                    consecutiveAuthProviderUnavailable = 0
                    tokenStore?.clear(serverOrigin)
                    activeTokens = null
                    disconnectChat()
                    publishSignInRequired()
                } else {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        connectionState = ConnectionState.Disconnected,
                        connectionError = "Hermes authentication is temporarily unavailable",
                    )
                }
            } else {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    connectionState = ConnectionState.Disconnected,
                    connectionError = "Could not reach Hermes Serve",
                )
            }
        }
    }

    private fun startProjectTreeLoad(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
        durableSessions: List<SessionSummary>,
        profile: String = "default",
        preserveContent: Boolean = false,
    ) {
        projectLoadJob?.cancel()
        val connector = projectConnector
        if (connector == null) {
            publishProjectStateIfCurrent(
                serverOrigin = serverOrigin,
                originGeneration = originGeneration,
                projectState = ProjectLoadState.Unsupported,
            )
            return
        }

        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return
        if (!preserveContent) {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                projects = emptyList(),
                projectState = ProjectLoadState.Loading,
                activeProjectId = null,
                scopedSessionIds = emptySet(),
                projectSessions = emptyMap(),
            )
        }
        projectLoadJob = viewModelScope.launch {
            try {
                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                val tree = withProjectMetadataSession(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    accessToken = accessToken,
                ) { session ->
                    session.loadProjectTree(profile = profile)
                }

                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                val projects = tree.projects.map { project ->
                    project.copy(
                        previewSessions = project.previewSessions.map { preview ->
                            reconcileProjectSession(project.id, preview, durableSessions)
                        },
                    )
                }
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    projects = projects,
                    projectState = ProjectLoadState.Loaded(
                        projects = projects,
                        activeProjectId = tree.activeProjectId,
                        scopedSessionIds = tree.scopedSessionIds,
                    ),
                    activeProjectId = tree.activeProjectId,
                    scopedSessionIds = tree.scopedSessionIds,
                    projectSessions = emptyMap(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HermesChatMethodNotFoundException) {
                disconnectProjectMetadata()
                publishProjectStateIfCurrent(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    projectState = ProjectLoadState.Unsupported,
                )
            } catch (error: Exception) {
                publishProjectStateIfCurrent(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    projectState = ProjectLoadState.TransientError(
                        error.message?.take(160)?.takeIf(String::isNotBlank)
                            ?: "Could not load project metadata",
                    ),
                )
            }
        }
    }

    private fun isCurrentRestOperation(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ): Boolean {
        val scope = currentConnectionOperationScope() ?: return false
        return scope.origin == serverOrigin &&
            scope.generation == originGeneration &&
            isCurrentRestOperation(scope)
    }

    private fun isCurrentRestOperation(scope: ConnectionOperationScope): Boolean {
        val authenticationState = mutableSnapshots.value.authenticationState
        return scope.relayTargetId == null &&
            operationGuard.isCurrent(scope) &&
            (authenticationState == AuthenticationState.Authenticated ||
                authenticationState == AuthenticationState.NotRequired)
    }
    private fun currentConnectionOperationScope(): ConnectionOperationScope? {
        val snapshot = mutableSnapshots.value
        val authenticated = snapshot.authenticationState == AuthenticationState.Authenticated ||
            snapshot.authenticationState == AuthenticationState.NotRequired
        return connectionCoordinator.currentScope(snapshot.selectedProfile, authenticated)
    }

    private suspend fun <T> withHermesRestOperation(
        scope: ConnectionOperationScope = operationGuard.currentScope()
            ?: throw HermesConnectionException("Hermes Serve is not connected"),
        block: suspend (ServerOrigin, String?) -> T,
    ): T {
        if (scope.relayTargetId != null) throw HermesConnectionException("Direct host operations are unavailable over relay")
        operationGuard.ensureCurrent(scope)
        val accessToken = accessTokenForRequest(scope.origin, scope.generation)
        operationGuard.ensureCurrent(scope)
        if (mutableSnapshots.value.authenticationState == AuthenticationState.Authenticated && accessToken == null) {
            throw HermesConnectionException("Hermes host files require authentication")
        }
        return operationGuard.run(scope) { block(scope.origin, accessToken) }
    }

    suspend fun refreshVoiceCapabilities() = voiceSettingsOwner.refresh()
    suspend fun setVoiceAutoTts(enabled: Boolean): Boolean = voiceSettingsOwner.setAutoTts(enabled)
    suspend fun setElevenLabsVoice(voiceId: String): Boolean = voiceSettingsOwner.setElevenLabsVoice(voiceId)
    suspend fun loadElevenLabsVoices(): List<ElevenLabsVoice> = voiceSettingsOwner.loadVoices()

    /** Transcribe a dictation recording via the server's audited STT chain. */
    suspend fun transcribeDictation(dataUrl: String, mimeType: String?): TranscriptionResult =
        withHermesRestOperation { origin, token ->
            client.transcribeAudio(
                origin,
                token.orEmpty(),
                mutableSnapshots.value.selectedProfile,
                dataUrl,
                mimeType,
            )
        }

    /**
     * Open a fresh-ticket streaming-speech socket to `/api/audio/speak-stream`
     * for the active origin/profile. Each call mints a new single-use ticket;
     * the chat socket is untouched. Null when streaming is unavailable.
     */
    suspend fun openSpeechStream(): SpeechStreamSocket? {
        val connector = speechStreamConnector ?: return null
        if (!voiceCapabilities.value.canStreamSpeech) return null
        var candidate: SpeechStreamSocket? = null
        return try {
            withHermesRestOperation { origin, token ->
                connector.connect(origin, token.orEmpty(), mutableSnapshots.value.selectedProfile)
                    .also { candidate = it }
            }
        } catch (cancelled: CancellationException) {
            // The REST guard can reject the handoff after connect() returns when
            // origin/profile changes during the suspend. The caller never received
            // the socket, so close this unpublished candidate non-cancellably.
            withContext(NonCancellable) {
                runCatching { candidate?.close() }
            }
            throw cancelled
        }
    }

    /** Synthesize [text] to speech via the server's TTS chain (read-aloud REST path). */
    suspend fun synthesizeSpeech(text: String): SpeechAudio =
        withHermesRestOperation { origin, token ->
            client.speakText(
                origin,
                token.orEmpty(),
                mutableSnapshots.value.selectedProfile,
                text,
            )
        }

    /**
     * Opens an unpublished observer candidate. The caller owns it until
     * [adoptProjectMetadataSessionCandidate] either publishes or closes it.
     */
    private suspend fun connectProjectMetadataCandidate(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
    ): HermesChatSession {
        val connector = projectConnector
            ?: throw HermesChatMethodNotFoundException("projects.tree")
        val candidate = connector.connect(serverOrigin, accessToken)
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrentOrigin(serverOrigin, originGeneration)) {
                throw CancellationException("Server origin was replaced")
            }
            return candidate
        } catch (cancelled: CancellationException) {
            closeChatSessionNonCancellably(candidate)
            throw cancelled
        } catch (error: Exception) {
            closeChatSessionNonCancellably(candidate)
            throw error
        }
    }

    /**
     * Publishes a candidate only after authentication and origin identity are
     * current. It closes both stale candidates and displaced owners.
     */
    private suspend fun adoptProjectMetadataSessionCandidate(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
        candidate: HermesChatSession,
    ): HermesChatSession? {
        var sessionToUse: HermesChatSession? = null
        val sessionsToClose = mutableListOf<HermesChatSession>()
        synchronized(projectMetadataLock) {
            if (!isCurrentProjectLoad(serverOrigin, originGeneration)) {
                sessionsToClose += candidate
            } else {
                val current = activeProjectMetadataSession
                if (
                    current != null &&
                    current.origin == serverOrigin &&
                    current.generation == originGeneration &&
                    current.accessToken == accessToken
                ) {
                    sessionToUse = current.session
                    sessionsToClose += candidate
                } else {
                    current?.session?.let(sessionsToClose::add)
                    activeProjectMetadataSession = ProjectMetadataSessionRecord(
                        origin = serverOrigin,
                        generation = originGeneration,
                        accessToken = accessToken,
                        session = candidate,
                    )
                    sessionToUse = candidate
                }
            }
        }
        sessionsToClose.distinct().forEach { closeChatSessionNonCancellably(it) }
        return sessionToUse
    }

    /**
     * Reuses one dedicated observer connection for project metadata. Ownership is
     * scoped to the exact origin, generation, and access token; candidates are
     * published only after those identities are revalidated.
     */
    private suspend fun acquireProjectMetadataSession(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
    ): HermesChatSession {
        synchronized(projectMetadataLock) {
            activeProjectMetadataSession
                ?.takeIf {
                    it.origin == serverOrigin &&
                        it.generation == originGeneration &&
                        it.accessToken == accessToken
                }
                ?.let { return it.session }
        }

        val candidate = connectProjectMetadataCandidate(
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            accessToken = accessToken,
        )
        return adoptProjectMetadataSessionCandidate(
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            accessToken = accessToken,
            candidate = candidate,
        ) ?: throw CancellationException("Server origin was replaced")
    }

    private suspend fun <T> withProjectMetadataSession(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        accessToken: String,
        block: suspend (HermesChatSession) -> T,
    ): T {
        // The OS can silently kill the metadata WebSocket while the app is
        // locked/backgrounded. The cached session then fails its next RPC with a
        // closed-transport error. Self-heal: replace the dead connection and retry
        // the RPC once on a fresh session instead of surfacing a raw transport
        // error. A second failure surfaces — a genuinely unreachable host must not
        // spin reconnect attempts.
        var healedOnce = false
        while (true) {
            val session = acquireProjectMetadataSession(
                serverOrigin = serverOrigin,
                originGeneration = originGeneration,
                accessToken = accessToken,
            )
            try {
                val result = block(session)
                currentCoroutineContext().ensureActive()
                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    throw CancellationException("Server origin was replaced")
                }
                return result
            } catch (error: HermesChatTransportException) {
                invalidateProjectMetadataSession(session)
                currentCoroutineContext().ensureActive()
                if (healedOnce || !isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    throw error
                }
                healedOnce = true
            }
        }
    }

    private suspend fun <T> withProjectMetadataSession(
        block: suspend (HermesChatSession) -> T,
    ): T {
        val serverOrigin = activeOrigin
            ?: throw HermesChatProtocolException("Hermes Serve is not connected")
        val originGeneration = generation
        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) {
            throw HermesChatProtocolException("Hermes project metadata is unavailable")
        }
        val accessToken = accessTokenForRequest(serverOrigin, originGeneration)
            ?: throw HermesChatProtocolException("Hermes project metadata requires authentication")
        return withProjectMetadataSession(
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            accessToken = accessToken,
            block = block,
        )
    }

    private fun detachProjectMetadataSession(): HermesChatSession? =
        synchronized(projectMetadataLock) {
            activeProjectMetadataSession?.session.also {
                activeProjectMetadataSession = null
            }
        }

    private suspend fun invalidateProjectMetadataSession(session: HermesChatSession) {
        val detached = synchronized(projectMetadataLock) {
            if (activeProjectMetadataSession?.session === session) {
                activeProjectMetadataSession = null
                session
            } else {
                null
            }
        }
        closeChatSessionNonCancellably(detached)
    }

    private suspend fun disconnectProjectMetadata() {
        closeChatSessionNonCancellably(detachProjectMetadataSession())
    }

    private fun isCurrentOrigin(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ): Boolean =
        activeOrigin == serverOrigin && generation == originGeneration

    private fun isCurrentProjectLoad(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ): Boolean =
        isCurrentOrigin(serverOrigin, originGeneration) &&
            mutableSnapshots.value.authenticationState == AuthenticationState.Authenticated

    private fun publishProjectStateIfCurrent(
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        projectState: ProjectLoadState,
    ) {
        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return
        mutableSnapshots.value = mutableSnapshots.value.copy(
            projects = emptyList(),
            projectState = projectState,
            activeProjectId = null,
            scopedSessionIds = emptySet(),
            projectSessions = emptyMap(),
        )
    }

    /**
     * Opens one project through the dedicated observer connection. This path may
     * only call `projects.project_sessions`; it never resumes or creates a runtime.
     */
    fun openProject(projectId: ProjectId, profile: String = "default"): Job {
        val requestGeneration = connectionCoordinator.nextProjectSessionGeneration(projectId)
        val serverOrigin = activeOrigin
        val originGeneration = generation
        val connector = projectConnector
        val relayTarget = activeRelayTarget

        if (relayTarget != null) {
            val factory = relaySessionFactory
            if (serverOrigin == null || factory == null ||
                !isCurrentProjectLoad(serverOrigin, originGeneration)
            ) {
                if (serverOrigin != null && factory == null) {
                    publishProjectSessionStateIfCurrent(
                        projectId = projectId,
                        serverOrigin = serverOrigin,
                        originGeneration = originGeneration,
                        requestGeneration = requestGeneration,
                        projectState = ProjectSessionLoadState.Unsupported,
                    )
                }
                return viewModelScope.launch { }
            }
            publishProjectSessionStateIfCurrent(
                projectId = projectId,
                serverOrigin = serverOrigin,
                originGeneration = originGeneration,
                requestGeneration = requestGeneration,
                projectState = ProjectSessionLoadState.Loading,
            )
            return viewModelScope.launch {
                try {
                    val result = withRelayReader(relayTarget, profile) { session ->
                        session.loadProjectSessions(projectId = projectId, profile = profile)
                    }
                    if (!isCurrentProjectSession(
                            projectId,
                            serverOrigin,
                            originGeneration,
                            requestGeneration,
                        ) || activeRelayTarget?.id != relayTarget.id
                    ) return@launch
                    val durableSessions = mutableSnapshots.value.durableSessions
                    val sessions = result.sessions
                        .take(ProjectSummary.MAX_PROJECT_SESSIONS)
                        .map { reconcileProjectSession(projectId, it, durableSessions) }
                    publishProjectSessionStateIfCurrent(
                        projectId = projectId,
                        serverOrigin = serverOrigin,
                        originGeneration = originGeneration,
                        requestGeneration = requestGeneration,
                        projectState = ProjectSessionLoadState.Loaded(sessions),
                    )
                    reconcileProjectSummaryFromDrillIn(
                        fresh = result.project,
                        serverOrigin = serverOrigin,
                        originGeneration = originGeneration,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: HermesChatMethodNotFoundException) {
                    publishProjectSessionStateIfCurrent(
                        projectId = projectId,
                        serverOrigin = serverOrigin,
                        originGeneration = originGeneration,
                        requestGeneration = requestGeneration,
                        projectState = ProjectSessionLoadState.Unsupported,
                    )
                } catch (error: Exception) {
                    publishProjectSessionStateIfCurrent(
                        projectId = projectId,
                        serverOrigin = serverOrigin,
                        originGeneration = originGeneration,
                        requestGeneration = requestGeneration,
                        projectState = ProjectSessionLoadState.TransientError(
                            error.message?.take(160)?.takeIf(String::isNotBlank)
                                ?: "Could not load project sessions",
                        ),
                    )
                }
            }.also { connectionCoordinator.setProjectSessionJob(projectId, it) }
        }

        if (serverOrigin == null || connector == null ||
            !isCurrentProjectLoad(serverOrigin, originGeneration)
        ) {
            if (serverOrigin != null && connector == null) {
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.Unsupported,
                )
            }
            return viewModelScope.launch { }
        }

        publishProjectSessionStateIfCurrent(
            projectId = projectId,
            serverOrigin = serverOrigin,
            originGeneration = originGeneration,
            requestGeneration = requestGeneration,
            projectState = ProjectSessionLoadState.Loading,
        )

        val job = viewModelScope.launch {
            try {
                if (!isCurrentProjectSession(
                        projectId,
                        serverOrigin,
                        originGeneration,
                        requestGeneration,
                    )
                ) return@launch
                val result = withProjectMetadataSession { metadataSession ->
                    metadataSession.loadProjectSessions(
                        projectId = projectId,
                        profile = profile,
                    )
                }

                if (!isCurrentProjectSession(
                        projectId,
                        serverOrigin,
                        originGeneration,
                        requestGeneration,
                    )
                ) return@launch
                val durableSessions = mutableSnapshots.value.durableSessions
                val sessions = result.sessions
                    .take(ProjectSummary.MAX_PROJECT_SESSIONS)
                    .map { session ->
                        reconcileProjectSession(projectId, session, durableSessions)
                    }
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.Loaded(sessions),
                )
                reconcileProjectSummaryFromDrillIn(
                    fresh = result.project,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentProjectSession(
                        projectId,
                        serverOrigin,
                        originGeneration,
                        requestGeneration,
                    )
                ) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: HermesChatMethodNotFoundException) {
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.Unsupported,
                )
            } catch (error: Exception) {
                publishProjectSessionStateIfCurrent(
                    projectId = projectId,
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    requestGeneration = requestGeneration,
                    projectState = ProjectSessionLoadState.TransientError(
                        error.message?.take(160)?.takeIf(String::isNotBlank)
                            ?: "Could not load project sessions",
                    ),
                )
            } finally {
                connectionCoordinator.finishProjectSession(projectId, requestGeneration)
            }
        }
        connectionCoordinator.setProjectSessionJob(projectId, job)
        return job
    }

    /** Alias retained for callers that name the operation after its RPC. */
    fun loadProjectSessions(projectId: ProjectId, profile: String = "default"): Job =
        openProject(projectId, profile)

    /**
     * Refreshes the public profile-scoped status at most once per 60 seconds unless
     * explicitly forced by an authenticated Home refresh. Old origin/profile results
     * are discarded, and a transient failure retains the last good snapshot. Every
     * completed fetch schedules the next 60-second tick so an open operational
     * overview keeps refreshing without needing another caller.
     */
    fun refreshOperationalStatus(
        profile: String = mutableSnapshots.value.selectedProfile,
        force: Boolean = false,
    ): Job {
        val origin = activeOrigin
        val expectedGeneration = generation
        val boundedProfile = profile.trim().take(64).ifEmpty { "default" }
        val operationScope = currentConnectionOperationScope()?.takeIf {
            it.relayTargetId == null && it.origin == origin && it.profile == boundedProfile
        }
        if (origin == null || operationScope == null ||
            !isCurrentOperationalScope(origin, expectedGeneration, boundedProfile)
        ) {
            return viewModelScope.launch { }
        }
        val now = nowEpochSeconds()
        val last = lastOperationalStatusFetch
        if (
            !force && last != null &&
            last.origin == origin &&
            last.generation == expectedGeneration &&
            last.profile == boundedProfile &&
            now - last.attemptedAtEpochSeconds < 60L
        ) {
            // Rate-limited: keep an in-flight fetch; a completed fetch already
            // scheduled the next 60-second tick, so no further action is needed.
            return operationalStatusJob?.takeIf(Job::isActive) ?: viewModelScope.launch { }
        }
        if (force) operationalStatusJob?.cancel()
        val fetch = OperationalStatusFetch(origin, expectedGeneration, boundedProfile, now)
        lastOperationalStatusFetch = fetch
        val previous = mutableSnapshots.value.operationalStatusState.lastGoodOrNull()
            ?.takeIf { it.origin == origin.value && it.profile == boundedProfile }
        val job = viewModelScope.launch {
            if (!isCurrentOperationalScope(origin, expectedGeneration, boundedProfile) ||
                !operationGuard.isCurrent(operationScope)
            ) return@launch
            mutableSnapshots.value = mutableSnapshots.value.copy(
                operationalStatusState = OperationalStatusState.Loading(previous),
            )
            try {
                val status = operationGuard.run(operationScope) {
                    client.loadOperationalStatus(operationScope.origin, operationScope.profile)
                }
                currentCoroutineContext().ensureActive()
                if (isCurrentOperationalScope(origin, expectedGeneration, boundedProfile)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        operationalStatusState = OperationalStatusState.Ready(
                            OperationalSnapshot(
                                origin = origin.value,
                                profile = boundedProfile,
                                status = status,
                                fetchedAtEpochSeconds = nowEpochSeconds(),
                            ),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentOperationalScope(origin, expectedGeneration, boundedProfile)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        operationalStatusState = OperationalStatusState.TransientError(previous),
                    )
                }
            }
            // Continue the 60-second cadence while the scope is still current.
            if (isCurrentOperationalScope(origin, expectedGeneration, boundedProfile)) {
                scheduleOperationalStatusTick(origin, expectedGeneration, boundedProfile)
            }
        }
        operationalStatusJob = job
        return job
    }

    /** One-shot 60-second timer that resumes the status cadence; dies with the scope. */
    private fun scheduleOperationalStatusTick(
        origin: ServerOrigin,
        expectedGeneration: Long,
        boundedProfile: String,
    ): Job = viewModelScope.launch {
        delay(OPERATIONAL_STATUS_POLL_INTERVAL_MILLIS)
        if (isCurrentOperationalScope(origin, expectedGeneration, boundedProfile)) {
            refreshOperationalStatus(profile = boundedProfile, force = false)
        }
    }

    private fun isCurrentOperationalScope(
        origin: ServerOrigin,
        expectedGeneration: Long,
        profile: String,
    ): Boolean = activeOrigin == origin &&
        generation == expectedGeneration &&
        mutableSnapshots.value.selectedProfile == profile

    /**
     * Manually refreshes the Home snapshot: re-reads the durable REST sessions
     * and reloads the project tree through the official contracts. The tree
     * reload preserves the existing list while it is in flight so a pull-to-
     * refresh never blanks the screen; [homeRefreshing] reports the window.
     */
    fun refreshHomeData(): Job {
        activeRelayTarget?.let { return refreshRelaySessions(it) }
        val serverOrigin = activeOrigin
        val originGeneration = generation
        if (serverOrigin == null || !isCurrentProjectLoad(serverOrigin, originGeneration)) {
            return viewModelScope.launch { }
        }
        refreshHomeJob?.cancel()
        val job = viewModelScope.launch {
            _homeRefreshing.value = true
            try {
                val accessToken = accessTokenForRequest(serverOrigin, originGeneration)
                    ?: return@launch
                currentCoroutineContext().ensureActive()
                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                val profile = mutableSnapshots.value.selectedProfile.trim().take(64).ifEmpty { "default" }
                val durableSessions = client.loadSessionsForProfile(
                    serverOrigin,
                    accessToken,
                    profile,
                    archivedOnly = activeSessionArchivedFilter,
                ).also {
                    lastDurableSessionsFetchKey = DurableSessionsFetchKey(
                        origin = serverOrigin.value,
                        generation = originGeneration,
                        profile = profile,
                        archivedOnly = activeSessionArchivedFilter,
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return@launch
                val mergedHomeSessions = mergeServerSessionsPreservingDrafts(
                    serverSessions = durableSessions,
                    currentSessions = mutableSnapshots.value.durableSessions,
                    pendingDrafts = pendingDraftSessions,
                )
                if (mergedHomeSessions != mutableSnapshots.value.durableSessions) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        durableSessions = mergedHomeSessions,
                    )
                }
                startProjectTreeLoad(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    accessToken = accessToken,
                    durableSessions = durableSessions,
                    preserveContent = true,
                )
                projectLoadJob?.join()
                loadManagementSettings(
                    profile = mutableSnapshots.value.selectedProfile,
                    refreshStatus = false,
                ).join()
                refreshOperationalStatus(
                    profile = mutableSnapshots.value.selectedProfile,
                    force = true,
                ).join()
                val delegationStatus = try {
                    withProjectMetadataSession(HermesChatSession::loadDelegationStatus)
                } catch (_: HermesChatMethodNotFoundException) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (delegationStatus != null && isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        delegationStatus = delegationStatus,
                        delegationStatusAvailable = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: HermesAuthenticationRejectedException) {
                if (isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: Exception) {
                // Transient refresh failure: keep the current snapshot intact so
                // a later pull can retry. Authentication rejections are handled
                // above and never degrade into this branch.
            } finally {
                if (generation == originGeneration) {
                    _homeRefreshing.value = false
                }
            }
        }
        refreshHomeJob = job
        return job
    }

    /**
     * Silent one-shot refresh of working-state presence: reads the read-only
     * `session.active_list` snapshot and publishes the set of durable session
     * IDs currently running a turn. The Home screen calls this on a short
     * timer while visible — never a full-screen reload. Fails closed: an
     * unsupported or failed RPC leaves the previous value untouched rather
     * than showing stale spinners, and an empty snapshot clears them.
     */
    fun refreshActiveWorkingSessions(): Job {
        activeRelayTarget?.let { target ->
            val expectedGeneration = generation
            return viewModelScope.launch {
                try {
                    withRelayReader(target, mutableSnapshots.value.selectedProfile) { session ->
                        session.loadActiveSessionPresence()
                    }.let { working ->
                        if (generation == expectedGeneration && activeRelayTarget?.id == target.id) {
                            mutableSnapshots.value = mutableSnapshots.value.copy(
                                activeWorkingSessionIds = working,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Fail closed to an empty working set rather than
                    // preserving stale spinners.
                    if (generation == expectedGeneration && activeRelayTarget?.id == target.id) {
                        mutableSnapshots.value = mutableSnapshots.value.copy(
                            activeWorkingSessionIds = emptySet(),
                        )
                    }
                }
            }
        }
        val serverOrigin = activeOrigin
        val originGeneration = generation
        if (serverOrigin == null || !isCurrentProjectLoad(serverOrigin, originGeneration)) {
            return viewModelScope.launch { }
        }
        return viewModelScope.launch {
            try {
                val working = withProjectMetadataSession(
                    serverOrigin = serverOrigin,
                    originGeneration = originGeneration,
                    accessToken = accessTokenForRequest(serverOrigin, originGeneration)
                        ?: return@launch,
                ) { session -> session.loadActiveSessionPresence() }
                if (isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        activeWorkingSessionIds = working,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HermesChatMethodNotFoundException) {
                // Older server without the snapshot: fail closed to empty.
                if (isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        activeWorkingSessionIds = emptySet(),
                    )
                }
            } catch (_: Exception) {
                // Fail closed to an empty working set rather than
                // preserving stale spinners.
                if (isCurrentProjectLoad(serverOrigin, originGeneration)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        activeWorkingSessionIds = emptySet(),
                    )
                }
            }
        }
    }

    fun loadManagementSettings(
        profile: String = mutableSnapshots.value.selectedProfile,
        refreshStatus: Boolean = true,
    ): Job {
        activeRelayTarget?.let { return connectRelay(it, profile) }
        managementJob?.cancel()
        val origin = activeOrigin ?: return viewModelScope.launch { }
        val boundedProfile = profile.trim().take(64).ifEmpty { "default" }
        setSessionFilterScope(origin, boundedProfile)
        val expectedGeneration = generation
        val previousProfile = mutableSnapshots.value.selectedProfile
        if (previousProfile != boundedProfile) {
            connectionCoordinator.advanceProfileGeneration()
            // The Home list clears its filter when the scope changes; reloads for
            // the new profile must start from the unfiltered (exclude) list.
            activeSessionArchivedFilter = false
            lastDurableSessionsFetchKey = null
            viewModelScope.launch {
                cacheRepository?.clearTranscriptTails(CacheScope(origin, previousProfile))
            }
        }
        val requestGeneration = ++managementRequestGeneration
        val job = viewModelScope.launch {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                selectedProfile = boundedProfile,
                defaultModelOptions = null,
                currentModelInfo = null,
                profileReasoningEffort = null,
                profileReasoningDefault = null,
                profileModelReasoningOverrides = emptyMap(),
                managementLoading = true,
                managementError = null,
            )
            try {
                val token = accessTokenForRequest(origin, expectedGeneration)
                    ?: throw HermesConnectionException("Hermes profile settings require authentication")
                val profiles = client.loadProfiles(origin, token)
                if (!isCurrentManagementRequest(origin, expectedGeneration, requestGeneration)) {
                    return@launch
                }
                val selected = boundedProfile.takeIf(profiles::contains) ?: profiles.firstOrNull() ?: "default"
                if (selected != boundedProfile) setSessionFilterScope(origin, selected)
                val options = client.loadDefaultModelOptions(origin, token, selected)
                currentCoroutineContext().ensureActive()
                if (options.profile != null && options.profile != selected) {
                    throw HermesConnectionException("Hermes model options returned the wrong profile")
                }
                val currentInfo = try {
                    client.loadCurrentModelInfo(origin, token, selected)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                currentCoroutineContext().ensureActive()
                if (isCurrentManagementRequest(origin, expectedGeneration, requestGeneration)) {
                    val profileReasoningEffort = options.current?.let { current ->
                        try {
                            client.loadProfileReasoningEffort(
                                serverOrigin = origin,
                                accessToken = token,
                                profile = selected,
                                provider = current.provider,
                                model = current.model,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val profileReasoningDefault = try {
                        client.loadProfileReasoningDefault(
                            serverOrigin = origin,
                            accessToken = token,
                            profile = selected,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val profileModelReasoningOverrides = try {
                        client.loadProfileReasoningOverrides(
                            serverOrigin = origin,
                            accessToken = token,
                            profile = selected,
                            options = options,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    // The durable rows must belong to the profile that is about to
                    // become selected; validating a bulk-delete selection against
                    // another profile's rows would be a destructive scope mismatch.
                    // A transient failure keeps the previous rows visible instead
                    // of failing the whole management load.
                    val profileSessions = try {
                        client.loadSessionsForProfile(
                            serverOrigin = origin,
                            accessToken = token,
                            profile = selected,
                            archivedOnly = activeSessionArchivedFilter,
                        ).also {
                            lastDurableSessionsFetchKey = DurableSessionsFetchKey(
                                origin = origin.value,
                                generation = expectedGeneration,
                                profile = selected,
                                archivedOnly = activeSessionArchivedFilter,
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentManagementRequest(origin, expectedGeneration, requestGeneration)) {
                        return@launch
                    }
                    val currentSnapshot = mutableSnapshots.value
                    val effectiveModel = currentInfo
                        ?.takeIf { it.profile == selected && it.model != null && it.provider != null }
                        ?.let { ModelSelection(checkNotNull(it.provider), checkNotNull(it.model)) }
                        ?: options.current
                    val effectiveCapabilities = effectiveModel?.let { selection ->
                        resolveModelCapabilities(currentInfo, options, selection)
                    }
                    val updatedChats = currentSnapshot.chatSessions.mapValues { (_, chat) ->
                        if (
                            effectiveModel != null &&
                            chat.provider == effectiveModel.provider &&
                            modelIdentifiersMatch(chat.model, effectiveModel.model)
                        ) {
                            chat.copy(
                                modelCapabilities = effectiveCapabilities,
                                reasoningEffort = chat.reasoningEffort ?: profileReasoningEffort,
                            )
                        } else {
                            chat
                        }
                    }
                    mutableSnapshots.value = currentSnapshot.copy(
                        profiles = profiles,
                        selectedProfile = selected,
                        durableSessions = profileSessions?.let { sessions ->
                            mergeServerSessionsPreservingDrafts(
                                serverSessions = sessions,
                                currentSessions = currentSnapshot.durableSessions,
                                pendingDrafts = pendingDraftSessions,
                            )
                        } ?: currentSnapshot.durableSessions,
                        defaultModelOptions = options,
                        currentModelInfo = currentInfo?.takeIf { it.profile == selected },
                        profileReasoningEffort = profileReasoningEffort,
                        profileReasoningDefault = profileReasoningDefault,
                        profileModelReasoningOverrides = profileModelReasoningOverrides,
                        chatSessions = updatedChats,
                        managementLoading = false,
                    )
                    if (refreshStatus) {
                        refreshOperationalStatus(profile = selected, force = false)
                    }
                    loadCachedMetadata(origin, selected, expectedGeneration, profileGeneration)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrentManagementRequest(origin, expectedGeneration, requestGeneration)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        defaultModelOptions = null,
                        managementLoading = false,
                        managementError = "Could not load profile settings",
                    )
                }
            }
        }
        managementJob = job
        return job
    }

    /**
     * Re-fetches durable rows for the currently selected profile and the active
     * Home filter. An `is:archived` filter maps to the official `archived=only`
     * query (the default `archived=exclude` listing would hide every archived
     * row and make the filter look empty); clearing the filter re-fetches with
     * `archived=exclude` so archived rows do not leak into the normal list.
     * Identical fetches (same origin, generation, profile, mode) are skipped,
     * and stale results are never published after a scope change.
     */
    fun refreshDurableSessions(archivedOnly: Boolean): Job {
        activeRelayTarget?.let { return refreshRelaySessions(it) }
        val origin = activeOrigin ?: return viewModelScope.launch { }
        val expectedGeneration = generation
        val profile = mutableSnapshots.value.selectedProfile.trim().take(64).ifEmpty { "default" }
        val key = DurableSessionsFetchKey(origin.value, expectedGeneration, profile, archivedOnly)
        if (lastDurableSessionsFetchKey == key) return viewModelScope.launch { }
        return viewModelScope.launch {
            val accessToken = accessTokenForRequest(origin, expectedGeneration)
                ?: return@launch
            val sessions = try {
                client.loadSessionsForProfile(origin, accessToken, profile, archivedOnly)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A filter refresh is auxiliary; keep the previous rows visible.
                return@launch
            }
            currentCoroutineContext().ensureActive()
            if (
                activeOrigin == origin &&
                generation == expectedGeneration &&
                mutableSnapshots.value.selectedProfile == profile
            ) {
                lastDurableSessionsFetchKey = key
                activeSessionArchivedFilter = archivedOnly
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    durableSessions = mergeServerSessionsPreservingDrafts(
                        serverSessions = sessions,
                        currentSessions = mutableSnapshots.value.durableSessions,
                        pendingDrafts = pendingDraftSessions,
                    ),
                )
            }
        }
    }

    fun loadRecentSessions(): Job {
        val snapshot = mutableSnapshots.value
        val origin = activeOrigin ?: return viewModelScope.launch { }
        val profile = snapshot.selectedProfile.trim().take(64).ifEmpty { "default" }
        val scopeKey = "${origin.value}\u0000$profile"
        if (recentSessionsScopeKey != scopeKey) {
            recentSessionsScopeKey = scopeKey
            recentSessionsJob?.cancel()
            mutableSnapshots.value = snapshot.copy(recentSessions = RecentSessionsState())
        }
        if (
            mutableSnapshots.value.recentSessions.sessions.isNotEmpty() ||
            mutableSnapshots.value.recentSessions.isLoading
        ) {
            return viewModelScope.launch { }
        }
        return loadRecentSessionsPage(loadMore = false)
    }

    fun loadMoreRecentSessions(): Job {
        val state = mutableSnapshots.value.recentSessions
        if (!state.hasMore || state.isLoading || state.isLoadingMore) {
            return viewModelScope.launch { }
        }
        return loadRecentSessionsPage(loadMore = true)
    }

    private fun loadRecentSessionsPage(loadMore: Boolean): Job {
        val origin = activeOrigin ?: return viewModelScope.launch { }
        val expectedGeneration = generation
        val relayTarget = activeRelayTarget
        val profile = mutableSnapshots.value.selectedProfile.trim().take(64).ifEmpty { "default" }
        val previous = mutableSnapshots.value.recentSessions
        val offset = if (loadMore) previous.nextOffset else 0
        val job = viewModelScope.launch {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                recentSessions = previous.copy(
                    isLoading = !loadMore,
                    isLoadingMore = loadMore,
                    error = null,
                ),
            )
            try {
                val page = if (relayTarget != null) {
                    loadRelaySessionPage(
                        target = relayTarget,
                        profile = profile,
                        limit = RECENT_SESSIONS_PAGE_SIZE,
                        offset = offset,
                    )
                } else {
                    val token = accessTokenForRequest(origin, expectedGeneration)
                    if (
                        token == null &&
                        mutableSnapshots.value.authenticationState != AuthenticationState.NotRequired
                    ) {
                        if (activeOrigin == origin && generation == expectedGeneration) {
                            mutableSnapshots.value = mutableSnapshots.value.copy(
                                recentSessions = mutableSnapshots.value.recentSessions.copy(
                                    isLoading = false,
                                    isLoadingMore = false,
                                    error = "Sign in required",
                                ),
                            )
                        }
                        return@launch
                    }
                    client.loadSessionsPageForProfile(
                        serverOrigin = origin,
                        accessToken = token,
                        profile = profile,
                        limit = RECENT_SESSIONS_PAGE_SIZE,
                        offset = offset,
                        archivedOnly = false,
                    )
                }
                currentCoroutineContext().ensureActive()
                val current = mutableSnapshots.value.recentSessions
                val combined = (if (loadMore) current.sessions else emptyList())
                    .plus(page.sessions)
                    .distinctBy(SessionSummary::id)
                val nextOffset = page.offset + page.sessions.size
                val total = page.total
                val hasMore = if (total != null) {
                    nextOffset < total
                } else {
                    page.sessions.size >= page.limit
                }
                if (
                    activeOrigin == origin &&
                    generation == expectedGeneration &&
                    mutableSnapshots.value.selectedProfile == profile
                ) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        recentSessions = RecentSessionsState(
                            sessions = combined,
                            total = page.total,
                            nextOffset = nextOffset,
                            hasMore = hasMore,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (
                    activeOrigin == origin &&
                    generation == expectedGeneration &&
                    mutableSnapshots.value.selectedProfile == profile
                ) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        recentSessions = mutableSnapshots.value.recentSessions.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = "Could not load recent sessions",
                        ),
                    )
                }
            }
        }
        recentSessionsJob = job
        return job
    }

    private suspend fun loadRelaySessionPage(
        target: RelayPairedTarget,
        profile: String,
        limit: Int,
        offset: Int,
    ): SessionPage = withRelayReader(target, profile) { session ->
            val result = session.relayRequest(
                "relay.sessions.list",
                buildJsonObject {
                    put("profile", profile)
                    put("limit", limit)
                    put("offset", offset)
                },
            )
            SessionPage(
                sessions = parseRelaySessionRows(result),
                total = (result["total"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(0),
                limit = limit,
                offset = offset,
            )
    }

    private fun isCurrentManagementRequest(
        origin: ServerOrigin,
        expectedGeneration: Long,
        requestGeneration: Long,
    ): Boolean = activeOrigin == origin &&
        generation == expectedGeneration &&
        managementRequestGeneration == requestGeneration

    suspend fun setProfileDefaultModel(
        selection: ModelSelection,
        confirmExpensiveModel: Boolean = false,
    ): ModelSwitchResult {
        val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
        val expectedGeneration = generation
        val profile = mutableSnapshots.value.selectedProfile
        val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
        val result = client.setDefaultModel(
            origin,
            token,
            profile,
            selection,
            confirmExpensiveModel,
        )
        currentCoroutineContext().ensureActive()
        if (!isCurrentOrigin(origin, expectedGeneration) || mutableSnapshots.value.selectedProfile != profile) {
            return ModelSwitchResult(accepted = false)
        }
        if (result.accepted) loadManagementSettings(profile).join()
        return result
    }

    suspend fun setProfileReasoningEffort(effort: String): Result<Unit> {
        return try {
            val canonicalEffort = canonicalReasoningEffort(effort)
                ?: throw HermesConnectionException("Reasoning effort is invalid")
            val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
            val expectedGeneration = generation
            val profile = mutableSnapshots.value.selectedProfile
            val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
            client.setProfileReasoningEffort(origin, token, profile, canonicalEffort)
            currentCoroutineContext().ensureActive()
            check(isCurrentOrigin(origin, expectedGeneration) && mutableSnapshots.value.selectedProfile == profile) {
                "Profile settings changed while saving reasoning default"
            }
            val current = mutableSnapshots.value
            val currentSelection = current.defaultModelOptions?.current
            val effectiveCurrentEffort = currentSelection
                ?.let { current.profileModelReasoningOverrides[it] }
                ?: canonicalEffort
            mutableSnapshots.value = current.copy(
                profileReasoningDefault = canonicalEffort,
                profileReasoningEffort = effectiveCurrentEffort,
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun setProfileModelReasoningOverride(
        selection: ModelSelection,
        effort: String,
    ): Result<Unit> {
        return try {
            val canonicalEffort = canonicalReasoningEffort(effort)
                ?: throw HermesConnectionException("Reasoning effort is invalid")
            val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
            val expectedGeneration = generation
            val profile = mutableSnapshots.value.selectedProfile
            val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
            client.setProfileModelReasoningOverride(origin, token, profile, selection, canonicalEffort)
            currentCoroutineContext().ensureActive()
            check(isCurrentOrigin(origin, expectedGeneration) && mutableSnapshots.value.selectedProfile == profile) {
                "Profile settings changed while saving reasoning override"
            }
            val current = mutableSnapshots.value
            val updatedOverrides = current.profileModelReasoningOverrides + (selection to canonicalEffort)
            mutableSnapshots.value = current.copy(
                profileModelReasoningOverrides = updatedOverrides,
                // Keep the global summary in sync when the override targets the
                // currently-selected default model, so the card reflects it.
                profileReasoningEffort = if (current.defaultModelOptions?.current == selection) {
                    canonicalEffort
                } else {
                    current.profileReasoningEffort
                },
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun logout() {
        val origin = activeOrigin ?: return
        val expectedGeneration = generation
        cacheRepository?.clearTranscriptTailsForOrigin(origin)
        tokenStore?.clear(origin)
        currentCoroutineContext().ensureActive()
        if (generation != expectedGeneration || activeOrigin != origin) return
        activeTokens = null
        disconnectChat()
        publishSignInRequired()
    }

    fun searchTranscripts(query: String): Job {
        searchJob?.cancel()
        val origin = activeOrigin ?: return viewModelScope.launch { }
        val expectedGeneration = generation
        val bounded = query.trim().take(256)
        if (bounded.isEmpty()) {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                searchQuery = "",
                transcriptSearchResults = emptyList(),
                searchLoading = false,
                searchError = null,
            )
            return viewModelScope.launch { }
        }
        return viewModelScope.launch {
            mutableSnapshots.value = mutableSnapshots.value.copy(
                searchQuery = bounded,
                searchLoading = true,
                searchError = null,
            )
            try {
                val token = accessTokenForRequest(origin, expectedGeneration) ?: return@launch
                val results = client.searchSessions(
                    origin,
                    token,
                    bounded,
                    mutableSnapshots.value.selectedProfile,
                )
                if (generation == expectedGeneration && activeOrigin == origin) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        transcriptSearchResults = results,
                        searchLoading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == expectedGeneration) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        searchLoading = false,
                        searchError = "Transcript search unavailable",
                    )
                }
            }
        }.also { searchJob = it }
    }

    suspend fun renameSession(sessionId: DurableSessionId, title: String) {
        updateSession(sessionId, title = title.trim().take(512))
    }

    suspend fun setSessionPinned(sessionId: DurableSessionId, pinned: Boolean) {
        updateSession(sessionId, pinned = pinned)
    }

    suspend fun setSessionArchived(sessionId: DurableSessionId, archived: Boolean) {
        updateSession(sessionId, archived = archived)
    }

    suspend fun deleteSession(sessionId: DurableSessionId) {
        check(mutableSnapshots.value.chatSessions[sessionId]?.isSending != true) {
            "Stop the active turn before deleting this session"
        }
        val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
        val expectedGeneration = generation
        val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
        client.deleteSession(origin, token, sessionId, mutableSnapshots.value.selectedProfile)
        cacheRepository?.deleteSession(
            CacheScope(origin, mutableSnapshots.value.selectedProfile),
            sessionId,
        )
        detachFailedRuntime(sessionId)
        mutableSnapshots.value = mutableSnapshots.value.removeSession(sessionId)
    }

    suspend fun bulkDeleteSessions(sessionIds: Collection<DurableSessionId>): BulkDeleteResult {
        val snapshot = mutableSnapshots.value
        val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
        val expectedGeneration = generation
        val selected = sessionIds.distinct()
        val controllerRuntimeIds = snapshot.activeRuntimes
            .filter { it.access == RuntimeAccess.Controller }
            .mapNotNull { it.durableSessionId }
            .toSet()
        val activeTurns = snapshot.chatSessions
            .filterValues { it.isSending }
            .keys + activeTurnIds
        val decision = evaluateBulkDeleteSelection(
            selectedIds = selected,
            sessions = snapshot.durableSessions,
            controllerRuntimeSessionIds = controllerRuntimeIds,
            activeTurnSessionIds = activeTurns,
        )
        check(decision.canDelete) { bulkDeletePolicyError(decision) }
        val profile = snapshot.selectedProfile
        val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
        val result = try {
            client.bulkDeleteSessions(origin, token, decision.selectedSessionIds, profile)
        } catch (unsupported: HermesSessionBulkDeleteUnsupportedException) {
            if (generation == expectedGeneration && activeOrigin == origin &&
                mutableSnapshots.value.selectedProfile == profile
            ) {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    bulkDeleteCapability = SessionBulkDeleteCapability.Unsupported,
                )
            }
            throw unsupported
        }
        currentCoroutineContext().ensureActive()
        check(generation == expectedGeneration && activeOrigin == origin &&
            mutableSnapshots.value.selectedProfile == profile
        ) { "Server scope changed while deleting sessions" }
        check(result.ok) { "Hermes did not confirm bulk session deletion" }
        mutableSnapshots.value = mutableSnapshots.value.copy(
            bulkDeleteCapability = SessionBulkDeleteCapability.Supported,
        )
        reconcileAfterBulkDelete(origin, expectedGeneration, token, profile)
        return result
    }

    private suspend fun reconcileAfterBulkDelete(
        origin: ServerOrigin,
        expectedGeneration: Long,
        accessToken: String,
        profile: String,
    ) {
        val durableSessions = client.loadSessionsForProfile(origin, accessToken, profile)
        currentCoroutineContext().ensureActive()
        check(generation == expectedGeneration && activeOrigin == origin &&
            mutableSnapshots.value.selectedProfile == profile
        ) { "Server scope changed while refreshing sessions" }
        mutableSnapshots.value = mutableSnapshots.value.copy(
            durableSessions = mergeServerSessionsPreservingDrafts(
                serverSessions = durableSessions,
                currentSessions = mutableSnapshots.value.durableSessions,
                pendingDrafts = pendingDraftSessions,
            ),
        )
        startProjectTreeLoad(
            serverOrigin = origin,
            originGeneration = expectedGeneration,
            accessToken = accessToken,
            durableSessions = durableSessions,
            profile = profile,
            preserveContent = true,
        )
        projectLoadJob?.join()
        currentCoroutineContext().ensureActive()
        check(generation == expectedGeneration && activeOrigin == origin &&
            mutableSnapshots.value.selectedProfile == profile
        ) { "Server scope changed while refreshing sessions" }
    }

    private suspend fun updateSession(
        sessionId: DurableSessionId,
        title: String? = null,
        archived: Boolean? = null,
        pinned: Boolean? = null,
    ) {
        val origin = checkNotNull(activeOrigin) { "No Hermes server configured" }
        val expectedGeneration = generation
        val token = checkNotNull(accessTokenForRequest(origin, expectedGeneration)) { "Sign in required" }
        client.updateSession(
            origin,
            token,
            sessionId,
            mutableSnapshots.value.selectedProfile,
            title,
            archived,
            pinned,
        )
        mutableSnapshots.value = mutableSnapshots.value.mapSession(sessionId) { current ->
            current.copy(
                title = title ?: current.title,
                archived = archived ?: current.archived,
                pinned = pinned ?: current.pinned,
            )
        }
    }

    private fun isCurrentProjectSession(
        projectId: ProjectId,
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        requestGeneration: Long,
    ): Boolean =
        isCurrentProjectLoad(serverOrigin, originGeneration) &&
            connectionCoordinator.projectSessionGeneration(projectId) == requestGeneration

    private fun publishProjectSessionStateIfCurrent(
        projectId: ProjectId,
        serverOrigin: ServerOrigin,
        originGeneration: Long,
        requestGeneration: Long,
        projectState: ProjectSessionLoadState,
    ) {
        if (!isCurrentProjectSession(
                projectId,
                serverOrigin,
                originGeneration,
                requestGeneration,
            )
        ) return
        val snapshot = mutableSnapshots.value
        val projectSessions = if (projectState is ProjectSessionLoadState.Loaded) {
            snapshot.projectSessions + (projectId to projectState.sessions)
        } else {
            snapshot.projectSessions
        }
        mutableSnapshots.value = snapshot.copy(
            projectSessions = projectSessions,
            projectSessionStates = snapshot.projectSessionStates + (projectId to projectState),
        )
    }

    /**
     * Adopts the drill-in response's authoritative summary into the shared
     * projects list so a project's header count, label, and path always match
     * the session rows rendered from that same response. Tree preview sessions
     * are preserved; only identity-free fields are replaced.
     */
    private fun reconcileProjectSummaryFromDrillIn(
        fresh: ProjectSummary,
        serverOrigin: ServerOrigin,
        originGeneration: Long,
    ) {
        if (!isCurrentProjectLoad(serverOrigin, originGeneration)) return
        val snapshot = mutableSnapshots.value
        val projects = snapshot.projects.map { existing ->
            if (existing.id == fresh.id) {
                existing.copy(
                    label = fresh.label,
                    primaryPath = fresh.primaryPath,
                    sessionCount = fresh.sessionCount,
                )
            } else {
                existing
            }
        }
        if (projects != snapshot.projects) {
            mutableSnapshots.value = snapshot.copy(projects = projects)
        }
    }

    fun signInWithPassword(username: String, password: String): Job {
        signInJob?.cancel()
        val job = viewModelScope.launch {
            val serverOrigin = activeOrigin ?: return@launch
            val currentGeneration = generation
            val login = passwordLogin ?: return@launch
            val beforeSignIn = mutableSnapshots.value
            val provider = beforeSignIn.authProviders.firstOrNull { it.supportsPassword }
                ?: return@launch
            if (beforeSignIn.authenticationState != AuthenticationState.SignInRequired) return@launch
            mutableSnapshots.value = beforeSignIn.copy(
                authenticationState = AuthenticationState.SigningIn,
                connectionError = null,
            )
            try {
                val tokens = login.signIn(serverOrigin, provider.name, username, password)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                val authenticated = client.authenticate(serverOrigin, tokens.accessToken)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                tokenStore?.save(serverOrigin, tokens)
                activeTokens = ActiveTokenRecord(serverOrigin, currentGeneration, tokens)
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    authenticationState = AuthenticationState.Authenticated,
                    connectionError = null,
                    durableSessions = authenticated.sessions,
                )
                startProjectTreeLoad(
                    serverOrigin = serverOrigin,
                    originGeneration = currentGeneration,
                    accessToken = tokens.accessToken,
                    durableSessions = authenticated.sessions,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    authenticationState = AuthenticationState.SignInRequired,
                    connectionError = (error as? HermesConnectionException)?.message
                        ?: "Hermes sign-in failed",
                )
            }
        }
        signInJob = job
        return job
    }

    fun signIn(openBrowser: suspend (String) -> Unit): Job {
        signInJob?.cancel()
        val job = viewModelScope.launch {
            val serverOrigin = activeOrigin ?: return@launch
            val currentGeneration = generation
            val login = nativeLogin ?: return@launch
            val beforeSignIn = mutableSnapshots.value
            if (
                beforeSignIn.authenticationState != AuthenticationState.SignInRequired ||
                !beforeSignIn.nativeOAuthSupported ||
                beforeSignIn.authProviders.none { it.name == "nous" }
            ) return@launch

            mutableSnapshots.value = beforeSignIn.copy(
                authenticationState = AuthenticationState.SigningIn,
                connectionError = null,
            )
            try {
                val tokens = login.signIn(serverOrigin, "nous", openBrowser)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                val authenticated = client.authenticate(serverOrigin, tokens.accessToken)
                currentCoroutineContext().ensureActive()
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                tokenStore?.save(serverOrigin, tokens)
                activeTokens = ActiveTokenRecord(serverOrigin, currentGeneration, tokens)
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    authenticationState = AuthenticationState.Authenticated,
                    connectionError = null,
                    durableSessions = authenticated.sessions,
                )
                startProjectTreeLoad(
                    serverOrigin = serverOrigin,
                    originGeneration = currentGeneration,
                    accessToken = tokens.accessToken,
                    durableSessions = authenticated.sessions,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != currentGeneration || activeOrigin != serverOrigin) return@launch
                val safeError = (error as? HermesConnectionException)
                    ?.message
                    ?.takeIf(String::isNotBlank)
                    ?: "Hermes sign-in failed (${error.javaClass.simpleName})"
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    authenticationState = AuthenticationState.SignInRequired,
                    connectionError = safeError,
                )
            }
        }
        signInJob = job
        return job
    }

    private val relayFoldersClient = RelayFoldersClient()

    suspend fun loadHostDirectories(
        path: String? = null,
    ): HostDirectoryListing {
        val target = activeRelayTarget
        if (target == null) {
            return withHermesRestOperation { serverOrigin, accessToken ->
                client.loadHostDirectories(serverOrigin, accessToken, path)
            }
        }
        val profile = mutableSnapshots.value.selectedProfile
        val expectedGeneration = generation
        val expectedOrigin = activeOrigin
        return withRelayReader(target, profile) { session ->
            relayFoldersClient.list(
                profile = profile,
                path = path,
                ensureCurrent = {
                    ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                },
                request = { method, params ->
                    ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                    session.relayRequest(method, params).also {
                        ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                    }
                },
            )
        }.also {
            ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
        }
    }

    suspend fun loadHostFiles(path: String? = null): HostFileListing =
        withHermesRestOperation { serverOrigin, accessToken ->
            client.loadHostFiles(serverOrigin, accessToken, path)
        }

    suspend fun loadManagedFile(path: String): HostFileContent =
        withHermesRestOperation { serverOrigin, accessToken ->
            client.downloadManagedFile(serverOrigin, accessToken, path)
        }

    suspend fun createHostDirectory(
        parentPath: String,
        name: String,
    ): HostDirectoryListing {
        val target = activeRelayTarget
        if (target == null) {
            return withHermesRestOperation { serverOrigin, accessToken ->
                client.createHostDirectory(serverOrigin, accessToken, parentPath, name)
            }
        }
        val profile = mutableSnapshots.value.selectedProfile
        val expectedGeneration = generation
        val expectedOrigin = activeOrigin
        return withRelayReader(target, profile) { session ->
            relayFoldersClient.create(
                profile = profile,
                parentPath = parentPath,
                name = name,
                ensureCurrent = {
                    ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                },
                request = { method, params ->
                    ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                    session.relayRequest(method, params).also {
                        ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                    }
                },
            )
        }.also {
            ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
        }
    }

    private val relayImageReader = com.unsupportedpastels.hermesandroid.relay.RelayImageReader()

    suspend fun downloadManagedImage(path: String): ByteArray = withContext(Dispatchers.Main.immediate) {
        val expectedGeneration = generation
        val target = activeRelayTarget
        val profile = mutableSnapshots.value.selectedProfile
        val bytes = if (target != null) {
            // Reuse the admitted live channel. A second socket would evict the active controller.
            val session = liveControllers.values.firstOrNull()?.session
                ?: throw HermesConnectionException("Connect the Relay chat to load images")
            relayImageReader.read(profile, path) { method, params ->
                currentCoroutineContext().ensureActive()
                if (generation != expectedGeneration || activeRelayTarget?.id != target.id ||
                    mutableSnapshots.value.selectedProfile != profile ||
                    liveControllers.values.none { it.session === session }
                ) throw CancellationException("Image connection changed")
                session.relayRequest(method, params)
            }
        } else {
            withHermesRestOperation { serverOrigin, accessToken ->
                client.downloadManagedImage(serverOrigin, accessToken, path)
            }
        }
        currentCoroutineContext().ensureActive()
        if (generation != expectedGeneration || activeRelayTarget?.id != target?.id ||
            mutableSnapshots.value.selectedProfile != profile
        ) throw CancellationException("Image connection changed")
        bytes
    }

    /**
     * Previously downloaded managed video for [path], if present in the
     * origin-scoped cache. Never touches the network; powers poster previews.
     */
    suspend fun peekManagedVideo(path: String): ManagedVideoMedia? {
        val cache = videoCache ?: return null
        val scope = operationGuard.currentScope() ?: return null
        if (scope.relayTargetId != null || !com.unsupportedpastels.mercury.core.artifacts.ManagedVideoPolicy.isManagedVideoPath(path)) return null
        var acquired: ManagedVideoMedia? = null
        try {
            return operationGuard.run(scope) {
                withContext(Dispatchers.IO) { cache.acquire(scope.origin, path).also { acquired = it } }
            }
        } catch (failure: Throwable) {
            acquired?.close()
            throw failure
        }
    }

    /**
     * Download the managed video at [path] into the origin-scoped disk cache and
     * return the local file for playback. Previously completed downloads are
     * reused without hitting the network, and concurrent callers targeting the
     * same entry are serialized behind a shared cache check.
     */
    suspend fun downloadManagedVideo(path: String): ManagedVideoMedia {
        if (!com.unsupportedpastels.mercury.core.artifacts.ManagedVideoPolicy.isManagedVideoPath(path)) {
            throw HermesConnectionException("Host video path is invalid")
        }
        val cache = videoCache ?: throw HermesConnectionException("Managed videos are unavailable")
        var acquired: ManagedVideoMedia? = null
        try {
            return withHermesRestOperation { serverOrigin, accessToken ->
                val lock = videoDownloadLocks.computeIfAbsent(
                    "${serverOrigin.value}\u0000$path",
                ) { Mutex() }
                lock.withLock {
                    withContext(Dispatchers.IO) {
                        cache.acquire(serverOrigin, path)?.also { acquired = it } ?: run {
                            val reservation = cache.reserve(serverOrigin, path)
                            acquired = reservation
                            try {
                                val downloaded = client.streamManagedVideoToFile(
                                    serverOrigin, accessToken, path, reservation.file,
                                )
                                val media = ManagedVideoMedia(downloaded.file, downloaded.mimeType) { reservation.close() }
                                acquired = media
                                cache.prune(serverOrigin, keep = media.file)
                                media
                            } catch (failure: Throwable) {
                                reservation.close()
                                throw failure
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            acquired?.close()
            throw failure
        }
    }

    suspend fun createProject(
        name: String,
        path: String,
        profile: String = mutableSnapshots.value.selectedProfile,
    ): ProjectSummary {
        val target = activeRelayTarget
        val created = if (target != null) {
            val expectedGeneration = generation
            val expectedOrigin = activeOrigin
            withRelayReader(target, profile) { session ->
                ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                session.createProject(name, path, profile).also {
                    ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
                }
            }.also {
                ensureRelayFolderScope(target, expectedOrigin, profile, expectedGeneration)
            }
        } else {
            withProjectMetadataSession { session ->
                session.createProject(name, path, profile)
            }
        }
        val snapshot = mutableSnapshots.value
        val projects = listOf(created) + snapshot.projects.filterNot { it.id == created.id }
        val loaded = snapshot.projectState as? ProjectLoadState.Loaded
        mutableSnapshots.value = snapshot.copy(
            projects = projects,
            projectState = ProjectLoadState.Loaded(
                projects = projects,
                activeProjectId = created.id,
                scopedSessionIds = loaded?.scopedSessionIds ?: snapshot.scopedSessionIds,
            ),
            activeProjectId = created.id,
        )
        return created
    }

    /** Adds an explicit unscoped local "New chat" draft to Home. */
    fun createNewSession(title: String = "New chat"): DurableSessionId {
        val draftId = DurableSessionId("draft-${++draftCounter}")
        pendingDraftSessions += draftId
        val snapshot = mutableSnapshots.value
        mutableSnapshots.value = snapshot.copy(
            durableSessions = listOf(
                SessionSummary(
                    id = draftId,
                    title = title,
                    projectId = null,
                    workspacePath = null,
                    profile = snapshot.selectedProfile,
                    isLocalDraft = true,
                ),
            ) + snapshot.durableSessions,
            chatSessions = snapshot.chatSessions + (draftId to ChatSessionSnapshot(owningProfile = snapshot.selectedProfile)),
        )
        hydrateDraftDefaults(draftId)
        return draftId
    }

    /** Adds a local draft to the exact project identified by [projectId]. */
    fun createProjectSession(projectId: ProjectId, title: String): DurableSessionId {
        val project = mutableSnapshots.value.projects.firstOrNull { it.id == projectId }
            ?: throw IllegalArgumentException("Unknown project")
        val draftId = DurableSessionId("draft-${++draftCounter}")
        val workspacePath = validProjectWorkspacePath(project.primaryPath)
        val draft = SessionSummary(
            id = draftId,
            title = title,
            projectId = project.id,
            workspacePath = workspacePath,
            profile = mutableSnapshots.value.selectedProfile,
            isLocalDraft = true,
        )
        pendingDraftSessions += draftId

        val snapshot = mutableSnapshots.value
        val projectSessions = listOf(draft) +
            snapshot.projectSessions[projectId].orEmpty().filterNot { it.id == draftId }
        val existingChat = snapshot.chatSessions[draftId]
        val chatSessions = if (workspacePath == null && !isNoProjectBucket(projectId)) {
            snapshot.chatSessions + (
                draftId to (existingChat ?: ChatSessionSnapshot(owningProfile = draft.profile)).copy(error = "No workspace")
                )
        } else {
            snapshot.chatSessions
        }
        mutableSnapshots.value = snapshot.copy(
            projectSessions = snapshot.projectSessions + (projectId to projectSessions),
            projectSessionStates = snapshot.projectSessionStates + (
                projectId to ProjectSessionLoadState.Loaded(projectSessions)
                ),
            chatSessions = chatSessions + (draftId to (chatSessions[draftId] ?: ChatSessionSnapshot(owningProfile = draft.profile))),
        )
        hydrateDraftDefaults(draftId)
        return draftId
    }

    private fun hydrateDraftDefaults(durableSessionId: DurableSessionId) {
        val origin = activeOrigin
        val originGeneration = generation
        val profile = localDraftSession(durableSessionId)?.profile
            ?: mutableSnapshots.value.selectedProfile
        viewModelScope.launch {
            if (origin == null) {
                updateChat(durableSessionId) { it.copy(draftDefaultsLoaded = true) }
                return@launch
            }
            try {
                val relayTarget = activeRelayTarget
                val accessToken = if (relayTarget != null) {
                    ""
                } else {
                    accessTokenForRequest(origin, originGeneration)
                        ?: throw HermesConnectionException("Sign in is required to load session defaults")
                }
                val options = if (relayTarget != null) {
                    loadRelayProfileModelOptions(relayTarget, profile)
                } else {
                    client.loadDefaultModelOptions(origin, accessToken, profile)
                }
                val selection = options.current
                val reasoningEffort = selection?.takeIf { relayTarget == null }?.let {
                    try {
                        client.loadProfileReasoningEffort(
                            serverOrigin = origin,
                            accessToken = accessToken,
                            profile = profile,
                            provider = it.provider,
                            model = it.model,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (expired: NativeRefreshExpiredException) {
                        throw expired
                    } catch (_: Exception) {
                        null
                    }
                }
                if (
                    !isCurrentOrigin(origin, originGeneration) ||
                    durableSessionId !in pendingDraftSessions
                ) return@launch
                updateChat(durableSessionId) { chat ->
                    chat.copy(
                        model = selection?.model,
                        provider = selection?.provider,
                        modelCapabilities = options.capabilitiesFor(selection),
                        reasoningEffort = reasoningEffort,
                        draftDefaultsLoaded = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentOrigin(origin, originGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: Exception) {
                if (
                    isCurrentOrigin(origin, originGeneration) &&
                    durableSessionId in pendingDraftSessions
                ) {
                    updateChat(durableSessionId) { it.copy(draftDefaultsLoaded = true) }
                }
            }
        }
    }

    private suspend fun loadRelayProfileModelOptions(
        target: RelayPairedTarget,
        profile: String,
    ): ModelOptions = withRelayReader(target, profile) { session ->
        session.loadProfileModelOptions()
    }

    /**
     * Stage new picker results into the composer, enforcing count/size caps at
     * metadata time. Accepted attachments are published for the chip row; rejected
     * ones are returned as reasons so the UI can surface them.
     */
    fun addAttachments(
        durableSessionId: DurableSessionId,
        candidates: List<ComposerAttachment>,
    ): List<String> {
        val current = mutableAttachments.value[durableSessionId].orEmpty()
        val accepted = mutableListOf<ComposerAttachment>()
        val rejected = mutableListOf<String>()
        for (candidate in candidates) {
            val safeCandidate = candidate.copy(
                displayName = AttachmentPolicy.sanitizeDisplayName(candidate.displayName),
            )
            when (val result = AttachmentPolicy.checkAdd(current + accepted, safeCandidate)) {
                is AttachmentAddResult.Accepted -> accepted += safeCandidate
                is AttachmentAddResult.Rejected -> rejected += result.reason
            }
        }
        if (accepted.isNotEmpty()) {
            mutableAttachments.value = mutableAttachments.value + (durableSessionId to current + accepted)
        }
        return rejected
    }

    fun removeAttachment(durableSessionId: DurableSessionId, attachmentId: String) {
        val updated = mutableAttachments.value[durableSessionId].orEmpty().filterNot { it.id == attachmentId }
        mutableAttachments.value = if (updated.isEmpty()) {
            mutableAttachments.value - durableSessionId
        } else {
            mutableAttachments.value + (durableSessionId to updated)
        }
    }

    private fun clearAttachments(
        durableSessionId: DurableSessionId,
        attachmentIds: Set<String>? = null,
    ) {
        val current = mutableAttachments.value[durableSessionId].orEmpty()
        if (current.isEmpty()) return
        val updated = if (attachmentIds == null) {
            emptyList()
        } else {
            current.filterNot { it.id in attachmentIds }
        }
        mutableAttachments.value = if (updated.isEmpty()) {
            mutableAttachments.value - durableSessionId
        } else {
            mutableAttachments.value + (durableSessionId to updated)
        }
    }

    private fun acknowledgeTerminalSubmission(
        durableSessionId: DurableSessionId,
        effect: PromptSubmissionLifecycle.TerminalEffect.ReleasedPending,
    ) {
        clearAttachments(durableSessionId, effect.attachmentIds.toSet())
        updateChat(durableSessionId) { current ->
            current.copy(
                acceptedSubmissionCount = current.acceptedSubmissionCount + 1,
                acceptedSubmissionText = effect.draft,
                isSending = false,
                connectionPhase = ChatConnectionPhase.Idle,
            )
        }
    }

    private suspend fun loadRelayTranscript(
        target: RelayPairedTarget,
        durableSessionId: DurableSessionId,
        profile: String,
    ): List<ChatMessage> = withRelayReader(target, profile) { session ->
            val expectedGeneration = generation
            val expectedOperation = sessionControllerRegistry.operationGeneration(durableSessionId)
            val result = session.relayRequest(
                "relay.session.transcript",
                buildJsonObject {
                    put("profile", profile)
                    put("session_id", serverDurableId(durableSessionId).value)
                    put("limit", 100)
                    put("offset", 0)
                    put("order", "latest")
                },
            )
            if (generation != expectedGeneration || activeRelayTarget?.id != target.id ||
                mutableSnapshots.value.selectedProfile != profile ||
                sessionControllerRegistry.operationGeneration(durableSessionId) != expectedOperation) {
                throw CancellationException("Relay transcript owner changed")
            }
            session.relayLeaseSnapshot?.let { snapshot ->
                updateChat(durableSessionId) { current -> current.copy(
                    backgroundTasks = current.backgroundTasks.recoverRelayTasks(
                        snapshot, serverDurableId(durableSessionId).value, profile),
                ) }
            }
            parseRelayTranscriptRows(result)
    }

    private suspend fun ensureRelayFolderScope(
        target: RelayPairedTarget,
        expectedOrigin: ServerOrigin?,
        profile: String,
        expectedGeneration: Long,
    ) {
        currentCoroutineContext().ensureActive()
        if (
            generation != expectedGeneration ||
            activeOrigin != expectedOrigin ||
            activeRelayTarget?.id != target.id ||
            mutableSnapshots.value.selectedProfile != profile
        ) {
            throw CancellationException("Relay folder scope changed")
        }
    }

    /** Metadata borrows a controller, never replaces its admitted device channel. */
    private suspend fun <T> withRelayReader(
        target: RelayPairedTarget,
        profile: String,
        read: suspend (HermesChatSession) -> T,
    ): T = relayConnectionLock.withLock {
        val expectedGeneration = generation
        if (activeRelayTarget?.id != target.id) throw CancellationException("Relay target changed")
        val borrowed = liveControllers.values.firstOrNull()?.session
        val session = borrowed ?: relaySessionFactory?.invoke(target, profile, null)
            ?: throw HermesConnectionException("Mercury Relay chat is unavailable")
        try {
            currentCoroutineContext().ensureActive()
            if (generation != expectedGeneration || activeRelayTarget?.id != target.id) {
                throw CancellationException("Relay target changed")
            }
            read(session)
        } finally {
            if (borrowed == null) closeChatSessionNonCancellably(session)
        }
    }

    private fun loadBackgroundViewedTranscript(durableSessionId: DurableSessionId): Job {
        pinChatProfile(durableSessionId)
        return viewModelScope.launch {
            val origin = activeOrigin ?: return@launch
            val originGeneration = generation
            val profile = owningProfile(durableSessionId)
            updateChat(durableSessionId) { it.copy(isLoading = true, error = null) }
            try {
                val relayTarget = activeRelayTarget
                val messages = if (relayTarget != null) {
                    loadRelayTranscript(relayTarget, durableSessionId, mutableSnapshots.value.selectedProfile)
                } else {
                    val accessToken = accessTokenForRequest(origin, originGeneration)
                    if (!isCurrentOrigin(origin, originGeneration)) return@launch
                    client.loadTranscript(origin, accessToken, serverDurableId(durableSessionId), profile)
                }
                if (!isCurrentOrigin(origin, originGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(messages = messages, isLoading = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HermesUnauthorizedException) {
                if (!isCurrentOrigin(origin, originGeneration)) return@launch
                updateChat(durableSessionId) { it.copy(isLoading = false, error = null) }
                retryConnection()
            } catch (error: Exception) {
                if (!isCurrentOrigin(origin, originGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(
                        isLoading = false,
                        error = error.message?.take(120)
                            ?: "Could not load transcript (${error.javaClass.simpleName})",
                    )
                }
            }
        }
    }

    fun openSession(durableSessionId: DurableSessionId): Job {
        pinChatProfile(durableSessionId)
        sessionControllerRegistry.chatJobs[durableSessionId]?.takeIf { it.isActive }?.let { return it }
        if (durableSessionId in pendingDraftSessions) {
            // Selecting a local draft is UI navigation only. Its runtime is opened
            // by the first send, so this must not replace or interrupt another
            // selected runtime.
            return viewModelScope.launch { }
        }
        if (liveControllers[durableSessionId] != null) {
            return viewModelScope.launch { }
        }
        val operationGeneration = sessionControllerRegistry.nextOperationGeneration(durableSessionId)
        sessionControllerRegistry.cancelOperationJob(durableSessionId)
        val job = viewModelScope.launch {
            val origin = activeOrigin ?: return@launch
            var originGeneration = generation
            val expectedProfileGeneration = profileGeneration
            val selectedProfile = mutableSnapshots.value.selectedProfile
            val profile = owningProfile(durableSessionId)
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
            val cached = cacheRepository?.read(
                CacheScope(origin, profile),
                nowEpochSeconds(),
            )?.sessions?.firstOrNull { it.summary.id == durableSessionId }
            if (
                activeOrigin != origin || generation != originGeneration ||
                profileGeneration != expectedProfileGeneration ||
                mutableSnapshots.value.selectedProfile != selectedProfile
            ) return@launch
            val hasCachedMessages = cached?.messages?.isNotEmpty() == true
            if (cached != null) {
                updateChat(durableSessionId) {
                    it.copy(
                        messages = cached.messages,
                        isLoading = false,
                        error = null,
                        transcriptSource = if (hasCachedMessages) CacheSource.Cached else CacheSource.Live,
                    )
                }
            }
            updateChat(durableSessionId) { it.copy(isLoading = !hasCachedMessages, error = null) }
            try {
                val relayTarget = activeRelayTarget
                var accessToken: String? = null
                var messages: List<ChatMessage>? = null
                var retriedAfterUnauthorized = false
                if (relayTarget != null) {
                    accessToken = ""
                    messages = loadRelayTranscript(relayTarget, durableSessionId, profile)
                }
                while (messages == null && relayTarget == null) {
                    try {
                        accessToken = accessTokenForRequest(origin, originGeneration)
                        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                        messages = client.loadTranscript(
                            origin,
                            accessToken,
                            serverDurableId(durableSessionId),
                            profile = profile,
                        )
                        if (
                            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
                            profileGeneration != expectedProfileGeneration ||
                            mutableSnapshots.value.selectedProfile != selectedProfile
                        ) return@launch
                    } catch (unauthorized: HermesUnauthorizedException) {
                        if (retriedAfterUnauthorized) throw unauthorized
                        retriedAfterUnauthorized = true
                        retryConnection().join()
                        if (
                            activeOrigin != origin ||
                            mutableSnapshots.value.authenticationState !in setOf(
                                AuthenticationState.Authenticated,
                                AuthenticationState.NotRequired,
                            )
                        ) return@launch
                        originGeneration = generation
                    }
                }
                val loadedMessages = checkNotNull(messages)
                updateChat(durableSessionId) {
                    it.copy(
                        messages = loadedMessages,
                        isLoading = false,
                        error = null,
                        transcriptSource = CacheSource.Live,
                    )
                }
                if (relayTarget == null) {
                    cachedSummary(durableSessionId)?.let { summary ->
                        persistCachedTranscript(origin, profile, summary, loadedMessages, originGeneration)
                    }
                }
                if (
                    accessToken != null &&
                    (chatConnector != null || (relayTarget != null && relaySessionFactory != null)) &&
                    (relayTarget == null || liveControllers.isEmpty()) &&
                    mutableSnapshots.value.authenticationState == AuthenticationState.Authenticated
                ) {
                    ensureLiveSession(
                        origin = origin,
                        originGeneration = originGeneration,
                        operationGeneration = operationGeneration,
                        accessToken = accessToken,
                        durableSessionId = durableSessionId,
                        closeWhenIdle = true,
                    ).let { session ->
                        liveControllers[durableSessionId]?.let { controller ->
                            loadProcessRowsIfCurrent(
                                durableSessionId = durableSessionId,
                                session = session,
                                runtimeSessionId = controller.runtimeSessionId,
                                origin = origin,
                                originGeneration = originGeneration,
                                operationGeneration = operationGeneration,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    updateChat(durableSessionId) { it.copy(isLoading = false) }
                }
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(
                        isLoading = false,
                        error = error.message
                            ?.take(120)
                            ?: "Could not load transcript (${error.javaClass.simpleName})",
                    )
                }
            }
        }
        sessionControllerRegistry.setOperationJob(durableSessionId, job)
        return job
    }

    /**
     * [interrupted] marks a voice barge-in follow-up; it is forwarded to
     * `prompt.submit` for exactly this one submission.
     */
    fun sendMessage(
        durableSessionId: DurableSessionId,
        rawText: String,
        interrupted: Boolean = false,
    ): Job {
        pinChatProfile(durableSessionId)
        if (mutableSnapshots.value.chatSessions[durableSessionId]?.connectionPhase?.let {
                it == ChatConnectionPhase.Connecting || it == ChatConnectionPhase.Submitting
            } == true) {
            rejectSubmission(durableSessionId, rawText.trim(), "A submission is already pending")
            return viewModelScope.launch { }
        }
        val text = rawText.trim()
        val hasAttachments = mutableAttachments.value[durableSessionId].orEmpty().isNotEmpty()
        if (text.isEmpty() && !hasAttachments) {
            rejectSubmission(durableSessionId, text, "Message cannot be blank")
            return viewModelScope.launch { }
        }
        val draft = localDraftSession(durableSessionId)
        if (
            draft?.projectId != null &&
            !isNoProjectBucket(draft.projectId) &&
            validProjectWorkspacePath(draft.workspacePath) == null
        ) {
            updateChat(durableSessionId) {
                it.copy(isLoading = false, isSending = false, error = "No workspace")
            }
            rejectSubmission(durableSessionId, text, "No workspace")
            return viewModelScope.launch { }
        }
        val pendingAttachments = mutableAttachments.value[durableSessionId].orEmpty()
        val attempt = sessionControllerRegistry.beginPromptSubmission(
            durableSessionId = durableSessionId,
            draft = text,
            attachmentIds = pendingAttachments.map(ComposerAttachment::id),
        ) ?: run {
            rejectSubmission(durableSessionId, text, "A submission is already pending")
            return viewModelScope.launch { }
        }
        val operationGeneration = sessionControllerRegistry.nextOperationGeneration(durableSessionId)
        sessionControllerRegistry.cancelOperationJob(durableSessionId)
        clearSendingState(durableSessionId)
        updateChat(durableSessionId) {
            it.copy(runState = RunEventState(), connectionPhase = ChatConnectionPhase.Connecting)
        }
        val job = viewModelScope.launch {
            val origin = activeOrigin ?: run {
                sessionControllerRegistry.resolvePromptSubmission(durableSessionId, attempt, accepted = false)
                updateChat(durableSessionId) { it.copy(connectionPhase = ChatConnectionPhase.Idle, error = "Not connected") }
                rejectSubmission(durableSessionId, text, "Not connected")
                return@launch
            }
            val originGeneration = generation
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
            var promptStaged = false
            var stagingFailed = false
            var accepted = false
            try {
                val accessToken = if (activeRelayTarget != null) {
                    ""
                } else {
                    accessTokenForRequest(origin, originGeneration)
                        ?: throw HermesConnectionException("Sign in is required to send messages")
                }
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeId = checkNotNull(liveControllers[durableSessionId]?.runtimeSessionId)
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                loadProcessRowsIfCurrent(
                    durableSessionId = durableSessionId,
                    session = session,
                    runtimeSessionId = runtimeId,
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                )

                currentCoroutineContext().ensureActive()
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) { it.copy(connectionPhase = ChatConnectionPhase.Submitting) }
                // Stage attachments on the host BEFORE the optimistic bubble: bytes
                // live on this device, so nothing can be sent without uploading
                // them first, and chips must survive a staging failure.
                var submittedText = text
                if (pendingAttachments.isNotEmpty()) {
                    try {
                        val staged = AttachmentStager(session, runtimeId, attachmentReader)
                            .stage(pendingAttachments)
                        submittedText = AttachmentPolicy.composePromptText(
                            typedText = text,
                            fileRefs = staged.refTexts,
                            attachedNames = staged.names,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        stagingFailed = true
                        throw error
                    }
                }

                currentCoroutineContext().ensureActive()
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) { current ->
                    current.copy(
                        messages = current.messages +
                            ChatMessage(ChatMessageRole.User, text) +
                            ChatMessage(ChatMessageRole.Assistant, "", isStreaming = true),
                        isLoading = false,
                        isSending = true,
                        error = null,
                        notice = null,
                        billingNotice = null,
                    )
                }
                promptStaged = true
                yield()
                currentCoroutineContext().ensureActive()
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                if (liveControllers[durableSessionId]?.session !== session) {
                    throw HermesChatTransportException("Connection closed before submission")
                }
                if (activeRelayTarget != null) {
                    // Host SessionLease accepts params.submission_id (1..128 chars).
                    // IDs are lease-local; never retry an ambiguously accepted prompt.
                    session.submitPrompt(runtimeId, submittedText, interrupted, java.util.UUID.randomUUID().toString())
                } else {
                    session.submitPrompt(runtimeId, submittedText, interrupted)
                }
                val resolution = sessionControllerRegistry.resolvePromptSubmission(
                    durableSessionId = durableSessionId,
                    attempt = attempt,
                    accepted = true,
                )
                if (resolution !is PromptSubmissionLifecycle.Resolution.Accepted) return@launch
                accepted = true
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                clearAttachments(durableSessionId, resolution.attachmentIds.toSet())
                if (!resolution.terminalAlreadyObserved) {
                    updateChat(durableSessionId) {
                        it.copy(
                            acceptedSubmissionCount = it.acceptedSubmissionCount + 1,
                            acceptedSubmissionText = resolution.draft,
                            connectionPhase = ChatConnectionPhase.Idle,
                        )
                    }
                    // Commit accepted prompt state before any auxiliary suspension. A
                    // replacement/steer may cancel this job while metadata is loading.
                    markTurnActive(durableSessionId)
                    markDraftPersisted(
                        durableSessionId,
                        origin,
                        originGeneration,
                        operationGeneration,
                    )
                }
                // A prompt can launch background processes, so refresh the activity
                // stack only after the turn is accepted: the pre-submit snapshot
                // cannot contain processes created by this turn.
                loadProcessRowsIfCurrent(
                    durableSessionId = durableSessionId,
                    session = session,
                    runtimeSessionId = runtimeId,
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                )
            } catch (cancelled: CancellationException) {
                when (sessionControllerRegistry.resolvePromptSubmission(durableSessionId, attempt, accepted = false)) {
                    PromptSubmissionLifecycle.Resolution.AuthoritativeTerminal -> accepted = true
                    PromptSubmissionLifecycle.Resolution.Stale -> Unit
                    else -> if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                        clearSendingState(durableSessionId)
                    }
                }
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (sessionControllerRegistry.resolvePromptSubmission(durableSessionId, attempt, accepted = false) ==
                    PromptSubmissionLifecycle.Resolution.AuthoritativeTerminal
                ) {
                    accepted = true
                }
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (_: HermesChatUnauthorizedException) {
                // A ws-ticket/connect 401 despite a just-refreshed access token means
                // the session is terminally rejected — drop to a clean sign-in rather
                // than spinning recovery against a credential the server won't accept.
                if (sessionControllerRegistry.resolvePromptSubmission(durableSessionId, attempt, accepted = false) ==
                    PromptSubmissionLifecycle.Resolution.AuthoritativeTerminal
                ) {
                    accepted = true
                }
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                val resolution = sessionControllerRegistry.resolvePromptSubmission(
                    durableSessionId = durableSessionId,
                    attempt = attempt,
                    accepted = false,
                )
                if (resolution == PromptSubmissionLifecycle.Resolution.AuthoritativeTerminal) {
                    accepted = true
                    return@launch
                }
                if (resolution == PromptSubmissionLifecycle.Resolution.Stale ||
                    !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)
                ) return@launch
                if (stagingFailed) {
                    // Nothing was submitted; the draft stays editable with its chips.
                    // A fresh draft runtime may hold partially staged orphaned files,
                    // so drop it — the next send creates a clean runtime.
                    if (durableSessionId in pendingDraftSessions || error is HermesChatException) {
                        detachFailedRuntime(durableSessionId)
                    }
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) { current ->
                        current.copy(
                            error = error.message?.take(160) ?: "Could not attach files",
                        )
                    }
                    return@launch
                }
                if (
                    promptStaged &&
                    hasAttachments &&
                    error is HermesChatException &&
                    error !is HermesChatTransportException
                ) {
                    detachFailedRuntime(durableSessionId)
                }
                if (promptStaged && error is HermesChatTransportException) {
                    val recoveryAttempt = startChatRecovery(durableSessionId, operationGeneration)
                    if (recoveryAttempt != null) {
                        try {
                            recoverChat(
                                durableSessionId,
                                origin,
                                originGeneration,
                                operationGeneration,
                                recoveryAttempt,
                            )
                        } finally {
                            finishChatRecovery(recoveryAttempt)
                        }
                        if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) &&
                            mutableSnapshots.value.chatSessions[durableSessionId]?.isSending != true
                        ) {
                            updateChat(durableSessionId) {
                                it.copy(error = it.error ?: "Submission was not confirmed. Check the transcript before retrying.")
                            }
                        }
                    } else if (!isChatRecoveryInProgress(durableSessionId, operationGeneration)) {
                        clearSendingState(durableSessionId)
                        updateChat(durableSessionId) {
                            it.copy(error = "Connection lost while receiving response")
                        }
                    }
                } else {
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) { current ->
                        current.copy(
                            error = error.message?.take(160) ?: "Could not send message",
                        )
                    }
                }
            } finally {
                val currentOperation = sessionControllerRegistry.operationGeneration(durableSessionId)
                if (isCurrentOrigin(origin, originGeneration) &&
                    (currentOperation == operationGeneration || currentOperation == null) &&
                    durableSessionId in mutableSnapshots.value.chatSessions
                ) {
                    updateChat(durableSessionId) { it.copy(connectionPhase = ChatConnectionPhase.Idle) }
                    if (!accepted) {
                        rejectSubmission(durableSessionId, text,
                            mutableSnapshots.value.chatSessions[durableSessionId]?.error
                                ?: "Submission was not confirmed. Check the transcript before retrying.")
                    }
                }
            }
        }
        sessionControllerRegistry.setOperationJob(durableSessionId, job)
        return job
    }

    /** A local failure acknowledgment is not proof that an ambiguous RPC was rejected remotely. */
    private fun rejectSubmission(durableSessionId: DurableSessionId, text: String, error: String) {
        updateChat(durableSessionId) {
            it.copy(
                rejectedSubmissionCount = it.rejectedSubmissionCount + 1,
                rejectedSubmissionText = text,
                error = error,
            )
        }
    }

    /** Responds to the currently displayed clarification for exactly one live runtime. */
    fun respondToClarification(
        durableSessionId: DurableSessionId,
        requestId: String,
        answer: String,
    ): Job = respondToClarification(durableSessionId, requestId, questionId = null, answer = answer)

    /** [questionId] names the batch question being answered; null for a single-question request. */
    fun respondToClarification(
        durableSessionId: DurableSessionId,
        requestId: String,
        questionId: String?,
        answer: String,
    ): Job {
        val operation = beginClarificationResponse(durableSessionId, requestId)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val response = operation.session.respondToClarification(requestId, questionId, answer)
                currentCoroutineContext().ensureActive()
                val lifecycle = when (response.status) {
                    HermesChatResponseStatus.Ok,
                    HermesChatResponseStatus.Resolved,
                    -> RunInteractionLifecycle.Resolved
                    HermesChatResponseStatus.Expired -> RunInteractionLifecycle.Expired
                    HermesChatResponseStatus.Interrupted,
                    HermesChatResponseStatus.Unknown,
                    -> RunInteractionLifecycle.Failed
                }
                publishClarificationResponse(operation, lifecycle, questionId)
                if (lifecycle == RunInteractionLifecycle.Failed) {
                    publishControllerError(operation, "Could not respond to clarification")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishClarificationResponse(operation, RunInteractionLifecycle.Failed, questionId)
                publishControllerError(operation, "Could not respond to clarification")
            }
        }
    }

    /** Responds once to the exact pending sudo, secret, or renderer-read bridge request. */
    fun respondToBlockingPrompt(
        durableSessionId: DurableSessionId,
        kind: UnsupportedBlockingKind,
        requestId: String,
        value: String,
    ): Job {
        val operation = beginBlockingResponse(durableSessionId, kind, requestId)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val response = operation.session.respondToBlockingPrompt(kind, requestId, value)
                currentCoroutineContext().ensureActive()
                val lifecycle = when (response.status) {
                    HermesChatResponseStatus.Ok,
                    HermesChatResponseStatus.Resolved,
                    -> RunInteractionLifecycle.Resolved
                    HermesChatResponseStatus.Expired -> RunInteractionLifecycle.Expired
                    HermesChatResponseStatus.Interrupted,
                    HermesChatResponseStatus.Unknown,
                    -> RunInteractionLifecycle.Failed
                }
                publishBlockingResponse(operation, lifecycle)
                if (lifecycle == RunInteractionLifecycle.Failed) publishBlockingError(operation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishBlockingResponse(operation, RunInteractionLifecycle.Failed)
                publishBlockingError(operation)
            }
        }
    }

    /** Responds to the currently displayed approval for exactly one live runtime. */
    fun respondToApproval(
        durableSessionId: DurableSessionId,
        choice: String,
        all: Boolean = false,
    ): Job {
        val operation = beginApprovalResponse(durableSessionId, choice)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val response = operation.session.respondToApproval(
                    runtimeSessionId = operation.runtimeSessionId,
                    choice = choice,
                    all = all,
                    requestId = operation.requestId,
                )
                currentCoroutineContext().ensureActive()
                val lifecycle = when (response.status) {
                    HermesChatResponseStatus.Ok,
                    HermesChatResponseStatus.Resolved,
                    -> RunInteractionLifecycle.Resolved
                    HermesChatResponseStatus.Expired -> RunInteractionLifecycle.Expired
                    HermesChatResponseStatus.Interrupted,
                    HermesChatResponseStatus.Unknown,
                    -> RunInteractionLifecycle.Failed
                }
                publishApprovalResponse(operation, lifecycle, response.nextApproval)
                if (lifecycle == RunInteractionLifecycle.Failed) {
                    publishApprovalError(operation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishApprovalResponse(operation, RunInteractionLifecycle.Failed)
                publishApprovalError(operation)
            }
        }
    }

    /** Interrupts exactly the selected session's current sending controller runtime. */
    fun stopSession(durableSessionId: DurableSessionId): Job {
        val operation = beginStopSession(durableSessionId)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val response = operation.session.interruptSession(operation.runtimeSessionId)
                currentCoroutineContext().ensureActive()
                if (
                    response.status == HermesChatResponseStatus.Interrupted ||
                    response.status == HermesChatResponseStatus.Ok
                ) {
                    publishStopSuccess(operation)
                } else {
                    publishStopFailure(operation)
                }
            } catch (cancelled: CancellationException) {
                clearStoppingIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishStopFailure(operation)
            }
        }
    }

    /** Queues guidance for exactly this HAM-controlled session's active turn. */
    fun steerSession(durableSessionId: DurableSessionId, text: String): Job {
        val guidance = text.trim()
        if (guidance.isEmpty()) {
            return viewModelScope.launch {
                updateChat(durableSessionId) {
                    it.copy(error = "Guidance cannot be blank", notice = null)
                }
            }
        }
        val operation = beginSteerSession(durableSessionId)
            ?: return viewModelScope.launch { }
        return viewModelScope.launch {
            try {
                val result = operation.session.steer(operation.runtimeSessionId, guidance)
                currentCoroutineContext().ensureActive()
                if (result.status == "queued") {
                    publishSteerSuccess(operation)
                } else {
                    publishSteerFailure(operation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishSteerFailure(operation)
            }
        }
    }

    /** Loads read-only usage and context data for an existing controlled runtime. */
    fun loadSessionInsights(durableSessionId: DurableSessionId): Job {
        val requestGeneration = (sessionInsightsGenerations[durableSessionId] ?: 0L) + 1L
        sessionInsightsGenerations[durableSessionId] = requestGeneration
        sessionInsightsJobs.remove(durableSessionId)?.cancel()
        val operation = beginSessionInsights(durableSessionId)
        if (operation == null) {
            return viewModelScope.launch {
                updateChat(durableSessionId) {
                    it.copy(
                        insightsLoading = false,
                        insightsError = "Session details require an active HAM runtime",
                    )
                }
            }
        }
        updateChat(durableSessionId) {
            it.copy(insightsLoading = true, insightsError = null)
        }
        val job = viewModelScope.launch {
            try {
                val usage = operation.session.loadSessionUsage(operation.runtimeSessionId)
                val context = operation.session.loadContextBreakdown(operation.runtimeSessionId)
                currentCoroutineContext().ensureActive()
                publishSessionInsights(operation, requestGeneration, usage, context)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishSessionInsightsFailure(operation, requestGeneration)
            }
        }
        sessionInsightsJobs[durableSessionId] = job
        return job
    }

    fun compressSession(durableSessionId: DurableSessionId, focusTopic: String? = null): Job {
        val operation = beginMaintenanceSession(durableSessionId)
            ?: return unavailableMaintenanceJob(durableSessionId)
        return viewModelScope.launch {
            try {
                val result = operation.session.compressSession(operation.runtimeSessionId, focusTopic)
                currentCoroutineContext().ensureActive()
                if (result.aborted || result.status != "compressed") {
                    publishMaintenanceFailure(operation, "Compression was not applied")
                } else {
                    publishCompressedSession(operation, result.messages)
                }
            } catch (cancelled: CancellationException) {
                clearMaintenanceIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishMaintenanceFailure(operation, "Could not compress context")
            }
        }
    }

    fun undoSession(durableSessionId: DurableSessionId): Job {
        val operation = beginMaintenanceSession(durableSessionId)
            ?: return unavailableMaintenanceJob(durableSessionId)
        val profile = owningProfile(durableSessionId)
        return viewModelScope.launch {
            try {
                operation.session.undoSession(operation.runtimeSessionId)
                val token = accessTokenForRequest(operation.origin, operation.originGeneration)
                    ?: throw HermesConnectionException("Sign in is required")
                val messages = client.loadTranscript(
                    operation.origin,
                    token,
                    serverDurableId(durableSessionId),
                    profile = profile,
                )
                currentCoroutineContext().ensureActive()
                publishUndoneSession(operation, messages)
            } catch (cancelled: CancellationException) {
                clearMaintenanceIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishMaintenanceFailure(operation, "Could not undo last turn")
            }
        }
    }

    fun branchSession(
        durableSessionId: DurableSessionId,
        count: Int? = null,
        name: String? = null,
    ): Job {
        val operation = beginMaintenanceSession(durableSessionId)
            ?: return unavailableMaintenanceJob(durableSessionId)
        return viewModelScope.launch {
            try {
                val result = operation.session.branchSession(operation.runtimeSessionId, count, name)
                currentCoroutineContext().ensureActive()
                publishBranchedSession(operation, result)
            } catch (cancelled: CancellationException) {
                clearMaintenanceIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishMaintenanceFailure(operation, "Could not branch session")
            }
        }
    }

    fun setDelegationPaused(durableSessionId: DurableSessionId, paused: Boolean): Job {
        val operation = beginDelegationControl(durableSessionId)
            ?: return unavailableDelegationJob()
        return viewModelScope.launch {
            try {
                val result = operation.session.pauseDelegation(paused)
                currentCoroutineContext().ensureActive()
                publishDelegationSuccess(
                    operation,
                    notice = if (result.paused) "New delegation paused" else "New delegation resumed",
                    paused = result.paused,
                )
            } catch (cancelled: CancellationException) {
                clearDelegationLoadingIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishDelegationFailure(operation, "Could not update delegation")
            }
        }
    }

    fun steerSubagent(
        durableSessionId: DurableSessionId,
        subagentId: String,
        text: String,
    ): Job {
        if (subagentId.isBlank() || text.isBlank()) return unavailableDelegationJob("Subagent guidance is incomplete")
        val operation = beginDelegationControl(durableSessionId)
            ?: return unavailableDelegationJob()
        return viewModelScope.launch {
            try {
                val result = operation.session.steerSubagent(
                    operation.runtimeSessionId,
                    subagentId.trim(),
                    text.trim(),
                )
                currentCoroutineContext().ensureActive()
                if (result.status == "queued") {
                    publishDelegationSuccess(operation, "Guidance queued for subagent")
                } else {
                    publishDelegationFailure(operation, "Subagent guidance was rejected")
                }
            } catch (cancelled: CancellationException) {
                clearDelegationLoadingIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishDelegationFailure(operation, "Could not steer subagent")
            }
        }
    }

    fun interruptSubagent(durableSessionId: DurableSessionId, subagentId: String): Job {
        if (subagentId.isBlank()) return unavailableDelegationJob("Subagent ID is required")
        val operation = beginDelegationControl(durableSessionId)
            ?: return unavailableDelegationJob()
        return viewModelScope.launch {
            try {
                val result = operation.session.interruptSubagent(subagentId.trim())
                currentCoroutineContext().ensureActive()
                if (result.found) {
                    publishDelegationSuccess(
                        operation,
                        notice = "Subagent interrupted",
                        removeSubagentId = result.subagentId ?: subagentId.trim(),
                    )
                } else {
                    publishDelegationFailure(operation, "Subagent is no longer active")
                }
            } catch (cancelled: CancellationException) {
                clearDelegationLoadingIfCurrent(operation)
                throw cancelled
            } catch (_: Exception) {
                publishDelegationFailure(operation, "Could not interrupt subagent")
            }
        }
    }

    private fun unavailableDelegationJob(
        message: String = "Subagent controls require an active HAM runtime",
    ): Job = viewModelScope.launch {
        mutableSnapshots.value = mutableSnapshots.value.copy(
            delegationStatus = mutableSnapshots.value.delegationStatus.copy(
                actionLoading = false,
                error = message,
                notice = null,
            ),
        )
    }

    fun refreshCronJobs(): Job {
        if (activeRelayTarget != null) return viewModelScope.launch { }
        val origin = activeOrigin
        val originGeneration = generation
        val profile = mutableSnapshots.value.selectedProfile.trim().take(64).ifEmpty { "default" }
        if (
            origin == null ||
            !isCurrentProjectLoad(origin, originGeneration) ||
            mutableSnapshots.value.selectedProfile != profile
        ) {
            return viewModelScope.launch {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    cronJobsState = CronJobsState.Error(
                        "Cron jobs require an authenticated server",
                    ),
                )
            }
        }
        mutableSnapshots.value = mutableSnapshots.value.copy(
            cronJobsState = CronJobsState.Loading,
        )
        return viewModelScope.launch {
            val state = try {
                CronJobsState.Ready(
                    withProjectMetadataSession { session ->
                        if (profile == "default") session.loadCronJobs()
                        else session.loadCronJobsForProfile(profile)
                    },
                    profile = profile,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HermesChatMethodNotFoundException) {
                CronJobsState.Unsupported
            } catch (_: Exception) {
                CronJobsState.Error("Could not load cron jobs")
            }
            currentCoroutineContext().ensureActive()
            if (
                isCurrentProjectLoad(origin, originGeneration) &&
                mutableSnapshots.value.selectedProfile == profile
            ) {
                mutableSnapshots.value = mutableSnapshots.value.copy(cronJobsState = state)
            }
        }
    }

    /**
     * Runs one lifecycle action against a cron job, then reloads the list so the
     * panel reflects the server's post-action state rather than an optimistic guess.
     */
    fun manageCronJob(jobId: String, action: CronJobAction): Job {
        val origin = activeOrigin
        val originGeneration = generation
        if (origin == null || !isCurrentProjectLoad(origin, originGeneration)) {
            return viewModelScope.launch {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    cronJobActionError = "Cron jobs require an authenticated server",
                )
            }
        }
        mutableSnapshots.value = mutableSnapshots.value.copy(
            cronJobActionJobId = jobId,
            cronJobActionError = null,
        )
        return viewModelScope.launch {
            val error = try {
                withProjectMetadataSession { session -> session.manageCronJob(jobId, action) }
                null
            } catch (cancelled: CancellationException) {
                if (isCurrentProjectLoad(origin, originGeneration)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(cronJobActionJobId = null)
                }
                throw cancelled
            } catch (_: HermesChatMethodNotFoundException) {
                "Cron job controls are not supported by this server"
            } catch (_: Exception) {
                "Could not ${action.failureVerb} the job"
            }
            currentCoroutineContext().ensureActive()
            if (isCurrentProjectLoad(origin, originGeneration)) {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    cronJobActionJobId = null,
                    cronJobActionError = error,
                )
                if (error == null) refreshCronJobs()
            }
        }
    }

    /**
     * Triggers one cron job through the audited dashboard REST route. The returned job is
     * deliberately not applied optimistically; the existing JSON-RPC list is refreshed after a
     * successful trigger so the server remains authoritative.
     */
    fun triggerCronJob(jobId: String): Job {
        val origin = activeOrigin
        val originGeneration = generation
        val profile = mutableSnapshots.value.selectedProfile.take(64).ifBlank { "default" }
        val operationScope = currentConnectionOperationScope()?.takeIf {
            it.relayTargetId == null && it.origin == origin && it.profile == profile
        }
        val scope = origin?.let { CronJobScope(it.value, profile, jobId) }
        if (
            origin == null ||
                operationScope == null ||
                scope == null ||
                !isCurrentRestOperation(operationScope) ||
                mutableSnapshots.value.cronTriggerCapability == CronRestCapability.Unsupported
        ) {
            return viewModelScope.launch { }
        }
        if (scope in mutableSnapshots.value.cronRunLoadingScopes) return viewModelScope.launch { }
        mutableSnapshots.value = mutableSnapshots.value.copy(
            cronRunLoadingScopes = mutableSnapshots.value.cronRunLoadingScopes + scope,
            cronRunErrors = mutableSnapshots.value.cronRunErrors - scope,
        )
        return viewModelScope.launch {
            try {
                val token = operationGuard.run(operationScope) {
                    accessTokenForRequest(operationScope.origin, operationScope.generation)
                        ?: throw HermesConnectionException("Cron job controls require authentication")
                }
                operationGuard.run(operationScope) {
                    client.triggerCronJob(operationScope.origin, token, operationScope.profile, jobId)
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrentRestOperation(operationScope)) return@launch
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    cronTriggerCapability = CronRestCapability.Supported,
                    cronRunLoadingScopes = mutableSnapshots.value.cronRunLoadingScopes - scope,
                    cronRunErrors = mutableSnapshots.value.cronRunErrors - scope,
                )
                refreshCronJobs().join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HermesCronRestUnsupportedException) {
                if (isCurrentRestOperation(operationScope)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        cronTriggerCapability = CronRestCapability.Unsupported,
                        cronRunLoadingScopes = mutableSnapshots.value.cronRunLoadingScopes - scope,
                        cronRunErrors = mutableSnapshots.value.cronRunErrors - scope,
                    )
                }
            } catch (_: HermesCronRestLegacyUnsupportedException) {
                if (isCurrentRestOperation(operationScope)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        cronTriggerCapability = CronRestCapability.Unsupported,
                        cronRunLoadingScopes = mutableSnapshots.value.cronRunLoadingScopes - scope,
                        cronRunErrors = mutableSnapshots.value.cronRunErrors - scope,
                    )
                }
            } catch (_: HermesCronJobClaimedException) {
                if (isCurrentRestOperation(operationScope)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        cronRunLoadingScopes = mutableSnapshots.value.cronRunLoadingScopes - scope,
                        cronRunErrors = mutableSnapshots.value.cronRunErrors + (
                            scope to "Cron job is already running or was claimed by another scheduler"
                        ),
                    )
                }
            } catch (_: Exception) {
                if (isCurrentRestOperation(operationScope)) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(
                        cronRunLoadingScopes = mutableSnapshots.value.cronRunLoadingScopes - scope,
                        cronRunErrors = mutableSnapshots.value.cronRunErrors + (
                            scope to "Could not run the job"
                        ),
                    )
                }
            }
        }
    }

    /** Expand/collapse one bounded run history and fetch it only on first expansion. */
    fun toggleCronJobRuns(jobId: String): Job {
        val origin = activeOrigin
        val originGeneration = generation
        val profile = mutableSnapshots.value.selectedProfile.take(64).ifBlank { "default" }
        val operationScope = currentConnectionOperationScope()?.takeIf {
            it.relayTargetId == null && it.origin == origin && it.profile == profile
        }
        val scope = origin?.let { CronJobScope(it.value, profile, jobId) }
        if (
            origin == null ||
                operationScope == null ||
                scope == null ||
                !isCurrentRestOperation(operationScope) ||
                mutableSnapshots.value.cronHistoryCapability == CronRestCapability.Unsupported
        ) {
            return viewModelScope.launch { }
        }
        return when (val current = mutableSnapshots.value.cronRunsByScope[scope]) {
            is CronJobRunsState.Ready -> {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    cronRunsByScope = mutableSnapshots.value.cronRunsByScope +
                        (scope to CronJobRunsState.Cached(current.runs)),
                )
                viewModelScope.launch { }
            }
            is CronJobRunsState.Cached -> {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    cronRunsByScope = mutableSnapshots.value.cronRunsByScope +
                        (scope to CronJobRunsState.Ready(current.runs)),
                )
                viewModelScope.launch { }
            }
            CronJobRunsState.Loading -> viewModelScope.launch { }
            CronJobRunsState.Unsupported -> viewModelScope.launch { }
            else -> {
                mutableSnapshots.value = mutableSnapshots.value.copy(
                    cronRunsByScope = mutableSnapshots.value.cronRunsByScope +
                        (scope to CronJobRunsState.Loading),
                )
                viewModelScope.launch {
                    try {
                        val token = operationGuard.run(operationScope) {
                            accessTokenForRequest(operationScope.origin, operationScope.generation)
                                ?: throw HermesConnectionException("Cron history requires authentication")
                        }
                        val runs = operationGuard.run(operationScope) {
                            client.loadCronJobRuns(
                                serverOrigin = operationScope.origin,
                                accessToken = token,
                                profile = operationScope.profile,
                                jobId = jobId,
                            )
                        }
                        currentCoroutineContext().ensureActive()
                        if (!isCurrentRestOperation(operationScope)) return@launch
                        mutableSnapshots.value = mutableSnapshots.value.copy(
                            cronHistoryCapability = CronRestCapability.Supported,
                            cronRunsByScope = mutableSnapshots.value.cronRunsByScope +
                                (scope to CronJobRunsState.Ready(runs)),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: HermesCronRestUnsupportedException) {
                        if (isCurrentRestOperation(operationScope)) {
                            mutableSnapshots.value = mutableSnapshots.value.copy(
                                cronHistoryCapability = CronRestCapability.Unsupported,
                                cronRunsByScope = mutableSnapshots.value.cronRunsByScope +
                                    (scope to CronJobRunsState.Unsupported),
                            )
                        }
                    } catch (_: HermesCronRestLegacyUnsupportedException) {
                        if (isCurrentRestOperation(operationScope)) {
                            mutableSnapshots.value = mutableSnapshots.value.copy(
                                cronHistoryCapability = CronRestCapability.Unsupported,
                                cronRunsByScope = mutableSnapshots.value.cronRunsByScope +
                                    (scope to CronJobRunsState.Unsupported),
                            )
                        }
                    } catch (_: Exception) {
                        if (isCurrentRestOperation(operationScope)) {
                            mutableSnapshots.value = mutableSnapshots.value.copy(
                                cronRunsByScope = mutableSnapshots.value.cronRunsByScope +
                                    (scope to CronJobRunsState.Error("Could not load job runs")),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun unavailableMaintenanceJob(durableSessionId: DurableSessionId): Job = viewModelScope.launch {
        updateChat(durableSessionId) {
            it.copy(
                maintenanceLoading = false,
                maintenanceError = "Session maintenance requires an idle HAM runtime",
            )
        }
    }

    /**
     * Debounced live slash-command completion for the composer of [durableSessionId].
     * Non-slash text clears the menu without a request. Results publish only when the
     * request is still the latest for that composer; failures clear the menu silently.
     * Completion requires the chat's live runtime (opened on first send/resume).
     */
    fun updateSlashCompletion(durableSessionId: DurableSessionId, text: String) {
        val requestGeneration = (slashCompletionGenerations[durableSessionId] ?: 0L) + 1L
        slashCompletionGenerations[durableSessionId] = requestGeneration
        slashCompletionJobs.remove(durableSessionId)?.cancel()
        if (!isSlashCommandContext(text)) {
            mutableSlashCompletions.value = mutableSlashCompletions.value - durableSessionId
            return
        }
        val job = viewModelScope.launch {
            delay(SLASH_COMPLETION_DEBOUNCE_MS)
            if (slashCompletionGenerations[durableSessionId] != requestGeneration) return@launch
            val session = liveControllers[durableSessionId]?.session
            if (session == null) {
                if (slashCompletionGenerations[durableSessionId] == requestGeneration) {
                    mutableSlashCompletions.value =
                        mutableSlashCompletions.value - durableSessionId
                }
                return@launch
            }
            val result = try {
                session.completeSlash(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (slashCompletionGenerations[durableSessionId] == requestGeneration) {
                    mutableSlashCompletions.value =
                        mutableSlashCompletions.value - durableSessionId
                }
                return@launch
            }
            if (slashCompletionGenerations[durableSessionId] != requestGeneration) return@launch
            mutableSlashCompletions.value = if (result.items.isEmpty()) {
                mutableSlashCompletions.value - durableSessionId
            } else {
                mutableSlashCompletions.value + (
                    durableSessionId to SlashCompletionState(
                        composerText = text,
                        items = result.items,
                        replaceFrom = result.replaceFrom,
                    )
                    )
            }
        }
        slashCompletionJobs[durableSessionId] = job
    }

    fun clearSlashCompletion(durableSessionId: DurableSessionId) {
        slashCompletionGenerations[durableSessionId] =
            (slashCompletionGenerations[durableSessionId] ?: 0L) + 1L
        slashCompletionJobs.remove(durableSessionId)?.cancel()
        mutableSlashCompletions.value = mutableSlashCompletions.value - durableSessionId
    }

    fun setReasoningEffort(durableSessionId: DurableSessionId, effort: String): Job {
        val canonicalEffort = canonicalReasoningEffort(effort)
        if (canonicalEffort == null) {
            return viewModelScope.launch {
                updateChat(durableSessionId) { it.copy(error = "Reasoning effort is invalid") }
            }
        }
        val operationGeneration = liveControllers[durableSessionId]?.operationGeneration
            ?: sessionControllerRegistry.nextOperationGeneration(durableSessionId)
        val job = viewModelScope.launch {
            val origin = activeOrigin
            if (origin == null) {
                updateChat(durableSessionId) { it.copy(error = "Hermes is not connected") }
                return@launch
            }
            val originGeneration = generation
            try {
                val accessToken = if (activeRelayTarget != null) {
                    ""
                } else {
                    accessTokenForRequest(origin, originGeneration)
                        ?: throw HermesConnectionException("Sign in is required to change reasoning")
                }
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeSessionId = liveControllers[durableSessionId]?.runtimeSessionId
                    ?: throw HermesConnectionException("Hermes session is not ready")
                session.setReasoning(runtimeSessionId, canonicalEffort)
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(reasoningEffort = canonicalEffort, error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(error = error.message?.take(160) ?: "Could not change reasoning")
                }
            }
        }
        return job
    }

    fun setFast(durableSessionId: DurableSessionId, fast: Boolean): Job {
        val chat = mutableSnapshots.value.chatSessions[durableSessionId]
        if (chat?.modelCapabilities?.fast != true) {
            return viewModelScope.launch {
                updateChat(durableSessionId) { it.copy(error = "Fast mode is unavailable for this model") }
            }
        }
        val operationGeneration = liveControllers[durableSessionId]?.operationGeneration
            ?: sessionControllerRegistry.nextOperationGeneration(durableSessionId)
        val job = viewModelScope.launch {
            val origin = activeOrigin
            if (origin == null) {
                updateChat(durableSessionId) { it.copy(error = "Hermes is not connected") }
                return@launch
            }
            val originGeneration = generation
            try {
                val accessToken = if (activeRelayTarget != null) {
                    ""
                } else {
                    accessTokenForRequest(origin, originGeneration)
                        ?: throw HermesConnectionException("Sign in is required to change Fast mode")
                }
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeSessionId = liveControllers[durableSessionId]?.runtimeSessionId
                    ?: throw HermesConnectionException("Hermes session is not ready")
                session.setFast(runtimeSessionId, fast)
                currentCoroutineContext().ensureActive()
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
                    !isExactControllerRuntime(durableSessionId, session, runtimeSessionId)
                ) return@launch
                updateChat(durableSessionId) {
                    it.copy(fastMode = if (fast) "fast" else "normal", error = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
                updateChat(durableSessionId) {
                    it.copy(error = error.message?.take(160) ?: "Could not change fast mode")
                }
            }
        }
        return job
    }

    fun openModelPicker(durableSessionId: DurableSessionId): Job {
        val requestGeneration = ++modelPickerGeneration
        modelPickerJob?.cancel()
        mutableModelPickerState.value = ModelPickerState.Loading(durableSessionId)
        val operationGeneration = liveControllers[durableSessionId]?.operationGeneration
            ?: sessionControllerRegistry.nextOperationGeneration(durableSessionId)
        val job = viewModelScope.launch {
            val origin = activeOrigin
            if (origin == null) {
                publishModelPickerError(requestGeneration, durableSessionId, "Hermes is not connected")
                return@launch
            }
            val originGeneration = generation
            try {
                val accessToken = if (activeRelayTarget != null) {
                    ""
                } else {
                    accessTokenForRequest(origin, originGeneration)
                        ?: throw HermesConnectionException("Sign in is required to select a model")
                }
                val session = ensureLiveSession(
                    origin = origin,
                    originGeneration = originGeneration,
                    operationGeneration = operationGeneration,
                    accessToken = accessToken,
                    durableSessionId = durableSessionId,
                )
                val runtimeSessionId = liveControllers[durableSessionId]?.runtimeSessionId
                    ?: throw HermesConnectionException("Hermes session is not ready")
                val options = session.loadModelOptions(runtimeSessionId)
                if (!isCurrentModelPicker(requestGeneration, durableSessionId)) return@launch
                val chat = mutableSnapshots.value.chatSessions[durableSessionId]
                val chatSelection = if (chat?.provider != null && chat.model != null) {
                    ModelSelection(chat.provider, chat.model)
                } else {
                    options.current
                }
                val capabilities = options.capabilitiesFor(chatSelection)
                if (capabilities != null && isCurrentModelPicker(requestGeneration, durableSessionId)) {
                    updateChat(durableSessionId) { current ->
                        current.copy(modelCapabilities = capabilities)
                    }
                }
                mutableModelPickerState.value = ModelPickerState.Ready(
                    durableSessionId = durableSessionId,
                    options = options,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                if (isCurrentModelPicker(requestGeneration, durableSessionId)) {
                    mutableModelPickerState.value = ModelPickerState.Closed
                    disconnectChat()
                    publishSignInRequired()
                }
            } catch (error: Exception) {
                publishModelPickerError(
                    requestGeneration,
                    durableSessionId,
                    error.message?.take(160) ?: "Could not load models",
                )
            }
        }
        modelPickerJob = job
        return job
    }

    fun retryModelPicker(): Job {
        val durableSessionId = when (val state = mutableModelPickerState.value) {
            is ModelPickerState.Error -> state.durableSessionId
            is ModelPickerState.Ready -> state.durableSessionId
            is ModelPickerState.Loading -> state.durableSessionId
            ModelPickerState.Closed -> return viewModelScope.launch { }
        }
        return openModelPicker(durableSessionId)
    }

    fun dismissModelPicker() {
        modelPickerGeneration += 1
        modelPickerJob?.cancel()
        modelPickerJob = null
        mutableModelPickerState.value = ModelPickerState.Closed
    }

    fun selectModel(selection: ModelSelection): Job = applyModelSelection(selection, confirmExpensive = false)

    fun confirmModelSelection(): Job {
        val selection = (mutableModelPickerState.value as? ModelPickerState.Ready)
            ?.pendingSelection
            ?: return viewModelScope.launch { }
        return applyModelSelection(selection, confirmExpensive = true)
    }

    private fun applyModelSelection(
        selection: ModelSelection,
        confirmExpensive: Boolean,
    ): Job {
        val state = mutableModelPickerState.value as? ModelPickerState.Ready
            ?: return viewModelScope.launch { }
        if (state.applying) return viewModelScope.launch { }
        val advertised = state.options.providers.any { provider ->
            provider.slug == selection.provider && selection.model in provider.models
        }
        if (!advertised) {
            mutableModelPickerState.value = state.copy(error = "That model is no longer available")
            return viewModelScope.launch { }
        }
        val controller = liveControllers[state.durableSessionId]
        val session = controller?.session
        val runtimeSessionId = controller?.runtimeSessionId
        if (session == null || runtimeSessionId == null) {
            mutableModelPickerState.value = state.copy(error = "Hermes session is not ready")
            return viewModelScope.launch { }
        }
        val requestGeneration = modelPickerGeneration
        mutableModelPickerState.value = state.copy(
            applying = true,
            error = null,
            pendingSelection = null,
            confirmationMessage = null,
        )
        val job = viewModelScope.launch {
            try {
                val result = session.setModel(
                    runtimeSessionId = runtimeSessionId,
                    provider = selection.provider,
                    model = selection.model,
                    confirmExpensiveModel = confirmExpensive,
                )
                val current = mutableModelPickerState.value as? ModelPickerState.Ready
                if (
                    requestGeneration != modelPickerGeneration ||
                    current?.durableSessionId != state.durableSessionId
                ) return@launch
                if (result.accepted) {
                    updateChat(state.durableSessionId) { chat ->
                        chat.copy(
                            model = selection.model,
                            provider = selection.provider,
                            modelCapabilities = state.options.capabilitiesFor(selection),
                            error = null,
                        )
                    }
                }
                mutableModelPickerState.value = when {
                    result.confirmationRequired -> current.copy(
                        applying = false,
                        pendingSelection = selection,
                        confirmationMessage = result.confirmationMessage
                            ?: "Hermes requires confirmation for this model.",
                    )
                    result.accepted -> ModelPickerState.Closed
                    else -> current.copy(applying = false, error = "Hermes did not accept the model change")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val current = mutableModelPickerState.value as? ModelPickerState.Ready
                if (
                    requestGeneration == modelPickerGeneration &&
                    current?.durableSessionId == state.durableSessionId
                ) {
                    mutableModelPickerState.value = current.copy(
                        applying = false,
                        error = error.message?.take(160) ?: "Could not change model",
                    )
                }
            }
        }
        modelPickerJob = job
        return job
    }

    private fun publishModelPickerError(
        requestGeneration: Long,
        durableSessionId: DurableSessionId,
        message: String,
    ) {
        if (!isCurrentModelPicker(requestGeneration, durableSessionId)) return
        mutableModelPickerState.value = ModelPickerState.Error(durableSessionId, message)
    }

    private fun isCurrentModelPicker(
        requestGeneration: Long,
        durableSessionId: DurableSessionId,
    ): Boolean {
        if (requestGeneration != modelPickerGeneration) return false
        return when (val state = mutableModelPickerState.value) {
            is ModelPickerState.Loading -> state.durableSessionId == durableSessionId
            is ModelPickerState.Ready -> state.durableSessionId == durableSessionId
            is ModelPickerState.Error -> state.durableSessionId == durableSessionId
            ModelPickerState.Closed -> false
        }
    }

    private fun serverDurableId(localId: DurableSessionId): DurableSessionId =
        serverDurableIds[localId] ?: localId

    private fun owningProfile(durableSessionId: DurableSessionId): String =
        mutableSnapshots.value.chatSessions[durableSessionId]?.owningProfile
            ?: cachedSummary(durableSessionId)?.profile
            ?: mutableSnapshots.value.selectedProfile

    private fun pinChatProfile(durableSessionId: DurableSessionId) {
        if (mutableSnapshots.value.chatSessions[durableSessionId]?.owningProfile != null) return
        val profile = owningProfile(durableSessionId)
        updateChat(durableSessionId) { it.copy(owningProfile = profile) }
    }

    private fun cachedSummary(durableSessionId: DurableSessionId): SessionSummary? =
        mutableSnapshots.value.durableSessions.firstOrNull { it.id == durableSessionId }
            ?: mutableSnapshots.value.projectSessions.values.asSequence()
                .flatten()
                .firstOrNull { it.id == durableSessionId }

    private fun localDraftSession(durableSessionId: DurableSessionId): SessionSummary? =
        mutableSnapshots.value.durableSessions.firstOrNull {
            it.id == durableSessionId && it.isLocalDraft
        } ?: mutableSnapshots.value.projectSessions.values
            .asSequence()
            .flatten()
            .firstOrNull { it.id == durableSessionId && it.isLocalDraft }

    private fun clearAllSlashCompletions() {
        slashCompletionJobs.values.forEach(Job::cancel)
        slashCompletionJobs.clear()
        slashCompletionGenerations.clear()
        mutableSlashCompletions.value = emptyMap()
    }

    private fun isExactControllerRuntime(
        durableSessionId: DurableSessionId,
        session: HermesChatSession,
        runtimeSessionId: RuntimeSessionId,
    ): Boolean = sessionControllerRegistry.isExactControllerRuntime(
        durableSessionId = durableSessionId,
        session = session,
        runtimeSessionId = runtimeSessionId,
        isPublishedControllerRuntime = { id, runtime ->
            mutableSnapshots.value.activeRuntimes.any {
                it.durableSessionId == id &&
                    it.runtimeSessionId == runtime &&
                    it.access == RuntimeAccess.Controller
            }
        },
    )

    private suspend fun loadProcessRowsIfCurrent(
        durableSessionId: DurableSessionId,
        session: HermesChatSession,
        runtimeSessionId: RuntimeSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ) {
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            !isExactControllerRuntime(durableSessionId, session, runtimeSessionId)
        ) return

        val identity = ProcessListIdentity(
            durableSessionId = durableSessionId,
            runtimeSessionId = runtimeSessionId,
            origin = origin.value,
            originGeneration = originGeneration,
            operationGeneration = operationGeneration,
        )
        val rows = try {
            // Optional activity metadata must never hold the composer hostage.
            // A local deadline keeps the previous snapshot and leaves chat attached.
            withTimeoutOrNull(5_000L) { session.loadProcessList(runtimeSessionId) } ?: return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A process snapshot is auxiliary activity. Keep the last successful
            // snapshot and never turn a missing optional method into chat failure.
            return
        }
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            !isExactControllerRuntime(durableSessionId, session, runtimeSessionId)
        ) return

        val current = mutableSnapshots.value.chatSessions[durableSessionId] ?: return
        val next = ProcessRowsState(
            durableSessionId = durableSessionId,
            runtimeSessionId = runtimeSessionId,
            origin = origin.value,
            originGeneration = originGeneration,
            operationGeneration = operationGeneration,
            rows = current.processRows,
        ).reduce(
            expected = identity,
            incoming = identity,
            rows = rows,
        )
        updateChat(durableSessionId) { chat ->
            if (chat.processRows == next.rows) chat else chat.copy(processRows = next.rows)
        }
    }

    private suspend fun ensureLiveSession(
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        accessToken: String,
        durableSessionId: DurableSessionId,
        closeWhenIdle: Boolean = false,
    ): HermesChatSession {
        val previousPhase = mutableSnapshots.value.chatSessions[durableSessionId]?.connectionPhase ?: ChatConnectionPhase.Idle
        if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
            updateChat(durableSessionId) { it.copy(connectionPhase = ChatConnectionPhase.Connecting) }
        }
        try {
            return withTimeoutOrNull(30_000L) {
                controllerConnectionLock(durableSessionId).withLock {
                    ensureLiveSessionLocked(origin, originGeneration, operationGeneration, accessToken, durableSessionId, closeWhenIdle)
                }
            } ?: throw HermesChatTransportException("Timed out connecting to session")
        } finally {
            if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                updateChat(durableSessionId) {
                    // Send retains its lock until staging/ack; open/control releases it.
                    if (previousPhase == ChatConnectionPhase.Idle) it.copy(connectionPhase = ChatConnectionPhase.Idle) else it
                }
            }
        }
    }

    private suspend fun ensureLiveSessionLocked(
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        accessToken: String,
        durableSessionId: DurableSessionId,
        closeWhenIdle: Boolean = false,
    ): HermesChatSession {
        currentCoroutineContext().ensureActive()
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
            throw CancellationException("Chat operation was replaced")
        }
        val existingController = sessionControllerRegistry.controller(durableSessionId)
        if (existingController != null) {
            val existing = existingController.session
            val runtimeId = existingController.runtimeSessionId
            sessionControllerRegistry.prepareExistingController(durableSessionId, operationGeneration)
            publishActiveRuntime(durableSessionId, runtimeId)
            collectEvents(
                existing,
                durableSessionId,
                runtimeId,
                origin,
                originGeneration,
                operationGeneration,
            )
            return existing
        }
        // Relay: every session owns its own lease channel on the host, so other
        // open sessions on this phone are never closed or blocked by this one.
        val relayTarget = activeRelayTarget
            ?.takeIf { activeOrigin == origin && generation == originGeneration }
        val connector = chatConnector
        if (relayTarget == null && connector == null) {
            throw HermesConnectionException("Live chat is unavailable")
        }
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
            throw CancellationException("Chat operation was replaced")
        }
        val session = if (relayTarget != null) {
            relaySessionFactory?.invoke(
                relayTarget,
                localDraftSession(durableSessionId)?.profile ?: mutableSnapshots.value.selectedProfile,
                RelayAdmissionEnvelope.channelForSession(durableSessionId.value),
            ) ?: throw HermesConnectionException("Mercury Relay chat is unavailable")
        } else {
            connector!!.connect(origin, accessToken)
        }
        try {
            currentCoroutineContext().ensureActive()
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                throw CancellationException("Chat operation was replaced")
            }
            val creatingDraft = durableSessionId in pendingDraftSessions
            val resumed = if (creatingDraft) {
                session.createSession(
                    durableSessionId = durableSessionId,
                    profile = owningProfile(durableSessionId),
                    workspacePath = localDraftSession(durableSessionId)
                        ?.workspacePath
                        ?.let(::validProjectWorkspacePath),
                )
            } else {
                session.resume(serverDurableId(durableSessionId), profile = owningProfile(durableSessionId))
            }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                throw CancellationException("Chat operation was replaced")
            }
            resumed.durableSessionId
                ?.takeIf { it != durableSessionId }
                ?.let { serverDurableIds[durableSessionId] = it }
            applyResume(durableSessionId, resumed)
            if (creatingDraft) {
                val draftSettings = mutableSnapshots.value.chatSessions[durableSessionId]
                if (draftSettings?.modelCapabilities?.fast == true && draftSettings.fastMode != null) {
                    try {
                        session.setFast(resumed.runtimeSessionId, draftSettings.fastMode == "fast")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A transient Fast-mode RPC failure must not orphan the draft
                        // runtime createSession just returned (draft creation uses
                        // close_on_disconnect=false): keep the controller below and
                        // let the explicit Fast control retry the setting.
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                throw CancellationException("Chat operation was replaced")
            }
            // Relay permits one admitted device socket. Keep its idle channel for
            // capability-gated image reads; opening a second reader would evict chat.
            if (closeWhenIdle && !resumed.running && relayTarget == null) {
                session.close()
                return session
            }
            sessionControllerRegistry.activateController(
                PerSessionController(
                    durableSessionId = durableSessionId,
                    session = session,
                    runtimeSessionId = resumed.runtimeSessionId,
                    operationGeneration = operationGeneration,
                    recoveryState = ChatRecoveryState(operationGeneration),
                ),
            )
            publishActiveRuntime(durableSessionId, resumed.runtimeSessionId)
            // A turn already running on the server (started by another client or before
            // an app restart) is an active turn from this client's perspective too: it
            // needs the foreground service so background completion/approval events can
            // be delivered without crashing and the working notification stays truthful.
            if (resumed.running) markTurnActive(durableSessionId)
            collectEvents(
                session,
                durableSessionId,
                resumed.runtimeSessionId,
                origin,
                originGeneration,
                operationGeneration,
            )
            return session
        } catch (error: Throwable) {
            closeChatSessionNonCancellably(session)
            throw error
        }
    }

    private fun markDraftPersisted(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ) {
        val wasDraft = pendingDraftSessions.remove(durableSessionId)
        if (wasDraft) promoteLocalDraftSummary(durableSessionId)
        refreshSessionsAfterFirstPrompt(
            durableSessionId,
            origin,
            originGeneration,
            operationGeneration,
        )
    }

    private fun reconcileCanonicalSessionMetadata(
        localId: DurableSessionId,
        canonical: SessionSummary,
    ) {
        val snapshot = mutableSnapshots.value
        fun reconcile(session: SessionSummary): SessionSummary =
            if (session.id == localId) {
                canonical.copy(
                    id = localId,
                    projectId = session.projectId,
                    workspacePath = canonical.workspacePath ?: session.workspacePath,
                    isLocalDraft = false,
                )
            } else {
                session
            }
        val projectSessions = snapshot.projectSessions.mapValues { (_, sessions) ->
            sessions.map(::reconcile)
        }
        val projectSessionStates = snapshot.projectSessionStates.mapValues { (_, state) ->
            if (state is ProjectSessionLoadState.Loaded) {
                state.copy(sessions = state.sessions.map(::reconcile))
            } else {
                state
            }
        }
        val canonicalDurable = canonical.copy(id = localId, isLocalDraft = false)
        val durableSessions = if (snapshot.durableSessions.any { it.id == localId }) {
            snapshot.durableSessions.map(::reconcile)
        } else {
            listOf(canonicalDurable) + snapshot.durableSessions
        }
        mutableSnapshots.value = snapshot.copy(
            durableSessions = durableSessions,
            projectSessions = projectSessions,
            projectSessionStates = projectSessionStates,
        )
    }

    private fun promoteLocalDraftSummary(durableSessionId: DurableSessionId) {
        val snapshot = mutableSnapshots.value
        fun promote(session: SessionSummary): SessionSummary =
            if (session.id == durableSessionId && session.isLocalDraft) {
                session.copy(isLocalDraft = false)
            } else {
                session
            }
        val durableSessions = snapshot.durableSessions.map(::promote)
        val projectSessions = snapshot.projectSessions.mapValues { (_, sessions) ->
            sessions.map(::promote)
        }
        val projectSessionStates = snapshot.projectSessionStates.mapValues { (_, state) ->
            when (state) {
                is ProjectSessionLoadState.Loaded -> state.copy(sessions = state.sessions.map(::promote))
                is ProjectSessionLoadState.TransientError,
                ProjectSessionLoadState.Loading,
                ProjectSessionLoadState.Unsupported,
                -> state
            }
        }
        mutableSnapshots.value = snapshot.copy(
            durableSessions = durableSessions,
            projectSessions = projectSessions,
            projectSessionStates = projectSessionStates,
        )
    }

    /**
     * Once the gateway accepts a draft's first prompt, its durable row exists server-side;
     * reload the transcript so the local draft converges with the canonical session.
     */
    private fun refreshSessionsAfterFirstPrompt(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ) {
        val profile = owningProfile(durableSessionId)
        viewModelScope.launch {
            val accessToken = try {
                accessTokenForRequest(origin, originGeneration)
            } catch (_: Exception) {
                null
            } ?: return@launch
            val canonicalSessions = try {
                client.authenticate(origin, accessToken).sessions
            } catch (_: Exception) {
                emptyList()
            }
            if (!isCurrentOrigin(origin, originGeneration)) return@launch
            val canonicalId = serverDurableId(durableSessionId)
            val canonical = canonicalSessions.firstOrNull { it.id == canonicalId }
            if (canonical != null) {
                reconcileCanonicalSessionMetadata(durableSessionId, canonical)
            }
            val messages = try {
                client.loadTranscript(origin, accessToken, canonicalId, profile)
            } catch (_: Exception) {
                return@launch
            }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return@launch
            updateChat(durableSessionId) { current ->
                if (current.messages.isEmpty()) {
                    current.copy(messages = messages)
                } else {
                    current
                }
            }
        }
    }

    private fun collectEvents(
        session: HermesChatSession,
        durableSessionId: DurableSessionId,
        runtimeSessionId: RuntimeSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        previousRuntimeSessionId: RuntimeSessionId = runtimeSessionId,
    ) {
        val controller = sessionControllerRegistry.controller(durableSessionId) ?: return
        if (controller.session !== session || controller.eventJob?.isActive == true) return
        session.relayLeaseSnapshot?.let { snapshot ->
            updateChat(durableSessionId) { current ->
                current.copy(backgroundTasks = current.backgroundTasks.recoverRelayTasks(
                    snapshot, serverDurableId(durableSessionId).value,
                    mutableSnapshots.value.selectedProfile, runtimeSessionId,
                ))
            }
        }
        if (mutableSnapshots.value.chatSessions[durableSessionId]?.backgroundTasks?.rows?.isNotEmpty() == true) {
            viewModelScope.launch {
                try {
                    val status = session.loadDelegationStatus()
                    if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) &&
                        liveControllers[durableSessionId]?.session === session) {
                        updateChat(durableSessionId) {
                            it.copy(backgroundTasks = it.backgroundTasks.reconcile(status, runtimeSessionId, previousRuntimeSessionId))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain last known rows; absence is not success. */ }
            }
        }
        val eventJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            session.events.catch { error ->
                if (error is CancellationException) throw error
                // An exceptional close is the same ownership fence as normal EOF.
            }.collect { event ->
                val operationGeneration = controller.operationGeneration
                if (
                    !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
                    event.sessionId != runtimeSessionId ||
                    liveControllers[durableSessionId]?.session !== session
                ) {
                    (event as? com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskEvent)?.let {
                        session.acknowledgeRelayEvent(it.eventId)
                    }
                    return@collect
                }
                when (event) {
                    is com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskEvent -> {
                        updateChat(durableSessionId) {
                            it.copy(backgroundTasks = it.backgroundTasks.reduce(event, runtimeSessionId, System.currentTimeMillis()))
                        }
                        session.acknowledgeRelayEvent(event.eventId)
                    }
                    is HermesChatEvent.MessageStart,
                    is HermesChatEvent.MessageDelta,
                    is HermesChatEvent.ReasoningDelta,
                    is HermesChatEvent.MessageInterim,
                    -> updateChat(durableSessionId) { it.applyTranscriptEvent(event) }
                    is HermesChatEvent.ToolGenerating -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.SessionTitle -> updateSessionTitle(
                        durableSessionId,
                        event.title,
                    )
                    is HermesChatEvent.SessionInfo -> {
                        event.storedSessionId
                            ?.takeIf { it != durableSessionId }
                            ?.let { serverDurableIds[durableSessionId] = it }
                        updateChat(durableSessionId) { current ->
                            current.copy(
                                model = event.model ?: current.model,
                                provider = event.provider ?: current.provider,
                                reasoningEffort = event.reasoningEffort ?: current.reasoningEffort,
                                fastMode = event.fastMode?.let { if (it) "fast" else "normal" } ?: current.fastMode,
                                isSending = event.running ?: current.isSending,
                            )
                        }
                        event.title?.takeIf(String::isNotBlank)?.let { title ->
                            updateSessionTitle(durableSessionId, title)
                        }
                    }
                    is HermesChatEvent.MessageComplete -> {
                        updateChat(durableSessionId) { it.applyTranscriptEvent(event) }
                        val billingNotice = event.billing?.let { billing ->
                            ChatBillingNotice(
                                provider = billing.provider,
                                billingUrl = billing.billingUrl,
                                isNous = billing.isNous,
                                message = billing.message ?: event.failureReason,
                            )
                        }
                        val terminalError = when (event.status?.lowercase()) {
                            "error", "failed" -> if (billingNotice != null) {
                                null
                            } else {
                                "Hermes response failed"
                            }
                            "cancelled", "canceled", "interrupted" ->
                                "Hermes response was cancelled"
                            else -> if (event.error.isNullOrBlank()) {
                                null
                            } else {
                                "Hermes response failed"
                            }
                        }
                        when (val terminalEffect = sessionControllerRegistry.observePromptTerminal(durableSessionId)) {
                            is PromptSubmissionLifecycle.TerminalEffect.ReleasedPending ->
                                acknowledgeTerminalSubmission(durableSessionId, terminalEffect)
                            PromptSubmissionLifecycle.TerminalEffect.Ignored -> Unit
                        }
                        updateChat(durableSessionId) {
                            it.copy(
                                isSending = false,
                                connectionPhase = ChatConnectionPhase.Idle,
                                error = terminalError,
                                notice = event.warning?.takeIf(String::isNotBlank),
                                billingNotice = billingNotice,
                                runState = it.runState.reduce(event),
                            )
                        }
                        notifications.turnCompleted(
                            durableSessionId,
                            sessionTitle(durableSessionId),
                            event.text.orEmpty(),
                            event.status,
                        )
                        // The controller remains connected after a normal completion, so the
                        // runtime marker is retained (maintenance stays available while idle).
                        // It is removed when the controller is detached (error, stop, or
                        // event-stream end below) or on disconnect.
                        markDraftPersisted(
                            durableSessionId,
                            origin,
                            originGeneration,
                            operationGeneration,
                        )
                    }
                    is HermesChatEvent.Error -> {
                        when (val terminalEffect = sessionControllerRegistry.observePromptTerminal(durableSessionId)) {
                            is PromptSubmissionLifecycle.TerminalEffect.ReleasedPending -> {
                                acknowledgeTerminalSubmission(durableSessionId, terminalEffect)
                                markDraftPersisted(
                                    durableSessionId,
                                    origin,
                                    originGeneration,
                                    operationGeneration,
                                )
                            }
                            PromptSubmissionLifecycle.TerminalEffect.Ignored -> Unit
                        }
                        updateChat(durableSessionId) {
                            it.applyTranscriptEvent(event).copy(
                                isSending = false,
                                connectionPhase = ChatConnectionPhase.Idle,
                                error = event.message.take(160),
                                runState = it.runState.reduce(event),
                            )
                        }
                        removeActiveRuntime(runtimeSessionId)
                        detachController(durableSessionId, session, closeSession = true)
                    }
                    is HermesChatEvent.ToolStart -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.ToolComplete -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.StatusUpdate -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.ClarifyRequest -> {
                        updateRunState(durableSessionId, event)
                        notifications.clarificationRequired(
                            durableSessionId,
                            sessionTitle(durableSessionId),
                            event.question,
                        )
                    }
                    is HermesChatEvent.ClarifyExpire -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.ApprovalRequest -> {
                        updateRunState(durableSessionId, event)
                        notifications.approvalRequired(
                            durableSessionId,
                            sessionTitle(durableSessionId),
                            event.description ?: event.command ?: NotificationTextPolicy.APPROVAL_FALLBACK,
                        )
                    }
                    is HermesChatEvent.ApprovalExpire -> updateRunState(durableSessionId, event)
                    is HermesChatEvent.UnsupportedBlockingRequest -> {
                        updateRunState(durableSessionId, event)
                        if (event.kind == UnsupportedBlockingKind.TerminalRead ||
                            event.kind == UnsupportedBlockingKind.PreviewRead ||
                            event.kind == UnsupportedBlockingKind.WindowRead
                        ) {
                            // Android owns none of Desktop's terminal/preview/window surfaces.
                            // The released bridge contract defines an empty response as unavailable.
                            respondToBlockingPrompt(durableSessionId, event.kind, event.requestId, "")
                        } else {
                            notifications.unsupportedInputRequired(
                                durableSessionId,
                                sessionTitle(durableSessionId),
                                event.prompt ?: NotificationTextPolicy.SECURE_INPUT_FALLBACK,
                            )
                        }
                    }
                    is HermesChatEvent.UnsupportedBlockingExpire -> updateRunState(durableSessionId, event)
                }
            }
            val operationGeneration = controller.operationGeneration
            val recoverBackground = activeRelayTarget != null &&
                mutableSnapshots.value.chatSessions[durableSessionId]?.backgroundTasks?.rows?.any { !it.terminal } == true
            if (liveControllers[durableSessionId]?.session === session) {
                updateChat(durableSessionId) { it.copy(backgroundTasks = it.backgroundTasks.unavailable()) }
                removeActiveRuntime(runtimeSessionId)
                if (mutableSnapshots.value.chatSessions[durableSessionId]?.isSending != true && !recoverBackground) {
                    sessionControllerRegistry.replaceControllerForRecovery(durableSessionId, session)
                    closeChatSessionNonCancellably(session)
                }
            }
            if (
                isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) &&
                (mutableSnapshots.value.chatSessions[durableSessionId]?.isSending == true || recoverBackground)
            ) {
                val recoveryAttempt = startChatRecovery(durableSessionId, operationGeneration)
                if (recoveryAttempt != null) {
                    try {
                        recoverChat(
                            durableSessionId,
                            origin,
                            originGeneration,
                            operationGeneration,
                            recoveryAttempt,
                        )
                    } finally {
                        finishChatRecovery(recoveryAttempt)
                    }
                } else if (!isChatRecoveryInProgress(durableSessionId, operationGeneration)) {
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) {
                        it.copy(error = "Connection lost while receiving response")
                    }
                }
            }
        }
        sessionControllerRegistry.setEventJob(durableSessionId, session, eventJob)
        eventJob.start()
    }

    private fun startChatRecovery(
        durableSessionId: DurableSessionId,
        operationGeneration: Long,
    ): ChatRecoveryAttempt? = sessionControllerRegistry.startRecovery(
        durableSessionId,
        operationGeneration,
    )

    private fun finishChatRecovery(attempt: ChatRecoveryAttempt) {
        sessionControllerRegistry.finishRecovery(attempt)
    }

    private fun isChatRecoveryInProgress(
        durableSessionId: DurableSessionId,
        operationGeneration: Long,
    ): Boolean = sessionControllerRegistry.isRecoveryInProgress(
        durableSessionId,
        operationGeneration,
    )

    private suspend fun recoverChat(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        recoveryAttempt: ChatRecoveryAttempt,
    ) {
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
        updateChat(durableSessionId) { it.copy(connectionPhase = ChatConnectionPhase.Reconnecting) }
        val recoveryJob = checkNotNull(currentCoroutineContext()[Job])
        sessionControllerRegistry.setRecoveryJob(durableSessionId, recoveryJob)
        try {
            controllerConnectionLock(durableSessionId).withLock {
                recoverChatLocked(durableSessionId, origin, originGeneration, operationGeneration, recoveryAttempt)
            }
        } finally {
            sessionControllerRegistry.clearRecoveryJob(durableSessionId, recoveryJob)
            if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                updateChat(durableSessionId) { it.copy(connectionPhase = ChatConnectionPhase.Idle) }
            }
        }
    }

    private suspend fun recoverChatLocked(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
        recoveryAttempt: ChatRecoveryAttempt,
    ) {
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
        val relayTarget = activeRelayTarget
        val connector = chatConnector
        if (relayTarget == null && connector == null) return
        val profile = owningProfile(durableSessionId)
        val previous = sessionControllerRegistry.controller(durableSessionId)
        if (previous != null) {
            sessionControllerRegistry.replaceControllerForRecovery(durableSessionId, previous.session)
            removeActiveRuntime(previous.runtimeSessionId)
            closeChatSessionNonCancellably(previous.session)
        }
        clearProcessRows(durableSessionId)

        for (backoffMillis in listOf(500L, 1_000L, 2_000L)) {
            // Offline time is not a failed attempt. Keep one suspended owner;
            // Android's validated default-network callback wakes it on return.
            // Direct mode retains its existing recovery contract.
            if (relayTarget != null) relayNetworkAvailable.first { it }
            appForegroundStates.first { it }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
            delay(backoffMillis)
            if (relayTarget != null) relayNetworkAvailable.first { it }
            appForegroundStates.first { it }
            if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
            var candidate: HermesChatSession? = null
            try {
                val token = if (relayTarget != null) "" else {
                    accessTokenForRequest(origin, originGeneration)
                        ?: throw HermesConnectionException("Sign in is required to reconnect")
                }
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
                candidate = if (relayTarget != null) {
                    relaySessionFactory?.invoke(
                        relayTarget,
                        mutableSnapshots.value.selectedProfile,
                        RelayAdmissionEnvelope.channelForSession(durableSessionId.value),
                    ) ?: throw HermesConnectionException("Mercury Relay chat is unavailable")
                } else {
                    connector!!.connect(origin, token)
                }
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    closeChatSessionNonCancellably(candidate)
                    return
                }
                // An open socket is not proof that resume will ever answer. Keep
                // each reconciliation attempt bounded; never replay prompt.submit.
                val recoverySnapshot = candidate.relayLeaseSnapshot
                if (recoverySnapshot != null && !recoverySnapshot.hasLiveBinding(
                        serverDurableId(durableSessionId).value, profile)) {
                    // Missing retained ownership is not permission to take over another
                    // runtime. Reconcile durable evidence; only a later explicit Send
                    // or Open may resume/create control after lease/process loss.
                    val transcript = withTimeoutOrNull(30_000L) {
                        candidate.relayRequest(
                            "relay.session.transcript", buildJsonObject {
                                put("profile", profile)
                                put("session_id", serverDurableId(durableSessionId).value)
                                put("limit", 100); put("offset", 0); put("order", "latest")
                            },
                        )
                    } ?: throw HermesChatTransportException("Timed out reading recovery transcript")
                    val messages = parseRelayTranscriptRows(transcript)
                    if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                        closeChatSessionNonCancellably(candidate)
                        return
                    }
                    clearSendingState(durableSessionId)
                    updateChat(durableSessionId) { current -> current.copy(
                        messages = messages,
                        backgroundTasks = current.backgroundTasks.recoverRelayTasks(
                            recoverySnapshot, serverDurableId(durableSessionId).value, profile),
                        error = "Live session ownership was lost. Background status shows only recorded evidence.",
                    ) }
                    closeChatSessionNonCancellably(candidate)
                    return
                }
                val resumed = withTimeoutOrNull(30_000L) {
                    candidate.resume(serverDurableId(durableSessionId), profile = profile)
                } ?: throw HermesChatTransportException("Timed out resuming session")
                if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    closeChatSessionNonCancellably(candidate)
                    return
                }
                markDraftPersisted(
                    durableSessionId,
                    origin,
                    originGeneration,
                    operationGeneration,
                )
                applyResume(durableSessionId, resumed)
                if (resumed.running || relayTarget != null) {
                    sessionControllerRegistry.activateController(
                        PerSessionController(
                            durableSessionId = durableSessionId,
                            session = candidate,
                            runtimeSessionId = resumed.runtimeSessionId,
                            operationGeneration = operationGeneration,
                            recoveryState = recoveryAttempt.state,
                        ),
                    )
                    publishActiveRuntime(durableSessionId, resumed.runtimeSessionId)
                    if (resumed.running) markTurnActive(durableSessionId)
                    finishChatRecovery(recoveryAttempt)
                    collectEvents(
                        candidate,
                        durableSessionId,
                        resumed.runtimeSessionId,
                        origin,
                        originGeneration,
                        operationGeneration,
                        previousRuntimeSessionId = previous?.runtimeSessionId ?: resumed.runtimeSessionId,
                    )
                } else {
                    val messages = if (relayTarget != null) {
                        parseRelayTranscriptRows(candidate.relayRequest(
                            "relay.session.transcript",
                            buildJsonObject {
                                put("profile", mutableSnapshots.value.selectedProfile)
                                put("session_id", serverDurableId(durableSessionId).value)
                                put("limit", 100)
                                put("offset", 0)
                                put("order", "latest")
                            },
                        ))
                    } else {
                        client.loadTranscript(origin, token, serverDurableId(durableSessionId), profile)
                    }
                    if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                        closeChatSessionNonCancellably(candidate)
                        return
                    }
                    updateChat(durableSessionId) {
                        it.copy(messages = messages, isSending = false, error = null)
                    }
                    closeChatSessionNonCancellably(candidate)
                }
                return
            } catch (cancelled: CancellationException) {
                closeChatSessionNonCancellably(candidate)
                throw cancelled
            } catch (_: NativeRefreshExpiredException) {
                closeChatSessionNonCancellably(candidate)
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
                return
            } catch (_: HermesChatUnauthorizedException) {
                // Ticket/connect 401 during recovery: the session is terminally
                // rejected. Stop the backoff loop and drop to sign-in instead of
                // retrying a credential the server will never accept.
                closeChatSessionNonCancellably(candidate)
                if (isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) {
                    disconnectChat()
                    publishSignInRequired()
                }
                return
            } catch (_: Exception) {
                closeChatSessionNonCancellably(candidate)
            }
        }

        appForegroundStates.first { it }
        if (!isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration)) return
        clearSendingState(durableSessionId)
        updateChat(durableSessionId) {
            it.copy(
                error = "Connection lost while receiving response",
            )
        }
    }

    private fun applyResume(durableSessionId: DurableSessionId, resumed: ResumedChatSession) {
        val resumedMessages = resumed.messages.mapNotNull(::chatMessageFromJson)
        updateChat(durableSessionId) { current ->
            val baseMessages = if (resumedMessages.isNotEmpty()) resumedMessages else current.messages
            val withInflightUser = resumed.inflight?.user
                ?.takeIf(String::isNotBlank)
                ?.let { prompt ->
                    val messages = baseMessages.toMutableList()
                    val latestUser = messages.indexOfLast { it.role == ChatMessageRole.User }
                    if (latestUser < 0 || messages[latestUser].text != prompt) {
                        messages += ChatMessage(ChatMessageRole.User, prompt)
                    }
                    messages
                }
                ?: baseMessages
            val withInflight = resumed.inflight?.assistant?.let { partial ->
                val messages = withInflightUser.toMutableList()
                val localPartialIndex = messages.indexOfLast {
                    it.role == ChatMessageRole.Assistant && it.isStreaming
                }
                val snapshot = ChatMessage(
                    role = ChatMessageRole.Assistant,
                    text = partial,
                    isStreaming = true,
                )
                if (localPartialIndex >= 0) {
                    messages[localPartialIndex] = snapshot
                } else {
                    messages += snapshot
                }
                messages
            } ?: withInflightUser
            current.copy(
                messages = withInflight,
                isLoading = false,
                isSending = resumed.running,
                error = null,
                model = resumed.model ?: current.model,
                provider = resumed.provider ?: current.provider,
                reasoningEffort = resumed.reasoningEffort ?: current.reasoningEffort,
                modelCapabilities = resumed.model
                    ?.takeIf(String::isNotBlank)
                    ?.let { model ->
                        resumed.provider?.takeIf(String::isNotBlank)?.let { provider ->
                            val snapshot = mutableSnapshots.value
                            resolveModelCapabilities(
                                currentInfo = snapshot.currentModelInfo,
                                options = snapshot.defaultModelOptions,
                                selection = ModelSelection(provider, model),
                            )
                        }
                    }
                    ?: current.modelCapabilities,
            )
        }
        // A retained idle runtime is still a controller, but not an active turn.
        // Replay intentionally omits foreground events; authoritative running=false
        // must provide the missing terminal fence without another explicit Send.
        if (activeRelayTarget != null && !resumed.running) clearSendingState(durableSessionId)
    }

    private fun updateSessionTitle(
        durableSessionId: DurableSessionId,
        title: String,
    ) {
        val clean = title.trim().take(MAX_SESSION_TITLE_CHARS)
        if (clean.isEmpty()) return
        val snapshot = mutableSnapshots.value
        val sessions = snapshot.durableSessions.map { session ->
            if (session.id == durableSessionId) session.copy(title = clean) else session
        }
        val projectSessions = snapshot.projectSessions.mapValues { (_, entries) ->
            entries.map { session ->
                if (session.id == durableSessionId) session.copy(title = clean) else session
            }
        }
        mutableSnapshots.value = snapshot.copy(
            durableSessions = sessions,
            projectSessions = projectSessions,
        )
    }

    private fun updateRunState(
        durableSessionId: DurableSessionId,
        event: HermesChatEvent,
    ) {
        updateChat(durableSessionId) { current ->
            current.copy(runState = current.runState.reduce(event))
        }
    }

    private fun beginClarificationResponse(
        durableSessionId: DurableSessionId,
        requestId: String,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val session = controller.session
        val runtimeSessionId = controller.runtimeSessionId
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        val clarification = chat.runState.clarification ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            clarification.runtimeSessionId != runtimeSessionId ||
            clarification.requestId != requestId ||
            clarification.lifecycle != RunInteractionLifecycle.Pending
        ) return@synchronized null

        val updatedChat = chat.copy(
            runState = chat.runState.transitionClarificationLifecycle(
                requestId,
                RunInteractionLifecycle.Responding,
            ),
        )
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (durableSessionId to updatedChat),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = session,
            runtimeSessionId = runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
            requestId = requestId,
        )
    }

    private fun beginBlockingResponse(
        durableSessionId: DurableSessionId,
        kind: UnsupportedBlockingKind,
        requestId: String,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        val interaction = chat.runState.unsupportedBlocking ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            interaction.runtimeSessionId != controller.runtimeSessionId ||
            interaction.kind != kind ||
            interaction.requestId != requestId ||
            interaction.lifecycle != RunInteractionLifecycle.Pending
        ) return@synchronized null

        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (
                durableSessionId to chat.copy(
                    runState = chat.runState.transitionUnsupportedBlockingLifecycle(
                        controller.runtimeSessionId,
                        kind,
                        requestId,
                        RunInteractionLifecycle.Responding,
                    ),
                )
                ),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = controller.session,
            runtimeSessionId = controller.runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
            requestId = requestId,
            blockingKind = kind,
        )
    }

    private fun beginApprovalResponse(
        durableSessionId: DurableSessionId,
        choice: String,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val session = controller.session
        val runtimeSessionId = controller.runtimeSessionId
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        val approval = chat.runState.approval ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            approval.runtimeSessionId != runtimeSessionId ||
            approval.lifecycle != RunInteractionLifecycle.Pending ||
            choice !in approval.choices
        ) return@synchronized null

        val updatedChat = chat.copy(
            runState = chat.runState.transitionApprovalLifecycle(
                runtimeSessionId,
                approval.requestId,
                RunInteractionLifecycle.Responding,
            ),
        )
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (durableSessionId to updatedChat),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = session,
            runtimeSessionId = runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
            requestId = approval.requestId,
            advertisedChoices = approval.choices,
        )
    }

    private fun beginStopSession(
        durableSessionId: DurableSessionId,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val session = controller.session
        val runtimeSessionId = controller.runtimeSessionId
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            !chat.isSending ||
            chat.isStopping ||
            snapshot.activeRuntimes.none {
                it.runtimeSessionId == runtimeSessionId &&
                    it.durableSessionId == durableSessionId &&
                    it.access == RuntimeAccess.Controller
            }
        ) return@synchronized null

        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (
                durableSessionId to chat.copy(isStopping = true)
                ),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = session,
            runtimeSessionId = runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
        )
    }

    private fun beginSteerSession(
        durableSessionId: DurableSessionId,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            !chat.isSending ||
            chat.isStopping ||
            snapshot.activeRuntimes.none {
                it.runtimeSessionId == controller.runtimeSessionId &&
                    it.durableSessionId == durableSessionId &&
                    it.access == RuntimeAccess.Controller
            }
        ) return@synchronized null
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = controller.session,
            runtimeSessionId = controller.runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
        )
    }

    private fun publishSteerSuccess(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isSending || chat.isStopping) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        error = null,
                        notice = "Guidance queued for the active turn",
                    )
                    ),
            )
        }
    }

    private fun publishSteerFailure(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isSending || chat.isStopping) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        error = "Could not steer active turn",
                        notice = null,
                    )
                    ),
            )
        }
    }

    private fun beginSessionInsights(
        durableSessionId: DurableSessionId,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        val snapshot = mutableSnapshots.value
        if (
            snapshot.chatSessions[durableSessionId] == null ||
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            snapshot.activeRuntimes.none {
                it.runtimeSessionId == controller.runtimeSessionId &&
                    it.durableSessionId == durableSessionId &&
                    it.access == RuntimeAccess.Controller
            }
        ) return@synchronized null
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = controller.session,
            runtimeSessionId = controller.runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
        )
    }

    private fun publishSessionInsights(
        operation: ControllerOperation,
        requestGeneration: Long,
        usage: SessionUsage,
        context: SessionContextBreakdown,
    ) {
        synchronized(controllerLock) {
            if (
                sessionInsightsGenerations[operation.durableSessionId] != requestGeneration ||
                !isCurrentControllerOperation(operation)
            ) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        sessionUsage = usage,
                        contextBreakdown = context,
                        insightsLoading = false,
                        insightsError = null,
                    )
                    ),
            )
        }
    }

    private fun publishSessionInsightsFailure(
        operation: ControllerOperation,
        requestGeneration: Long,
    ) {
        synchronized(controllerLock) {
            if (
                sessionInsightsGenerations[operation.durableSessionId] != requestGeneration ||
                !isCurrentControllerOperation(operation)
            ) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        insightsLoading = false,
                        insightsError = "Could not load session details",
                    )
                    ),
            )
        }
    }

    private fun beginDelegationControl(
        durableSessionId: DurableSessionId,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        val snapshot = mutableSnapshots.value
        if (
            snapshot.delegationStatus.actionLoading ||
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            snapshot.activeRuntimes.none {
                it.runtimeSessionId == controller.runtimeSessionId &&
                    it.durableSessionId == durableSessionId &&
                    it.access == RuntimeAccess.Controller
            }
        ) return@synchronized null
        mutableSnapshots.value = snapshot.copy(
            delegationStatus = snapshot.delegationStatus.copy(
                actionLoading = true,
                error = null,
                notice = null,
            ),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = controller.session,
            runtimeSessionId = controller.runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
        )
    }

    private fun publishDelegationSuccess(
        operation: ControllerOperation,
        notice: String,
        paused: Boolean? = null,
        removeSubagentId: String? = null,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            if (!snapshot.delegationStatus.actionLoading) return
            mutableSnapshots.value = snapshot.copy(
                delegationStatus = snapshot.delegationStatus.copy(
                    active = removeSubagentId?.let { id ->
                        snapshot.delegationStatus.active.filterNot { it.subagentId == id }
                    } ?: snapshot.delegationStatus.active,
                    paused = paused ?: snapshot.delegationStatus.paused,
                    notice = notice,
                    actionLoading = false,
                    error = null,
                ),
            )
        }
    }

    private fun publishDelegationFailure(operation: ControllerOperation, message: String) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            if (!snapshot.delegationStatus.actionLoading) return
            mutableSnapshots.value = snapshot.copy(
                delegationStatus = snapshot.delegationStatus.copy(
                    notice = null,
                    actionLoading = false,
                    error = message.take(160),
                ),
            )
        }
    }

    private fun clearDelegationLoadingIfCurrent(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            if (!snapshot.delegationStatus.actionLoading) return
            mutableSnapshots.value = snapshot.copy(
                delegationStatus = snapshot.delegationStatus.copy(actionLoading = false),
            )
        }
    }

    private fun beginMaintenanceSession(
        durableSessionId: DurableSessionId,
    ): ControllerOperation? = synchronized(controllerLock) {
        val origin = activeOrigin ?: return@synchronized null
        val controller = liveControllers[durableSessionId] ?: return@synchronized null
        val operationGeneration = controller.operationGeneration
        val originGeneration = generation
        val snapshot = mutableSnapshots.value
        val chat = snapshot.chatSessions[durableSessionId] ?: return@synchronized null
        if (
            !isCurrentChatOperation(durableSessionId, origin, originGeneration, operationGeneration) ||
            chat.isSending ||
            chat.isStopping ||
            chat.maintenanceLoading
        ) return@synchronized null
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (
                durableSessionId to chat.copy(
                    maintenanceLoading = true,
                    maintenanceError = null,
                    notice = null,
                )
                ),
        )
        ControllerOperation(
            durableSessionId = durableSessionId,
            session = controller.session,
            runtimeSessionId = controller.runtimeSessionId,
            origin = origin,
            originGeneration = originGeneration,
            chatOperationGeneration = operationGeneration,
        )
    }

    private fun publishCompressedSession(
        operation: ControllerOperation,
        rows: List<JsonObject>,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.maintenanceLoading) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        messages = rows.mapNotNull(::chatMessageFromJson),
                        maintenanceLoading = false,
                        maintenanceError = null,
                        notice = "Context compressed",
                    )
                    ),
            )
        }
    }

    private fun publishUndoneSession(
        operation: ControllerOperation,
        messages: List<ChatMessage>,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.maintenanceLoading) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        messages = messages,
                        maintenanceLoading = false,
                        maintenanceError = null,
                        notice = "Last turn undone",
                    )
                    ),
            )
        }
    }

    private fun publishBranchedSession(
        operation: ControllerOperation,
        result: SessionBranchResult,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val parentChat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!parentChat.maintenanceLoading) return
            val parentSummary = snapshot.durableSessions.firstOrNull { it.id == operation.durableSessionId }
                ?: snapshot.projectSessions.values.asSequence().flatten()
                    .firstOrNull { it.id == operation.durableSessionId }
            val title = result.title?.takeIf(String::isNotBlank) ?: "Branched session"
            val branchSummary = parentSummary?.copy(
                id = result.durableSessionId,
                title = title,
                isLocalDraft = false,
                messageCount = result.messages.size,
            ) ?: SessionSummary(
                id = result.durableSessionId,
                title = title,
                messageCount = result.messages.size,
                profile = mutableSnapshots.value.selectedProfile,
            )
            val updatedProjectSessions = branchSummary.projectId?.let { projectId ->
                snapshot.projectSessions + (
                    projectId to (
                        snapshot.projectSessions[projectId].orEmpty()
                            .filterNot { it.id == branchSummary.id } + branchSummary
                        )
                    )
            } ?: snapshot.projectSessions
            mutableSnapshots.value = snapshot.copy(
                durableSessions = snapshot.durableSessions.filterNot { it.id == branchSummary.id } + branchSummary,
                projectSessions = updatedProjectSessions,
                chatSessions = snapshot.chatSessions +
                    (operation.durableSessionId to parentChat.copy(
                        maintenanceLoading = false,
                        maintenanceError = null,
                        notice = "Session branched",
                    )) +
                    (result.durableSessionId to ChatSessionSnapshot(
                        messages = result.messages.mapNotNull(::chatMessageFromJson),
                        owningProfile = owningProfile(operation.durableSessionId),
                    )),
                lastBranchedSessionId = result.durableSessionId,
            )
        }
    }

    private fun publishMaintenanceFailure(operation: ControllerOperation, message: String) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.maintenanceLoading) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        maintenanceLoading = false,
                        maintenanceError = message.take(160),
                    )
                    ),
            )
        }
    }

    private fun clearMaintenanceIfCurrent(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.maintenanceLoading) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(maintenanceLoading = false)
                    ),
            )
        }
    }

    private fun publishStopSuccess(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isStopping) return
            val messages = chat.messages.mapNotNull { message ->
                when {
                    message.role == ChatMessageRole.Assistant &&
                        message.isStreaming &&
                        message.text.isEmpty() -> null
                    message.isStreaming -> message.copy(isStreaming = false)
                    else -> message
                }
            }
            mutableSnapshots.value = snapshot.copy(
                activeRuntimes = snapshot.activeRuntimes.filterNot {
                    it.runtimeSessionId == operation.runtimeSessionId
                },
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        messages = messages,
                        isLoading = false,
                        isSending = false,
                        isStopping = false,
                        error = null,
                        runState = terminalizeLiveInteractions(chat.runState),
                    )
                    ),
            )
        }
    }

    private fun publishStopFailure(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isStopping) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        isStopping = false,
                        error = "Could not stop session",
                    )
                    ),
            )
        }
    }

    private fun clearStoppingIfCurrent(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            if (!chat.isStopping) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(isStopping = false)
                    ),
            )
        }
    }

    private fun terminalizeLiveInteractions(runState: RunEventState): RunEventState = runState.copy(
        clarification = runState.clarification?.let { interaction ->
            if (interaction.lifecycle == RunInteractionLifecycle.Pending ||
                interaction.lifecycle == RunInteractionLifecycle.Responding
            ) interaction.copy(lifecycle = RunInteractionLifecycle.Expired) else interaction
        },
        approval = runState.approval?.let { interaction ->
            if (interaction.lifecycle == RunInteractionLifecycle.Pending ||
                interaction.lifecycle == RunInteractionLifecycle.Responding
            ) interaction.copy(lifecycle = RunInteractionLifecycle.Expired) else interaction
        },
        unsupportedBlocking = runState.unsupportedBlocking?.let { interaction ->
            if (interaction.lifecycle == RunInteractionLifecycle.Pending ||
                interaction.lifecycle == RunInteractionLifecycle.Responding
            ) interaction.copy(lifecycle = RunInteractionLifecycle.Expired) else interaction
        },
    )

    private fun isCurrentControllerOperation(operation: ControllerOperation): Boolean =
        isCurrentChatOperation(
            operation.durableSessionId,
            operation.origin,
            operation.originGeneration,
            operation.chatOperationGeneration,
        ) &&
            liveControllers[operation.durableSessionId]?.let { controller ->
                controller.session === operation.session &&
                    controller.runtimeSessionId == operation.runtimeSessionId &&
                    controller.operationGeneration == operation.chatOperationGeneration
            } == true

    private fun publishClarificationResponse(
        operation: ControllerOperation,
        lifecycle: RunInteractionLifecycle,
        questionId: String? = null,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val current = chat.runState.clarification ?: return
            if (
                current.runtimeSessionId != operation.runtimeSessionId ||
                current.requestId != operation.requestId ||
                current.lifecycle != RunInteractionLifecycle.Responding
            ) return
            // A successful answer clears the card immediately: HAM's clarification
            // is transient run state (not a persisted transcript card like the
            // desktop settled Q&A), so it should disappear the moment the user
            // responds rather than lingering until the next turn. A failed send
            // reverts to Pending so the user can retry; an expiry keeps the
            // informational settled state.
            // A batch stays on screen until its last question is answered; the
            // host only resolves the request then.
            val remainingAfterAnswer = questionId != null && current.isBatch &&
                current.questions.any { it.qid != questionId && it.qid !in current.answeredQuestionIds }
            val nextRunState = when (lifecycle) {
                RunInteractionLifecycle.Resolved ->
                    if (remainingAfterAnswer) {
                        chat.runState.markClarificationQuestionAnswered(checkNotNull(operation.requestId), checkNotNull(questionId))
                    } else {
                        chat.runState.copy(clarification = null)
                    }
                RunInteractionLifecycle.Failed ->
                    chat.runState.transitionClarificationLifecycle(
                        checkNotNull(operation.requestId),
                        RunInteractionLifecycle.Pending,
                    )
                else ->
                    chat.runState.transitionClarificationLifecycle(
                        checkNotNull(operation.requestId),
                        lifecycle,
                    )
            }
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        error = null,
                        runState = nextRunState,
                    )
                    ),
            )
        }
    }

    private fun publishBlockingResponse(
        operation: ControllerOperation,
        lifecycle: RunInteractionLifecycle,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val current = chat.runState.unsupportedBlocking ?: return
            val kind = operation.blockingKind ?: return
            if (
                current.runtimeSessionId != operation.runtimeSessionId ||
                current.requestId != operation.requestId ||
                current.kind != kind ||
                current.lifecycle != RunInteractionLifecycle.Responding
            ) return
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        error = null,
                        runState = chat.runState.transitionUnsupportedBlockingLifecycle(
                            operation.runtimeSessionId,
                            kind,
                            checkNotNull(operation.requestId),
                            lifecycle,
                        ),
                    )
                    ),
            )
        }
    }

    private fun publishBlockingError(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val current = chat.runState.unsupportedBlocking ?: return
            if (
                current.runtimeSessionId != operation.runtimeSessionId ||
                current.requestId != operation.requestId ||
                current.kind != operation.blockingKind ||
                current.lifecycle != RunInteractionLifecycle.Failed
            ) return
            updateChat(operation.durableSessionId) {
                it.copy(error = "Could not respond to secure input request")
            }
        }
    }

    private fun publishApprovalResponse(
        operation: ControllerOperation,
        lifecycle: RunInteractionLifecycle,
        nextApproval: HermesChatEvent.ApprovalRequest? = null,
    ) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val current = chat.runState.approval ?: return
            if (
                current.runtimeSessionId != operation.runtimeSessionId ||
                current.requestId != operation.requestId ||
                current.choices != operation.advertisedChoices ||
                current.lifecycle != RunInteractionLifecycle.Responding
            ) return
            val transitionedRunState = chat.runState.transitionApprovalLifecycle(
                operation.runtimeSessionId,
                operation.requestId,
                lifecycle,
            )
            val nextRunState = if (
                lifecycle != RunInteractionLifecycle.Failed &&
                nextApproval?.sessionId == operation.runtimeSessionId
            ) {
                transitionedRunState.reduce(nextApproval)
            } else {
                transitionedRunState
            }
            mutableSnapshots.value = snapshot.copy(
                chatSessions = snapshot.chatSessions + (
                    operation.durableSessionId to chat.copy(
                        error = null,
                        runState = nextRunState,
                    )
                    ),
            )
        }
    }

    private fun publishApprovalError(operation: ControllerOperation) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val approval = chat.runState.approval ?: return
            if (
                approval.runtimeSessionId != operation.runtimeSessionId ||
                approval.requestId != operation.requestId ||
                approval.choices != operation.advertisedChoices ||
                approval.lifecycle != RunInteractionLifecycle.Failed
            ) return
            updateChat(operation.durableSessionId) { current ->
                current.copy(error = "Could not respond to approval")
            }
        }
    }

    private fun publishControllerError(operation: ControllerOperation, error: String) {
        synchronized(controllerLock) {
            if (!isCurrentControllerOperation(operation)) return
            val snapshot = mutableSnapshots.value
            val chat = snapshot.chatSessions[operation.durableSessionId] ?: return
            val interaction = chat.runState.clarification ?: return
            if (
                interaction.runtimeSessionId != operation.runtimeSessionId ||
                interaction.requestId != operation.requestId ||
                interaction.lifecycle != RunInteractionLifecycle.Failed
            ) return
            updateChat(operation.durableSessionId) { current ->
                current.copy(error = error.take(160))
            }
        }
    }

    private fun publishActiveRuntime(
        durableSessionId: DurableSessionId,
        runtimeSessionId: RuntimeSessionId,
    ) {
        val title = mutableSnapshots.value.durableSessions
            .firstOrNull { it.id == durableSessionId }
            ?.title
            ?: mutableSnapshots.value.projectSessions.values
                .asSequence()
                .flatten()
                .firstOrNull { it.id == durableSessionId }
                ?.title
            ?: "Untitled session"
        val active = ActiveRuntimeSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            title = title,
            access = RuntimeAccess.Controller,
        )
        mutableSnapshots.value = mutableSnapshots.value.copy(
            activeRuntimes = mutableSnapshots.value.activeRuntimes
                .filterNot { it.runtimeSessionId == runtimeSessionId } + active,
        )
    }

    private fun sessionTitle(durableSessionId: DurableSessionId): String =
        mutableSnapshots.value.durableSessions.firstOrNull { it.id == durableSessionId }?.title
            ?: mutableSnapshots.value.projectSessions.values.asSequence().flatten()
                .firstOrNull { it.id == durableSessionId }?.title
            ?: "Hermes session"

    private fun removeActiveRuntime(runtimeSessionId: RuntimeSessionId) {
        if (mutableSnapshots.value.activeRuntimes.none { it.runtimeSessionId == runtimeSessionId }) return
        mutableSnapshots.value = mutableSnapshots.value.copy(
            activeRuntimes = mutableSnapshots.value.activeRuntimes
                .filterNot { it.runtimeSessionId == runtimeSessionId },
        )
    }

    private fun isCurrentChatOperation(
        durableSessionId: DurableSessionId,
        origin: ServerOrigin,
        originGeneration: Long,
        operationGeneration: Long,
    ): Boolean = sessionControllerRegistry.isCurrentChatOperation(
        durableSessionId = durableSessionId,
        origin = origin,
        currentOrigin = activeOrigin,
        originGeneration = originGeneration,
        currentGeneration = generation,
        operationGeneration = operationGeneration,
    )

    private fun clearTransientChatStates() {
        val snapshot = mutableSnapshots.value
        if (snapshot.chatSessions.none { (_, chat) ->
                chat.isLoading || chat.isSending || chat.messages.any(ChatMessage::isStreaming)
            }
        ) return
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions.mapValues { (_, chat) ->
                chat.copy(
                    messages = chat.messages.mapNotNull { message ->
                        when {
                            message.role == ChatMessageRole.Assistant &&
                                message.isStreaming &&
                                message.text.isEmpty() -> null
                            message.isStreaming -> message.copy(isStreaming = false)
                            else -> message
                        }
                    },
                    isLoading = false,
                    isSending = false,
                    isStopping = false,
                    runState = chat.runState.finishRunningTools(),
                )
            },
        )
    }

    private fun clearSendingState(durableSessionId: DurableSessionId) {
        updateChat(durableSessionId) { current ->
            current.copy(
                messages = current.messages.mapNotNull { message ->
                    when {
                        message.role == ChatMessageRole.Assistant &&
                            message.isStreaming &&
                            message.text.isEmpty() -> null
                        message.isStreaming -> message.copy(isStreaming = false)
                        else -> message
                    }
                },
                isLoading = false,
                isSending = false,
                isStopping = false,
                runState = current.runState.finishRunningTools(),
            )
        }
    }

    private fun clearProcessRows(durableSessionId: DurableSessionId) {
        updateChat(durableSessionId) { chat ->
            if (chat.processRows.isEmpty()) chat else chat.copy(processRows = emptyList())
        }
    }

    private fun updateChat(
        durableSessionId: DurableSessionId,
        transform: (ChatSessionSnapshot) -> ChatSessionSnapshot,
    ) {
        val snapshot = mutableSnapshots.value
        val current = snapshot.chatSessions[durableSessionId] ?: ChatSessionSnapshot()
        mutableSnapshots.value = snapshot.copy(
            chatSessions = snapshot.chatSessions + (durableSessionId to transform(current)),
        )
        syncActiveTurnNotifications()
    }

    /**
     * Reconciles the accepted-turn set against authoritative `isSending` state and
     * republishes the foreground-service count when it changed. Turns are added only
     * explicitly ([markTurnActive]) once the server accepted the prompt or reported
     * the turn running; removal is derived here so that every termination path
     * (completion, error, stop, recovery give-up, `running = false` resume) releases
     * the ongoing "Hermes is working" notification.
     */
    private fun syncActiveTurnNotifications() {
        sessionControllerRegistry.syncActiveTurns(
            isSending = { durableSessionId ->
                mutableSnapshots.value.chatSessions[durableSessionId]?.isSending == true
            },
            onCountChanged = notifications::activeCountChanged,
        )
    }

    private fun markTurnActive(durableSessionId: DurableSessionId) {
        sessionControllerRegistry.markTurnActive(
            durableSessionId = durableSessionId,
            title = sessionTitle(durableSessionId),
            onStarted = notifications::turnStarted,
        )
    }

    private suspend fun accessTokenForRequest(
        origin: ServerOrigin,
        expectedGeneration: Long,
    ): String? {
        if (mutableSnapshots.value.authenticationState == AuthenticationState.NotRequired) return null
        if (generation != expectedGeneration || activeOrigin != origin) {
            throw CancellationException("Server origin was replaced")
        }
        return tokenRefreshMutex.withLock {
            refreshAndPublishTokensLocked(origin, expectedGeneration)
        }
    }

    private suspend fun refreshAndPublishTokensLocked(
        origin: ServerOrigin,
        expectedGeneration: Long,
    ): String? {
        if (generation != expectedGeneration || activeOrigin != origin) {
            throw CancellationException("Server origin was replaced")
        }
        // Re-read under the lock: a concurrent caller may have already refreshed and
        // published a newer token set while this caller was waiting.
        val active = activeTokens
            ?.takeIf { it.origin == origin && it.generation == expectedGeneration }
            ?: return null
        val refreshed = try {
            refreshIfNeeded(origin, active.tokens)
        } catch (expired: NativeRefreshExpiredException) {
            currentCoroutineContext().ensureActive()
            if (generation != expectedGeneration || activeOrigin != origin) {
                throw CancellationException("Server origin was replaced")
            }
            tokenStore?.clear(origin)
            if (activeTokens == active) activeTokens = null
            throw expired
        }
        currentCoroutineContext().ensureActive()
        if (generation != expectedGeneration || activeOrigin != origin) {
            throw CancellationException("Server origin was replaced")
        }
        if (refreshed == null) {
            tokenStore?.clear(origin)
            if (activeTokens == active) activeTokens = null
            throw NativeRefreshExpiredException()
        }
        if (refreshed != active.tokens) {
            tokenStore?.save(origin, refreshed)
            currentCoroutineContext().ensureActive()
            if (generation != expectedGeneration || activeOrigin != origin) {
                throw CancellationException("Server origin was replaced")
            }
        }
        activeTokens = ActiveTokenRecord(origin, expectedGeneration, refreshed)
        return refreshed.accessToken
    }

    private suspend fun refreshIfNeeded(
        origin: ServerOrigin,
        tokens: NativeTokenSet,
    ): NativeTokenSet? {
        if (tokens.expiresAt <= 0L || tokens.expiresAt > nowEpochSeconds() + TOKEN_REFRESH_SKEW_SECONDS) {
            return tokens
        }
        if (tokens.refreshToken.isBlank() || tokens.provider.isBlank()) return null
        return refreshClient?.refresh(origin, tokens.refreshToken, tokens.provider)
    }

    private suspend fun publishSignInRequired() {
        disconnectProjectMetadata()
        pendingDraftSessions.clear()
        serverDurableIds.clear()
        mutableAttachments.value = emptyMap()
        clearAllSlashCompletions()
        mutableSnapshots.value = mutableSnapshots.value.copy(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.SignInRequired,
            connectionError = null,
            durableSessions = emptyList(),
            chatSessions = emptyMap(),
            projects = emptyList(),
            projectState = ProjectLoadState.Loaded(emptyList()),
            activeProjectId = null,
            scopedSessionIds = emptySet(),
            projectSessions = emptyMap(),
            projectSessionStates = emptyMap(),
            activeRuntimes = emptyList(),
        )
    }

    private fun detachFailedRuntime(durableSessionId: DurableSessionId) {
        clearSlashCompletion(durableSessionId)
        val controller = liveControllers[durableSessionId] ?: return
        controller.eventJob?.cancel()
        removeActiveRuntime(controller.runtimeSessionId)
        detachController(durableSessionId, controller.session, closeSession = true)
    }

    private fun detachController(
        durableSessionId: DurableSessionId,
        expectedSession: HermesChatSession,
        closeSession: Boolean,
    ) {
        val controller = sessionControllerRegistry.detachController(durableSessionId, expectedSession)
            ?: return
        updateChat(durableSessionId) {
            it.copy(backgroundTasks = it.backgroundTasks.unavailable(), connectionPhase = ChatConnectionPhase.Idle)
        }
        clearProcessRows(durableSessionId)
        syncActiveTurnNotifications()
        if (closeSession) {
            viewModelScope.launch { closeChatSessionNonCancellably(controller.session) }
        }
    }

    private suspend fun disconnectChat() {
        clearAllSlashCompletions()
        val controllers = sessionControllerRegistry.detachAll()
        controllers.forEach { controller ->
            removeActiveRuntime(controller.runtimeSessionId)
            closeChatSessionNonCancellably(controller.session)
        }
        mutableSnapshots.value = mutableSnapshots.value.copy(
            chatSessions = mutableSnapshots.value.chatSessions.mapValues { (_, chat) ->
                chat.copy(processRows = emptyList(), backgroundTasks = chat.backgroundTasks.unavailable(), connectionPhase = ChatConnectionPhase.Idle)
            },
        )
        notifications.activeCountChanged(0)
    }

    override fun onCleared() {
        connectionCoordinator.cancelAll()
        signInJob?.cancel()
        val controllerSessions = sessionControllerRegistry.detachAll().map { it.session }
        // The ViewModel is the only owner of the foreground service; without this the
        // ongoing notification survives the task being swiped away.
        notifications.activeCountChanged(0)
        val metadataSession = detachProjectMetadataSession()
        viewModelScope.launch {
            closeChatSessionNonCancellably(metadataSession)
            controllerSessions.forEach { closeChatSessionNonCancellably(it) }
        }
        closeResources()
    }

    class Factory(
        private val settingsStates: Flow<ServerSettingsState>,
        private val client: HermesConnectionClient,
        private val nativeLogin: NativeLogin? = null,
        private val passwordLogin: NativePasswordLogin? = null,
        private val closeResources: () -> Unit = {},
        private val tokenStore: NativeTokenStore? = null,
        private val refreshClient: NativeRefreshClient? = null,
        private val chatConnector: HermesChatConnector? = null,
        private val projectConnector: HermesChatConnector? = null,
        private val cacheRepository: OfflineCacheRepository? = null,
        private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
        private val sessionFilterRepository: SessionFilterRepository? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HermesConnectionViewModel::class.java))
            return HermesConnectionViewModel(
                settingsStates = settingsStates,
                client = client,
                nativeLogin = nativeLogin,
                passwordLogin = passwordLogin,
                closeResources = closeResources,
                tokenStore = tokenStore,
                refreshClient = refreshClient,
                chatConnector = chatConnector,
                projectConnector = projectConnector,
                cacheRepository = cacheRepository,
                nowEpochSeconds = nowEpochSeconds,
                sessionFilterRepository = sessionFilterRepository,
            ) as T
        }
    }

    class ProductionFactory(
        private val context: Context,
        private val settingsStates: Flow<ServerSettingsState>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HermesConnectionViewModel::class.java))
            val httpClient = HttpClient(CIO) {
                configureHermesHttpClient()
                install(HttpCookies) {
                    storage = EncryptedHermesCookieStorage(context)
                }
                install(WebSockets) {
                    maxFrameSize = HERMES_CHAT_MAX_FRAME_BYTES.toLong()
                    pingIntervalMillis = 30_000L
                }
            }
            fun newConnector() = HermesChatConnector { origin, accessToken ->
                HermesChatGateway(
                    origin = origin,
                    accessToken = accessToken,
                    ticketClient = KtorWsTicketClient(httpClient),
                    socketFactory = KtorChatWebSocketFactory(httpClient),
                ).connect()
            }
            val chatConnector = newConnector()
            val projectConnector = newConnector()
            val relayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val relaySocketFactory = TlsRelayBinarySocketFactory()
            val relayNetwork = com.unsupportedpastels.hermesandroid.relay.RelayNetworkAvailability(context)
            val relayTargetStore = com.unsupportedpastels.hermesandroid.relay.EncryptedRelayTargetStore(context)
            val leaseCheckpoints = mutableMapOf<List<String>, RelayLeaseCheckpoint>()
            // Shared monotonic IDs fence late inner-controller RPC replies across
            // attachments; a random process prefix also fences Android relaunch.
            val relayRequestIds = java.util.concurrent.atomic.AtomicLong(java.security.SecureRandom().nextLong().ushr(12))
            val relaySessionFactory: suspend (RelayPairedTarget, String, String?) -> HermesChatSession = { target, profile, channel ->
                val checkpoint = synchronized(leaseCheckpoints) {
                    val key = listOf(target.relayOrigin, target.id, target.deviceId, target.fingerprint, profile, channel.orEmpty())
                    leaseCheckpoints.getOrPut(key) {
                        if (leaseCheckpoints.size >= 64) leaseCheckpoints.remove(leaseCheckpoints.keys.first())
                        RelayLeaseCheckpoint()
                    }
                }
                val connected = RelayConnector.connect(
                    target = target,
                    profile = profile,
                    socketFactory = relaySocketFactory,
                    resumeCursor = checkpoint.cursor,
                    recoveryVersion = 1,
                    leaseChannel = channel,
                )
                val socket = RelayLeaseRecoverySocket(RelayHermesChatSocket(connected), profile, checkpoint)
                try {
                    kotlinx.coroutines.withTimeout(15_000L) { socket.initialize() }
                    // The host renews the router token on every attach; keep the
                    // pairing current so it never ages out and needs a re-pair.
                    socket.snapshot?.routingToken?.takeIf { it != target.relayRoutingToken }?.let { renewed ->
                        relayScope.launch { runCatching { relayTargetStore.updateRoutingToken(target.id, renewed) } }
                    }
                    HermesChatConnection(
                        socket, HERMES_CHAT_MAX_FRAME_BYTES, relayScope, relayRequestIds,
                        projectPathPreflight = false,
                    )
                } catch (error: Throwable) {
                    withContext(NonCancellable) { socket.close() }
                    throw error
                }
            }
            return HermesConnectionViewModel(
                settingsStates = settingsStates,
                client = HttpHermesConnectionClient(httpClient),
                nativeLogin = HermesNativeLogin(
                    exchanger = HttpHermesNativeAuthClient(httpClient),
                    awaitExchangeReady = {
                        HermesWindowFocus.state.first { it }
                    },
                ),
                passwordLogin = HttpHermesPasswordAuthClient(httpClient),
                closeResources = {
                    relayNetwork.close()
                    relayScope.cancel()
                    httpClient.close()
                },
                tokenStore = EncryptedNativeTokenStore(context),
                refreshClient = HttpHermesNativeRefreshClient(httpClient),
                chatConnector = chatConnector,
                projectConnector = projectConnector,
                relaySessionFactory = relaySessionFactory,
                cacheRepository = EncryptedOfflineCacheRepository(context),
                attachmentReader = ContentAttachmentByteReader(context),
                appForegroundStates = HermesAppForeground.states,
                relayNetworkAvailable = relayNetwork.available,
                notifications = AndroidTurnNotificationController(context),
                sessionFilterRepository = DataStoreSessionFilterRepository(context),
                speechStreamConnector = StreamingSpeechTransport(
                    ticketClient = KtorWsTicketClient(httpClient),
                    socketFactory = KtorSpeechWebSocketFactory(httpClient),
                ),
                videoCache = ManagedVideoCache(File(context.cacheDir, "managed-video")),
            ) as T
        }
    }
}
