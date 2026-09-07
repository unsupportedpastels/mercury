package com.unsupportedpastels.hermesandroid

import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.os.Build
import android.view.WindowManager

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.connection.HermesConnectionViewModel
import com.unsupportedpastels.hermesandroid.connection.HermesAppForeground
import com.unsupportedpastels.hermesandroid.connection.HermesWindowFocus
import com.unsupportedpastels.hermesandroid.voice.ComposerVoiceConversation
import com.unsupportedpastels.hermesandroid.voice.MessageReadAloud
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import com.unsupportedpastels.hermesandroid.voice.VoiceInputPolicy
import com.unsupportedpastels.hermesandroid.voice.VoiceServerConfig
import com.unsupportedpastels.hermesandroid.voice.VoiceSettings
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsViewModel
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.launchBrowserAndAwaitReturn
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.notifications.NotificationNavigationInbox
import com.unsupportedpastels.hermesandroid.notifications.SessionNotificationVisibilityRegistry
import com.unsupportedpastels.hermesandroid.notifications.synchronizeVisibleSessionNotifications
import com.unsupportedpastels.hermesandroid.relay.RelayCodeScanner
import com.unsupportedpastels.hermesandroid.relay.RelayUiState
import com.unsupportedpastels.hermesandroid.relay.RelayViewModel
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.hermesandroid.session.SavedSessionFilter
import com.unsupportedpastels.hermesandroid.share.SharePayload
import com.unsupportedpastels.hermesandroid.share.nextShareRequestId
import com.unsupportedpastels.hermesandroid.share.parseIncomingShare
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.ui.HermesApp
import com.unsupportedpastels.hermesandroid.ui.PaneLayoutPreferencesViewModel
import com.unsupportedpastels.hermesandroid.ui.ProjectDockState
import com.unsupportedpastels.hermesandroid.ui.ProjectIconAssignmentsState
import com.unsupportedpastels.hermesandroid.ui.ProjectIconViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val incomingShare = MutableStateFlow<SharePayload?>(null)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    private val serverSettingsViewModel by viewModels<ServerSettingsViewModel> {
        ServerSettingsViewModel.Factory(this)
    }
    private val connectionViewModel by viewModels<HermesConnectionViewModel> {
        HermesConnectionViewModel.ProductionFactory(
            context = applicationContext,
            settingsStates = serverSettingsViewModel.states,
        )
    }
    private val projectIconViewModel by viewModels<ProjectIconViewModel> {
        ProjectIconViewModel.Factory(this)
    }
    private val paneLayoutPreferencesViewModel by viewModels<PaneLayoutPreferencesViewModel> {
        PaneLayoutPreferencesViewModel.Factory(this)
    }
    private val cloudViewModel by viewModels<com.unsupportedpastels.hermesandroid.connection.HermesCloudViewModel> {
        com.unsupportedpastels.hermesandroid.connection.HermesCloudViewModel.Factory(this)
    }
    private val relayViewModel by viewModels<RelayViewModel> {
        RelayViewModel.ProductionFactory(applicationContext)
    }
    private val relayScanner by lazy { RelayCodeScanner(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.unsupportedpastels.hermesandroid.relay.RelayDeviceIdentity.initialize(applicationContext)
        consumeIncomingShare(intent)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            HermesAndroidTheme {
                val snapshot by connectionViewModel.snapshots.collectAsStateWithLifecycle()
                NotificationPermissionEffect(snapshot) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val preferences = getSharedPreferences("notification_permission", MODE_PRIVATE)
                        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!granted &&
                            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
                            !preferences.getBoolean("requested", false)
                        ) {
                            // Persist before launching: denial and Activity recreation must not reprompt.
                            preferences.edit().putBoolean("requested", true).apply()
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                val relayState by relayViewModel.state.collectAsStateWithLifecycle()
                val transcriptCachingEnabled by connectionViewModel.transcriptCachingEnabled
                    .collectAsStateWithLifecycle()
                val notificationRequest by NotificationNavigationInbox.requests.collectAsStateWithLifecycle()
                val sharePayload by incomingShare.collectAsStateWithLifecycle()
                val hasSensitivePrompt = snapshot.chatSessions.values.any { chat ->
                    chat.runState.unsupportedBlocking?.let { interaction ->
                        interaction.lifecycle in setOf(
                            com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle.Pending,
                            com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle.Responding,
                        ) &&
                            (interaction.kind == UnsupportedBlockingKind.Sudo ||
                                interaction.kind == UnsupportedBlockingKind.Secret)
                    } == true
                }
                LaunchedEffect(hasSensitivePrompt) {
                    if (hasSensitivePrompt) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
                HermesAppHost(
                    viewModel = serverSettingsViewModel,
                    connectionViewModel = connectionViewModel,
                    cloudViewModel = cloudViewModel,
                    projectIconViewModel = projectIconViewModel,
                    paneLayoutPreferencesViewModel = paneLayoutPreferencesViewModel,
                    snapshot = snapshot,
                    relayState = relayState,
                    onRelayScan = {
                        relayScanner.scan(
                            onResult = { relayViewModel.beginPairing(it) },
                            onUnavailable = { relayViewModel.scannerUnavailable() },
                        )
                    },
                    onRelayPair = { relayViewModel.beginPairing(it) },
                    onRelayConnect = { connectionViewModel.connectRelay(it) },
                    onRelayRemove = { target ->
                        if (snapshot.relayTargetId == target.id) connectionViewModel.leaveRelayMode()
                        relayViewModel.removeTarget(target)
                    },
                    onRelayCancelPairing = relayViewModel::cancelPairing,
                    onRelayRetry = relayViewModel::resetFailure,
                    transcriptCachingEnabled = transcriptCachingEnabled,
                    onTranscriptCachingChanged = { enabled ->
                        connectionViewModel.setTranscriptCachingEnabled(enabled)
                    },
                    onClearOfflineCache = { connectionViewModel.clearOfflineCache() },
                    sharePayload = sharePayload,
                    onSharePayloadConsumed = { incomingShare.value = null },
                    requestedSessionId = notificationRequest?.sessionId,
                    requestedSessionRequestId = notificationRequest?.requestId,
                    onVisibleSessionChanged = { sessionId ->
                        SessionNotificationVisibilityRegistry.publishVisibleSession(sessionId)
                        synchronizeVisibleSessionNotifications(this)
                    },
                    onSignIn = {
                        connectionViewModel.signIn { authorizationUrl ->
                            launchBrowserAndAwaitReturn(HermesWindowFocus.state) {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, authorizationUrl.toUri()),
                                )
                            }
                        }
                    },
                    onPasswordSignIn = connectionViewModel::signInWithPassword,
                    onCloudSignIn = {
                        cloudViewModel.signIn { verificationUrl ->
                            launchBrowserAndAwaitReturn(HermesWindowFocus.state) {
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, verificationUrl.toUri()),
                                )
                            }
                        }
                    },
                    onOpenProject = connectionViewModel::openProject,
                    onCreateProjectSession = { projectId ->
                        connectionViewModel.createProjectSession(projectId, "New task")
                    },
                    onOpenSession = connectionViewModel::openSession,
                    onSendMessage = connectionViewModel::sendMessage,
                    onSteerMessage = connectionViewModel::steerSession,
                    onReasoningSelected = connectionViewModel::setReasoningEffort,
                    onFastSelected = connectionViewModel::setFast,
                    onClarificationResponse = { sessionId, requestId, answer ->
                        connectionViewModel.respondToClarification(sessionId, requestId, answer)
                    },
                    onApprovalResponse = { sessionId, choice, all ->
                        connectionViewModel.respondToApproval(sessionId, choice, all)
                    },
                    onBlockingResponse = { sessionId, kind, requestId, value ->
                        connectionViewModel.respondToBlockingPrompt(sessionId, kind, requestId, value)
                    },
                    onStopSession = connectionViewModel::stopSession,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingShare(intent)
    }

    private fun consumeIncomingShare(intent: Intent) {
        parseIncomingShare(this, intent, nextShareRequestId())?.let { incomingShare.value = it }
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        HermesWindowFocus.state.value = hasFocus
        SessionNotificationVisibilityRegistry.publishWindowFocused(hasFocus)
        synchronizeVisibleSessionNotifications(this)
    }

    override fun onStart() {
        super.onStart()
        HermesAppForeground.publish(true)
        SessionNotificationVisibilityRegistry.publishAppForeground(true)
        synchronizeVisibleSessionNotifications(this)
    }

    override fun onStop() {
        HermesAppForeground.publish(false)
        SessionNotificationVisibilityRegistry.publishAppForeground(false)
        super.onStop()
    }
}

@Composable
internal fun NotificationPermissionEffect(snapshot: HermesGatewaySnapshot, request: () -> Unit) {
    var requested by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val ready = snapshot.connectionState == com.unsupportedpastels.hermesandroid.gateway.ConnectionState.Connected &&
        snapshot.authenticationState in setOf(
            com.unsupportedpastels.hermesandroid.gateway.AuthenticationState.Authenticated,
            com.unsupportedpastels.hermesandroid.gateway.AuthenticationState.NotRequired,
        )
    LaunchedEffect(ready) {
        if (ready && !requested) {
            requested = true
            request()
        }
    }
}

private const val VOICE_SCREEN_OFF_PREF = "screen_off_continuation"

internal fun voiceScreenOffPreferenceKey(origin: ServerOrigin?): String =
    "$VOICE_SCREEN_OFF_PREF:${origin?.value ?: "unconfigured"}"

@Composable
internal fun HermesAppHost(
    viewModel: ServerSettingsViewModel,
    connectionViewModel: HermesConnectionViewModel? = null,
    cloudViewModel: com.unsupportedpastels.hermesandroid.connection.HermesCloudViewModel? = null,
    projectIconViewModel: ProjectIconViewModel? = null,
    paneLayoutPreferencesViewModel: PaneLayoutPreferencesViewModel? = null,
    snapshot: HermesGatewaySnapshot,
    relayState: RelayUiState = RelayUiState(),
    onRelayScan: () -> Unit = {},
    onRelayPair: (String) -> Unit = {},
    onRelayConnect: (RelayPairedTarget) -> Unit = {},
    onRelayRemove: (RelayPairedTarget) -> Unit = {},
    onRelayCancelPairing: () -> Unit = {},
    onRelayRetry: () -> Unit = {},
    transcriptCachingEnabled: Boolean = false,
    onTranscriptCachingChanged: (Boolean) -> Unit = {},
    onClearOfflineCache: () -> Unit = {},
    sharePayload: SharePayload? = null,
    onSharePayloadConsumed: () -> Unit = {},
    requestedSessionId: DurableSessionId? = null,
    requestedSessionRequestId: Long? = null,
    onVisibleSessionChanged: (DurableSessionId?) -> Unit = {},
    onSignIn: () -> Unit = {},
    onPasswordSignIn: (String, String) -> Unit = { _, _ -> },
    onCloudSignIn: () -> Unit = {},
    onOpenProject: (ProjectId) -> Unit = {},
    onCreateProjectSession: (ProjectId) -> DurableSessionId? = { null },
    onOpenSession: (DurableSessionId) -> Unit = {},
    onSendMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
    onSteerMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
    onReasoningSelected: (DurableSessionId, String) -> Unit = { _, _ -> },
    onFastSelected: (DurableSessionId, Boolean) -> Unit = { _, _ -> },
    onClarificationResponse: (DurableSessionId, String, String) -> Unit = { _, _, _ -> },
    onApprovalResponse: (DurableSessionId, String, Boolean) -> Unit = { _, _, _ -> },
    onBlockingResponse: (DurableSessionId, UnsupportedBlockingKind, String, String) -> Unit = { _, _, _, _ -> },
    onStopSession: (DurableSessionId) -> Unit = {},
) {
    val serverSettingsState by viewModel.states.collectAsStateWithLifecycle()
    val cloudConnectStateFlow = cloudViewModel?.state
        ?: remember {
            MutableStateFlow<com.unsupportedpastels.hermesandroid.connection.CloudConnectState>(
                com.unsupportedpastels.hermesandroid.connection.CloudConnectState.SignedOut,
            )
        }
    val cloudConnectState by cloudConnectStateFlow.collectAsStateWithLifecycle()
    // Silently resume a persisted Hermes Cloud session so the roster is ready
    // when the user opens the connect surface; no-op when nothing is stored.
    LaunchedEffect(cloudViewModel) {
        cloudViewModel?.resume()
    }
    val projectIconAssignmentsFlow = projectIconViewModel?.assignments
        ?: remember {
            MutableStateFlow<ProjectIconAssignmentsState>(ProjectIconAssignmentsState.Loading)
        }
    val projectIconAssignments by projectIconAssignmentsFlow.collectAsStateWithLifecycle()
    val currentServerOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.activeOrigin
    val currentServerCatalog = (serverSettingsState as? ServerSettingsState.Ready)?.catalog
        ?: com.unsupportedpastels.hermesandroid.connection.ServerCatalog.empty()
    var relayAutoConnectAttemptedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverSettingsState, relayState.targets, snapshot.relayTargetId) {
        if (serverSettingsState !is ServerSettingsState.Ready || currentServerOrigin != null) {
            return@LaunchedEffect
        }
        if (snapshot.relayTargetId != null) return@LaunchedEffect
        val target = relayState.targets
            .asSequence()
            .filter { it.status == com.unsupportedpastels.mercury.core.relay.RelayTargetStatus.Approved }
            .maxWithOrNull(
                compareBy<RelayPairedTarget>(
                    { it.lastUsedEpochSeconds ?: Long.MIN_VALUE },
                    { it.createdAtEpochSeconds },
                ),
            )
            ?: return@LaunchedEffect
        if (relayAutoConnectAttemptedId == target.id) return@LaunchedEffect
        relayAutoConnectAttemptedId = target.id
        onRelayConnect(target)
    }
    val projectIcons = (projectIconAssignments as? ProjectIconAssignmentsState.Ready)
        ?.assignments
        .orEmpty()
        .filterKeys { it.serverOrigin == currentServerOrigin }
        .mapKeys { it.key.projectId }
    val slashCompletionsFlow = connectionViewModel?.slashCompletions
        ?: remember { MutableStateFlow(emptyMap<DurableSessionId, SlashCompletionState>()) }
    val slashCompletions by slashCompletionsFlow.collectAsStateWithLifecycle()
    val attachmentsFlow = connectionViewModel?.attachments
        ?: remember { MutableStateFlow(emptyMap<DurableSessionId, List<ComposerAttachment>>()) }
    val attachments by attachmentsFlow.collectAsStateWithLifecycle()
    val modelPickerFlow = connectionViewModel?.modelPickerState
        ?: remember { MutableStateFlow<ModelPickerState>(ModelPickerState.Closed) }
    val modelPickerState by modelPickerFlow.collectAsStateWithLifecycle()
    val homeRefreshingFlow = connectionViewModel?.homeRefreshing
        ?: remember { MutableStateFlow(false) }
    val homeRefreshing by homeRefreshingFlow.collectAsStateWithLifecycle()
    val savedSessionFiltersFlow = connectionViewModel?.savedSessionFilters
        ?: remember { MutableStateFlow(emptyList<SavedSessionFilter>()) }
    val savedSessionFilters by savedSessionFiltersFlow.collectAsStateWithLifecycle()
    val persistedPaneProportionFlow = paneLayoutPreferencesViewModel?.projectSessionPaneProportion
        ?: remember { MutableStateFlow<Float?>(null) }
    val persistedPaneProportion by persistedPaneProportionFlow.collectAsStateWithLifecycle()
    val persistedProjectDockStateFlow = paneLayoutPreferencesViewModel?.projectDockState
        ?: remember { MutableStateFlow<ProjectDockState?>(null) }
    val persistedProjectDockState by persistedProjectDockStateFlow.collectAsStateWithLifecycle()

    val voiceCapabilitiesFlow = connectionViewModel?.voiceCapabilities
        ?: remember { MutableStateFlow(VoiceCapabilities.NONE) }
    val voiceCapabilities by voiceCapabilitiesFlow.collectAsStateWithLifecycle()
    val voiceServerConfigFlow = connectionViewModel?.voiceServerConfig
        ?: remember { MutableStateFlow(VoiceServerConfig.DEFAULT) }
    val voiceServerConfig by voiceServerConfigFlow.collectAsStateWithLifecycle()

    // Re-probe voice contracts whenever the connection or selected profile
    // changes; refreshVoiceCapabilities is fail-closed so an unauthenticated or
    // older server simply leaves the mic hidden.
    LaunchedEffect(connectionViewModel, currentServerOrigin, snapshot.relayTargetId, snapshot.authenticationState, snapshot.selectedProfile) {
        connectionViewModel?.refreshVoiceCapabilities()
    }

    val voiceViewModel = connectionViewModel
    val messageReadAloud = remember(voiceViewModel, voiceCapabilities) {
        if (voiceCapabilities.canReadAloud && voiceViewModel != null) {
            MessageReadAloud { text ->
                resultPreservingCancellation { voiceViewModel.synthesizeSpeech(text) }
            }
        } else {
            null
        }
    }
    // Client-side opt-in for screen-off voice continuation; the voice loop
    // itself and all audio stay in-memory only.
    val appContext = LocalContext.current.applicationContext
    val voicePreferences = remember(appContext) {
        appContext.getSharedPreferences("voice", android.content.Context.MODE_PRIVATE)
    }
    val screenOffConsentKey = voiceScreenOffPreferenceKey(currentServerOrigin)
    var voiceScreenOffContinuation by remember(voicePreferences, screenOffConsentKey) {
        mutableStateOf(voicePreferences.getBoolean(screenOffConsentKey, false))
    }

    // The hands-free loop needs both STT (transcribe) and TTS (speak) contracts.
    val composerVoiceConversation = remember(voiceViewModel, voiceCapabilities, voiceServerConfig) {
        if (VoiceInputPolicy.canUseServerConversation(
                voiceCapabilities,
                voiceServerConfig,
            ) && voiceViewModel != null
        ) {
            ComposerVoiceConversation(
                serverConfig = voiceServerConfig,
                transcribe = { dataUrl, mimeType ->
                    resultPreservingCancellation { voiceViewModel.transcribeDictation(dataUrl, mimeType) }
                },
                openStream = { voiceViewModel.openSpeechStream() },
                synthesize = { text ->
                    resultPreservingCancellation { voiceViewModel.synthesizeSpeech(text) }
                },
            )
        } else {
            null
        }
    }

    HermesApp(
        snapshot = snapshot,
        sharePayload = sharePayload,
        onSharePayloadConsumed = onSharePayloadConsumed,
        requestedSessionId = requestedSessionId,
        requestedSessionRequestId = requestedSessionRequestId,
        persistedProjectDockState = persistedProjectDockState,
        onProjectDockStateChanged = { state ->
            paneLayoutPreferencesViewModel?.saveProjectDockState(state)
        },
        projectSessionPaneProportion = persistedPaneProportion,
        onProjectSessionPaneProportionChanged = { proportion ->
            paneLayoutPreferencesViewModel?.saveProjectSessionPaneProportion(proportion)
        },
        onVisibleSessionChanged = onVisibleSessionChanged,
        serverSettingsState = serverSettingsState,
        serverCatalog = currentServerCatalog,
        relayState = relayState,
        onRelayScan = onRelayScan,
        onRelayPair = onRelayPair,
        onRelayConnect = onRelayConnect,
        onRelayRemove = onRelayRemove,
        onRelayCancelPairing = onRelayCancelPairing,
        onRelayRetry = onRelayRetry,
        transcriptCachingEnabled = transcriptCachingEnabled,
        onTranscriptCachingChanged = onTranscriptCachingChanged,
        onClearOfflineCache = onClearOfflineCache,
        onSaveServerOrigin = { origin ->
            connectionViewModel?.leaveRelayMode()
            viewModel.save(origin).await()
        },
        onSaveServerEntry = { entry ->
            connectionViewModel?.leaveRelayMode()
            viewModel.save(entry).await()
        },
        onUpdateServerLabel = { entry -> viewModel.updateLabel(entry).await() },
        onSelectServerOrigin = { origin ->
            connectionViewModel?.leaveRelayMode()
            viewModel.select(origin).await()
        },
        onRemoveServerOrigin = { origin -> viewModel.remove(origin).await() },
        cloudState = cloudConnectState,
        onCloudSignIn = onCloudSignIn,
        onCloudRefresh = { cloudViewModel?.refresh() },
        onCloudSignOut = { cloudViewModel?.signOut() },
        onCloudSelectOrg = { org -> cloudViewModel?.selectOrg(org) },
        onCloudSelectAgent = { agent ->
            val dashboard = agent.dashboardUrl
                ?: return@HermesApp Result.failure(
                    IllegalStateException("Agent is still provisioning"),
                )
            val origin = runCatching {
                com.unsupportedpastels.hermesandroid.connection.ServerOrigin.parse(dashboard)
            }.getOrElse {
                return@HermesApp Result.failure(it)
            }
            connectionViewModel?.leaveRelayMode()
            viewModel.save(origin).await()
        },
        onLoadManagementSettings = { profile -> connectionViewModel?.loadManagementSettings(profile) },
        onRefreshDurableSessions = { archivedOnly ->
            connectionViewModel?.refreshDurableSessions(archivedOnly)
        },
        onSetProfileDefaultModel = { selection, confirm ->
            connectionViewModel?.setProfileDefaultModel(selection, confirm)
                ?: ModelSwitchResult(accepted = false)
        },
        onSetProfileReasoningEffort = { effort ->
            connectionViewModel?.setProfileReasoningEffort(effort)
                ?: Result.failure(IllegalStateException("Profile settings unavailable"))
        },
        onSetModelReasoningOverride = { selection, effort ->
            connectionViewModel?.setProfileModelReasoningOverride(selection, effort)
                ?: Result.failure(IllegalStateException("Profile settings unavailable"))
        },
        onLogout = { connectionViewModel?.logout() },
        onSignIn = onSignIn,
        onPasswordSignIn = onPasswordSignIn,
        onRetryConnection = { connectionViewModel?.retryConnection() },
        onOpenProject = onOpenProject,
        onCreateProjectSession = onCreateProjectSession,
        onOpenSession = onOpenSession,
        onLoadSessionInsights = { sessionId ->
            connectionViewModel?.loadSessionInsights(sessionId)
        },
        onCompressSession = { sessionId, focusTopic ->
            connectionViewModel?.compressSession(sessionId, focusTopic)
        },
        onUndoSession = { sessionId ->
            connectionViewModel?.undoSession(sessionId)
        },
        onBranchSession = { sessionId, count, name ->
            connectionViewModel?.branchSession(sessionId, count, name)
        },
        onRefreshCronJobs = {
            connectionViewModel?.refreshCronJobs()
        },
        onCronJobAction = { jobId, action ->
            connectionViewModel?.manageCronJob(jobId, action)
        },
        onRunCronJob = { jobId ->
            connectionViewModel?.triggerCronJob(jobId)
        },
        onToggleCronJobRuns = { jobId ->
            connectionViewModel?.toggleCronJobRuns(jobId)
        },
        isHomeRefreshing = homeRefreshing,
        onRefreshHome = { connectionViewModel?.refreshHomeData() },
        onRefreshWorkingPresence = { connectionViewModel?.refreshActiveWorkingSessions() },
        onLoadRecentSessions = { connectionViewModel?.loadRecentSessions() },
        onLoadMoreRecentSessions = { connectionViewModel?.loadMoreRecentSessions() },
        onRenameSession = { sessionId, title ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.renameSession(sessionId, title) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        onSetSessionPinned = { sessionId, pinned ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.setSessionPinned(sessionId, pinned) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        onSetSessionArchived = { sessionId, archived ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.setSessionArchived(sessionId, archived) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        onDeleteSession = { sessionId ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.deleteSession(sessionId) } }
                ?: Result.failure(IllegalStateException("Session management unavailable"))
        },
        savedSessionFilters = savedSessionFilters,
        onSaveSessionFilter = { filter ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.saveSessionFilter(filter) } }
                ?: Result.failure(IllegalStateException("Saved session filters unavailable"))
        },
        onRemoveSessionFilter = { name ->
            connectionViewModel?.let { vm -> resultPreservingCancellation { vm.removeSessionFilter(name) } }
                ?: Result.failure(IllegalStateException("Saved session filters unavailable"))
        },
        onSearchTranscripts = { query -> connectionViewModel?.searchTranscripts(query) },
        readAloud = messageReadAloud,
        voiceConversation = composerVoiceConversation,
        onSendVoiceMessage = { sessionId, text, interrupted ->
            connectionViewModel?.sendMessage(sessionId, text, interrupted)
        },
        voiceSettings = if (voiceCapabilities.audioRoutesPresent && voiceViewModel != null) {
            VoiceSettings(
                identity = com.unsupportedpastels.hermesandroid.voice.VoiceSettingsIdentity(
                    currentServerOrigin, snapshot.relayTargetId, snapshot.selectedProfile,
                ),
                capabilities = voiceCapabilities,
                config = voiceServerConfig,
                setAutoTts = { enabled -> voiceViewModel.setVoiceAutoTts(enabled) },
                setElevenLabsVoice = { voiceId -> voiceViewModel.setElevenLabsVoice(voiceId) },
                loadVoices = { voiceViewModel.loadElevenLabsVoices() },
                screenOffContinuationEnabled = voiceScreenOffContinuation,
                setScreenOffContinuation = { enabled ->
                    voiceScreenOffContinuation = enabled
                    voicePreferences
                        .edit()
                        .putBoolean(screenOffConsentKey, enabled)
                        .apply()
                },
            )
        } else {
            null
        },
        autoSpeakEnabled = voiceCapabilities.canReadAloud && voiceServerConfig.autoTts,
        voiceScreenOffContinuation = voiceScreenOffContinuation,
        onSendMessage = onSendMessage,
        onSteerMessage = onSteerMessage,
        onReasoningSelected = onReasoningSelected,
        onFastSelected = onFastSelected,
        onClarificationResponse = onClarificationResponse,
        onApprovalResponse = onApprovalResponse,
        onBlockingResponse = onBlockingResponse,
        onStopSession = onStopSession,
        onCreateSession = { connectionViewModel?.createNewSession() },
        onLoadHostDirectories = { path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.loadHostDirectories(path) }
            } ?: Result.failure(IllegalStateException("Host folder browsing unavailable"))
        },
        onLoadHostFiles = { path ->
            connectionViewModel?.let { connection ->
                resultPreservingCancellation { connection.loadHostFiles(path) }
            } ?: Result.failure(IllegalStateException("Host file browsing unavailable"))
        },
        onLoadManagedFile = { path ->
            connectionViewModel?.let { connection ->
                resultPreservingCancellation { connection.loadManagedFile(path) }
            } ?: Result.failure(IllegalStateException("Managed files unavailable"))
        },
        onCreateHostDirectory = { parentPath, name ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.createHostDirectory(parentPath, name) }
            } ?: Result.failure(IllegalStateException("Host folder creation unavailable"))
        },
        onCreateProject = { name, path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.createProject(name, path) }
            } ?: Result.failure(IllegalStateException("Project creation unavailable"))
        },
        onLoadManagedImage = { path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.downloadManagedImage(path) }
            } ?: Result.failure(IllegalStateException("Managed images unavailable"))
        },
        onLoadManagedVideo = { path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.downloadManagedVideo(path) }
            } ?: Result.failure(IllegalStateException("Managed videos unavailable"))
        },
        onPeekManagedVideo = { path ->
            connectionViewModel?.let { viewModel ->
                resultPreservingCancellation { viewModel.peekManagedVideo(path) }.getOrNull()
            }
        },
        modelPickerState = modelPickerState,
        onOpenModelPicker = { sessionId -> connectionViewModel?.openModelPicker(sessionId) },
        onDismissModelPicker = { connectionViewModel?.dismissModelPicker() },
        onRetryModelPicker = { connectionViewModel?.retryModelPicker() },
        onModelSelected = { selection -> connectionViewModel?.selectModel(selection) },
        onConfirmModelSelection = { connectionViewModel?.confirmModelSelection() },
        slashCompletions = slashCompletions,
        attachments = attachments,
        onAddAttachments = { sessionId, candidates ->
            connectionViewModel?.addAttachments(sessionId, candidates).orEmpty()
        },
        onRemoveAttachment = { sessionId, attachmentId ->
            connectionViewModel?.removeAttachment(sessionId, attachmentId)
        },
        projectIcons = projectIcons,
        onSaveProjectIcon = { projectId, iconId ->
            val origin = currentServerOrigin
            if (origin == null || projectIconViewModel == null) {
                Result.failure(IllegalStateException("Project icon persistence unavailable"))
            } else {
                projectIconViewModel.save(origin, projectId, iconId).await()
            }
        },
        onSlashCompletionRequested = { sessionId, text ->
            connectionViewModel?.updateSlashCompletion(sessionId, text)
        },
    )
}

private suspend fun <T> resultPreservingCancellation(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
