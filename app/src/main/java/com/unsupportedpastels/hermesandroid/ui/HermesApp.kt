package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.material3.adaptive.layout.PaneExpansionStateKey
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.isNoProjectBucket
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.voice.ComposerVoiceConversation
import com.unsupportedpastels.hermesandroid.voice.VoiceSettings
import com.unsupportedpastels.hermesandroid.voice.MessageReadAloud
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerCatalog
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.relay.RelayUiState
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.navigation.HomeRoute
import com.unsupportedpastels.hermesandroid.navigation.ProjectRoute
import com.unsupportedpastels.hermesandroid.navigation.RecentSessionsRoute
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.navigation.ServerSettingsRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsServersRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsConnectionRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsModelRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsVoiceRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsOfflineRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsJobsRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsAccountRoute
import com.unsupportedpastels.hermesandroid.session.SavedSessionFilter
import com.unsupportedpastels.hermesandroid.share.SharePayload
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.voice.VoiceInputPolicy
import kotlinx.coroutines.delay
import java.net.URI

private const val PROJECT_DOCK_MIN_WIDTH_DP = 800

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun HermesApp(
    snapshot: HermesGatewaySnapshot,
    modifier: Modifier = Modifier,
    sharePayload: SharePayload? = null,
    onSharePayloadConsumed: () -> Unit = {},
    initialRoute: NavKey = HomeRoute,
    requestedSessionId: DurableSessionId? = null,
    requestedSessionRequestId: Long? = null,
    onVisibleSessionChanged: (DurableSessionId?) -> Unit = {},
    initialHomeSearchOpen: Boolean = false,
    initialProjectDockCollapsed: Boolean = false,
    persistedProjectDockState: ProjectDockState? = null,
    onProjectDockStateChanged: (ProjectDockState) -> Unit = {},
    projectSessionPaneProportion: Float? = DEFAULT_PROJECT_SESSION_PANE_PROPORTION,
    onProjectSessionPaneProportionChanged: (Float) -> Unit = {},
    initialProjectCreatorOpen: Boolean = false,
    initialProjectCreatorListing: HostDirectoryListing? = null,
    serverSettingsState: ServerSettingsState = ServerSettingsState.Ready(null),
    transcriptCachingEnabled: Boolean = false,
    onTranscriptCachingChanged: (Boolean) -> Unit = {},
    onClearOfflineCache: () -> Unit = {},
    onSaveServerOrigin: suspend (ServerOrigin) -> Result<Unit> = { Result.success(Unit) },
    serverCatalog: ServerCatalog = ServerCatalog.empty(),
    onSaveServerEntry: (suspend (ServerCatalogEntry) -> Result<Unit>)? = null,
    onUpdateServerLabel: (suspend (ServerCatalogEntry) -> Result<Unit>)? = null,
    onSelectServerOrigin: suspend (ServerOrigin) -> Result<Unit> = { origin ->
        onSaveServerOrigin(origin)
    },
    onRemoveServerOrigin: suspend (ServerOrigin) -> Result<Unit> = { _ ->
        Result.failure(UnsupportedOperationException("Removing servers is unavailable"))
    },
    cloudState: com.unsupportedpastels.hermesandroid.connection.CloudConnectState? = null,
    relayState: RelayUiState = RelayUiState(),
    onRelayScan: () -> Unit = {},
    onRelayPair: (String) -> Unit = {},
    onRelayConnect: (RelayPairedTarget) -> Unit = {},
    onRelayRemove: (RelayPairedTarget) -> Unit = {},
    onRelayCancelPairing: () -> Unit = {},
    onRelayRetry: () -> Unit = {},
    onCloudSignIn: () -> Unit = {},
    onCloudRefresh: () -> Unit = {},
    onCloudSignOut: () -> Unit = {},
    onCloudSelectOrg: (com.unsupportedpastels.hermesandroid.connection.CloudOrg) -> Unit = {},
    onCloudSelectAgent: suspend (com.unsupportedpastels.hermesandroid.connection.CloudAgent) -> Result<Unit> = {
        Result.success(Unit)
    },
    onLoadManagementSettings: (String) -> Unit = {},
    onRefreshDurableSessions: (Boolean) -> Unit = {},
    onSetProfileDefaultModel: suspend (ModelSelection, Boolean) -> ModelSwitchResult = { _, _ ->
        ModelSwitchResult(accepted = false)
    },
    onSetProfileReasoningEffort: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onSetModelReasoningOverride: suspend (ModelSelection, String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onLogout: suspend () -> Unit = {},
    onSignIn: () -> Unit = {},
    onPasswordSignIn: (String, String) -> Unit = { _, _ -> },
    onRetryConnection: () -> Unit = {},
    onOpenProject: (ProjectId) -> Unit = {},
    onOpenSession: (DurableSessionId) -> Unit = {},
    onLoadSessionInsights: (DurableSessionId) -> Unit = {},
    onCompressSession: (DurableSessionId, String?) -> Unit = { _, _ -> },
    onUndoSession: (DurableSessionId) -> Unit = {},
    onBranchSession: (DurableSessionId, Int?, String?) -> Unit = { _, _, _ -> },
    onRefreshCronJobs: () -> Unit = {},
    onCronJobAction: (String, CronJobAction) -> Unit = { _, _ -> },
    onRunCronJob: (String) -> Unit = {},
    onToggleCronJobRuns: (String) -> Unit = {},
    isHomeRefreshing: Boolean = false,
    onRefreshHome: () -> Unit = {},
    onRefreshWorkingPresence: () -> Unit = {},
    onLoadRecentSessions: () -> Unit = {},
    onLoadMoreRecentSessions: () -> Unit = {},
    onRenameSession: suspend (DurableSessionId, String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onSetSessionPinned: suspend (DurableSessionId, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onSetSessionArchived: suspend (DurableSessionId, Boolean) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onDeleteSession: suspend (DurableSessionId) -> Result<Unit> = { Result.success(Unit) },
    savedSessionFilters: List<SavedSessionFilter> = emptyList(),
    onSaveSessionFilter: suspend (SavedSessionFilter) -> Result<Unit> = { Result.success(Unit) },
    onRemoveSessionFilter: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onSearchTranscripts: (String) -> Unit = {},
    readAloud: MessageReadAloud? = null,
    voiceConversation: ComposerVoiceConversation? = null,
    voiceSettings: VoiceSettings? = null,
    autoSpeakEnabled: Boolean = false,
    voiceScreenOffContinuation: Boolean = false,
    onSendVoiceMessage: (DurableSessionId, String, Boolean) -> Unit = { _, _, _ -> },
    onSendMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
    onSteerMessage: (DurableSessionId, String) -> Unit = { _, _ -> },
    onReasoningSelected: (DurableSessionId, String) -> Unit = { _, _ -> },
    onFastSelected: (DurableSessionId, Boolean) -> Unit = { _, _ -> },
    onClarificationResponse: (DurableSessionId, String, String?, String) -> Unit = { _, _, _, _ -> },
    onApprovalResponse: (DurableSessionId, String, Boolean) -> Unit = { _, _, _ -> },
    onBlockingResponse: (DurableSessionId, UnsupportedBlockingKind, String, String) -> Unit = { _, _, _, _ -> },
    onStopSession: (DurableSessionId) -> Unit = {},
    onRetrySessionConnection: (DurableSessionId) -> Unit = {},
    onGetSessionProgress: (DurableSessionId) -> Unit = {},
    onCreateSession: () -> DurableSessionId? = { null },
    onCreateProjectSession: (ProjectId) -> DurableSessionId? = { null },
    onLoadHostDirectories: suspend (String?) -> Result<HostDirectoryListing> = {
        Result.failure(UnsupportedOperationException("Host folder browsing is unavailable"))
    },
    onLoadHostFiles: suspend (String?) -> Result<HostFileListing> = {
        Result.failure(UnsupportedOperationException("Host file browsing is unavailable"))
    },
    onLoadManagedFile: suspend (String) -> Result<HostFileContent> = {
        Result.failure(UnsupportedOperationException("Managed files are unavailable"))
    },
    onCreateHostDirectory: suspend (String, String) -> Result<HostDirectoryListing> = { _, _ ->
        Result.failure(UnsupportedOperationException("Host folder creation is unavailable"))
    },
    onCreateProject: suspend (String, String) -> Result<ProjectSummary> = { _, _ ->
        Result.failure(UnsupportedOperationException("Project creation is unavailable"))
    },
    onLoadManagedImage: suspend (String) -> Result<ByteArray> = {
        Result.failure(UnsupportedOperationException("Managed images are unavailable"))
    },
    onLoadManagedVideo: suspend (String) -> Result<ManagedVideoMedia> = {
        Result.failure(UnsupportedOperationException("Managed videos are unavailable"))
    },
    onPeekManagedVideo: suspend (String) -> ManagedVideoMedia? = { null },
    modelPickerState: ModelPickerState = ModelPickerState.Closed,
    onOpenModelPicker: (DurableSessionId) -> Unit = {},
    onDismissModelPicker: () -> Unit = {},
    onRetryModelPicker: () -> Unit = {},
    onModelSelected: (ModelSelection) -> Unit = {},
    onConfirmModelSelection: () -> Unit = {},
    slashCompletions: Map<DurableSessionId, SlashCompletionState> = emptyMap(),
    onSlashCompletionRequested: (DurableSessionId, String) -> Unit = { _, _ -> },
    attachments: Map<DurableSessionId, List<ComposerAttachment>> = emptyMap(),
    onAddAttachments: (DurableSessionId, List<ComposerAttachment>) -> List<String> = { _, _ -> emptyList() },
    onRemoveAttachment: (DurableSessionId, String) -> Unit = { _, _ -> },
    projectIcons: Map<ProjectId, ProjectIconId> = emptyMap(),
    onSaveProjectIcon: suspend (ProjectId, ProjectIconId) -> Result<Unit> = { _, _ ->
        Result.success(Unit)
    },
) {
    val loadedProjectState = snapshot.projectState as? ProjectLoadState.Loaded
    val projects = loadedProjectState?.projects ?: snapshot.projects
    val sessionInbox = rememberSessionInboxMetadata(
        durableSessions = snapshot.durableSessions,
        projectSessions = snapshot.projectSessions,
        recentSessionMetadata = snapshot.recentSessions.sessions,
        transcriptSearchResults = snapshot.transcriptSearchResults,
        projects = projects,
    )
    val sessions = sessionInbox.sessions
    val recentSessions = sessionInbox.recentSessions
    val serverOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.activeOrigin
    val connectionScopeKey = snapshot.relayTargetId?.let { "relay:$it" }
        ?: serverOrigin?.value?.let { "direct:$it" }
        ?: "unconfigured"
    val effectiveServerCatalog = when (val ready = serverSettingsState) {
        is ServerSettingsState.Ready -> if (serverCatalog.entries.isEmpty()) ready.catalog else serverCatalog
        else -> serverCatalog
    }
    val saveServerEntry = onSaveServerEntry ?: { entry: ServerCatalogEntry ->
        onSaveServerOrigin(entry.origin)
    }
    val updateServerLabel = onUpdateServerLabel ?: saveServerEntry
    var observedServerOrigin by remember { mutableStateOf(serverOrigin) }
    val initialBackStack = remember(initialRoute, sessions) {
        when (initialRoute) {
            HomeRoute -> arrayOf<NavKey>(HomeRoute)
            is SessionDetailRoute -> {
                val projectId = sessions
                    .firstOrNull { it.id == initialRoute.durableSessionId }
                    ?.projectId
                if (projectId == null) {
                    arrayOf(HomeRoute, initialRoute)
                } else {
                    arrayOf(HomeRoute, ProjectRoute(projectId), initialRoute)
                }
            }
            else -> arrayOf(HomeRoute, initialRoute)
        }
    }
    val backStack = rememberNavBackStack(*initialBackStack)
    val drafts = rememberSaveable(saver = DraftsSaver) { mutableStateMapOf() }
    val hostReferences = rememberSaveable(saver = DraftsSaver) { mutableStateMapOf() }
    val hostReferenceScopeKey = "$connectionScopeKey\u0000${snapshot.selectedProfile}"
    // Owned by the app, not the detail route: navigation must not lose an
    // in-flight acknowledgment. A transport/profile switch invalidates it.
    val pendingComposerSubmissions = remember(hostReferenceScopeKey) {
        mutableStateMapOf<DurableSessionId, PendingComposerSubmission>()
    }
    LaunchedEffect(snapshot.chatSessions, pendingComposerSubmissions.toMap()) {
        pendingComposerSubmissions.toMap().forEach { (sessionId, pending) ->
            val chat = snapshot.chatSessions[sessionId] ?: return@forEach
            if (chat.acceptedSubmissionCount > pending.acceptedCount &&
                chat.acceptedSubmissionText == pending.prompt
            ) {
                if (drafts[pending.draftKey].orEmpty() == pending.draft) {
                    drafts[pending.draftKey] = ""
                    onSlashCompletionRequested(sessionId, "")
                }
                val remaining = hostReferences[pending.referenceKey].orEmpty()
                    .lineSequence()
                    .filter { it.isNotBlank() && it !in pending.references }
                    .toList()
                if (remaining.isEmpty()) hostReferences.remove(pending.referenceKey)
                else hostReferences[pending.referenceKey] = remaining.joinToString("\n")
                pendingComposerSubmissions.remove(sessionId)
            } else if (
                (chat.rejectedSubmissionCount > pending.rejectedCount && chat.rejectedSubmissionText == pending.prompt) ||
                chat.acceptedSubmissionCount > pending.acceptedCount
            ) {
                pendingComposerSubmissions.remove(sessionId)
            }
        }
    }
    val observedSendingSessions = remember { mutableStateMapOf<String, Boolean>() }
    val unreadCompletedSessions = remember { mutableStateMapOf<String, Boolean>() }
    var projectDockState by rememberSaveable {
        mutableStateOf(
            if (initialProjectDockCollapsed) ProjectDockState.Collapsed else ProjectDockState.Expanded,
        )
    }
    LaunchedEffect(persistedProjectDockState) {
        persistedProjectDockState?.let { projectDockState = it }
    }
    var workspaceWidthPx by remember { mutableStateOf(0) }
    var measuredProjectSessionPaneProportion by remember { mutableStateOf<Float?>(null) }
    var iconPickerProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var projectCreatorOpen by rememberSaveable { mutableStateOf(initialProjectCreatorOpen) }
    var shareResultMessage by remember { mutableStateOf<String?>(null) }
    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val supportsListDetail =
        windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
    val windowWidthDp = with(LocalDensity.current) { currentWindowSize().width.toDp() }
    val supportsNavigationRail = windowWidthDp >= PROJECT_DOCK_MIN_WIDTH_DP.dp
    val paneExpansionState = rememberPaneExpansionState(PaneExpansionStateKey.Default)
    LaunchedEffect(projectSessionPaneProportion, supportsListDetail) {
        if (supportsListDetail && projectSessionPaneProportion != null) {
            paneExpansionState.setFirstPaneProportion(
                projectSessionPaneProportion.coerceIn(
                    MIN_PROJECT_SESSION_PANE_PROPORTION,
                    MAX_PROJECT_SESSION_PANE_PROPORTION,
                ),
            )
        }
    }
    LaunchedEffect(measuredProjectSessionPaneProportion, projectSessionPaneProportion) {
        val measured = measuredProjectSessionPaneProportion ?: return@LaunchedEffect
        val persisted = projectSessionPaneProportion ?: return@LaunchedEffect
        if (kotlin.math.abs(measured - persisted) >= 0.005f) {
            delay(400)
            onProjectSessionPaneProportionChanged(measured)
        }
    }
    val recordProjectSessionPaneWidth = { width: Int ->
        if (supportsListDetail && workspaceWidthPx > 0 && width > 0) {
            measuredProjectSessionPaneProportion = (width.toFloat() / workspaceWidthPx)
                .coerceIn(
                    MIN_PROJECT_SESSION_PANE_PROPORTION,
                    MAX_PROJECT_SESSION_PANE_PROPORTION,
                )
        }
        Unit
    }
    val directive = remember(windowAdaptiveInfo, supportsListDetail) {
        calculatePaneScaffoldDirective(windowAdaptiveInfo)
            .copy(
                maxHorizontalPartitions = if (supportsListDetail) 2 else 1,
                horizontalPartitionSpacerSize = 0.dp,
            )
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(
        directive = directive,
        paneExpansionDragHandle = { state ->
            val interactionSource = remember { MutableInteractionSource() }
            VerticalDragHandle(
                modifier = Modifier
                    .testTag("Project session pane resize handle")
                    .paneExpansionDraggable(
                        state = state,
                        minTouchTargetSize = LocalMinimumInteractiveComponentSize.current,
                        interactionSource = interactionSource,
                    ),
                interactionSource = interactionSource,
            )
        },
        paneExpansionState = paneExpansionState,
    )

    val navigateBack = {
        if (backStack.size > 1) backStack.removeLastOrNull()
        Unit
    }
    val navigateToProject = { projectId: ProjectId ->
        while (backStack.size > 1 && backStack.lastOrNull() !is HomeRoute) {
            backStack.removeLastOrNull()
        }
        backStack.add(ProjectRoute(projectId))
        onOpenProject(projectId)
        Unit
    }
    val navigateToSession = { sessionId: DurableSessionId ->
        unreadCompletedSessions.remove(sessionId.value)
        if (backStack.lastOrNull() is SessionDetailRoute) {
            backStack.removeLastOrNull()
        }
        backStack.add(SessionDetailRoute(sessionId))
        Unit
    }
    val navigateToRecentSessions = {
        if (backStack.lastOrNull() !is RecentSessionsRoute) {
            backStack.add(RecentSessionsRoute)
        }
        Unit
    }
    val stageShareIntoSession = { sessionId: DurableSessionId ->
        sharePayload?.let { payload ->
            val draftKey = "$connectionScopeKey\u0000${sessionId.value}"
            val currentDraft = drafts[draftKey].orEmpty()
            drafts[draftKey] = listOf(currentDraft, payload.text)
                .filter(String::isNotBlank)
                .joinToString("\n\n")
            onSlashCompletionRequested(sessionId, drafts[draftKey].orEmpty())
            val skipped = payload.rejections + onAddAttachments(sessionId, payload.attachments)
            if (skipped.isNotEmpty()) {
                shareResultMessage = skipped.distinct().joinToString("\n")
            }
            onSharePayloadConsumed()
            navigateToSession(sessionId)
        }
        Unit
    }
    var handledRequestedSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedSessionRequestId, requestedSessionId, sessions) {
        val sessionId = requestedSessionId
        val requestKey = requestedSessionRequestId
            ?.let { "request:$it" }
            ?: sessionId?.let { "session:${it.value}" }
        if (
            sessionId != null &&
            requestKey != null &&
            requestKey != handledRequestedSessionKey &&
            sessions.any { it.id == sessionId }
        ) {
            handledRequestedSessionKey = requestKey
            navigateToSession(sessionId)
        }
    }
    var handledBranchId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshot.lastBranchedSessionId, sessions) {
        val branchId = snapshot.lastBranchedSessionId
        if (
            branchId != null &&
            branchId.value != handledBranchId &&
            sessions.any { it.id == branchId }
        ) {
            handledBranchId = branchId.value
            navigateToSession(branchId)
        }
    }
    val isUnconfigured =
        serverSettingsState is ServerSettingsState.Ready &&
            effectiveServerCatalog.entries.isEmpty() &&
            snapshot.relayTargetId == null &&
            snapshot.connectionState != ConnectionState.Connected
    val openServerSettings = {
        while (backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(ServerSettingsRoute)
        if (isUnconfigured) {
            // First-run setup is one focused workflow, not a trip through the
            // general settings hub followed by its only available section.
            backStack.add(SettingsServersRoute)
        }
        Unit
    }
    val needsInitialOnboarding =
        isUnconfigured &&
            relayState.isLoaded &&
            relayState.targets.none { it.status == RelayTargetStatus.Approved }
    LaunchedEffect(needsInitialOnboarding) {
        if (
            needsInitialOnboarding && backStack.size == 1 && backStack.lastOrNull() == HomeRoute
        ) {
            openServerSettings()
        }
    }
    val openSettingsSection = { section: SettingsSection ->
        backStack.add(
            when (section) {
                SettingsSection.Servers -> SettingsServersRoute
                SettingsSection.Connection -> SettingsConnectionRoute
                SettingsSection.Model -> SettingsModelRoute
                SettingsSection.Voice -> SettingsVoiceRoute
                SettingsSection.Offline -> SettingsOfflineRoute
                SettingsSection.Jobs -> SettingsJobsRoute
                SettingsSection.Account -> SettingsAccountRoute
            },
        )
        Unit
    }
    val navigateHome = {
        while (backStack.size > 1) backStack.removeLastOrNull()
        Unit
    }
    LaunchedEffect(serverOrigin) {
        if (observedServerOrigin != serverOrigin) {
            while (
                backStack.size > 1 &&
                !backStack.lastOrNull().isSettingsRoute()
            ) {
                backStack.removeLastOrNull()
            }
            drafts.clear()
            observedSendingSessions.clear()
            unreadCompletedSessions.clear()
            handledRequestedSessionKey = null
            handledBranchId = null
            iconPickerProjectId = null
            projectCreatorOpen = false
        }
        observedServerOrigin = serverOrigin
    }
    val selectedProjectId = when (val currentRoute = backStack.lastOrNull()) {
        is ProjectRoute -> currentRoute.projectId
        is SessionDetailRoute -> sessions
            .firstOrNull { it.id == currentRoute.durableSessionId }
            ?.projectId
        else -> null
    }
    val selectedSessionId = (backStack.lastOrNull() as? SessionDetailRoute)?.durableSessionId
    LaunchedEffect(selectedSessionId) {
        onVisibleSessionChanged(selectedSessionId)
    }
    DisposableEffect(Unit) {
        onDispose { onVisibleSessionChanged(null) }
    }
    val workingSessionIds = buildSet {
        snapshot.chatSessions
            .filterValues(ChatSessionSnapshot::isSending)
            .keys
            .forEach(::add)
    }
    LaunchedEffect(workingSessionIds, selectedSessionId) {
        val sessionIds = observedSendingSessions.keys
            .map(::DurableSessionId)
            .toSet() + workingSessionIds
        sessionIds.forEach { sessionId ->
            val key = sessionId.value
            val isSending = sessionId in workingSessionIds
            if (observedSendingSessions[key] == true && !isSending && selectedSessionId != sessionId) {
                unreadCompletedSessions[key] = true
            }
            if (selectedSessionId == sessionId) unreadCompletedSessions.remove(key)
            observedSendingSessions[key] = isSending
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalManagedImageScope provides "$connectionScopeKey|${snapshot.selectedProfile}|${snapshot.activeRuntimes}",
    ) {
    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (supportsNavigationRail && projectDockState != ProjectDockState.Hidden) {
                ProjectDock(
                    state = projectDockState,
                    projects = projects,
                    selectedProjectId = selectedProjectId,
                    projectIcons = projectIcons,
                    canStartNewTask = snapshot.authenticationState == AuthenticationState.Authenticated,
                    settingsSelected = backStack.lastOrNull().isSettingsRoute(),
                    onProjectSelected = navigateToProject,
                    onChooseProjectIcon = { iconPickerProjectId = it.value },
                    onCreateProject = { projectCreatorOpen = true },
                    onNewTask = {
                        val newSessionId = if (selectedProjectId != null) {
                            onCreateProjectSession(selectedProjectId)
                        } else {
                            onCreateSession()
                        }
                        if (newSessionId != null) navigateToSession(newSessionId)
                    },
                    onSettings = openServerSettings,
                    onExpand = {
                        projectDockState = ProjectDockState.Expanded
                        onProjectDockStateChanged(ProjectDockState.Expanded)
                    },
                    onCollapse = {
                        projectDockState = ProjectDockState.Collapsed
                        onProjectDockStateChanged(ProjectDockState.Collapsed)
                    },
                    onHide = {
                        projectDockState = ProjectDockState.Hidden
                        onProjectDockStateChanged(ProjectDockState.Hidden)
                    },
                )
            }
            val renderSettingsSection: @Composable (SettingsSection) -> Unit = { section ->
                // During first-time setup the catalog is empty and the hub only
                // offers Servers, so a successful save or Back should land on Home
                // rather than an otherwise-empty settings hub.
                val sectionBack =
                    if (section == SettingsSection.Servers && effectiveServerCatalog.entries.isEmpty()) {
                        navigateHome
                    } else {
                        navigateBack
                    }
                ServerSettingsScreen(
                    serverOrigin = serverOrigin,
                    serverCatalog = effectiveServerCatalog,
                    snapshot = snapshot,
                    showBack = !supportsListDetail,
                    onBack = sectionBack,
                    onSave = onSaveServerOrigin,
                    onSaveEntry = saveServerEntry,
                    onUpdateServerLabel = updateServerLabel,
                    onSelectServer = onSelectServerOrigin,
                    onRemoveServer = onRemoveServerOrigin,
                    transcriptCachingEnabled = transcriptCachingEnabled,
                    onTranscriptCachingChanged = onTranscriptCachingChanged,
                    onClearOfflineCache = onClearOfflineCache,
                    onLoadManagementSettings = onLoadManagementSettings,
                    onSetProfileDefaultModel = onSetProfileDefaultModel,
                    onSetProfileReasoningEffort = onSetProfileReasoningEffort,
                    onSetModelReasoningOverride = onSetModelReasoningOverride,
                    voiceSettings = voiceSettings,
                    onRefreshCronJobs = onRefreshCronJobs,
                    onCronJobAction = onCronJobAction,
                    onRunCronJob = onRunCronJob,
                    onToggleCronJobRuns = onToggleCronJobRuns,
                    onLogout = onLogout,
                    visibleSections = setOf(section),
                    isInitialOnboarding = section == SettingsSection.Servers && isUnconfigured,
                    title = if (section == SettingsSection.Servers && isUnconfigured) {
                        "Connect to Hermes"
                    } else {
                        section.title
                    },
                    cloudState = cloudState,
                    relayState = relayState,
                    onRelayScan = onRelayScan,
                    onRelayPair = onRelayPair,
                    onRelayConnect = onRelayConnect,
                    onRelayRemove = onRelayRemove,
                    onRelayCancelPairing = onRelayCancelPairing,
                    onRelayRetry = onRelayRetry,
                    onCloudSignIn = onCloudSignIn,
                    onCloudRefresh = onCloudRefresh,
                    onCloudSignOut = onCloudSignOut,
                    onCloudSelectOrg = onCloudSelectOrg,
                    onCloudSelectAgent = onCloudSelectAgent,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .onSizeChanged { workspaceWidthPx = it.width },
            ) {
                key(connectionScopeKey) {
                    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = navigateBack,
        sceneStrategies = listOf(listDetailStrategy),
        entryProvider = entryProvider {
            entry<HomeRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = { SessionPlaceholder() },
                ) + ListDetailSceneStrategy.preferredPaneSize(width = 0.4f),
            ) {
                SessionListScreen(
                    projects = projects,
                    sessions = recentSessions,
                    modifier = Modifier.onSizeChanged {
                        recordProjectSessionPaneWidth(it.width)
                    },
                    projectState = snapshot.projectState,
                    snapshot = snapshot,
                    serverSettingsState = serverSettingsState,
                    initialSearchOpen = initialHomeSearchOpen,
                    showDockOwnedActions = !supportsNavigationRail,
                    isRefreshing = isHomeRefreshing,
                    onRefresh = onRefreshHome,
                    onRefreshWorkingPresence = onRefreshWorkingPresence,
                    onLoadManagementSettings = onLoadManagementSettings,
                    onRefreshDurableSessions = onRefreshDurableSessions,
                    onConfigureServer = openServerSettings,
                    onRetryConnection = onRetryConnection,
                    onSignIn = onSignIn,
                    onPasswordSignIn = onPasswordSignIn,
                    onProjectSelected = navigateToProject,
                    onSessionSelected = navigateToSession,
                    onRecentSessionsSelected = navigateToRecentSessions,
                    onRenameSession = onRenameSession,
                    onSetSessionPinned = onSetSessionPinned,
                    onSetSessionArchived = onSetSessionArchived,
                    onDeleteSession = onDeleteSession,
                    savedSessionFilters = savedSessionFilters,
                    onSaveSessionFilter = onSaveSessionFilter,
                    onRemoveSessionFilter = onRemoveSessionFilter,
                    onSearchTranscripts = onSearchTranscripts,
                    onCreateProject = { projectCreatorOpen = true },
                    onNewSession = {
                        val newSessionId = onCreateSession()
                        if (newSessionId != null) navigateToSession(newSessionId)
                    },
                )
            }
            entry<RecentSessionsRoute>(
                metadata = ListDetailSceneStrategy.detailPane(),
            ) {
                RecentSessionsScreen(
                    snapshot = snapshot,
                    projects = projects,
                    showBack = !supportsListDetail,
                    onBack = navigateBack,
                    onLoad = onLoadRecentSessions,
                    onLoadMore = onLoadMoreRecentSessions,
                    onRefreshWorkingPresence = onRefreshWorkingPresence,
                    onSessionSelected = navigateToSession,
                )
            }
            entry<ProjectRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = { SessionPlaceholder() },
                ) + ListDetailSceneStrategy.preferredPaneSize(width = 0.4f),
            ) { route ->
                val project = projects.firstOrNull { it.id == route.projectId }
                if (project == null) {
                    MissingProjectScreen()
                } else {
                    ProjectDetailScreen(
                        project = project,
                        state = snapshot.projectSessionStates[route.projectId],
                        sessions = snapshot.projectSessions[route.projectId].orEmpty(),
                        workingSessionIds = workingSessionIds,
                        unreadCompletedSessionIds = unreadCompletedSessions
                            .filterValues { it }
                            .keys
                            .mapTo(mutableSetOf(), ::DurableSessionId),
                        modifier = Modifier.onSizeChanged {
                            recordProjectSessionPaneWidth(it.width)
                        },
                        showBack = !supportsListDetail,
                        showNewTaskAction = !supportsNavigationRail,
                        onBack = navigateBack,
                        onSessionSelected = navigateToSession,
                        onNewTask = {
                            val newSessionId = onCreateProjectSession(project.id)
                            if (newSessionId != null) navigateToSession(newSessionId)
                        },
                        onDeleteSession = onDeleteSession,
                    )
                }
            }
            entry<SessionDetailRoute>(
                metadata = ListDetailSceneStrategy.detailPane(),
            ) { route ->
                val session = sessions.firstOrNull { it.id == route.durableSessionId }
                if (session == null) {
                    MissingSessionScreen()
                } else {
                    val draftKey = "$connectionScopeKey\u0000${session.id.value}"
                    val referenceKey = "$hostReferenceScopeKey\u0000${session.id.value}"
                    val stagedHostReferences = hostReferences[referenceKey]
                        .orEmpty()
                        .lineSequence()
                        .filter(String::isNotBlank)
                        .distinct()
                        .toList()
                    val chat = snapshot.chatSessions[session.id] ?: ChatSessionSnapshot()
                    val hasControllerRuntime = snapshot.activeRuntimes.any { runtime ->
                        runtime.durableSessionId == session.id && runtime.access == RuntimeAccess.Controller
                    }
                    val projectDraftMissingWorkspace = session.isLocalDraft &&
                        session.projectId != null &&
                        !isNoProjectBucket(session.projectId) &&
                        validProjectWorkspacePath(session.workspacePath) == null
                    LaunchedEffect(session.id) {
                        onOpenSession(session.id)
                    }
                    SessionDetailScreen(
                        session = session,
                        chat = chat,
                        readAloud = readAloud,
                        voiceConversation = voiceConversation,
                        // Voice turns are attachment-free and skip staged host
                        // references — the spoken words are the whole prompt.
                        onVoiceSubmit = { text, interrupted ->
                            onSendVoiceMessage(session.id, text, interrupted)
                        },
                        autoSpeakEnabled = autoSpeakEnabled,
                        voiceScreenOffContinuation = voiceScreenOffContinuation,
                        voiceInputScopeKey = VoiceInputPolicy.scopeKey(
                            serverOrigin = serverOrigin?.value,
                            profile = snapshot.selectedProfile,
                            durableSessionId = session.id.value,
                        ),
                        draft = drafts[draftKey].orEmpty(),
                        onDraftChanged = { updated ->
                            drafts[draftKey] = updated
                            onSlashCompletionRequested(session.id, updated)
                        },
                        canSend = snapshot.authenticationState == AuthenticationState.Authenticated &&
                            !projectDraftMissingWorkspace,
                        attachments = attachments[session.id].orEmpty(),
                        hostReferences = stagedHostReferences,
                        onAddAttachments = { candidates -> onAddAttachments(session.id, candidates) },
                        onRemoveAttachment = { attachmentId ->
                            onRemoveAttachment(session.id, attachmentId)
                        },
                        onRemoveHostReference = { reference ->
                            // A removed-and-readded reference is a new selection.
                            pendingComposerSubmissions[session.id]?.let { pending ->
                                pendingComposerSubmissions[session.id] = pending.copy(
                                    references = pending.references - reference,
                                )
                            }
                            hostReferences[referenceKey] = stagedHostReferences
                                .filterNot { it == reference }
                                .joinToString("\n")
                        },
                        onSend = { text ->
                            onSlashCompletionRequested(session.id, "")
                            val prompt = (stagedHostReferences + text.takeIf(String::isNotBlank))
                                .filterNotNull()
                                .joinToString("\n")
                            pendingComposerSubmissions[session.id] = PendingComposerSubmission(
                                referenceKey = referenceKey,
                                draftKey = draftKey,
                                draft = drafts[draftKey].orEmpty(),
                                acceptedCount = chat.acceptedSubmissionCount,
                                rejectedCount = chat.rejectedSubmissionCount,
                                prompt = prompt.trim(),
                                references = stagedHostReferences.toSet(),
                            )
                            onSendMessage(session.id, prompt)
                        },
                        onSteer = { text -> onSteerMessage(session.id, text) },
                        onReasoningSelected = { effort -> onReasoningSelected(session.id, effort) },
                        onFastSelected = { fast -> onFastSelected(session.id, fast) },
                        onOpenModelPicker = {
                            onSlashCompletionRequested(session.id, "")
                            onOpenModelPicker(session.id)
                        },
                        onLoadSessionInsights = { onLoadSessionInsights(session.id) },
                        maintenanceAvailable = hasControllerRuntime,
                        maintenanceEnabled = hasControllerRuntime &&
                            !chat.isLoading &&
                            !chat.isSending &&
                            !chat.isStopping &&
                            !chat.maintenanceLoading,
                        onCompressSession = { focusTopic ->
                            onCompressSession(session.id, focusTopic)
                        },
                        onUndoSession = { onUndoSession(session.id) },
                        onBranchSession = { count, name ->
                            onBranchSession(session.id, count, name)
                        },
                        onClarificationResponse = { requestId, questionId, answer ->
                            onClarificationResponse(session.id, requestId, questionId, answer)
                        },
                        onApprovalResponse = { choice, all ->
                            onApprovalResponse(session.id, choice, all)
                        },
                        onBlockingResponse = { kind, requestId, value ->
                            onBlockingResponse(session.id, kind, requestId, value)
                        },
                        showStop = chat.isSending && hasControllerRuntime,
                        stopping = chat.isStopping,
                        onStop = { onStopSession(session.id) },
                        onRetryConnection = { onRetrySessionConnection(session.id) },
                        onGetProgress = { onGetSessionProgress(session.id) },
                        slashCompletion = slashCompletions[session.id]?.takeIf {
                            it.composerText == drafts[draftKey].orEmpty()
                        },
                        onSlashCompletionSelected = { completion, item ->
                            val updated = applySlashCompletion(
                                drafts[draftKey].orEmpty(),
                                item,
                                completion.replaceFrom,
                            )
                            drafts[draftKey] = updated
                            onSlashCompletionRequested(session.id, updated)
                        },
                        showBack = !supportsListDetail,
                        onBack = navigateBack,
                        onLoadManagedImage = onLoadManagedImage,
                        onLoadManagedVideo = onLoadManagedVideo.takeIf { snapshot.relayTargetId == null },
                        onPeekManagedVideo = onPeekManagedVideo.takeIf { snapshot.relayTargetId == null },
                        onLoadHostFiles = onLoadHostFiles,
                        onLoadManagedFile = onLoadManagedFile,
                        onAttachHostReference = { reference ->
                            hostReferences[referenceKey] = (stagedHostReferences + reference)
                                .distinct()
                                .joinToString("\n")
                        },
                    )
                }
            }
            entry<ServerSettingsRoute>(
                metadata = ListDetailSceneStrategy.listPane(
                    detailPlaceholder = { SessionPlaceholder() },
                ) + ListDetailSceneStrategy.preferredPaneSize(width = 0.4f),
            ) {
                val authed = snapshot.authenticationState == AuthenticationState.Authenticated
                val available = if (authed) {
                    SettingsSection.entries.toSet()
                } else {
                    setOf(SettingsSection.Servers)
                }
                SettingsHubScreen(
                    availableSections = available,
                    showBack = !supportsListDetail,
                    onBack = navigateBack,
                    onOpenSection = openSettingsSection,
                )
            }
            entry<SettingsServersRoute>(
                metadata = if (isUnconfigured) emptyMap() else ListDetailSceneStrategy.detailPane(),
            ) {
                renderSettingsSection(SettingsSection.Servers)
            }
            entry<SettingsConnectionRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Connection)
            }
            entry<SettingsModelRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Model)
            }
            entry<SettingsVoiceRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Voice)
            }
            entry<SettingsOfflineRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Offline)
            }
            entry<SettingsJobsRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Jobs)
            }
            entry<SettingsAccountRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Account)
            }
            },
                )
                }
            }
        }
        if (supportsNavigationRail && projectDockState == ProjectDockState.Hidden) {
            ProjectDockEdgeTab(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .zIndex(1f),
                onShow = {
                    projectDockState = ProjectDockState.Collapsed
                    onProjectDockStateChanged(ProjectDockState.Collapsed)
                },
            )
        }
    }
    if (projectCreatorOpen) {
        ProjectCreationSheet(
            initialListing = initialProjectCreatorListing,
            onDismiss = { projectCreatorOpen = false },
            onLoadHostDirectories = onLoadHostDirectories,
            onCreateHostDirectory = onCreateHostDirectory,
            onCreateProject = onCreateProject,
            onCreated = { project ->
                projectCreatorOpen = false
                navigateToProject(project.id)
            },
        )
    }
    if (sharePayload != null) {
        ShareDestinationSheet(
            payload = sharePayload,
            sessions = sessions,
            projects = projects,
            onDismiss = onSharePayloadConsumed,
            onNewChat = { onCreateSession()?.let(stageShareIntoSession) },
            onProjectSelected = { projectId ->
                onCreateProjectSession(projectId)?.let(stageShareIntoSession)
            },
            onSessionSelected = stageShareIntoSession,
        )
    }
    shareResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { shareResultMessage = null },
            title = { Text("Some shared items were skipped") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { shareResultMessage = null }) { Text("Continue") }
            },
        )
    }
    ModelPickerSheet(
        state = modelPickerState,
        onDismiss = onDismissModelPicker,
        onRetry = onRetryModelPicker,
        onSelected = onModelSelected,
        onConfirm = onConfirmModelSelection,
    )
    val iconPickerProject = projects.firstOrNull { it.id.value == iconPickerProjectId }
    if (iconPickerProject != null) {
        ProjectIconPickerSheet(
            project = iconPickerProject,
            selectedIcon = projectIcons[iconPickerProject.id]
                ?: defaultProjectIconId(iconPickerProject),
            onDismiss = { iconPickerProjectId = null },
            onSave = { iconId -> onSaveProjectIcon(iconPickerProject.id, iconId) },
        )
    }
}

}

internal fun serverHostnameLabel(serverOrigin: ServerOrigin?): String {
    val hostname = serverOrigin?.value?.let { origin ->
        runCatching { URI(origin).host }.getOrNull()
    }
    return hostname?.takeIf { it.isNotBlank() } ?: "Hermes"
}

private val previewSessions = listOf(
    SessionSummary(DurableSessionId("stored-1"), "Android client planning"),
    SessionSummary(DurableSessionId("stored-2"), "Foldable UI review"),
    SessionSummary(DurableSessionId("stored-3"), "Hermes protocol notes"),
)

@Preview(name = "Cover screen", widthDp = 400, heightDp = 900, showBackground = true)
@Preview(name = "Unfolded", widthDp = 900, heightDp = 1000, showBackground = true)
@Composable
private fun HermesAppPreview() {
    HermesAndroidTheme {
        HermesApp(
            snapshot = HermesGatewaySnapshot(
                connectionState = ConnectionState.Connected,
                durableSessions = previewSessions,
            ),
        )
    }
}
