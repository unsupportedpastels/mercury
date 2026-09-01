package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.IntrinsicSize

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.material3.adaptive.layout.PaneExpansionStateKey
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.core.content.FileProvider
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.ApprovalInteraction
import com.unsupportedpastels.hermesandroid.app.ClarificationInteraction
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.RunStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.app.UnsupportedBlockingInteraction
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validHostFolderName
import com.unsupportedpastels.hermesandroid.app.isNoProjectBucket
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import com.unsupportedpastels.hermesandroid.artifacts.Artifact
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactExtractor
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactOrigin
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactType
import com.unsupportedpastels.hermesandroid.voice.AutoSpeakEffect
import com.unsupportedpastels.hermesandroid.voice.ComposerVoiceConversation
import com.unsupportedpastels.hermesandroid.voice.VoiceSettings
import com.unsupportedpastels.hermesandroid.voice.VoiceSettingsSection
import com.unsupportedpastels.hermesandroid.voice.MessageReadAloud
import com.unsupportedpastels.hermesandroid.voice.VoiceConversationBar
import com.unsupportedpastels.hermesandroid.voice.VoiceConversationState
import com.unsupportedpastels.hermesandroid.voice.VoiceConversationToggleButton
import com.unsupportedpastels.hermesandroid.voice.rememberReadAloudSession
import com.unsupportedpastels.hermesandroid.voice.rememberVoiceConversationHost
import com.unsupportedpastels.hermesandroid.voice.DeviceSpeechRecognizerController
import com.unsupportedpastels.hermesandroid.voice.DeviceSpeechInputButton
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerCatalog
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ServerConnectionMode
import com.unsupportedpastels.hermesandroid.connection.OriginTransportDecision
import com.unsupportedpastels.hermesandroid.connection.evaluateOriginTransport
import com.unsupportedpastels.hermesandroid.connection.DEFAULT_TUNNEL_ORIGIN
import com.unsupportedpastels.hermesandroid.connection.INSTALLATION_CHANGED_TITLE
import com.unsupportedpastels.hermesandroid.connection.TUNNEL_UNAVAILABLE_BODY
import com.unsupportedpastels.hermesandroid.connection.TunnelTestResult
import com.unsupportedpastels.hermesandroid.connection.MAX_SERVER_LABEL_CHARS
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.SessionSearchResult

import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.CacheSource
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.ContextBreakdownCategory
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.gateway.ValidReasoningEfforts
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileLaunchFailure
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.files.HostFileOpenEvent
import com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicy
import com.unsupportedpastels.hermesandroid.navigation.HomeRoute
import com.unsupportedpastels.hermesandroid.navigation.ProjectRoute
import com.unsupportedpastels.hermesandroid.navigation.RecentSessionsRoute
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.navigation.ServerSettingsRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsServersRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsFilesRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsConnectionRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsModelRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsVoiceRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsOfflineRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsJobsRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsAccountRoute
import com.unsupportedpastels.hermesandroid.session.SavedSessionFilter
import com.unsupportedpastels.hermesandroid.session.SessionListFilter
import com.unsupportedpastels.hermesandroid.share.SharePayload
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import com.unsupportedpastels.hermesandroid.voice.VoiceInputPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.Locale

private val DraftsSaver = Saver<SnapshotStateMap<String, String>, ArrayList<String>>(
    save = { drafts ->
        ArrayList(drafts.entries.flatMap { (sessionId, draft) -> listOf(sessionId, draft) })
    },
    restore = { saved ->
        mutableStateMapOf<String, String>().apply {
            saved.chunked(2).forEach { pair ->
                if (pair.size == 2) put(pair[0], pair[1])
            }
        }
    },
)

internal val SessionStatusPulseAlpha = SemanticsPropertyKey<Float>("SessionStatusPulseAlpha")
private var SemanticsPropertyReceiver.sessionStatusPulseAlpha by SessionStatusPulseAlpha

private const val SESSION_STATUS_PULSE_MILLIS = 900

/**
 * Minimum window width for the project dock rail. Sits between the M3 medium and expanded
 * breakpoints so unfolded foldables (~820dp windows) keep the dock. Compared against the current
 * window, not the device screen, so split-screen windows below this width hide the dock.
 */
private const val PROJECT_DOCK_MIN_WIDTH_DP = 800
private const val HOME_RECENT_SESSION_PREVIEW_LIMIT = 10

private fun mergeSessionCollections(
    durableSessions: List<SessionSummary>,
    projectSessions: List<SessionSummary>,
    searchResults: List<SessionSearchResult>,
): List<SessionSummary> {
    val merged = linkedMapOf<DurableSessionId, SessionSummary>()
    durableSessions.forEach { session -> merged[session.id] = session }
    projectSessions.forEach { projectSession ->
        val existing = merged[projectSession.id]
        merged[projectSession.id] = if (existing == null) {
            projectSession
        } else {
            existing.copy(
                projectId = existing.projectId ?: projectSession.projectId,
                workspacePath = existing.workspacePath ?: projectSession.workspacePath,
            )
        }
    }
    searchResults.forEach { result ->
        if (result.sessionId !in merged) {
            merged[result.sessionId] = SessionSummary(
                id = result.sessionId,
                title = result.title,
                preview = result.snippet,
            )
        }
    }
    return merged.values.toList()
}

private fun projectForSessionWorkspace(
    session: SessionSummary,
    projects: List<ProjectSummary>,
): ProjectSummary? {
    val workspace = validProjectWorkspacePath(session.workspacePath)
        ?.trimEnd('/', '\\')
        ?: return null
    return projects.asSequence()
        .filter { project ->
            val projectPath = validProjectWorkspacePath(project.primaryPath)
                ?.trimEnd('/', '\\')
                ?: return@filter false
            workspace == projectPath ||
                workspace.startsWith("$projectPath/") ||
                workspace.startsWith("$projectPath\\")
        }
        .maxByOrNull { project ->
            validProjectWorkspacePath(project.primaryPath)?.length ?: 0
        }
}

internal fun sessionStatusPulseAlphaAt(playTimeMillis: Long): Float {
    val boundedTime = playTimeMillis.coerceAtLeast(0L) % (SESSION_STATUS_PULSE_MILLIS * 2L)
    val phase = if (boundedTime <= SESSION_STATUS_PULSE_MILLIS) {
        boundedTime.toFloat() / SESSION_STATUS_PULSE_MILLIS
    } else {
        (SESSION_STATUS_PULSE_MILLIS * 2L - boundedTime).toFloat() / SESSION_STATUS_PULSE_MILLIS
    }
    val easedPhase = FastOutSlowInEasing.transform(phase)
    return 1f + (0.35f - 1f) * easedPhase
}


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
    inAppFilePreviewEnabled: Boolean = false,
    onInAppFilePreviewChanged: (Boolean) -> Unit = {},
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
    onRetryConnection: () -> Unit = {},
    onAcceptNewInstallation: () -> Unit = {},
    onCancelRecovery: () -> Unit = {},
    onTestTunnel: suspend (ServerOrigin) -> TunnelTestResult = {
        TunnelTestResult.Failure(
            com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure.TunnelUnavailable,
            TUNNEL_UNAVAILABLE_BODY,
        )
    },
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
    onReasoningSelected: (DurableSessionId, String) -> Unit = { _, _ -> },
    onFastSelected: (DurableSessionId, Boolean) -> Unit = { _, _ -> },
    onClarificationResponse: (DurableSessionId, String, String) -> Unit = { _, _, _ -> },
    onApprovalResponse: (DurableSessionId, String, Boolean) -> Unit = { _, _, _ -> },
    onBlockingResponse: (DurableSessionId, UnsupportedBlockingKind, String, String) -> Unit = { _, _, _, _ -> },
    onStopSession: (DurableSessionId) -> Unit = {},
    onSetDelegationPaused: (DurableSessionId, Boolean) -> Unit = { _, _ -> },
    onSteerSubagent: (DurableSessionId, String, String) -> Unit = { _, _, _ -> },
    onInterruptSubagent: (DurableSessionId, String) -> Unit = { _, _ -> },
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
    val mergedSessions = mergeSessionCollections(
        durableSessions = snapshot.durableSessions,
        projectSessions = snapshot.projectSessions.values.flatten() + snapshot.recentSessions.sessions,
        searchResults = snapshot.transcriptSearchResults,
    )
    val loadedProjectState = snapshot.projectState as? ProjectLoadState.Loaded
    val projects = loadedProjectState?.projects ?: snapshot.projects
    val sessions = mergedSessions.map { session ->
        session.copy(projectId = session.projectId ?: projectForSessionWorkspace(session, projects)?.id)
    }
    val recentSessions = sessions
        .sortedByDescending { it.lastActiveEpochSeconds ?: Double.NEGATIVE_INFINITY }
        .take(HOME_RECENT_SESSION_PREVIEW_LIMIT)
    val serverOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.activeOrigin
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
            val draftKey = "${serverOrigin?.value.orEmpty()}\u0000${sessionId.value}"
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
    val openServerSettings = {
        while (backStack.size > 1) backStack.removeLastOrNull()
        backStack.add(ServerSettingsRoute)
        Unit
    }
    val openSettingsSection = { section: SettingsSection ->
        backStack.add(
            when (section) {
                SettingsSection.Servers -> SettingsServersRoute
                SettingsSection.Files -> SettingsFilesRoute
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
                    inAppFilePreviewEnabled = inAppFilePreviewEnabled,
                    onInAppFilePreviewChanged = onInAppFilePreviewChanged,
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
                    title = section.title,
                    cloudState = cloudState,
                    onCloudSignIn = onCloudSignIn,
                    onCloudRefresh = onCloudRefresh,
                    onCloudSignOut = onCloudSignOut,
                    onCloudSelectOrg = onCloudSelectOrg,
                    onCloudSelectAgent = onCloudSelectAgent,
                    onTestTunnel = onTestTunnel,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .onSizeChanged { workspaceWidthPx = it.width },
            ) {
                key(serverOrigin?.value.orEmpty()) {
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
                    onLoadManagementSettings = onLoadManagementSettings,
                    onRefreshDurableSessions = onRefreshDurableSessions,
                    onConfigureServer = openServerSettings,
                    onRetryConnection = onRetryConnection,
                    onAcceptNewInstallation = onAcceptNewInstallation,
                    onCancelRecovery = onCancelRecovery,
                    onSignIn = onSignIn,
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
                    val draftKey = "${serverOrigin?.value.orEmpty()}\u0000${session.id.value}"
                    val stagedHostReferences = hostReferences[draftKey]
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
                    val latestUserMessageIndex = chat.messages
                        .indexOfLast { it.role == ChatMessageRole.User }
                    val hasAcceptedRunActivity = chat.runState.status != null ||
                        chat.runState.tools.isNotEmpty() ||
                        chat.runState.todos.isNotEmpty() ||
                        chat.processRows.isNotEmpty() ||
                        chat.runState.clarification != null ||
                        chat.runState.approval != null ||
                        chat.runState.unsupportedBlocking != null ||
                        chat.messages
                            .drop(latestUserMessageIndex + 1)
                            .any { it.role == ChatMessageRole.Assistant }
                    val latestAcceptedUserText = chat.messages
                        .getOrNull(latestUserMessageIndex)
                        ?.text
                        ?.takeIf { hasAcceptedRunActivity }
                    LaunchedEffect(session.id, latestAcceptedUserText) {
                        val currentDraft = drafts[draftKey].orEmpty()
                        if (
                            latestAcceptedUserText != null &&
                            currentDraft.trim() == latestAcceptedUserText.trim()
                        ) {
                            drafts[draftKey] = ""
                            onSlashCompletionRequested(session.id, "")
                        }
                    }
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
                            hostReferences[draftKey] = stagedHostReferences
                                .filterNot { it == reference }
                                .joinToString("\n")
                        },
                        onSend = { text ->
                            onSlashCompletionRequested(session.id, "")
                            val prompt = (stagedHostReferences + text.takeIf(String::isNotBlank))
                                .filterNotNull()
                                .joinToString("\n")
                            hostReferences.remove(draftKey)
                            onSendMessage(session.id, prompt)
                        },
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
                        onClarificationResponse = { requestId, answer ->
                            onClarificationResponse(session.id, requestId, answer)
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
                        delegationStatus = snapshot.delegationStatus,
                        delegationAvailable = hasControllerRuntime &&
                            snapshot.delegationStatus.active.isNotEmpty(),
                        onSetDelegationPaused = { paused ->
                            onSetDelegationPaused(session.id, paused)
                        },
                        onSteerSubagent = { subagentId, text ->
                            onSteerSubagent(session.id, subagentId, text)
                        },
                        onInterruptSubagent = { subagentId ->
                            onInterruptSubagent(session.id, subagentId)
                        },
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
                        snapshot = snapshot,
                        onRetryConnection = onRetryConnection,
                        onConfigureServer = openServerSettings,
                        onCancelRecovery = onCancelRecovery,
                        onAcceptNewInstallation = onAcceptNewInstallation,
                        onLoadManagedImage = onLoadManagedImage,
                        onLoadHostFiles = onLoadHostFiles,
                        onLoadManagedFile = onLoadManagedFile,
                        onAttachHostReference = { reference ->
                            hostReferences[draftKey] = (stagedHostReferences + reference)
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
            entry<SettingsServersRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Servers)
            }
            entry<SettingsFilesRoute>(metadata = ListDetailSceneStrategy.detailPane()) {
                renderSettingsSection(SettingsSection.Files)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareDestinationSheet(
    payload: SharePayload,
    sessions: List<SessionSummary>,
    projects: List<ProjectSummary>,
    onDismiss: () -> Unit,
    onNewChat: () -> Unit,
    onProjectSelected: (ProjectId) -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Send to chat", style = MaterialTheme.typography.headlineSmall)
            Text(
                buildString {
                    if (payload.text.isNotBlank()) append("Shared text")
                    if (payload.text.isNotBlank() && payload.attachments.isNotEmpty()) append(" · ")
                    if (payload.attachments.isNotEmpty()) append("${payload.attachments.size} attachment(s)")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Text("New chat")
            }
            if (projects.isNotEmpty()) {
                Text("Projects", style = MaterialTheme.typography.titleSmall)
                projects.take(8).forEach { project ->
                    ListItem(
                        headlineContent = { Text(project.label) },
                        supportingContent = { Text("Start a new task here") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProjectSelected(project.id) }
                            .semantics {
                                contentDescription = "Share with project ${project.label}"
                            },
                    )
                }
            }
            if (sessions.isNotEmpty()) {
                Text("Recent chats", style = MaterialTheme.typography.titleSmall)
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(sessions.take(20), key = { it.id.value }) { session ->
                        ListItem(
                            headlineContent = { Text(session.title) },
                            supportingContent = session.preview?.takeIf(String::isNotBlank)?.let { preview ->
                                { Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSessionSelected(session.id) }
                                .semantics {
                                    contentDescription = "Share with ${session.title}"
                                },
                        )
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCreationSheet(
    initialListing: HostDirectoryListing? = null,
    onDismiss: () -> Unit,
    onLoadHostDirectories: suspend (String?) -> Result<HostDirectoryListing>,
    onCreateHostDirectory: suspend (String, String) -> Result<HostDirectoryListing>,
    onCreateProject: suspend (String, String) -> Result<ProjectSummary>,
    onCreated: (ProjectSummary) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var projectName by rememberSaveable { mutableStateOf("") }
    var pathInput by rememberSaveable { mutableStateOf(initialListing?.path.orEmpty()) }
    var listing by remember { mutableStateOf(initialListing) }
    var loading by remember { mutableStateOf(initialListing == null) }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showNewFolder by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }

    suspend fun loadPath(path: String?) {
        loading = true
        errorMessage = null
        onLoadHostDirectories(path).fold(
            onSuccess = { loaded ->
                listing = loaded
                pathInput = loaded.path
            },
            onFailure = { error ->
                errorMessage = projectCreationError(error, "Could not open that host folder")
            },
        )
        loading = false
    }

    LaunchedEffect(initialListing) {
        if (initialListing == null) loadPath(null)
    }

    val sheetContent: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .fillMaxHeight()
                    .align(Alignment.Center)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("Create project sheet"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Create project", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Choose an existing folder on the Hermes host, or create a folder there.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = projectName,
                    onValueChange = { projectName = it.take(ProjectSummary.MAX_LABEL_LENGTH) },
                    label = { Text("Project name") },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("Project name input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { updated ->
                            pathInput = updated.take(1_024)
                            if (updated != listing?.path) listing = null
                        },
                        label = { Text("Host folder") },
                        singleLine = true,
                        enabled = !loading && !submitting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("Host folder input"),
                    )
                    Button(
                        onClick = {
                            coroutineScope.launch { loadPath(pathInput.trim()) }
                        },
                        enabled = !loading && !submitting &&
                            validProjectWorkspacePath(pathInput) != null,
                    ) {
                        Text("Open")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            listing?.parentPath?.let { parent ->
                                coroutineScope.launch { loadPath(parent) }
                            }
                        },
                        enabled = !loading && !submitting && listing?.parentPath != null,
                    ) {
                        Text("Up")
                    }
                    TextButton(
                        onClick = {
                            listing?.path?.let { current ->
                                coroutineScope.launch { loadPath(current) }
                            }
                        },
                        enabled = !loading && !submitting && listing != null,
                    ) {
                        Text("Refresh")
                    }
                    listing?.let { current ->
                        Text(
                            current.path,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                when {
                    loading -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Loading host folders…")
                    }
                    listing != null -> {
                        val directories = listing!!.directories
                        if (directories.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    "No subfolders here",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .testTag("Host directory list"),
                            ) {
                                items(directories, key = { it.path }) { directory ->
                                    ListItem(
                                        headlineContent = { Text(directory.name) },
                                        supportingContent = {
                                            Text(
                                                directory.path,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !submitting) {
                                                coroutineScope.launch { loadPath(directory.path) }
                                            },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                    else -> Spacer(modifier = Modifier.weight(1f))
                }
                TextButton(
                    onClick = { showNewFolder = !showNewFolder },
                    enabled = listing != null && !loading && !submitting,
                    modifier = Modifier.testTag("Toggle create host folder"),
                ) {
                    Text(if (showNewFolder) "Cancel new folder" else "Create folder here")
                }
                if (showNewFolder) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it.take(255) },
                            label = { Text("New folder name") },
                            singleLine = true,
                            enabled = !submitting,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("New folder name input"),
                        )
                        Button(
                            onClick = {
                                val parent = listing?.path ?: return@Button
                                val name = validHostFolderName(newFolderName) ?: return@Button
                                submitting = true
                                errorMessage = null
                                coroutineScope.launch {
                                    onCreateHostDirectory(parent, name).fold(
                                        onSuccess = { created ->
                                            listing = created
                                            pathInput = created.path
                                            if (projectName.isBlank()) projectName = name
                                            newFolderName = ""
                                            showNewFolder = false
                                        },
                                        onFailure = { error ->
                                            errorMessage = projectCreationError(
                                                error,
                                                "Could not create that host folder",
                                            )
                                        },
                                    )
                                    submitting = false
                                }
                            },
                            enabled = !submitting && validHostFolderName(newFolderName) != null,
                            modifier = Modifier.testTag("Confirm create host folder"),
                        ) {
                            Text("Create folder")
                        }
                    }
                }
                errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !submitting,
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val selectedPath = listing?.path ?: return@Button
                            val name = projectName.trim()
                            submitting = true
                            errorMessage = null
                            coroutineScope.launch {
                                onCreateProject(name, selectedPath).fold(
                                    onSuccess = onCreated,
                                    onFailure = { error ->
                                        errorMessage = projectCreationError(
                                            error,
                                            "Could not create the project",
                                        )
                                    },
                                )
                                submitting = false
                            }
                        },
                        enabled = !loading && !submitting && listing != null &&
                            projectName.trim().isNotEmpty() && pathInput == listing?.path,
                        modifier = Modifier.testTag("Confirm create project"),
                    ) {
                        Text(if (submitting) "Creating…" else "Create project")
                    }
                }
            }
        }
    }
    if (LocalInspectionMode.current) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 1.dp,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                sheetContent()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = { if (!submitting) onDismiss() },
            sheetState = sheetState,
        ) {
            sheetContent()
        }
    }
}

private fun projectCreationError(error: Throwable?, fallback: String): String =
    error?.message
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(180)
        ?: fallback

@Composable
private fun ProjectDock(
    state: ProjectDockState,
    projects: List<ProjectSummary>,
    selectedProjectId: ProjectId?,
    projectIcons: Map<ProjectId, ProjectIconId>,
    canStartNewTask: Boolean,
    settingsSelected: Boolean,
    onProjectSelected: (ProjectId) -> Unit,
    onChooseProjectIcon: (ProjectId) -> Unit,
    onCreateProject: () -> Unit,
    onNewTask: () -> Unit,
    onSettings: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onHide: () -> Unit,
) {
    val expanded = state == ProjectDockState.Expanded
    val dockWidth by animateDpAsState(
        targetValue = if (expanded) 228.dp else 76.dp,
        label = "Project dock width",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(dockWidth)
            .fillMaxSize()
            .semantics {
                contentDescription = if (expanded) {
                    "Project dock, expanded"
                } else {
                    "Project dock, collapsed"
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Fill the status-bar inset with the dock's own surface so app
            // content reads as starting *under* the system bar rather than
            // bleeding up behind a transparent status bar.
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars),
            )
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (expanded) 12.dp else 10.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            if (!expanded) {
                ProjectDockControl(
                    glyph = "›",
                    description = "Expand project dock",
                    onClick = onExpand,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // The expanded dock's collapse control floats in this top band;
                // keep the project list itself flush so the first option fills
                // the space immediately below the status-bar inset.
                contentPadding = PaddingValues(top = 2.dp, bottom = 2.dp),
            ) {
                itemsIndexed(projects, key = { _, project -> project.id.value }) { index, project ->
                    val iconId = projectIcons[project.id] ?: defaultProjectIconId(project)
                    val iconLabel = ProjectIconCatalog.entries.first { it.id == iconId }.label
                    ProjectDockAction(
                        glyph = projectDockInitial(project.label),
                        icon = projectIconVector(iconId),
                        iconDescription = "${project.label} icon $iconLabel",
                        label = project.label,
                        description = "Open project ${project.label}",
                        expanded = expanded,
                        selected = project.id == selectedProjectId,
                        trailingContent = if (expanded && project.id == selectedProjectId) {
                            {
                                IconButton(
                                    onClick = { onChooseProjectIcon(project.id) },
                                    modifier = Modifier
                                        // The floating collapse control occupies the
                                        // top-right corner. Keep the first row's edit
                                        // target clear without moving the row itself.
                                        .offset(x = if (index == 0) (-44).dp else 0.dp)
                                        .semantics {
                                        contentDescription = "Choose icon for ${project.label}"
                                        },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        onClick = { onProjectSelected(project.id) },
                    )
                }
            }
            HorizontalDivider()
            ProjectDockAction(
                glyph = "+",
                icon = Icons.Outlined.CreateNewFolder,
                iconDescription = null,
                label = "Create project",
                description = "Create project",
                expanded = expanded,
                selected = false,
                enabled = canStartNewTask,
                onClick = onCreateProject,
            )
            ProjectDockAction(
                glyph = "+",
                label = "New task",
                description = selectedProjectId
                    ?.let { id -> projects.firstOrNull { it.id == id }?.label }
                    ?.let { "New task in $it" }
                    ?: "New task",
                expanded = expanded,
                selected = false,
                enabled = canStartNewTask,
                accent = true,
                onClick = onNewTask,
            )
            ProjectDockAction(
                glyph = "⚙",
                label = "Settings",
                description = "Settings navigation",
                expanded = expanded,
                selected = settingsSelected,
                onClick = onSettings,
            )
            if (!expanded) {
                ProjectDockControl(
                    glyph = "‹",
                    description = "Hide project dock",
                    onClick = onHide,
                )
            }
            }
            // Floating collapse control: overlays the top-right corner so the
            // nav list can start flush at the top instead of reserving a full
            // header row (which left a large empty band on the left).
            if (expanded) {
                ProjectDockControl(
                    glyph = "‹",
                    description = "Collapse project dock",
                    onClick = onCollapse,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 6.dp, top = 2.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun ProjectDockAction(
    glyph: String,
    label: String,
    description: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accent: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconDescription: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val containerColor = when {
        accent -> semanticColors.active
        selected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val contentColor = when {
        accent -> semanticColors.onActive
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val actionModifier = if (expanded) {
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    } else {
        Modifier.size(48.dp)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = actionModifier.semantics {
            contentDescription = description
            this.selected = selected
        },
    ) {
        Row(
            modifier = if (expanded) {
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            } else {
                Modifier.fillMaxSize()
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.spacedBy(10.dp) else Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .then(
                                iconDescription?.let { description ->
                                    Modifier.semantics { contentDescription = description }
                                } ?: Modifier,
                            ),
                    )
                } else {
                    Text(glyph, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (expanded) {
                Text(
                    label,
                    modifier = if (trailingContent != null) Modifier.weight(1f) else Modifier,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                trailingContent?.invoke()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectIconPickerSheet(
    project: ProjectSummary,
    selectedIcon: ProjectIconId,
    onDismiss: () -> Unit,
    onSave: suspend (ProjectIconId) -> Result<Unit>,
) {
    var query by rememberSaveable(project.id.value) { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable(project.id.value) { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val normalizedQuery = query.trim().lowercase()
    val visibleIcons = remember(normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            ProjectIconCatalog.entries
        } else {
            ProjectIconCatalog.entries.filter { option ->
                option.label.lowercase().contains(normalizedQuery) ||
                    option.searchTerms.any { it.contains(normalizedQuery) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose project icon", style = MaterialTheme.typography.headlineSmall)
            Text(
                project.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it.take(80)
                    saveError = null
                },
                enabled = !isSaving,
                singleLine = true,
                label = { Text("Search icons") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search project icons" },
            )
            saveError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (visibleIcons.isEmpty()) {
                Text(
                    "No matching icons",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 84.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    gridItems(ProjectIconCatalog.entries.filter { it in visibleIcons }, key = { it.id.persistedValue }) { option ->
                        val selected = option.id == selectedIcon
                        Surface(
                            onClick = {
                                if (!isSaving) {
                                    coroutineScope.launch {
                                        isSaving = true
                                        saveError = null
                                        val result = onSave(option.id)
                                        isSaving = false
                                        if (result.isSuccess) {
                                            onDismiss()
                                        } else {
                                            saveError = "Could not save icon. Try again."
                                        }
                                    }
                                }
                            },
                            enabled = !isSaving,
                            selected = selected,
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            modifier = Modifier
                                .heightIn(min = 80.dp)
                                .semantics {
                                    contentDescription = "Project icon ${option.label}"
                                    this.selected = selected
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = projectIconVector(option.id),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                                Text(
                                    option.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectDockControl(
    glyph: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                glyph,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun ProjectDockEdgeTab(
    modifier: Modifier = Modifier,
    onShow: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 72.dp)
            .semantics { contentDescription = "Show project dock" }
            .clickable(onClick = onShow),
    ) {
        Surface(
            shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .size(width = 16.dp, height = 72.dp)
                .align(Alignment.CenterStart),
        ) {}
        Text(
            "›",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

private fun projectDockInitial(label: String): String {
    val initials = label
        .trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
        .joinToString(separator = "") { it.uppercaseChar().toString() }
    return initials.ifBlank { "•" }
}

internal fun serverHostnameLabel(serverOrigin: ServerOrigin?): String {
    val hostname = serverOrigin?.value?.let { origin ->
        runCatching { URI(origin).host }.getOrNull()
    }
    return hostname?.takeIf { it.isNotBlank() } ?: "Hermes"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    state: ModelPickerState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (ModelSelection) -> Unit,
    onConfirm: () -> Unit,
) {
    if (state == ModelPickerState.Closed) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose model", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Applies to this session only",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            when (state) {
                ModelPickerState.Closed -> Unit
                is ModelPickerState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Loading models…")
                    }
                }
                is ModelPickerState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
                is ModelPickerState.Ready -> {
                    ModelPickerReadyContent(
                        state = state,
                        onDismiss = onDismiss,
                        onRetry = onRetry,
                        onSelected = onSelected,
                        onConfirm = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerReadyContent(
    state: ModelPickerState.Ready,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (ModelSelection) -> Unit,
    onConfirm: () -> Unit,
) {
    val providers = state.options.providers
    state.confirmationMessage?.let { confirmation ->
        Text(confirmation, color = MaterialTheme.colorScheme.onSurface)
        state.pendingSelection?.let { selection ->
            Text(
                "${selection.provider} · ${selection.model}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.applying, onClick = onConfirm) {
                Text(if (state.applying) "Applying…" else "Use model")
            }
            TextButton(enabled = !state.applying, onClick = onDismiss) { Text("Cancel") }
        }
        return
    }
    if (providers.isEmpty()) {
        Text("No configured models are available for this profile.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
        return
    }

    val initialProvider = state.options.current
        ?.provider
        ?.takeIf { current -> providers.any { it.slug == current } }
        ?: providers.first().slug
    var selectedProviderSlug by remember(state.durableSessionId, providers) {
        mutableStateOf(initialProvider)
    }
    var query by rememberSaveable(state.durableSessionId.value) { mutableStateOf("") }
    val selectedProvider = providers.firstOrNull { it.slug == selectedProviderSlug }
        ?: providers.first()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        providers.forEach { provider ->
            FilterChip(
                selected = provider.slug == selectedProvider.slug,
                onClick = {
                    selectedProviderSlug = provider.slug
                    query = ""
                },
                enabled = !state.applying,
                label = { Text(provider.name) },
                modifier = Modifier.semantics {
                    contentDescription = "Provider ${provider.name}"
                },
            )
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(128) },
        label = { Text("Search ${selectedProvider.name} models") },
        singleLine = true,
        enabled = !state.applying,
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    val matchingModels = selectedProvider.models.filter { model ->
        query.isBlank() || model.contains(query.trim(), ignoreCase = true)
    }
    if (matchingModels.isEmpty()) {
        Text("No models match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            items(matchingModels, key = { model -> "${selectedProvider.slug}:$model" }) { model ->
                val selection = ModelSelection(selectedProvider.slug, model)
                val isCurrent = selection == state.options.current
                ModelPickerRow(
                    provider = selectedProvider,
                    model = model,
                    current = isCurrent,
                    enabled = !state.applying,
                    onClick = { onSelected(selection) },
                )
            }
        }
    }
    if (state.applying) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text("Applying model…")
        }
    }
}

@Composable
private fun ModelPickerRow(
    provider: ModelProviderOption,
    model: String,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(model) },
        supportingContent = { Text(provider.name) },
        trailingContent = {
            if (current) {
                Text("Current", color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = current }
            .clickable(enabled = enabled, onClick = onClick),
    )
    HorizontalDivider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionListScreen(
    projects: List<ProjectSummary>,
    sessions: List<SessionSummary>,
    modifier: Modifier = Modifier,
    projectState: ProjectLoadState,
    snapshot: HermesGatewaySnapshot,
    serverSettingsState: ServerSettingsState,
    initialSearchOpen: Boolean,
    showDockOwnedActions: Boolean = true,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onLoadManagementSettings: (String) -> Unit = {},
    onRefreshDurableSessions: (Boolean) -> Unit = {},
    onConfigureServer: () -> Unit,
    onRetryConnection: () -> Unit = {},
    onAcceptNewInstallation: () -> Unit = {},
    onCancelRecovery: () -> Unit = {},
    onSignIn: () -> Unit,
    onProjectSelected: (ProjectId) -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
    onRecentSessionsSelected: () -> Unit,
    onRenameSession: suspend (DurableSessionId, String) -> Result<Unit>,
    onSetSessionPinned: suspend (DurableSessionId, Boolean) -> Result<Unit>,
    onSetSessionArchived: suspend (DurableSessionId, Boolean) -> Result<Unit>,
    onDeleteSession: suspend (DurableSessionId) -> Result<Unit>,
    savedSessionFilters: List<SavedSessionFilter>,
    onSaveSessionFilter: suspend (SavedSessionFilter) -> Result<Unit>,
    onRemoveSessionFilter: suspend (String) -> Result<Unit>,
    onSearchTranscripts: (String) -> Unit,
    onCreateProject: () -> Unit,
    onNewSession: () -> Unit = {},
) {
    val connectionState = snapshot.connectionState
    val semanticColors = LocalHermesSemanticColors.current
    val serverOrigin = (serverSettingsState as? ServerSettingsState.Ready)?.serverOrigin
    val canStartNewChat = snapshot.authenticationState == AuthenticationState.Authenticated
    var searchOpen by rememberSaveable { mutableStateOf(initialSearchOpen) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var savedFilterMenuOpen by remember { mutableStateOf(false) }
    var saveFilterDialogOpen by remember { mutableStateOf(false) }
    var saveFilterName by remember { mutableStateOf("") }

    var editingSession by remember { mutableStateOf<SessionSummary?>(null) }
    var deletingSession by remember { mutableStateOf<SessionSummary?>(null) }
    var pendingDelete by remember { mutableStateOf<SessionSummary?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sessionActionScope = rememberCoroutineScope()
    val currentFilter = SessionListFilter.fromSearchQuery(searchQuery)
    val pinnedOnly = currentFilter.pinnedOnly
    val archivedOnly = currentFilter.archivedOnly
    val normalizedSearch = currentFilter.query
    val visibleProjects = projects.filter { project ->
        normalizedSearch.isEmpty() ||
            project.label.contains(normalizedSearch, ignoreCase = true) ||
            project.primaryPath?.contains(normalizedSearch, ignoreCase = true) == true ||
            project.previewSessions.any { session ->
                session.title.contains(normalizedSearch, ignoreCase = true)
            }
    }
    val visibleSessions = sessions.filter { session ->
        session.id != pendingDelete?.id &&
            (!pinnedOnly || session.pinned) &&
            (!archivedOnly || session.archived) &&
            (
                normalizedSearch.isEmpty() ||
                    session.title.contains(normalizedSearch, ignoreCase = true) ||
                    session.workspacePath?.contains(normalizedSearch, ignoreCase = true) == true
            )
    }
    val activeControllerSessionIds = snapshot.activeRuntimes
        .filter { it.access == RuntimeAccess.Controller }
        .mapNotNull { it.durableSessionId }
        .toSet()
    var observedFilterScopeKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverOrigin, snapshot.authenticationState) {
        if (
            serverOrigin != null &&
            snapshot.authenticationState == AuthenticationState.Authenticated
        ) {
            onLoadManagementSettings(snapshot.selectedProfile)
        }
    }
    // The durable listing is fetched with archived=exclude by default, which would
    // hide every archived row behind an `is:archived` filter; refetch with the
    // archived-only query while that filter is active, and restore the exclude
    // listing when it is cleared.
    LaunchedEffect(archivedOnly, serverOrigin, snapshot.selectedProfile) {
        if (serverOrigin != null && snapshot.authenticationState == AuthenticationState.Authenticated) {
            onRefreshDurableSessions(archivedOnly)
        }
    }
    LaunchedEffect(serverOrigin, snapshot.selectedProfile) {
        val scopeKey = "${serverOrigin?.value.orEmpty()}\u0000${snapshot.selectedProfile}"
        if (observedFilterScopeKey != null && observedFilterScopeKey != scopeKey) {
            searchQuery = ""
            searchOpen = false
        }
        observedFilterScopeKey = scopeKey
    }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            if (showDockOwnedActions && canStartNewChat) {
                Surface(
                    onClick = dropUnlessResumed { onNewSession() },
                    shape = MaterialTheme.shapes.small,
                    color = semanticColors.active,
                    contentColor = semanticColors.onActive,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "New task" },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                    }
                }
            }
        },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val context = connectionContext(snapshot, serverOrigin)
                        val contextColor = when {
                            connectionState == ConnectionState.Disconnected && serverOrigin != null ->
                                MaterialTheme.colorScheme.error
                            connectionState == ConnectionState.Disconnected ->
                                MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.semantics {
                                contentDescription = "Sessions. Connection: $context"
                                stateDescription = context
                            },
                        ) {
                            Text(
                                serverHostnameLabel(serverOrigin),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Agent workspace", style = MaterialTheme.typography.labelMedium)
                                Text("·", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    context,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = contextColor,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                searchOpen = !searchOpen
                                if (!searchOpen) searchQuery = ""
                            },
                            modifier = Modifier.semantics {
                                contentDescription = if (searchOpen) {
                                    "Close search"
                                } else {
                                    "Search projects and sessions"
                                }
                            },
                        ) {
                            Text(
                                if (searchOpen) "×" else "⌕",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        if (savedSessionFilters.isNotEmpty()) {
                            Box {
                                TextButton(
                                    onClick = { savedFilterMenuOpen = true },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Saved session filters"
                                    },
                                ) { Text("Filters") }
                                DropdownMenu(
                                    expanded = savedFilterMenuOpen,
                                    onDismissRequest = { savedFilterMenuOpen = false },
                                ) {
                                    savedSessionFilters.forEach { saved ->
                                        DropdownMenuItem(
                                            text = { Text(saved.name) },
                                            onClick = {
                                                searchOpen = true
                                                searchQuery = saved.filter.toSearchQuery()
                                                onSearchTranscripts(searchQuery)
                                                savedFilterMenuOpen = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Remove ${saved.name}") },
                                            onClick = {
                                                sessionActionScope.launch {
                                                    onRemoveSessionFilter(saved.name)
                                                        .onFailure { snackbarHostState.showSnackbar("Could not remove saved filter") }
                                                }
                                                savedFilterMenuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (showDockOwnedActions) {
                            IconButton(
                                enabled = canStartNewChat,
                                onClick = dropUnlessResumed { onCreateProject() },
                                modifier = Modifier.semantics {
                                    contentDescription = "Create project"
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CreateNewFolder,
                                    contentDescription = null,
                                )
                            }
                            IconButton(
                                enabled = serverSettingsState !is ServerSettingsState.Loading,
                                onClick = dropUnlessResumed { onConfigureServer() },
                                modifier = Modifier.semantics {
                                    contentDescription = "Settings"
                                },
                            ) {
                                Text("⚙", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    },
                )
                if (snapshot.sessionMetadataSource == CacheSource.Cached) {
                    Text(
                        "Cached offline data — reconnecting to Hermes",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                ConnectionRecoveryBanner(
                    snapshot = snapshot,
                    onRetry = onRetryConnection,
                    onConnectionSetup = onConfigureServer,
                    onCancel = onCancelRecovery,
                    onAcceptNewServer = onAcceptNewInstallation,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (searchOpen) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("Opaque project search"),
                        color = MaterialTheme.colorScheme.background,
                        shadowElevation = 3.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it.take(128)
                                    if (searchQuery.trim().length >= 2) onSearchTranscripts(searchQuery)
                                    else onSearchTranscripts("")
                                },
                                label = { Text("Search projects and sessions") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    enabled = searchQuery.isNotBlank(),
                                    onClick = {
                                        saveFilterName = ""
                                        saveFilterDialogOpen = true
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Save current session filter"
                                    },
                                ) { Text("Save filter") }
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .testTag("Home pull to refresh"),
        ) {
            if (projects.isEmpty() && sessions.isEmpty()) {
            val (title, supportingText) = when {
                serverSettingsState is ServerSettingsState.Loading ->
                    "Loading server settings" to "Reading the saved server origin."
                serverSettingsState is ServerSettingsState.Unavailable ->
                    "Server settings unavailable" to "Open Server to replace the saved origin."
                connectionState == ConnectionState.Connected &&
                    snapshot.authenticationState == AuthenticationState.SignInRequired ->
                    "Server reachable" to
                        "Hermes ${snapshot.serverVersion ?: "unknown"} · Sign in required"
                connectionState == ConnectionState.Connected &&
                    snapshot.authenticationState == AuthenticationState.SigningIn ->
                    "Signing in to Hermes" to "Complete sign-in in your browser"
                connectionState == ConnectionState.Disconnected && serverOrigin == null ->
                    "No server configured" to "Add the HTTPS origin of your Hermes server."
                connectionState == ConnectionState.Disconnected ->
                    "Server configured" to serverOrigin?.value.orEmpty()
                connectionState == ConnectionState.Connecting ->
                    "Connecting" to "Waiting for the Hermes server."
                connectionState == ConnectionState.Connected ->
                    "No saved sessions" to "This server has no durable transcripts yet."
                else ->
                    "Reconnecting" to "Reconciling sessions with the Hermes server."
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            supportingText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (snapshot.tunnelConnectionFailure == null) {
                            snapshot.connectionError?.let { connectionError ->
                                Text(
                                    connectionError,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (
                            snapshot.authenticationState == AuthenticationState.SignInRequired &&
                            snapshot.nativeOAuthSupported &&
                            snapshot.authProviders.any { it.name == "nous" }
                        ) {
                            Button(onClick = dropUnlessResumed { onSignIn() }) {
                                Text("Sign in with Nous")
                            }
                        } else if (
                            connectionState == ConnectionState.Disconnected &&
                            serverSettingsState is ServerSettingsState.Ready &&
                            snapshot.tunnelConnectionFailure == null
                        ) {
                            if (serverOrigin != null) {
                                Button(onClick = dropUnlessResumed { onRetryConnection() }) {
                                    Text("Retry")
                                }
                            }
                            TextButton(onClick = dropUnlessResumed { onConfigureServer() }) {
                                Text(if (serverOrigin == null) "Configure server" else "Edit server")
                            }
                        } else if (
                            connectionState == ConnectionState.Disconnected &&
                            serverSettingsState is ServerSettingsState.Ready &&
                            serverOrigin == null
                        ) {
                            TextButton(onClick = dropUnlessResumed { onConfigureServer() }) {
                                Text("Configure server")
                            }
                        }
                    }
            }
        } else {
            val homeListState = rememberLazyListState()
            var userHasScrolled by remember { mutableStateOf(false) }
            val homeScrollObserver = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (isUserInitiatedHomeScroll(available, source)) userHasScrolled = true
                        return Offset.Zero
                    }
                }
            }
            // Sections load in asynchronously above the initial anchor (projects arrive
            // after "Recent Sessions"), and keyed LazyColumn items keep the viewport
            // anchored to the old first item — so pin to the top until the user scrolls.
            LaunchedEffect(homeListState) {
                snapshotFlow {
                    homeListState.firstVisibleItemIndex to homeListState.firstVisibleItemScrollOffset
                }.collect { (index, offset) ->
                    val decision = decideHomeListPinning(
                        userHasScrolled = userHasScrolled,
                        firstVisibleItemIndex = index,
                        firstVisibleItemScrollOffset = offset,
                    )
                    userHasScrolled = decision.userHasScrolled
                    if (decision.pinToTop) {
                        homeListState.scrollToItem(0)
                    }
                }
            }
            val layoutDirection = LocalLayoutDirection.current
            LazyColumn(
                state = homeListState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(homeScrollObserver),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
            ) {
                if (snapshot.delegationStatus.active.isNotEmpty()) {
                    item(key = "running-subagents-heading") {
                        Text(
                            "Running subagents",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(
                        snapshot.delegationStatus.active,
                        key = { "subagent:${it.subagentId}" },
                    ) { subagent ->
                        RunningSubagentRow(subagent)
                    }
                }
                if (normalizedSearch.isNotEmpty() && snapshot.transcriptSearchResults.isNotEmpty()) {
                    item(key = "transcript-search-heading") {
                        Text(
                            "Transcript matches",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(snapshot.transcriptSearchResults, key = { "search:${it.sessionId.value}" }) { result ->
                        ListItem(
                            headlineContent = { Text(result.title) },
                            supportingContent = {
                                Text(result.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier.clickable { onSessionSelected(result.sessionId) },
                        )
                    }
                }
                if (visibleProjects.isNotEmpty() && projectState is ProjectLoadState.Loaded) {
                    val sendingSessionIds = snapshot.chatSessions
                        .filterValues(ChatSessionSnapshot::isSending)
                        .keys
                    val workingProjectIds = buildSet {
                        snapshot.durableSessions
                            .asSequence()
                            .filter { it.id in sendingSessionIds }
                            .mapNotNull(SessionSummary::projectId)
                            .forEach(::add)
                        snapshot.projectSessions.forEach { (projectId, sessions) ->
                            if (sessions.any { it.id in sendingSessionIds }) add(projectId)
                        }
                    }
                    item(key = "projects-heading") {
                        Text(
                            "Projects",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    items(visibleProjects, key = { "project:${it.id.value}" }) { project ->
                        ProjectHomeRow(
                            project = project,
                            working = project.id in workingProjectIds,
                            onClick = dropUnlessResumed { onProjectSelected(project.id) },
                        )
                    }
                }
                item(key = "recent-sessions-heading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Recent Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = dropUnlessResumed { onRecentSessionsSelected() },
                            modifier = Modifier.semantics {
                                contentDescription = "View all recent sessions"
                            },
                        ) { Text("View all") }
                    }
                }
                if (visibleSessions.isEmpty()) {
                    item(key = "recent-sessions-empty") {
                        Text(
                            "No recent sessions",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    items(visibleSessions, key = { "session:${it.id.value}" }) { session ->
                        val isCurrent = session.id in activeControllerSessionIds
                        val row: @Composable () -> Unit = {
                            RecentSessionHomeRow(
                                session = session,
                                projectLabel = session.projectId
                                    ?.let { projectId -> projects.firstOrNull { it.id == projectId }?.label },
                                current = isCurrent,
                                onClick = dropUnlessResumed {
                                    onSessionSelected(session.id)
                                },
                                onRename = { editingSession = session },
                                onPin = { sessionActionScope.launch { onSetSessionPinned(session.id, !session.pinned) } },
                                onArchive = { sessionActionScope.launch { onSetSessionArchived(session.id, !session.archived) } },
                                onDelete = { deletingSession = session },
                            )
                        }
                        SwipeSessionRow(
                            onDeleteRequest = { deletingSession = session },
                            content = row,
                        )
                    }
                }
            }
            }
        }
    }
    editingSession?.let { session ->
        var title by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { editingSession = null },
            title = { Text("Rename session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(512) },
                        label = { Text("Session title") },
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        sessionActionScope.launch { onSetSessionPinned(session.id, !session.pinned) }
                        editingSession = null
                    }) { Text(if (session.pinned) "Unpin session" else "Pin session") }
                    TextButton(onClick = {
                        sessionActionScope.launch { onSetSessionArchived(session.id, !session.archived) }
                        editingSession = null
                    }) { Text(if (session.archived) "Restore session" else "Archive session") }
                    TextButton(onClick = {
                        editingSession = null
                        deletingSession = session
                    }) { Text("Delete session") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        sessionActionScope.launch { onRenameSession(session.id, title.trim()) }
                        editingSession = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSession = null }) { Text("Cancel") } },
        )
    }
    if (saveFilterDialogOpen) {
        AlertDialog(
            onDismissRequest = { saveFilterDialogOpen = false },
            title = { Text("Save session filter") },
            text = {
                OutlinedTextField(
                    value = saveFilterName,
                    onValueChange = { saveFilterName = it.take(64) },
                    label = { Text("Filter name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = saveFilterName.trim().isNotEmpty(),
                    onClick = {
                        val filter = SavedSessionFilter(saveFilterName.trim(), currentFilter)
                        sessionActionScope.launch {
                            onSaveSessionFilter(filter)
                                .onSuccess {
                                    saveFilterDialogOpen = false
                                    snackbarHostState.showSnackbar("Filter saved")
                                }
                                .onFailure {
                                    snackbarHostState.showSnackbar("Could not save session filter")
                                }
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { saveFilterDialogOpen = false }) { Text("Cancel") }
            },
        )
    }

    deletingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("Delete session?") },
            text = { Text("This permanently deletes ${session.title} from Hermes Serve.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = session
                    deletingSession = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingSession = null }) { Text("Cancel") } },
        )
    }
    LaunchedEffect(pendingDelete?.id) {
        val session = pendingDelete ?: return@LaunchedEffect
        val result = withTimeoutOrNull(5_000) {
            snackbarHostState.showSnackbar(
                message = "${session.title} will be deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) {
            pendingDelete = null
        } else if (pendingDelete?.id == session.id) {
            onDeleteSession(session.id)
            pendingDelete = null
        }
    }
}

@Composable
private fun SwipeSessionRow(
    onDeleteRequest: () -> Unit,
    backgroundPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    backgroundShape: Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit,
) {
    // The state's confirmValueChange lambda is captured once at creation, so
    // route the callback through rememberUpdatedState to avoid stale captures
    // when the row recomposes with a fresh SessionSummary.
    val currentDeleteRequest by rememberUpdatedState(onDeleteRequest)
    // Resizing the list pane can make swipe anchors coincide and request a
    // dismissal without input. Track a real pointer transition through the
    // post-release settlement phase instead of requiring the pointer to remain
    // pressed while confirmValueChange runs.
    var pointerPressed by remember { mutableStateOf(false) }
    var gestureSettlingToDelete by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    val requestDelete = shouldRequestSwipeDelete(
                        pointerPressed = pointerPressed,
                        gestureSettlingToDelete = gestureSettlingToDelete,
                    )
                    gestureSettlingToDelete = false
                    if (requestDelete) currentDeleteRequest()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> false
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                SwipeActionBackground(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    icon = Icons.Outlined.Delete,
                    label = "Delete",
                    alignment = Alignment.CenterStart,
                    padding = backgroundPadding,
                    shape = backgroundShape,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pointerPressed = true
                    gestureSettlingToDelete = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        pointerPressed = false
                        gestureSettlingToDelete =
                            dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
                    }
                }
            },
        ) {
            content()
        }
    }
}

internal data class HomeListPinDecision(
    val userHasScrolled: Boolean,
    val pinToTop: Boolean,
)

internal fun decideHomeListPinning(
    userHasScrolled: Boolean,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): HomeListPinDecision {
    return HomeListPinDecision(
        userHasScrolled = userHasScrolled,
        pinToTop = !userHasScrolled &&
            (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0),
    )
}

internal fun isUserInitiatedHomeScroll(
    available: Offset,
    source: NestedScrollSource,
): Boolean = source == NestedScrollSource.UserInput && available != Offset.Zero

internal fun shouldRequestSwipeDelete(
    pointerPressed: Boolean,
    gestureSettlingToDelete: Boolean,
): Boolean = pointerPressed || gestureSettlingToDelete

@Composable
private fun SwipeActionBackground(
    color: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    label: String,
    alignment: Alignment,
    padding: PaddingValues,
    shape: Shape,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(color, shape)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Text(label, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RunningSubagentRow(subagent: DelegatedSubagent) {
    val statusLine = buildString {
        append(subagent.status)
        subagent.parentSubagentId?.let { append(" · child of ").append(it) }
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Running subagent: ${subagent.goal}, $statusLine"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                subagent.goal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectHomeRow(
    project: ProjectSummary,
    working: Boolean,
    onClick: () -> Unit,
) {
    val sessionLabel = if (project.sessionCount == 1) "1 session" else "${project.sessionCount} sessions"
    val latestTitle = project.previewSessions.firstOrNull()?.title
    val description = buildString {
        append("Project ")
        append(project.label)
        if (working) append(", active session running")
        append(", ")
        append(sessionLabel)
        if (latestTitle != null) {
            append(", latest ")
            append(latestTitle)
        }
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (working) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (working) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = if (working) 1.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("Project home row:${project.label}")
            .semantics(mergeDescendants = true) {
                selected = working
                contentDescription = description
                if (working) stateDescription = "Active session running"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    project.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (working) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    sessionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                validProjectWorkspacePath(project.primaryPath) ?: "No workspace",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            latestTitle?.let { latest ->
                Text(
                    "Latest · $latest",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RecentSessionHomeRow(
    session: SessionSummary,
    projectLabel: String? = null,
    current: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (current) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("Recent session row:${session.id.value}")
            .combinedClickable(onClick = onClick, onLongClick = onRename)
            .semantics(mergeDescendants = true) {
                if (current) stateDescription = "Current controller session"
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val supportingLabel = listOfNotNull(
                    if (session.isLocalDraft) "Draft" else null,
                    if (current) "Controller active" else null,
                    projectLabel,
                ).joinToString(" · ")
                Text(
                    session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (supportingLabel.isNotEmpty()) {
                    Text(
                        supportingLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentSessionsScreen(
    snapshot: HermesGatewaySnapshot,
    projects: List<ProjectSummary>,
    showBack: Boolean,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onLoadMore: () -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
) {
    val state = snapshot.recentSessions
    val listState = rememberLazyListState()
    val projectBySessionId = buildMap {
        snapshot.projectSessions.forEach { (projectId, sessions) ->
            sessions.forEach { session -> put(session.id, projectId) }
        }
    }
    val projectById = projects.associateBy(ProjectSummary::id)
    val sessions = state.sessions.map { session ->
        val projectId = session.projectId
            ?: projectBySessionId[session.id]
            ?: projectForSessionWorkspace(session, projects)?.id
        val projectSession = projectId?.let { id ->
            snapshot.projectSessions[id]?.firstOrNull { it.id == session.id }
        }
        session.copy(
            projectId = projectId,
            workspacePath = session.workspacePath ?: projectSession?.workspacePath,
        )
    }
    val activeControllerSessionIds = snapshot.activeRuntimes
        .filter { it.access == RuntimeAccess.Controller }
        .mapNotNull { it.durableSessionId }
        .toSet()

    LaunchedEffect(snapshot.selectedProfile, snapshot.authenticationState) {
        if (snapshot.authenticationState in setOf(
                AuthenticationState.Authenticated,
                AuthenticationState.NotRequired,
            )
        ) onLoad()
    }
    LaunchedEffect(listState, state.hasMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= sessions.size - 5) onLoadMore()
            }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Recent Sessions")
                        val count = state.total ?: sessions.size
                        Text(
                            "$count across all projects",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) {
                            Text("Back")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("Recent sessions full list"),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            ),
        ) {
            if (state.isLoading && sessions.isEmpty()) {
                item(key = "recent-sessions-loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            } else if (state.error != null && sessions.isEmpty()) {
                item(key = "recent-sessions-error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onLoad) { Text("Retry") }
                    }
                }
            } else if (sessions.isEmpty()) {
                item(key = "recent-sessions-page-empty") {
                    Text(
                        "No recent sessions",
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                items(sessions, key = { "recent-page-session:${it.id.value}" }) { session ->
                    val projectLabel = session.projectId
                        ?.let(projectById::get)
                        ?.label
                        ?: session.projectId?.value
                        ?: "No project"
                    RecentSessionFullRow(
                        session = session,
                        projectLabel = projectLabel,
                        current = session.id in activeControllerSessionIds,
                        onClick = dropUnlessResumed { onSessionSelected(session.id) },
                    )
                }
                if (state.isLoadingMore) {
                    item(key = "recent-sessions-loading-more") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                } else if (state.error != null) {
                    item(key = "recent-sessions-load-more-error") {
                        TextButton(
                            onClick = onLoadMore,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Could not load more · Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSessionFullRow(
    session: SessionSummary,
    projectLabel: String,
    current: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (current) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                if (current) stateDescription = "Current controller session"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                projectLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
            )
            session.workspacePath?.let { workspace ->
                Text(
                    workspace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            session.preview?.let { preview ->
                Text(
                    preview,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun connectionContext(
    snapshot: HermesGatewaySnapshot,
    serverOrigin: ServerOrigin?,
): String = when (snapshot.connectionState) {
    ConnectionState.Connected -> when (snapshot.authenticationState) {
        AuthenticationState.SignInRequired -> "Sign in required"
        AuthenticationState.SigningIn -> "Signing in"
        else -> "Connected"
    }
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Recovering ->
        if (snapshot.tunnelConnectionFailure ==
            com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure.InstallationChanged
        ) {
            INSTALLATION_CHANGED_TITLE
        } else {
            "Reconnecting"
        }
    ConnectionState.Disconnected -> if (serverOrigin == null) "Not configured" else "Offline"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetailScreen(
    project: ProjectSummary,
    state: ProjectSessionLoadState?,
    sessions: List<SessionSummary>,
    workingSessionIds: Set<DurableSessionId>,
    unreadCompletedSessionIds: Set<DurableSessionId>,
    modifier: Modifier = Modifier,
    showBack: Boolean,
    showNewTaskAction: Boolean = true,
    onBack: () -> Unit,
    onSessionSelected: (DurableSessionId) -> Unit,
    onNewTask: () -> Unit,
    onDeleteSession: suspend (DurableSessionId) -> Result<Unit>,
) {
    val semanticColors = LocalHermesSemanticColors.current
    var deletingSession by remember { mutableStateOf<SessionSummary?>(null) }
    val sessionActionScope = rememberCoroutineScope()
    val loadedSessions = when (state) {
        is ProjectSessionLoadState.Loaded ->
            if (sessions.isEmpty()) state.sessions else sessions
        else -> emptyList()
    }
    val workspace = validProjectWorkspacePath(project.primaryPath)
    val workspaceLabel = workspace ?: "No workspace"
    Scaffold(
        modifier = modifier.semantics {
            contentDescription = "Project sessions for ${project.label}"
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(project.label)
                        Text("Project", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (showNewTaskAction && state is ProjectSessionLoadState.Loaded) {
                        TextButton(onClick = dropUnlessResumed { onNewTask() }) {
                            Text("New task")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Sessions inbox, ${project.sessionCount} sessions"
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sessions",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        project.sessionCount.toString(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    "Workspace: $workspaceLabel",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (workspace == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            when (state) {
                null,
                ProjectSessionLoadState.Loading,
                -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading project sessions")
                    }
                }
                ProjectSessionLoadState.Unsupported -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Project sessions unavailable")
                    }
                }
                is ProjectSessionLoadState.TransientError -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Could not load project sessions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ProjectSessionLoadState.Loaded -> {
                    if (loadedSessions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No sessions in this project")
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
                        ) {
                            items(loadedSessions, key = { it.id.value }) { session ->
                                val isWorking = session.id in workingSessionIds
                                val isUnreadComplete = !isWorking && session.id in unreadCompletedSessionIds
                                SwipeSessionRow(
                                    onDeleteRequest = { deletingSession = session },
                                    backgroundPadding = PaddingValues(0.dp),
                                    backgroundShape = RectangleShape,
                                ) {
                                    Surface(color = MaterialTheme.colorScheme.surface) {
                                        SessionInboxRow(
                                            session = session,
                                            projectLabel = project.label,
                                            isWorking = isWorking,
                                            isUnreadComplete = isUnreadComplete,
                                            activeColor = semanticColors.active,
                                            completedColor = semanticColors.completed,
                                            onClick = { onSessionSelected(session.id) },
                                        )
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    deletingSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("Delete session?") },
            text = { Text("This permanently deletes ${session.title} from Hermes Serve.") },
            confirmButton = {
                TextButton(onClick = {
                    sessionActionScope.launch { onDeleteSession(session.id) }
                    deletingSession = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingSession = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionInboxRow(
    session: SessionSummary,
    projectLabel: String,
    isWorking: Boolean,
    isUnreadComplete: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    completedColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val workspace = validProjectWorkspacePath(session.workspacePath)
    val workspaceLabel = workspace ?: "No workspace"
    val ownerLabel = session.profile ?: projectLabel
    val preview = session.preview?.trim()?.takeIf(String::isNotEmpty)
    val recency = session.lastActiveEpochSeconds?.let(::formatSessionRecency)
    val metadata = listOfNotNull(
        session.model?.trim()?.takeIf(String::isNotEmpty),
        session.messageCount?.let { count -> "$count ${if (count == 1) "message" else "messages"}" },
    ).joinToString(" · ")
    val rowDescription = buildString {
        append("Session ${session.title}, $workspaceLabel")
        if (isWorking) append(", running")
        if (isUnreadComplete) append(", completed unread")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = dropUnlessResumed { onClick() })
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics {
                contentDescription = rowDescription
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isWorking -> PulsingSessionStatusIndicator(
                    color = activeColor,
                    contentDescription = "${session.title} is running",
                    size = 10.dp,
                )
                isUnreadComplete -> Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(completedColor, androidx.compose.foundation.shape.CircleShape)
                        .semantics {
                            contentDescription = "${session.title} completed; unread"
                        },
                )
                else -> Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.outlineVariant,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ownerLabel,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                if (recency != null) {
                    Text(
                        recency,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics {
                            contentDescription = "Last active time available"
                        },
                    )
                }
            }
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (preview != null) {
                Text(
                    preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (metadata.isNotEmpty()) {
                Text(
                    metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                Text(
                    workspaceLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (workspace == null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (session.isLocalDraft) {
                Text(
                    "Draft",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatSessionRecency(epochSeconds: Double): String {
    val timestampMillis = (epochSeconds * 1_000.0).toLong()
    return DateUtils.getRelativeTimeSpanString(
        timestampMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@Composable
private fun PulsingSessionStatusIndicator(
    color: androidx.compose.ui.graphics.Color,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val pulse = rememberInfiniteTransition(label = "Session running pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SESSION_STATUS_PULSE_MILLIS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Session running indicator alpha",
    )
    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .background(color, androidx.compose.foundation.shape.CircleShape)
            .semantics {
                this.contentDescription = contentDescription
                sessionStatusPulseAlpha = alpha
            },
    )
}

@Composable
private fun MissingProjectScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Project is no longer available")
    }
}

/**
 * The distinct areas of the settings surface. Each maps to a hub row and a
 * section route so the settings screen renders one focused area at a time
 * instead of a single long scroll.
 */
internal enum class SettingsSection(val title: String, val summary: String) {
    Servers("Servers", "Add, switch, or remove Hermes servers"),
    Files("Files", "How files from chat open on this phone"),
    Connection("Connection & profile", "Version, sign-in, and active profile"),
    Model("Default model", "Model and reasoning for new chats"),
    Voice("Voice", "Dictation and hands-free conversation"),
    Offline("Offline & privacy", "Save conversations for offline reading"),
    Jobs("Scheduled jobs", "Cron jobs running on this server"),
    Account("Account", "Sign out of this server"),
}

/** True for the settings hub or any of its section routes. */
private fun NavKey?.isSettingsRoute(): Boolean = when (this) {
    ServerSettingsRoute,
    SettingsServersRoute,
    SettingsFilesRoute,
    SettingsConnectionRoute,
    SettingsModelRoute,
    SettingsVoiceRoute,
    SettingsOfflineRoute,
    SettingsJobsRoute,
    SettingsAccountRoute,
    -> true
    else -> false
}

/**
 * Settings landing: a compact list of sections instead of one giant scroll.
 * Each row navigates to its own section route; [availableSections] hides rows
 * (Connection/Model/Voice/Files/Offline/Jobs/Account) that require an authenticated
 * connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsHubScreen(
    availableSections: Set<SettingsSection>,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) { Text("Back") }
                    }
                },
                actions = {
                    if (!showBack) {
                        TextButton(onClick = dropUnlessResumed { onBack() }) { Text("Close") }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSection.entries
                .filter { it in availableSections }
                .forEach { section ->
                    ListItem(
                        headlineContent = { Text(section.title) },
                        supportingContent = { Text(section.summary) },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenSection(section) }
                            .semantics { contentDescription = "Open ${section.title} settings" },
                    )
                    HorizontalDivider()
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ServerSettingsScreen(
    serverOrigin: ServerOrigin?,
    serverCatalog: ServerCatalog = ServerCatalog.empty(),
    snapshot: HermesGatewaySnapshot = HermesGatewaySnapshot(),
    showBack: Boolean,
    onBack: () -> Unit,
    onSave: suspend (ServerOrigin) -> Result<Unit>,
    onSaveEntry: suspend (ServerCatalogEntry) -> Result<Unit> = { entry -> onSave(entry.origin) },
    onUpdateServerLabel: suspend (ServerCatalogEntry) -> Result<Unit> = { entry -> onSaveEntry(entry) },
    onSelectServer: suspend (ServerOrigin) -> Result<Unit> = { origin -> onSave(origin) },
    onRemoveServer: suspend (ServerOrigin) -> Result<Unit> = { _ ->
        Result.failure(UnsupportedOperationException("Removing servers is unavailable"))
    },
    transcriptCachingEnabled: Boolean = false,
    onTranscriptCachingChanged: (Boolean) -> Unit = {},
    inAppFilePreviewEnabled: Boolean = false,
    onInAppFilePreviewChanged: (Boolean) -> Unit = {},
    onClearOfflineCache: () -> Unit = {},
    onLoadManagementSettings: (String) -> Unit = {},
    onSetProfileDefaultModel: suspend (ModelSelection, Boolean) -> ModelSwitchResult = { _, _ ->
        ModelSwitchResult(accepted = false)
    },
    onSetProfileReasoningEffort: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    onSetModelReasoningOverride: suspend (ModelSelection, String) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onRefreshCronJobs: () -> Unit = {},
    onCronJobAction: (String, CronJobAction) -> Unit = { _, _ -> },
    onRunCronJob: (String) -> Unit = {},
    onToggleCronJobRuns: (String) -> Unit = {},
    onLogout: suspend () -> Unit = {},
    voiceSettings: VoiceSettings? = null,
    visibleSections: Set<SettingsSection> = SettingsSection.entries.toSet(),
    title: String = "Hermes server",
    cloudState: com.unsupportedpastels.hermesandroid.connection.CloudConnectState? = null,
    onCloudSignIn: () -> Unit = {},
    onCloudRefresh: () -> Unit = {},
    onCloudSignOut: () -> Unit = {},
    onCloudSelectOrg: (com.unsupportedpastels.hermesandroid.connection.CloudOrg) -> Unit = {},
    onCloudSelectAgent: suspend (com.unsupportedpastels.hermesandroid.connection.CloudAgent) -> Result<Unit> = {
        Result.success(Unit)
    },
    onTestTunnel: suspend (ServerOrigin) -> TunnelTestResult = {
        TunnelTestResult.Failure(
            com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure.TunnelUnavailable,
            TUNNEL_UNAVAILABLE_BODY,
        )
    },
) {
    var value by rememberSaveable(serverOrigin?.value) {
        mutableStateOf(serverOrigin?.value.orEmpty())
    }
    var label by rememberSaveable(serverOrigin?.value) {
        mutableStateOf(serverCatalog.activeEntry?.label.orEmpty())
    }
    var editingOrigin by rememberSaveable(serverOrigin?.value) { mutableStateOf<ServerOrigin?>(null) }
    var pendingRemoval by remember { mutableStateOf<ServerCatalogEntry?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var modelQuery by rememberSaveable { mutableStateOf("") }
    var pendingExpensive by remember { mutableStateOf<ModelSelection?>(null) }
    var modelPickerOpen by rememberSaveable { mutableStateOf(false) }
    var recentModels by rememberSaveable(
        stateSaver = listSaver(
            save = { it.flatMap { sel -> listOf(sel.provider, sel.model) } },
            restore = { flat ->
                flat.chunked(2).mapNotNull { pair ->
                    pair.takeIf { it.size == 2 }?.let { ModelSelection(it[0], it[1]) }
                }
            },
        ),
    ) { mutableStateOf(emptyList<ModelSelection>()) }
    var expensiveMessage by remember { mutableStateOf<String?>(null) }
    val cloudActive = cloudState != null &&
        cloudState !is com.unsupportedpastels.hermesandroid.connection.CloudConnectState.SignedOut
    val initialConnectMode = when {
        serverCatalog.activeEntry?.connectionMode ==
            ServerConnectionMode.ExternalSshTunnel -> ConnectMode.ExternalSshTunnel
        cloudActive -> ConnectMode.Cloud
        else -> ConnectMode.ServerUrl
    }
    var connectMode by rememberConnectMode(initial = initialConnectMode)
    var serverUrlDraft by rememberSaveable(serverOrigin?.value) {
        mutableStateOf(
            if (serverCatalog.activeEntry?.connectionMode ==
                ServerConnectionMode.ExternalSshTunnel
            ) {
                ""
            } else {
                serverOrigin?.value.orEmpty()
            },
        )
    }
    var tunnelOriginDraft by rememberSaveable(serverOrigin?.value) {
        mutableStateOf(
            if (serverCatalog.activeEntry?.connectionMode ==
                ServerConnectionMode.ExternalSshTunnel
            ) {
                serverOrigin?.value ?: DEFAULT_TUNNEL_ORIGIN
            } else {
                DEFAULT_TUNNEL_ORIGIN
            },
        )
    }
    var showExperimentalReasons by rememberSaveable { mutableStateOf(false) }
    var testingTunnel by remember { mutableStateOf(false) }
    var tunnelTestResult by remember { mutableStateOf<TunnelTestResult?>(null) }
    LaunchedEffect(serverOrigin, snapshot.authenticationState) {
        if (serverOrigin != null && snapshot.authenticationState == AuthenticationState.Authenticated) {
            onLoadManagementSettings(snapshot.selectedProfile)
            onRefreshCronJobs()
        }
    }
    val selectedConnectionMode = if (connectMode == ConnectMode.ExternalSshTunnel) {
        ServerConnectionMode.ExternalSshTunnel
    } else {
        ServerConnectionMode.Direct
    }
    val parsedOrigin = remember(value) {
        runCatching { ServerOrigin.parse(value) }.getOrNull()
    }
    val validationMessage = remember(value, selectedConnectionMode) {
        if (value.isBlank()) {
            null
        } else {
            val parsed = runCatching { ServerOrigin.parse(value) }
            parsed.exceptionOrNull()?.message
                ?: (evaluateOriginTransport(parsed.getOrThrow(), selectedConnectionMode)
                    as? OriginTransportDecision.Rejected)?.message
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (showBack) {
                        TextButton(
                            enabled = !isSaving,
                            onClick = dropUnlessResumed { onBack() },
                        ) {
                            Text("Back")
                        }
                    }
                },
                actions = {
                    if (!showBack) {
                        TextButton(
                            enabled = !isSaving,
                            onClick = dropUnlessResumed { onBack() },
                        ) {
                            Text("Close")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
        val compactSelector = !windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(
            WIDTH_DP_MEDIUM_LOWER_BOUND,
        )
        val shortViewport = LocalConfiguration.current.screenHeightDp < 800
        val compactConnectionSelector = compactSelector || shortViewport
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(
                        horizontal = 24.dp,
                        vertical = if (shortViewport) 8.dp else 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (shortViewport) 8.dp else 16.dp),
            ) {
                if (SettingsSection.Servers in visibleSections) {
                    Text("Servers", style = MaterialTheme.typography.titleMedium)
                    // Three explicit connection types. Catalog save carries the
                    // chosen mode so tunnel HTTP loopback is evaluated as tunnel,
                    // not Direct.
                    val compactTunnel = connectMode == ConnectMode.ExternalSshTunnel &&
                        (compactSelector || shortViewport)
                    val applyConnectMode: (ConnectMode) -> Unit = { next ->
                        when (connectMode) {
                            ConnectMode.ExternalSshTunnel -> tunnelOriginDraft = value.ifBlank {
                                DEFAULT_TUNNEL_ORIGIN
                            }
                            ConnectMode.ServerUrl, ConnectMode.Cloud -> serverUrlDraft = value
                        }
                        connectMode = next
                        value = when (next) {
                            ConnectMode.ExternalSshTunnel ->
                                tunnelOriginDraft.ifBlank { DEFAULT_TUNNEL_ORIGIN }
                            ConnectMode.ServerUrl, ConnectMode.Cloud -> serverUrlDraft
                        }
                        saveError = null
                        tunnelTestResult = null
                    }
                    ConnectionTypeSelector(
                        selected = connectMode,
                        onSelect = applyConnectMode,
                        compact = compactConnectionSelector,
                    )
                    if (connectMode == ConnectMode.Cloud) {
                        HermesCloudConnectPanel(
                            state = cloudState
                                ?: com.unsupportedpastels.hermesandroid.connection.CloudConnectState.SignedOut,
                            onSignIn = onCloudSignIn,
                            onRefresh = onCloudRefresh,
                            onSignOut = onCloudSignOut,
                            onSelectOrg = onCloudSelectOrg,
                            onSelectAgent = { agent ->
                                coroutineScope.launch {
                                    val result = onCloudSelectAgent(agent)
                                    if (result.isSuccess) {
                                        onBack()
                                    } else {
                                        saveError = "Could not connect to that agent. Try again."
                                    }
                                }
                            },
                        )
                    } else {
                    if (!(compactTunnel && shortViewport)) {
                    if (serverCatalog.entries.isEmpty()) {
                        Text(
                            "Add a Hermes server to get started.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            serverCatalog.entries.forEach { entry ->
                                val selected = entry.origin == serverCatalog.activeOrigin
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            entry.displayLabel,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            entry.origin.value,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    trailingContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                onClick = {
                                                    editingOrigin = entry.origin
                                                    value = entry.origin.value
                                                    label = entry.label
                                                    connectMode = if (
                                                        entry.connectionMode ==
                                                        ServerConnectionMode.ExternalSshTunnel
                                                    ) {
                                                        ConnectMode.ExternalSshTunnel
                                                    } else {
                                                        ConnectMode.ServerUrl
                                                    }
                                                    saveError = null
                                                    tunnelTestResult = null
                                                },
                                                modifier = Modifier.semantics {
                                                    contentDescription = "Edit ${entry.origin.value}"
                                                },
                                            ) {
                                                Text("Edit")
                                            }
                                            IconButton(
                                                onClick = { pendingRemoval = entry },
                                                enabled = !selected,
                                                modifier = Modifier.semantics {
                                                    contentDescription = "Remove ${entry.origin.value}"
                                                },
                                            ) {
                                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics {
                                            this.selected = selected
                                            contentDescription = if (selected) {
                                                "Selected ${entry.origin.value}"
                                            } else {
                                                "Select ${entry.origin.value}"
                                            }
                                        }
                                        .clickable(enabled = !selected) {
                                            coroutineScope.launch {
                                                val result = onSelectServer(entry.origin)
                                                if (result.isFailure) {
                                                    saveError = "Could not switch server. Try again."
                                                }
                                            }
                                        },
                                )
                                if (selected) {
                                    Text(
                                        "Active server",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(start = 16.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (serverCatalog.entries.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                editingOrigin = null
                                value = if (connectMode == ConnectMode.ExternalSshTunnel) {
                                    DEFAULT_TUNNEL_ORIGIN
                                } else {
                                    ""
                                }
                                label = ""
                                saveError = null
                                tunnelTestResult = null
                            },
                            enabled = !isSaving,
                        ) {
                            Text("Add server")
                        }
                    }
                    }
                    if (!compactTunnel) {
                        Text(
                            if (editingOrigin == null) {
                                "Add a server or edit its local label."
                            } else {
                                "Edit server label"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            if (connectMode == ConnectMode.ExternalSshTunnel) {
                                "External SSH tunnel origins must be http://127.0.0.1 or http://[::1] with any local port."
                            } else {
                                "Direct connections require HTTPS. HTTP is allowed only for 127.0.0.1 or [::1] in External SSH tunnel mode."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            saveError = null
                        },
                        label = { Text("Server origin") },
                        supportingText = {
                            Text(
                                validationMessage
                                    ?: if (connectMode == ConnectMode.ExternalSshTunnel) {
                                        "Numeric loopback only — http://127.0.0.1:<port>. localhost is rejected."
                                    } else {
                                        "HTTPS origin required except 127.0.0.1 or [::1] HTTP in External SSH tunnel mode — no path, credentials, query, or ticket."
                                    },
                            )
                        },
                        isError = validationMessage != null,
                        enabled = !isSaving && editingOrigin == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Server origin input" },
                    )
                    if (!compactTunnel) {
                        ServerDisplayLabelField(
                            value = label,
                            onValueChange = {
                                label = it
                                saveError = null
                            },
                            enabled = !isSaving,
                        )
                    }
                    if (connectMode == ConnectMode.ExternalSshTunnel) {
                        ExternalSshTunnelSetup(
                            testing = testingTunnel,
                            testResult = tunnelTestResult,
                            showExperimentalReasons = showExperimentalReasons,
                            onToggleExperimentalReasons = {
                                showExperimentalReasons = !showExperimentalReasons
                            },
                            onTestTunnel = {
                                val origin = parsedOrigin ?: return@ExternalSshTunnelSetup
                                coroutineScope.launch {
                                    testingTunnel = true
                                    tunnelTestResult = null
                                    tunnelTestResult = onTestTunnel(origin)
                                    testingTunnel = false
                                }
                            },
                            enabled = !isSaving,
                            compact = compactConnectionSelector,
                        )
                    }
                    saveError?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = parsedOrigin != null && validationMessage == null && !isSaving,
                            onClick = {
                                val origin = editingOrigin ?: parsedOrigin ?: return@Button
                                val transport = evaluateOriginTransport(
                                    origin,
                                    selectedConnectionMode,
                                )
                                if (transport is OriginTransportDecision.Rejected) {
                                    saveError = transport.message
                                    return@Button
                                }
                                val entry = runCatching {
                                    ServerCatalogEntry(
                                        origin = origin,
                                        label = label.trim(),
                                        connectionMode = selectedConnectionMode,
                                    )
                                }.getOrElse {
                                    saveError = it.message ?: "That server label is not valid."
                                    return@Button
                                }
                                coroutineScope.launch {
                                    isSaving = true
                                    saveError = null
                                    val result = if (editingOrigin != null) {
                                        onUpdateServerLabel(entry)
                                    } else {
                                        onSaveEntry(entry)
                                    }
                                    isSaving = false
                                    if (result.isSuccess) {
                                        if (serverCatalog.entries.isEmpty()) {
                                            onBack()
                                        } else {
                                            editingOrigin = null
                                            value = ""
                                            label = ""
                                        }
                                    } else {
                                        saveError = "Could not save server. Try again."
                                    }
                                }
                            },
                        ) {
                            Text(if (isSaving) "Saving…" else "Save")
                        }
                        TextButton(
                            enabled = !isSaving,
                            onClick = dropUnlessResumed { onBack() },
                        ) {
                            Text("Cancel")
                        }
                    }
                    if (connectMode == ConnectMode.ExternalSshTunnel) {
                        ExternalSshTunnelSetupGuide()
                        if (compactTunnel) {
                            ServerDisplayLabelField(
                                value = label,
                                onValueChange = {
                                    label = it
                                    saveError = null
                                },
                                enabled = !isSaving,
                            )
                        }
                    }
                    }
                }
                if (SettingsSection.Connection in visibleSections) {
                    HorizontalDivider()
                    OperationalOverviewItem(
                        snapshot = snapshot,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (snapshot.authenticationState == AuthenticationState.Authenticated) {
                        HorizontalDivider()
                        Text("Connection", style = MaterialTheme.typography.titleMedium)
                        Text("Hermes ${snapshot.serverVersion ?: "unknown"} · Authenticated")
                        if (snapshot.profiles.isNotEmpty()) {
                            Text("Profile", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                snapshot.profiles.forEach { profile ->
                                    FilterChip(
                                        selected = profile == snapshot.selectedProfile,
                                        onClick = { onLoadManagementSettings(profile) },
                                        label = { Text(profile) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (snapshot.authenticationState == AuthenticationState.Authenticated) {
                    val scopedModelOptions = snapshot.defaultModelOptions
                        ?.takeIf { it.profile == snapshot.selectedProfile }
                    val scopedCurrentModelInfo = snapshot.currentModelInfo
                        ?.takeIf { it.profile == snapshot.selectedProfile }
                    if (SettingsSection.Files in visibleSections) {
                        Text("Files", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Files the agent puts in chat open in another app on this phone. In-app preview is coming soon.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("In-app file preview")
                            Switch(
                                checked = inAppFilePreviewEnabled,
                                onCheckedChange = onInAppFilePreviewChanged,
                                enabled = false,
                                modifier = Modifier.semantics {
                                    contentDescription = "In-app file preview"
                                },
                            )
                        }
                    }
                    if (SettingsSection.Model in visibleSections) {
                        scopedCurrentModelInfo?.let { info ->
                            Text("Current profile model", style = MaterialTheme.typography.titleMedium)
                            val currentLabel = listOfNotNull(info.provider, info.model).joinToString(" / ")
                            if (currentLabel.isNotBlank()) Text(currentLabel)
                            info.effectiveContextLength?.let { length ->
                                Text("Effective context: $length tokens")
                            }
                        }
                        Text("Default model for new chats", style = MaterialTheme.typography.titleMedium)
                        val currentDefault = scopedModelOptions?.current
                        val currentDefaultCaps = scopedModelOptions?.capabilitiesFor(currentDefault)
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (currentDefault != null) {
                                    val providerName = scopedModelOptions.providers
                                        .firstOrNull { it.slug == currentDefault.provider }
                                        ?.name
                                        ?: currentDefault.provider
                                    Text(currentDefault.model, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        providerName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val caps = currentDefaultCaps?.let(::modelCapabilityLabels).orEmpty()
                                    if (caps.isNotEmpty()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            caps.forEach { CapabilityBadge(it) }
                                        }
                                    }
                                } else {
                                    Text(
                                        "No default model selected",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = {
                                        modelQuery = ""
                                        modelPickerOpen = true
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Change default model"
                                    },
                                ) {
                                    Text("Change model")
                                }
                            }
                        }
                    }
                    if (SettingsSection.Voice in visibleSections) {
                        voiceSettings?.let { settings ->
                            VoiceSettingsSection(settings)
                        }
                    }
                    if (SettingsSection.Offline in visibleSections) {
                        Text("Offline & privacy", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Session titles are always kept so your list works offline. " +
                                "Turn this on to also save recent conversations — encrypted on this " +
                                "device — so you can read them without a connection. Off by default.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Save conversations for offline reading")
                            Switch(
                                checked = transcriptCachingEnabled,
                                onCheckedChange = onTranscriptCachingChanged,
                                modifier = Modifier.semantics {
                                    contentDescription = "Save conversations for offline reading"
                                },
                            )
                        }
                        TextButton(onClick = onClearOfflineCache) { Text("Clear offline cache") }
                    }
                    if (SettingsSection.Account in visibleSections) {
                        TextButton(onClick = { coroutineScope.launch { onLogout() } }) { Text("Log out") }
                        snapshot.managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (SettingsSection.Jobs in visibleSections) {
                        CronJobsPanel(
                            state = snapshot.cronJobsState,
                            onRefresh = onRefreshCronJobs,
                            actionJobId = snapshot.cronJobActionJobId,
                            actionError = snapshot.cronJobActionError,
                            onJobAction = onCronJobAction,
                            cronServerOrigin = serverOrigin?.value,
                            cronProfile = snapshot.selectedProfile,
                            triggerCapability = snapshot.cronTriggerCapability,
                            historyCapability = snapshot.cronHistoryCapability,
                            runLoadingScopes = snapshot.cronRunLoadingScopes,
                            runErrors = snapshot.cronRunErrors,
                            runsByScope = snapshot.cronRunsByScope,
                            onRunNow = onRunCronJob,
                            onToggleRuns = onToggleCronJobRuns,
                        )
                    }
                }
            }
        }
    }
    pendingRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!isSaving) pendingRemoval = null },
            title = { Text("Remove server?") },
            text = { Text("Remove ${entry.displayLabel} from this device? This does not change the remote server.") },
            confirmButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = {
                        coroutineScope.launch {
                            isSaving = true
                            val result = onRemoveServer(entry.origin)
                            isSaving = false
                            if (result.isSuccess) {
                                pendingRemoval = null
                            } else {
                                pendingRemoval = null
                                saveError = "Could not remove server. Select another active server first."
                            }
                        }
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSaving,
                    onClick = { pendingRemoval = null },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
    if (modelPickerOpen) {
        val scopedModelOptions = snapshot.defaultModelOptions
            ?.takeIf { it.profile == snapshot.selectedProfile }
        ModelPickerSheet(
            options = scopedModelOptions,
            current = scopedModelOptions?.current,
            recents = recentModels,
            reasoningOverrides = snapshot.profileModelReasoningOverrides,
            profileDefaultEffort = snapshot.profileReasoningDefault,
            query = modelQuery,
            onQueryChange = { modelQuery = it.take(128) },
            onDismiss = { modelPickerOpen = false },
            onSetReasoning = { selection, effort ->
                coroutineScope.launch { onSetModelReasoningOverride(selection, effort) }
                Unit
            },
            onSelect = { selection ->
                coroutineScope.launch {
                    val result = onSetProfileDefaultModel(selection, false)
                    if (result.confirmationRequired) {
                        pendingExpensive = selection
                        expensiveMessage = result.confirmationMessage
                        modelPickerOpen = false
                    } else if (result.accepted) {
                        recentModels = (listOf(selection) + recentModels).distinct().take(5)
                        modelPickerOpen = false
                    }
                }
            },
        )
    }
    pendingExpensive?.let { selection ->
        AlertDialog(
            onDismissRequest = { pendingExpensive = null },
            title = { Text("Confirm expensive model") },
            text = { Text(expensiveMessage ?: "This model may have a high per-token cost.") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        val result = onSetProfileDefaultModel(selection, true)
                        if (result.accepted) {
                            recentModels = (listOf(selection) + recentModels).distinct().take(5)
                        }
                    }
                    pendingExpensive = null
                }) { Text("Set default") }
            },
            dismissButton = { TextButton(onClick = { pendingExpensive = null }) { Text("Cancel") } },
        )
    }
}

/**
 * A searchable, provider-grouped model picker in a modal bottom sheet. Recently
 * used models pin to the top for one-tap re-selection; the rest are grouped by
 * provider. Tapping a row expands it inline to reveal per-model controls —
 * Thinking on/off and a reasoning-effort scale for reasoning-capable models,
 * plus a Fast toggle where supported — mirroring the desktop's model edit menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    options: ModelOptions?,
    current: ModelSelection?,
    recents: List<ModelSelection>,
    reasoningOverrides: Map<ModelSelection, String>,
    profileDefaultEffort: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSetReasoning: (ModelSelection, String) -> Unit,
    onSelect: (ModelSelection) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filters by rememberSaveable(
        stateSaver = listSaver(
            save = { it.map(ModelCapabilityFilter::name) },
            restore = { it.map(ModelCapabilityFilter::valueOf).toSet() },
        ),
    ) { mutableStateOf(emptySet<ModelCapabilityFilter>()) }
    var expandedRow by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(options, query, filters) { modelProviderGroups(options, query, filters) }
    val recentOptions = remember(recents, options, query, filters) {
        if (query.isNotBlank() || filters.isNotEmpty()) {
            emptyList()
        } else {
            recentModelOptions(recents, options)
        }
    }
    fun rowKey(selection: ModelSelection) = "${selection.provider}/${selection.model}"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose a model", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search models") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search models" },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelCapabilityFilter.entries.forEach { filter ->
                    val active = filter in filters
                    val label = when (filter) {
                        ModelCapabilityFilter.Reasoning -> "Reasoning"
                        ModelCapabilityFilter.Fast -> "Fast"
                    }
                    FilterChip(
                        selected = active,
                        onClick = {
                            filters = if (active) filters - filter else filters + filter
                        },
                        label = { Text(label) },
                        leadingIcon = if (active) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier.semantics {
                            selected = active
                            contentDescription = "Filter by $label capability"
                        },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (recentOptions.isNotEmpty()) {
                    item(key = "recent-header") {
                        Text(
                            "Recently used",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(recentOptions, key = { "recent:${rowKey(it.selection)}" }) { option ->
                        val key = rowKey(option.selection)
                        ModelPickerRow(
                            option = option,
                            selected = option.selection == current,
                            expanded = expandedRow == key,
                            effortOverride = reasoningOverrides[option.selection],
                            profileDefaultEffort = profileDefaultEffort,
                            onToggleExpand = { expandedRow = if (expandedRow == key) null else key },
                            onSelect = { onSelect(option.selection) },
                            onSetReasoning = { effort -> onSetReasoning(option.selection, effort) },
                        )
                    }
                }
                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No models match your search.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "provider:${group.slug}") {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(group.models, key = { rowKey(it.selection) }) { option ->
                        val key = rowKey(option.selection)
                        ModelPickerRow(
                            option = option,
                            selected = option.selection == current,
                            expanded = expandedRow == key,
                            effortOverride = reasoningOverrides[option.selection],
                            profileDefaultEffort = profileDefaultEffort,
                            onToggleExpand = { expandedRow = if (expandedRow == key) null else key },
                            onSelect = { onSelect(option.selection) },
                            onSetReasoning = { effort -> onSetReasoning(option.selection, effort) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    option: ModelOption,
    selected: Boolean,
    expanded: Boolean,
    effortOverride: String?,
    profileDefaultEffort: String?,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onSetReasoning: (String) -> Unit,
) {
    val labels = remember(option.capabilities) { modelCapabilityLabels(option.capabilities) }
    val reasoningCapable = option.capabilities.reasoning == true
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(option.selection.model) },
            supportingContent = if (labels.isEmpty()) {
                null
            } else {
                { Text(labels.joinToString(" · ")) }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = "Current model")
                    }
                    if (reasoningCapable) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) {
                                "Hide options for ${option.selection.model}"
                            } else {
                                "Show options for ${option.selection.model}"
                            },
                            modifier = Modifier
                                .size(24.dp)
                                .clickable(onClick = onToggleExpand),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .semantics {
                    this.selected = selected
                    contentDescription = "Select ${option.providerName} ${option.selection.model}"
                },
        )
        if (expanded && reasoningCapable) {
            val thinkingOn = isThinkingEnabled(effortOverride)
            val effortValue = resolveReasoningEffort(effortOverride, profileDefaultEffort)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Thinking", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = thinkingOn,
                        onCheckedChange = { checked ->
                            onSetReasoning(if (checked) effortValue else "none")
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Thinking for ${option.selection.model}"
                        },
                    )
                }
                if (thinkingOn) {
                    Text(
                        "Reasoning effort",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReasoningEffortLevels.forEach { level ->
                            val active = level == effortValue
                            FilterChip(
                                selected = active,
                                onClick = { onSetReasoning(level) },
                                label = { Text(reasoningEffortShortLabel(level)) },
                                modifier = Modifier.semantics {
                                    this.selected = active
                                    contentDescription =
                                        "Set ${option.selection.model} reasoning to $level"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A non-interactive capability label (e.g. "Reasoning") shown on the current
 * model card. Deliberately not a chip, so it does not imply a tap target.
 */
@Composable
private fun CapabilityBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * True when the transcript should keep following new content: the newest item is still visible,
 * or the list has not laid out yet. Once the user scrolls up past the newest item, auto-follow
 * pauses until they return to the bottom.
 */
/**
 * Scroll offset that over-shoots the last item's height so the list clamps to
 * its true end. Anchoring the last item's TOP to the viewport leaves the tail
 * of anything taller than the screen (long replies, clarification pickers)
 * hidden below the fold.
 */
private const val TranscriptEndScrollOffset = 1 shl 20

/**
 * True when the transcript is at its actual end: the newest item is visible
 * AND its bottom is flush with the viewport end. Index visibility alone is
 * not enough — a streaming final message taller than the viewport keeps the
 * last index visible through any scroll inside its tail, so a drag would
 * disable follow with no way to re-engage on return.
 */
internal fun isTranscriptAtTrueEnd(
    lastVisibleItemIndex: Int?,
    totalItemsCount: Int,
    lastVisibleItemBottom: Int,
    viewportEnd: Int,
    tolerance: Int = 0,
): Boolean =
    lastVisibleItemIndex != null &&
        lastVisibleItemIndex >= totalItemsCount - 1 &&
        lastVisibleItemBottom <= viewportEnd + tolerance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailScreen(
    session: SessionSummary,
    chat: ChatSessionSnapshot,
    readAloud: MessageReadAloud? = null,
    voiceConversation: ComposerVoiceConversation? = null,
    onVoiceSubmit: (String, Boolean) -> Unit = { _, _ -> },
    autoSpeakEnabled: Boolean = false,
    voiceScreenOffContinuation: Boolean = false,
    voiceInputScopeKey: String,
    draft: String,
    onDraftChanged: (String) -> Unit,
    canSend: Boolean,
    attachments: List<ComposerAttachment>,
    hostReferences: List<String>,
    onAddAttachments: (List<ComposerAttachment>) -> List<String>,
    onRemoveAttachment: (String) -> Unit,
    onRemoveHostReference: (String) -> Unit,
    onSend: (String) -> Unit,
    onReasoningSelected: (String) -> Unit,
    onFastSelected: (Boolean) -> Unit,
    onOpenModelPicker: () -> Unit,
    onClarificationResponse: (String, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
    onBlockingResponse: (UnsupportedBlockingKind, String, String) -> Unit,
    showStop: Boolean,
    stopping: Boolean,
    onStop: () -> Unit,
    delegationStatus: DelegationStatus,
    delegationAvailable: Boolean,
    onSetDelegationPaused: (Boolean) -> Unit,
    onSteerSubagent: (String, String) -> Unit,
    onInterruptSubagent: (String) -> Unit,
    slashCompletion: SlashCompletionState? = null,
    onSlashCompletionSelected: (SlashCompletionState, SlashCompletionItem) -> Unit = { _, _ -> },
    onLoadSessionInsights: () -> Unit,
    maintenanceAvailable: Boolean,
    maintenanceEnabled: Boolean,
    onCompressSession: (String?) -> Unit,
    onUndoSession: () -> Unit,
    onBranchSession: (Int?, String?) -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    snapshot: HermesGatewaySnapshot = HermesGatewaySnapshot(),
    onRetryConnection: () -> Unit = {},
    onConfigureServer: () -> Unit = {},
    onCancelRecovery: () -> Unit = {},
    onAcceptNewInstallation: () -> Unit = {},
    onLoadManagedImage: suspend (String) -> Result<ByteArray>,
    onLoadHostFiles: suspend (String?) -> Result<HostFileListing>,
    onLoadManagedFile: suspend (String) -> Result<HostFileContent>,
    onAttachHostReference: (String) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val semanticColors = LocalHermesSemanticColors.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val transcriptScope = rememberCoroutineScope()
    val openChatHostFile: suspend (String) -> HostFileOpenEvent = { path ->
        openManagedHostFile(
            context = context,
            source = path,
            displayName = HostFileOpenPolicy.displayName(path),
            load = onLoadManagedFile,
        )
    }
    val readAloudSession = rememberReadAloudSession(readAloud, session.id.value)
    var showSessionInsights by remember(session.id) { mutableStateOf(false) }
    var showHostFiles by remember(session.id) { mutableStateOf(false) }
    var showArtifacts by remember(session.id) { mutableStateOf(false) }
    var attachmentMenuOpen by remember(session.id) { mutableStateOf(false) }
    val sessionArtifacts = remember(chat.messages) { ArtifactExtractor.extract(chat.messages) }
    val workspacePath = validProjectWorkspacePath(session.workspacePath)
    // Home-bucket sessions run in the server's default working directory; until
    // the server reports the actual cwd there is no path to show, and "No
    // workspace" would wrongly suggest the draft cannot start.
    val workspaceLabel = workspacePath
        ?: session.projectId?.takeUnless(::isNoProjectBucket)?.let { "No workspace" }
    val projectDraftMissingWorkspace = session.isLocalDraft &&
        session.projectId != null &&
        !isNoProjectBucket(session.projectId) &&
        workspacePath == null
    var attachmentError by remember(session.id) { mutableStateOf<String?>(null) }
    var pendingSend by remember(session.id) { mutableStateOf<Pair<String, Int>?>(null) }
    val currentDraft by rememberUpdatedState(draft)
    val currentOnDraftChanged by rememberUpdatedState(onDraftChanged)
    val deviceSpeechController = remember(context) {
        DeviceSpeechRecognizerController(context)
    }
    val voiceInputAvailable = remember(context) {
        DeviceSpeechRecognizerController.isAvailable(context)
    }
    LaunchedEffect(voiceInputScopeKey) {
        deviceSpeechController.cancel()
    }
    DisposableEffect(session.id, deviceSpeechController) {
        onDispose { deviceSpeechController.cancel() }
    }

    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val candidates = mutableListOf<ComposerAttachment>()
        val errors = mutableListOf<String>()
        uris.forEach { uri ->
            runCatching { resolvePickedAttachment(context, uri) }
                .onSuccess(candidates::add)
                .onFailure { errors += it.message ?: "Could not read selected file" }
        }
        errors += onAddAttachments(candidates)
        attachmentError = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
    val hasRunStateContent = chat.runState.hasVisibleContent() ||
        chat.processRows.isNotEmpty() ||
        (delegationAvailable && delegationStatus.active.isNotEmpty())
    val timelineLastIndex = (
        chat.messages.size + if (hasRunStateContent) 1 else 0
    ).minus(1).coerceAtLeast(0)
    val transcriptListState = rememberLazyListState(
        initialFirstVisibleItemIndex = timelineLastIndex,
    )
    // A settled scroll can land 1-2px short of the clamped end; allow that
    // slack when deciding the last item's bottom is flush with the viewport.
    val followEndTolerancePx = with(LocalDensity.current) { 2.dp.roundToPx() }
    val pinnedToBottom by remember(transcriptListState) {
        derivedStateOf {
            val layoutInfo = transcriptListState.layoutInfo
            val last = layoutInfo.visibleItemsInfo.lastOrNull()
            isTranscriptAtTrueEnd(
                lastVisibleItemIndex = last?.index,
                totalItemsCount = layoutInfo.totalItemsCount,
                lastVisibleItemBottom = last?.let { it.offset + it.size } ?: 0,
                viewportEnd = layoutInfo.viewportEndOffset,
                tolerance = followEndTolerancePx,
            )
        }
    }
    // Follow is an intent, not a position: only a user drag disengages it, and
    // returning to the bottom re-engages it. Gating on instantaneous index
    // visibility permanently broke follow whenever a burst of new items
    // cancelled the catch-up animation and left the view one frame behind, and
    // a tall streaming message kept the last index visible through any scroll
    // inside its tail, so returning to the bottom never re-engaged either.
    var followBottom by remember(session.id) { mutableStateOf(true) }
    LaunchedEffect(transcriptListState) {
        transcriptListState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followBottom = false
        }
    }
    LaunchedEffect(transcriptListState) {
        snapshotFlow { pinnedToBottom }.collect { atEnd ->
            if (atEnd) followBottom = true
        }
    }
    // The transcript often arrives after the screen composes (async load on a
    // fresh process), so the initial jump-to-end must wait for first content
    // instead of firing once on open and silently doing nothing.
    var initialScrollDone by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(session.id, chat.messages.size, hasRunStateContent) {
        if (!initialScrollDone && (chat.messages.isNotEmpty() || hasRunStateContent)) {
            transcriptListState.scrollToItem(timelineLastIndex, TranscriptEndScrollOffset)
            initialScrollDone = true
        }
    }
    var lastFollowedMessageCount by remember(session.id) { mutableStateOf(chat.messages.size) }
    LaunchedEffect(
        chat.messages.size,
        chat.messages.lastOrNull()?.text?.length,
        chat.runState,
    ) {
        if (chat.messages.isEmpty() && !hasRunStateContent) return@LaunchedEffect
        if (!followBottom) return@LaunchedEffect
        if (chat.messages.size != lastFollowedMessageCount) {
            lastFollowedMessageCount = chat.messages.size
            transcriptListState.animateScrollToItem(timelineLastIndex, TranscriptEndScrollOffset)
        } else {
            transcriptListState.scrollToItem(timelineLastIndex, TranscriptEndScrollOffset)
        }
    }
    // The context ring in the top bar needs usage data the gateway only returns
    // on demand: load it when the session opens and refresh when a turn ends.
    var wasSending by remember(session.id) { mutableStateOf(chat.isSending) }
    LaunchedEffect(session.id, maintenanceAvailable) {
        if (maintenanceAvailable) onLoadSessionInsights()
    }
    LaunchedEffect(chat.isSending) {
        if (wasSending && !chat.isSending && maintenanceAvailable) onLoadSessionInsights()
        wasSending = chat.isSending
    }
    // The draft clears optimistically at send time; this only restores it when
    // the gateway rejects the message, so nothing typed is ever lost.
    LaunchedEffect(session.id, chat.messages.size, chat.isSending, chat.error) {
        val pending = pendingSend ?: return@LaunchedEffect
        when {
            chat.error != null -> {
                if (draft.isBlank()) onDraftChanged(pending.first)
                pendingSend = null
            }
            chat.messages.size > pending.second -> pendingSend = null
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                expandedHeight = 48.dp,
                title = {
                    Text(
                        session.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(
                            onClick = dropUnlessResumed { onBack() },
                            modifier = Modifier.semantics { contentDescription = "Back" },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (projectDraftMissingWorkspace) {
                Text(
                    "No workspace",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics {
                        contentDescription = "Session workspace: No workspace"
                    },
                )
            }
            if (chat.transcriptSource == CacheSource.Cached) {
                Text(
                    "Cached transcript — reconnecting to Hermes",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            ConnectionRecoveryBanner(
                snapshot = snapshot,
                onRetry = onRetryConnection,
                onConnectionSetup = onConfigureServer,
                onCancel = onCancelRecovery,
                onAcceptNewServer = onAcceptNewInstallation,
            )
            when {
                chat.isLoading && chat.messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Loading transcript…")
                    }
                }
                chat.messages.isEmpty() && !hasRunStateContent -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No messages yet")
                    }
                }
                else -> {
                    val transcriptEntries = remember(chat.messages) {
                        coalesceTranscriptEntries(chat.messages)
                    }
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(
                        state = transcriptListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("Session timeline"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = transcriptEntries,
                            key = { entry ->
                                when (entry) {
                                    is TranscriptEntry.Single -> "message:${entry.index}"
                                    is TranscriptEntry.ToolRun ->
                                        "tool-run:${entry.tools.first().index}"
                                }
                            },
                        ) { entry ->
                            if (entry is TranscriptEntry.ToolRun) {
                                var toolsExpanded by rememberSaveable(
                                    session.id.value,
                                    entry.tools.first().index,
                                ) {
                                    mutableStateOf(false)
                                }
                                TranscriptToolRunGroup(
                                    tools = entry.tools,
                                    expanded = toolsExpanded,
                                    onToggle = { toolsExpanded = !toolsExpanded },
                                    sessionKey = session.id.value,
                                    loadManagedImage = { path ->
                                        onLoadManagedImage(path).getOrThrow()
                                    },
                                    onOpenManagedFile = openChatHostFile,
                                )
                                return@items
                            }
                            val messageIndex = (entry as TranscriptEntry.Single).index
                            val message = entry.message
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (message.role == ChatMessageRole.System) {
                                    Text(
                                        "System",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                message.reasoningText.takeIf { it.isNotBlank() }?.let { reasoning ->
                                    var showReasoning by rememberSaveable(session.id.value, messageIndex) {
                                        mutableStateOf(false)
                                    }
                                    ThinkingBlock(
                                        reasoning = reasoning,
                                        expanded = showReasoning,
                                        onToggle = { showReasoning = !showReasoning },
                                    )
                                }
                                val renderedText = message.text.ifEmpty {
                                    if (message.isStreaming) "…" else ""
                                }
                                when {
                                    message.role == ChatMessageRole.Tool -> {
                                        var showToolMessage by rememberSaveable(session.id.value, messageIndex) {
                                            mutableStateOf(false)
                                        }
                                        ToolMessageBlock(
                                            text = renderedText,
                                            expanded = showToolMessage,
                                            onToggle = { showToolMessage = !showToolMessage },
                                            loadManagedImage = { path ->
                                                onLoadManagedImage(path).getOrThrow()
                                            },
                                            onOpenManagedFile = openChatHostFile,
                                        )
                                    }
                                    message.role == ChatMessageRole.User -> {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.TopEnd,
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(
                                                    topStart = 18.dp,
                                                    topEnd = 18.dp,
                                                    bottomEnd = 4.dp,
                                                    bottomStart = 18.dp,
                                                ),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier
                                                    .padding(start = 48.dp)
                                                    .width(IntrinsicSize.Max),
                                            ) {
                                                MarkdownMessage(
                                                    renderedText,
                                                    modifier = Modifier.padding(
                                                        horizontal = 14.dp,
                                                        vertical = 10.dp,
                                                    ),
                                                    loadManagedImage = { path ->
                                                        onLoadManagedImage(path).getOrThrow()
                                                    },
                                                    onOpenManagedFile = openChatHostFile,
                                                )
                                            }
                                        }
                                    }
                                    message.role == ChatMessageRole.Assistant && message.isStreaming -> {
                                        // Only the tail past the last finalized block renders as
                                        // plain text: parsing partial markdown (unclosed code
                                        // fences, stray bold markers, half-built tables) garbles
                                        // output, but blocks terminated by a blank line are
                                        // complete and safe to render.
                                        val stableLength = remember(renderedText) {
                                            stableMarkdownPrefixLength(renderedText)
                                        }
                                        if (stableLength > 0) {
                                            MarkdownMessage(
                                                renderedText.substring(0, stableLength),
                                                loadManagedImage = { path ->
                                                    onLoadManagedImage(path).getOrThrow()
                                                },
                                                onOpenManagedFile = openChatHostFile,
                                            )
                                        }
                                        val streamingTail = renderedText.substring(stableLength)
                                        if (streamingTail.isNotEmpty()) {
                                            Text(
                                                streamingTail,
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier.testTag("Streaming assistant text"),
                                            )
                                        }
                                    }
                                    else -> {
                                        MarkdownMessage(
                                            renderedText,
                                            loadManagedImage = { path ->
                                                onLoadManagedImage(path).getOrThrow()
                                            },
                                            onOpenManagedFile = openChatHostFile,
                                        )
                                    }
                                }
                            }
                        }
                        if (hasRunStateContent) {
                            item(key = "run-state") {
                                RunStateContent(
                                    runState = chat.runState,
                                    processRows = chat.processRows,
                                    runActive = chat.isSending,
                                    delegationStatus = if (delegationAvailable) delegationStatus else DelegationStatus(),
                                    durableSessionId = session.id,
                                    onClarificationResponse = onClarificationResponse,
                                    onApprovalResponse = onApprovalResponse,
                                    onBlockingResponse = onBlockingResponse,
                                )
                            }
                        }
                    }
                        // Discord-style jump-to-bottom pill: shown only when the
                        // user has scrolled up off the latest message. Hidden when
                        // a pending interactive card (clarification/approval/secure
                        // input) is at the tail: those own the bottom-end corner
                        // with their own action buttons, and the user is already at
                        // the bottom, so the FAB would only obstruct them.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !pinnedToBottom && !hasPendingTailInteraction(chat.runState),
                            enter = fadeIn() + scaleIn(initialScale = 0.8f),
                            exit = fadeOut() + scaleOut(targetScale = 0.8f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 8.dp),
                        ) {
                            Surface(
                                onClick = {
                                    followBottom = true
                                    transcriptScope.launch {
                                        transcriptListState.animateScrollToItem(
                                            timelineLastIndex,
                                            TranscriptEndScrollOffset,
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .size(44.dp)
                                    .semantics { contentDescription = "Scroll to latest message" },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.ArrowDownward,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            chat.error
                ?.takeUnless { projectDraftMissingWorkspace && it == "No workspace" }
                ?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { onSend(draft) },
                        enabled = canSend && draft.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = "Retry action" },
                    ) {
                        Text("Retry action")
                    }
                }
            chat.notice?.let { notice ->
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (delegationAvailable) {
                DelegationControls(
                    status = delegationStatus,
                    onSetPaused = onSetDelegationPaused,
                    onSteer = onSteerSubagent,
                    onInterrupt = onInterruptSubagent,
                )
            }
            chat.billingNotice?.let { billing ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Billing action required" },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Billing action required", style = MaterialTheme.typography.titleSmall)
                        billing.message?.takeIf(String::isNotBlank)?.let { Text(it) }
                        billing.provider?.takeIf(String::isNotBlank)?.let { provider ->
                            Text("Provider: $provider", style = MaterialTheme.typography.bodySmall)
                        }
                        billing.billingUrl
                            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
                            ?.let { billingUrl ->
                                Button(onClick = { runCatching { uriHandler.openUri(billingUrl) } }) {
                                    Text(if (billing.isNous) "Open Nous billing" else "Open billing")
                                }
                            }
                    }
                }
            }
            if (chat.isSending) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Hermes is responding…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (slashCompletion != null && slashCompletion.items.isNotEmpty()) {
                SlashCompletionMenu(
                    completion = slashCompletion,
                    onItemSelected = { item -> onSlashCompletionSelected(slashCompletion, item) },
                )
            }
            attachmentError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Keep the composer available during a controlled turn so the user can
            // issue the server's /steer command through the normal send path.
            val composerEnabled = !chat.isLoading
            val attachmentsEnabled = canSend && composerEnabled && !chat.isSending
            val canSubmitDuringActiveTurn = !chat.isSending ||
                (attachments.isEmpty() && isSteerCommand(draft))
            val voiceHost = rememberVoiceConversationHost(
                conversation = voiceConversation,
                sessionId = session.id.value,
                chat = chat,
                onSubmit = onVoiceSubmit,
                // Barge-in cuts the running turn through the same seam as the
                // composer Stop button.
                onStopTurn = onStop,
                screenOffContinuation = voiceScreenOffContinuation,
            )
            val voiceConversationState = voiceHost?.controller?.state?.collectAsState()?.value
            val voiceActive = voiceConversationState != null &&
                voiceConversationState != VoiceConversationState.Idle
            if (voiceHost != null) {
                VoiceConversationBar(host = voiceHost)
            }
            // Server-configured auto-speak of new finalized replies; the active
            // voice conversation owns speech and suppresses it.
            AutoSpeakEffect(
                readAloudSession = readAloudSession,
                chat = chat,
                enabled = autoSpeakEnabled,
                suppressed = voiceActive,
                sessionId = session.id.value,
            )
            if (attachments.isNotEmpty() || hostReferences.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEach { attachment ->
                        InputChip(
                            selected = true,
                            onClick = { onRemoveAttachment(attachment.id) },
                            enabled = attachmentsEnabled,
                            label = { Text(attachment.displayName, maxLines = 1) },
                            trailingIcon = { Text("×") },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove ${attachment.displayName}"
                            },
                        )
                    }
                    hostReferences.forEach { reference ->
                        InputChip(
                            selected = true,
                            onClick = { onRemoveHostReference(reference) },
                            enabled = attachmentsEnabled,
                            label = {
                                Text(
                                    reference.substringAfter(':').trim('`', '\'', '"'),
                                    maxLines = 1,
                                )
                            },
                            trailingIcon = { Text("×") },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove host reference $reference"
                            },
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("Message composer"),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        IconButton(
                            onClick = { attachmentMenuOpen = true },
                            enabled = attachmentsEnabled,
                            modifier = Modifier
                                .size(44.dp)
                                .semantics { contentDescription = "Attach files" },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = attachmentMenuOpen,
                            onDismissRequest = { attachmentMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Device files") },
                                onClick = {
                                    attachmentMenuOpen = false
                                    attachmentError = null
                                    attachmentPicker.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Host files") },
                                onClick = {
                                    attachmentMenuOpen = false
                                    showHostFiles = true
                                },
                            )
                        }
                    }
                    var draftFieldValue by remember {
                        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
                    }
                    // Keep the caret after externally-inserted text (dictation, slash
                    // completion, clear-on-send) rather than leaving it at the start.
                    if (draftFieldValue.text != draft) {
                        draftFieldValue = draftFieldValue.copy(
                            text = draft,
                            selection = TextRange(draft.length),
                        )
                    }
                    BasicTextField(
                        value = draftFieldValue,
                        onValueChange = { newValue ->
                            val textChanged = newValue.text != draftFieldValue.text
                            draftFieldValue = newValue
                            if (textChanged) onDraftChanged(newValue.text)
                        },
                        enabled = composerEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 132.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = if (composerEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 1,
                        maxLines = 5,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (draft.isEmpty()) {
                                    Text(
                                        "Message Hermes",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    // Group the two voice controls tighter than the composer's
                    // 4.dp spacing: each 40.dp button pads ~8.dp around its glyph,
                    // so negative spacing pulls the glyphs closer without
                    // overlapping their touch targets' visual centers.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((-10).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DeviceSpeechInputButton(
                            controller = deviceSpeechController,
                            available = voiceInputAvailable,
                            enabled = composerEnabled && !voiceActive,
                            currentDraft = currentDraft,
                            onDraftChanged = currentOnDraftChanged,
                            onError = { message -> attachmentError = message },
                            modifier = Modifier.size(40.dp),
                        )
                        if (voiceHost != null) {
                            VoiceConversationToggleButton(
                                host = voiceHost,
                                enabled = canSend && composerEnabled,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    if (showStop && !canSubmitDuringActiveTurn) {
                        FilledIconButton(
                            enabled = !stopping,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = semanticColors.active,
                                contentColor = semanticColors.onActive,
                                disabledContainerColor = semanticColors.active.copy(alpha = 0.38f),
                                disabledContentColor = semanticColors.onActive.copy(alpha = 0.38f),
                            ),
                            onClick = dropUnlessResumed { onStop() },
                            modifier = Modifier
                                .size(40.dp)
                                .semantics {
                                    contentDescription = "Stop Hermes response"
                                    stateDescription = if (stopping) "Stopping" else "Ready to stop"
                                },
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null)
                        }
                    } else {
                        FilledIconButton(
                        onClick = {
                            deviceSpeechController.finish()
                            val message = draft.trim()
                            val reasoningEffort = reasoningEffortCommand(message)
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            when {
                                isModelPickerCommand(message) -> {
                                    pendingSend = null
                                    onDraftChanged("")
                                    onOpenModelPicker()
                                }
                                reasoningEffort != null -> {
                                    pendingSend = null
                                    onDraftChanged("")
                                    onReasoningSelected(reasoningEffort)
                                }
                                else -> {
                                    pendingSend = message to chat.messages.size
                                    onDraftChanged("")
                                    onSend(message)
                                    followBottom = true
                                    transcriptScope.launch {
                                        transcriptListState.scrollToItem(
                                            timelineLastIndex,
                                            TranscriptEndScrollOffset,
                                        )
                                    }
                                }
                            }
                        },
                        enabled = canSend &&
                            composerEnabled &&
                            !stopping &&
                            canSubmitDuringActiveTurn &&
                            (draft.isNotBlank() || attachments.isNotEmpty()),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        ),
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = "Send message" },
                    ) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = null)
                    }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                // The provider prefix ("openrouter/…") repeats what the details
                // sheet already shows and dominates the strip; keep the model's
                // own name and preserve its distinguishing tail when truncating.
                val displayModel = chat.model
                    ?.takeIf(String::isNotBlank)
                    ?.substringAfterLast('/')
                val modelLabel = displayModel ?: when {
                    session.isLocalDraft && !chat.draftDefaultsLoaded -> "Loading model…"
                    session.isLocalDraft -> "Profile default"
                    else -> "Model"
                }
                AssistChip(
                    onClick = onOpenModelPicker,
                    label = {
                        Text(
                            modelLabel,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .semantics { contentDescription = "Change session model" },
                )
                val reportedReasoningEffort = chat.reasoningEffort?.takeIf(String::isNotBlank)
                // Reasoning and Fast can attach a live session on explicit user action, so the
                // selectors stay available whenever the model explicitly advertises support.
                val reasoningEditable = chat.modelCapabilities?.reasoning == true
                if (reasoningEditable) {
                    var reasoningMenuOpen by remember(session.id) { mutableStateOf(false) }
                    val reasoningLabel = reportedReasoningEffort ?: "Reasoning"
                    Box {
                        AssistChip(
                            onClick = { reasoningMenuOpen = true },
                            label = { Text(reasoningLabel) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Change reasoning effort"
                            },
                        )
                        DropdownMenu(
                            expanded = reasoningMenuOpen,
                            onDismissRequest = { reasoningMenuOpen = false },
                        ) {
                            ValidReasoningEfforts.forEach { effort ->
                                DropdownMenuItem(
                                    text = { Text(effort) },
                                    onClick = {
                                        reasoningMenuOpen = false
                                        onReasoningSelected(effort)
                                    },
                                )
                            }
                        }
                    }
                } else if (reportedReasoningEffort != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.semantics {
                            contentDescription = "Reported reasoning effort"
                            stateDescription = reportedReasoningEffort
                        },
                    ) {
                        Text(
                            reportedReasoningEffort,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (chat.modelCapabilities?.fast == true) {
                    var fastMenuOpen by remember(session.id) { mutableStateOf(false) }
                    val fastEnabled = chat.fastMode == "fast"
                    Box {
                        IconButton(
                            onClick = { fastMenuOpen = true },
                            modifier = Modifier
                                .size(40.dp)
                                .semantics {
                                    contentDescription = "Change fast mode"
                                    stateDescription = if (fastEnabled) {
                                        "Fast mode enabled"
                                    } else {
                                        "Normal mode enabled"
                                    }
                                },
                        ) {
                            Icon(
                                Icons.Outlined.Speed,
                                contentDescription = null,
                                tint = if (fastEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        DropdownMenu(
                            expanded = fastMenuOpen,
                            onDismissRequest = { fastMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Fast") },
                                onClick = {
                                    fastMenuOpen = false
                                    onFastSelected(true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Normal") },
                                onClick = {
                                    fastMenuOpen = false
                                    onFastSelected(false)
                                },
                            )
                        }
                    }
                }
                }
                SessionContextRing(
                    percent = sessionContextPercent(chat),
                    artifactCount = sessionArtifacts.size,
                    onClick = {
                        showSessionInsights = true
                        if (maintenanceAvailable) onLoadSessionInsights()
                    },
                )
            }
        }
    }
    if (showSessionInsights) {
        SessionInsightsSheet(
            sessionTitle = session.title,
            chat = chat,
            workspaceLabel = workspaceLabel,
            provider = chat.provider,
            maintenanceAvailable = maintenanceAvailable,
            maintenanceEnabled = maintenanceEnabled,
            artifacts = sessionArtifacts,
            onOpenArtifacts = {
                showSessionInsights = false
                showArtifacts = true
            },
            onRefresh = onLoadSessionInsights,
            onCompress = onCompressSession,
            onUndo = onUndoSession,
            onBranch = onBranchSession,
            onDismiss = { showSessionInsights = false },
        )
    }
    if (showHostFiles) {
        HostFileBrowserSheet(
            onDismiss = { showHostFiles = false },
            onLoad = onLoadHostFiles,
            onAttach = { reference ->
                onAttachHostReference(reference)
                showHostFiles = false
            },
        )
    }
    if (showArtifacts) {
        ArtifactBrowserSheet(
            artifacts = sessionArtifacts,
            onDismiss = { showArtifacts = false },
            onLoadManagedImage = onLoadManagedImage,
            onLoadManagedFile = onLoadManagedFile,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostFileBrowserSheet(
    onDismiss: () -> Unit,
    onLoad: suspend (String?) -> Result<HostFileListing>,
    onAttach: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<HostFileListing?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(path: String?) {
        scope.launch {
            loading = true
            error = null
            onLoad(path).fold(
                onSuccess = { listing = it },
                onFailure = { failure ->
                    error = failure.message?.take(160)?.takeIf(String::isNotBlank)
                        ?: "Could not load host files"
                },
            )
            loading = false
        }
    }

    LaunchedEffect(Unit) { load(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Host files", style = MaterialTheme.typography.headlineSmall)
            Text(
                listing?.path ?: "Hermes managed files",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it.take(256) },
                label = { Text("Filter files") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listing?.parentPath?.let { parent ->
                    TextButton(onClick = { load(parent) }, enabled = !loading) { Text("Up") }
                }
                TextButton(
                    onClick = { load(listing?.path) },
                    enabled = !loading,
                ) { Text("Refresh") }
            }
            if (loading && listing == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val entries = listing?.entries.orEmpty().filter { entry ->
                filter.isBlank() ||
                    entry.name.contains(filter.trim(), ignoreCase = true) ||
                    entry.path.contains(filter.trim(), ignoreCase = true)
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(entries, key = { it.path }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                if (entry.isDirectory) "Folder" else entry.mimeType ?: "File",
                                maxLines = 1,
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { onAttach(entry.reference) }) { Text("Attach") }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.isDirectory && !loading) { load(entry.path) }
                            .semantics {
                                contentDescription = if (entry.isDirectory) {
                                    "Open host folder ${entry.name}"
                                } else {
                                    "Host file ${entry.name}"
                                }
                            },
                    )
                }
            }
            if (!loading && error == null && entries.isEmpty()) {
                Text("No matching files", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactBrowserSheet(
    artifacts: List<Artifact>,
    onDismiss: () -> Unit,
    onLoadManagedImage: suspend (String) -> Result<ByteArray>,
    onLoadManagedFile: suspend (String) -> Result<HostFileContent>,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var pendingSave by remember { mutableStateOf<Artifact?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf<ArtifactType?>(null) }
    var zoomedImage by remember { mutableStateOf<Artifact?>(null) }
    val filteredArtifacts = artifacts.filter { artifact ->
        (selectedType == null || artifact.type == selectedType) &&
            (query.isBlank() ||
                artifact.displayName.contains(query.trim(), ignoreCase = true) ||
                artifact.source.contains(query.trim(), ignoreCase = true))
    }

    fun shareManaged(artifact: Artifact) {
        scope.launch {
            onLoadManagedFile(artifact.source).fold(
                onSuccess = { content ->
                    runCatching {
                        val sharedFile = withContext(Dispatchers.IO) {
                            writeSharedArtifact(context, artifact, content.bytes)
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.files",
                            sharedFile,
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = content.mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Share artifact",
                            ),
                        )
                    }.onFailure { failure ->
                        error = failure.message?.take(160) ?: "Could not share artifact"
                    }
                },
                onFailure = { failure ->
                    error = failure.message?.take(160) ?: "Could not download artifact"
                },
            )
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { destination ->
        val artifact = pendingSave
        pendingSave = null
        if (destination != null && artifact != null) {
            scope.launch {
                onLoadManagedFile(artifact.source).fold(
                    onSuccess = { content ->
                        runCatching {
                            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                                output.write(content.bytes)
                            } ?: error("Destination could not be opened")
                        }.onFailure { failure ->
                            error = failure.message?.take(160) ?: "Could not save artifact"
                        }
                    },
                    onFailure = { failure ->
                        error = failure.message?.take(160) ?: "Could not download artifact"
                    },
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Artifacts", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Images, audio, and files explicitly referenced in this chat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(256) },
                label = { Text("Search artifacts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("Artifact search"),
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { selectedType = null },
                    label = { Text("All") },
                    modifier = Modifier.semantics { contentDescription = "Filter artifacts: All" },
                )
                ArtifactType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(type.name) },
                        modifier = Modifier.semantics {
                            contentDescription = "Filter artifacts: ${type.name}"
                        },
                    )
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (filteredArtifacts.isEmpty()) {
                Text(
                    if (artifacts.isEmpty()) "No artifacts in this chat" else "No matching artifacts",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(filteredArtifacts, key = Artifact::stableIdentity) { artifact ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (artifact.type == ArtifactType.Image) {
                                RemoteMediaImage(
                                    source = artifact.source,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 260.dp)
                                        .semantics {
                                            contentDescription = "Zoom image ${artifact.displayName}"
                                        },
                                    onImageClick = { zoomedImage = artifact },
                                    loadManagedImage = if (artifact.origin == ArtifactOrigin.ManagedPath) {
                                        { path -> onLoadManagedImage(path).getOrThrow() }
                                    } else {
                                        null
                                    },
                                )
                            }
                            ListItem(
                                headlineContent = {
                                    Text(artifact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        artifact.type.name.lowercase().replaceFirstChar(Char::uppercase),
                                        maxLines = 1,
                                    )
                                },
                                trailingContent = {
                                    if (artifact.origin == ArtifactOrigin.RemoteUrl) {
                                        TextButton(onClick = {
                                            runCatching { uriHandler.openUri(artifact.source) }
                                                .onFailure { error = "Could not open artifact" }
                                        }) { Text("Open") }
                                    }
                                },
                            )
                            if (artifact.origin == ArtifactOrigin.ManagedPath) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = { shareManaged(artifact) }) { Text("Share") }
                                    TextButton(onClick = {
                                        pendingSave = artifact
                                        saveLauncher.launch(artifact.displayName)
                                    }) { Text("Save") }
                                }
                            }
                            if (
                                artifact.type == ArtifactType.Audio &&
                                artifact.origin == ArtifactOrigin.ManagedPath
                            ) {
                                ManagedAudioPlayer(artifact, onLoadManagedFile)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
    zoomedImage?.let { artifact ->
        ZoomedArtifactDialog(
            artifact = artifact,
            onDismiss = { zoomedImage = null },
            onLoadManagedImage = onLoadManagedImage,
        )
    }
}

@Composable
private fun ManagedAudioPlayer(
    artifact: Artifact,
    onLoadManagedFile: suspend (String) -> Result<HostFileContent>,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(artifact.stableIdentity) { mutableStateOf<MediaPlayer?>(null) }
    var tempFile by remember(artifact.stableIdentity) { mutableStateOf<File?>(null) }
    var playing by remember(artifact.stableIdentity) { mutableStateOf(false) }
    var loading by remember(artifact.stableIdentity) { mutableStateOf(false) }
    var error by remember(artifact.stableIdentity) { mutableStateOf<String?>(null) }

    DisposableEffect(artifact.stableIdentity) {
        onDispose {
            player?.release()
            tempFile?.delete()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            enabled = !loading,
            onClick = {
                val current = player
                if (current != null) {
                    if (playing) current.pause() else current.start()
                    playing = !playing
                } else {
                    scope.launch {
                        loading = true
                        error = null
                        onLoadManagedFile(artifact.source).fold(
                            onSuccess = { content ->
                                runCatching {
                                    val (file, prepared) = withContext(Dispatchers.IO) {
                                        val directory = File(context.cacheDir, "artifact-audio").apply { mkdirs() }
                                        val file = File.createTempFile("audio-", ".bin", directory)
                                        file.writeBytes(content.bytes)
                                        file to MediaPlayer().apply {
                                            setDataSource(file.absolutePath)
                                            prepare()
                                        }
                                    }
                                    tempFile = file
                                    prepared.setOnCompletionListener { playing = false }
                                    player = prepared
                                    prepared.start()
                                    playing = true
                                }.onFailure { failure ->
                                    error = failure.message?.take(120) ?: "Could not play audio"
                                }
                            },
                            onFailure = { failure ->
                                error = failure.message?.take(120) ?: "Could not load audio"
                            },
                        )
                        loading = false
                    }
                }
            },
        ) {
            Text(if (loading) "Loading…" else if (playing) "Pause" else "Play")
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZoomedArtifactDialog(
    artifact: Artifact,
    onDismiss: () -> Unit,
    onLoadManagedImage: suspend (String) -> Result<ByteArray>,
) {
    var scale by remember(artifact.stableIdentity) { mutableStateOf(1f) }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(artifact.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 620.dp)
                    .transformable(transformState),
                contentAlignment = Alignment.Center,
            ) {
                RemoteMediaImage(
                    source = artifact.source,
                    modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
                    loadManagedImage = if (artifact.origin == ArtifactOrigin.ManagedPath) {
                        { path -> onLoadManagedImage(path).getOrThrow() }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal suspend fun openManagedHostFile(
    context: Context,
    source: String,
    displayName: String,
    load: suspend (String) -> Result<HostFileContent>,
    startActivity: (Intent) -> Unit = context::startActivity,
): HostFileOpenEvent {
    val loaded = load(source)
    val content = loaded.getOrElse { failure ->
        return HostFileOpenPolicy.eventForDownloadFailure(failure.message)
    }
    return try {
        val artifact = Artifact(
            stableIdentity = source,
            type = ArtifactType.File,
            origin = ArtifactOrigin.ManagedPath,
            source = source,
            displayName = displayName,
        )
        val sharedFile = withContext(Dispatchers.IO) {
            writeSharedArtifact(context, artifact, content.bytes)
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            sharedFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, content.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
        HostFileOpenEvent.LaunchSucceeded
    } catch (error: android.content.ActivityNotFoundException) {
        HostFileOpenPolicy.eventForLaunchFailure(HostFileLaunchFailure.NoHandler)
    } catch (error: Throwable) {
        HostFileOpenPolicy.eventForLaunchFailure(HostFileLaunchFailure.Other(error.message))
    }
}

private fun writeSharedArtifact(context: Context, artifact: Artifact, bytes: ByteArray): File {
    val directory = File(context.cacheDir, "shared-artifacts").apply { mkdirs() }
    directory.listFiles()
        .orEmpty()
        .sortedByDescending(File::lastModified)
        .drop(19)
        .forEach(File::delete)
    val extension = artifact.displayName.substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.matches(Regex("^[a-z0-9]{1,10}$")) }
        ?.let { ".$it" }
        .orEmpty()
    return File(
        directory,
        "artifact-${artifact.stableIdentity.hashCode().toUInt().toString(16)}$extension",
    ).apply { writeBytes(bytes) }
}

private const val MAX_SUBAGENT_GUIDANCE_LENGTH = 512

@Composable
private fun DelegationControls(
    status: DelegationStatus,
    onSetPaused: (Boolean) -> Unit,
    onSteer: (String, String) -> Unit,
    onInterrupt: (String) -> Unit,
) {
    var steeringSubagent by remember { mutableStateOf<DelegatedSubagent?>(null) }
    var interruptingSubagentId by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Subagent controls" },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Subagent controls", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { onSetPaused(!status.paused) },
                enabled = !status.actionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (status.paused) {
                            "Resume spawning"
                        } else {
                            "Pause spawning"
                        }
                    },
            ) {
                Text(if (status.paused) "Resume spawning" else "Pause spawning")
            }
            status.active.forEach { subagent ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            subagent.goal,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subagent.status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { steeringSubagent = subagent },
                                enabled = !status.actionLoading,
                                modifier = Modifier.semantics {
                                    contentDescription = "Steer subagent ${subagent.subagentId}"
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) {
                                Text("Steer")
                            }
                            Button(
                                onClick = { interruptingSubagentId = subagent.subagentId },
                                enabled = !status.actionLoading,
                                modifier = Modifier.semantics {
                                    contentDescription = "Interrupt subagent ${subagent.subagentId}"
                                },
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) {
                                Text("Interrupt")
                            }
                        }
                    }
                }
            }
            status.notice?.let { notice ->
                Text(
                    notice.take(180),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status.error?.let { error ->
                Text(
                    error.take(180),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    steeringSubagent?.let { subagent ->
        var guidance by remember(subagent.subagentId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { steeringSubagent = null },
            title = { Text("Steer subagent") },
            text = {
                OutlinedTextField(
                    value = guidance,
                    onValueChange = { guidance = it.take(MAX_SUBAGENT_GUIDANCE_LENGTH) },
                    label = { Text("Guidance") },
                    supportingText = {
                        Text("${guidance.length}/$MAX_SUBAGENT_GUIDANCE_LENGTH")
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Subagent guidance"
                    },
                    maxLines = 5,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = guidance.trim()
                        if (!status.actionLoading && trimmed.isNotEmpty()) {
                            steeringSubagent = null
                            onSteer(subagent.subagentId, trimmed)
                        }
                    },
                    enabled = !status.actionLoading && guidance.isNotBlank(),
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm steer"
                    },
                ) {
                    Text("Steer")
                }
            },
            dismissButton = {
                TextButton(onClick = { steeringSubagent = null }) { Text("Cancel") }
            },
        )
    }
    interruptingSubagentId?.let { subagentId ->
        AlertDialog(
            onDismissRequest = { interruptingSubagentId = null },
            title = { Text("Interrupt subagent?") },
            text = { Text("Stop the active process-local subagent?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!status.actionLoading) {
                            interruptingSubagentId = null
                            onInterrupt(subagentId)
                        }
                    },
                    enabled = !status.actionLoading,
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm interrupt subagent $subagentId"
                    },
                ) {
                    Text("Confirm interrupt")
                }
            },
            dismissButton = {
                TextButton(onClick = { interruptingSubagentId = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class SessionMaintenanceAction {
    Compress,
    Undo,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionInsightsSheet(
    sessionTitle: String,
    chat: ChatSessionSnapshot,
    workspaceLabel: String? = null,
    provider: String? = null,
    maintenanceAvailable: Boolean,
    maintenanceEnabled: Boolean,
    artifacts: List<Artifact>,
    onOpenArtifacts: () -> Unit,
    onRefresh: () -> Unit,
    onCompress: (String?) -> Unit,
    onUndo: () -> Unit,
    onBranch: (Int?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingAction by remember { mutableStateOf<SessionMaintenanceAction?>(null) }
    var branchDialogOpen by remember { mutableStateOf(false) }
    var branchName by remember(sessionTitle) { mutableStateOf("$sessionTitle branch") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Session details", style = MaterialTheme.typography.headlineSmall)
                TextButton(
                    onClick = onRefresh,
                    enabled = maintenanceAvailable && !chat.insightsLoading,
                ) {
                    Text("Refresh")
                }
            }
            listOfNotNull(
                provider?.takeIf(String::isNotBlank)?.let { "Provider: $it" },
                workspaceLabel?.let { "Workspace: $it" },
            ).takeIf(List<String>::isNotEmpty)?.let { details ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    details.forEach { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (chat.insightsLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Loading session details" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text("Loading session details…")
                }
            }
            chat.insightsError?.takeIf { maintenanceAvailable }?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Session details error" },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Could not load session details", style = MaterialTheme.typography.titleSmall)
                        Text(error.take(180))
                    }
                }
            }
            if (!chat.insightsLoading) {
                SessionUsageCard(chat)
            }
            SessionArtifactsCard(
                artifacts = artifacts,
                onOpenArtifacts = onOpenArtifacts,
            )
            if (!chat.insightsLoading) {
                SessionContextCard(chat)
            }
            if (chat.maintenanceLoading ||
                chat.maintenanceError != null ||
                chat.notice != null
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Maintenance", style = MaterialTheme.typography.titleSmall)
                        if (chat.maintenanceLoading) {
                            Text("Applying session maintenance…")
                        }
                        chat.maintenanceError?.let { error ->
                            Text(
                                error.take(180),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        chat.notice?.let { notice ->
                            Text(
                                notice.take(180),
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
            if (maintenanceAvailable) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Session maintenance", style = MaterialTheme.typography.titleMedium)
                        if (!maintenanceEnabled && !chat.maintenanceLoading) {
                            Text(
                                "Available when the session is idle",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = { pendingAction = SessionMaintenanceAction.Compress },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Compress")
                        }
                        Button(
                            onClick = { pendingAction = SessionMaintenanceAction.Undo },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Undo")
                        }
                        Button(
                            onClick = { branchDialogOpen = true },
                            enabled = maintenanceEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Branch")
                        }
                    }
                }
            }
        }
    }
    pendingAction?.let { action ->
        val title = when (action) {
            SessionMaintenanceAction.Compress -> "Compress session?"
            SessionMaintenanceAction.Undo -> "Undo last turn?"
        }
        val confirmLabel = when (action) {
            SessionMaintenanceAction.Compress -> "Confirm compression"
            SessionMaintenanceAction.Undo -> "Confirm undo"
        }
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(title) },
            text = {
                Text(
                    when (action) {
                        SessionMaintenanceAction.Compress -> "Compress this session context?"
                        SessionMaintenanceAction.Undo -> "Remove the last user turn from this session?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        when (action) {
                            SessionMaintenanceAction.Compress -> onCompress(null)
                            SessionMaintenanceAction.Undo -> onUndo()
                        }
                    },
                    enabled = maintenanceEnabled,
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
            },
        )
    }
    if (branchDialogOpen) {
        AlertDialog(
            onDismissRequest = { branchDialogOpen = false },
            title = { Text("Branch session") },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Branch name") },
                    singleLine = true,
                    enabled = maintenanceEnabled,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = branchName.trim()
                        if (maintenanceEnabled && name.isNotEmpty()) {
                            branchDialogOpen = false
                            onBranch(null, name)
                        }
                    },
                    enabled = maintenanceEnabled && branchName.isNotBlank(),
                ) {
                    Text("Create branch")
                }
            },
            dismissButton = {
                TextButton(onClick = { branchDialogOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SessionUsageCard(chat: ChatSessionSnapshot) {
    val usage = chat.sessionUsage
    val context = chat.contextBreakdown
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Token usage", style = MaterialTheme.typography.titleMedium)
            SessionInsightMetric("Input tokens", formatSessionTokens(usage?.inputTokens))
            SessionInsightMetric("Output tokens", formatSessionTokens(usage?.outputTokens))
            SessionInsightMetric("Total tokens", formatSessionTokens(usage?.totalTokens))
            Text("Context used", style = MaterialTheme.typography.labelLarge)
            Text(
                formatContextSummary(
                    used = usage?.contextUsedTokens ?: context?.usedTokens,
                    max = usage?.contextMaxTokens ?: context?.maxTokens,
                    percent = usage?.contextPercent ?: context?.percent,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Model: ${chat.model?.takeIf(String::isNotBlank) ?: "Unknown"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SessionContextCard(chat: ChatSessionSnapshot) {
    val categories = chat.contextBreakdown?.categories.orEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Context categories", style = MaterialTheme.typography.titleMedium)
            if (categories.isEmpty()) {
                Text(
                    "No context categories reported",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                categories.forEach { category ->
                    ContextCategoryRow(category)
                }
            }
        }
    }
}

@Composable
private fun SessionArtifactsCard(
    artifacts: List<Artifact>,
    onOpenArtifacts: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Artifacts", style = MaterialTheme.typography.titleMedium)
            Text(
                if (artifacts.size == 1) "1 artifact" else "${artifacts.size} artifacts",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (artifacts.isEmpty()) {
                Text(
                    "No images, audio, or files referenced in this chat",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                artifacts.take(3).forEach { artifact ->
                    Text(
                        artifact.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(
                    onClick = onOpenArtifacts,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("View all artifacts")
                }
            }
        }
    }
}

@Composable
private fun ContextCategoryRow(category: ContextBreakdownCategory) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(category.name, modifier = Modifier.weight(1f))
        Text(
            "${formatSessionTokens(category.tokens)} tokens",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SessionInsightMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatSessionTokens(value: Long?): String =
    value?.let { String.format(Locale.US, "%,d", it) } ?: "—"

private fun formatContextSummary(used: Long?, max: Long?, percent: Double?): String {
    val tokenSummary = when {
        used != null && max != null -> "${formatSessionTokens(used)} / ${formatSessionTokens(max)}"
        used != null -> formatSessionTokens(used)
        max != null -> "— / ${formatSessionTokens(max)}"
        else -> "—"
    }
    return if (percent == null) tokenSummary else "$tokenSummary (${formatPercent(percent)}%)"
}

private fun formatPercent(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.1f", value)

@Composable
private fun RunStateContent(
    runState: RunEventState,
    processRows: List<ProcessRow>,
    runActive: Boolean,
    delegationStatus: DelegationStatus,
    durableSessionId: DurableSessionId,
    onClarificationResponse: (String, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
    onBlockingResponse: (UnsupportedBlockingKind, String, String) -> Unit,
) {
    if (!runState.hasVisibleContent() && delegationStatus.active.isEmpty() && processRows.isEmpty()) return
    val runningTools = runState.tools.filter { it.state == RunToolState.Running }
    var toolsExpanded by remember(durableSessionId.value) {
        mutableStateOf(false)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (runActive || runningTools.isNotEmpty()) {
            runState.status?.let { status -> RunStatusPill(status) }
        }
        if (runState.todos.isNotEmpty() || delegationStatus.active.isNotEmpty() || processRows.isNotEmpty()) {
            ActivityStack(
                runState = runState,
                delegationStatus = delegationStatus,
                processRows = processRows,
                runActive = runActive,
            )
        } else if (runState.tools.isNotEmpty()) {
            // Preserve the established tool-only surface; the unified stack takes
            // over as soon as a second authoritative activity family is present.
            ToolActivityGroup(
                tools = runState.tools,
                expanded = toolsExpanded,
                onToggle = { toolsExpanded = !toolsExpanded },
            )
        }
        runState.clarification?.let { clarification ->
            ClarificationCard(
                durableSessionId = durableSessionId,
                interaction = clarification,
                onResponse = onClarificationResponse,
            )
        }
        runState.approval?.let { approval ->
            ApprovalCard(
                durableSessionId = durableSessionId,
                interaction = approval,
                onResponse = onApprovalResponse,
            )
        }
        runState.unsupportedBlocking?.let { interaction ->
            SecureBlockingCard(interaction, onBlockingResponse)
        }
    }
}

private fun RunEventState.hasVisibleContent(): Boolean =
    status != null ||
        tools.isNotEmpty() ||
        todos.isNotEmpty() ||
        clarification != null ||
        approval != null ||
        unsupportedBlocking != null

/**
 * Whether a still-pending interactive card (clarification, approval, or secure
 * blocking prompt) occupies the transcript tail. The jump-to-bottom FAB hides
 * while one is present so it never overlaps that card's own action buttons.
 */
private fun hasPendingTailInteraction(runState: RunEventState): Boolean =
    runState.clarification?.lifecycle == RunInteractionLifecycle.Pending ||
        runState.approval?.lifecycle == RunInteractionLifecycle.Pending ||
        runState.unsupportedBlocking?.lifecycle == RunInteractionLifecycle.Pending

/** A transcript message paired with its stable index in the source list. */
internal data class IndexedChatMessage(val index: Int, val message: ChatMessage)

/**
 * A renderable transcript unit: either a single non-tool message or a run of
 * consecutive tool messages that collapse into one dropdown.
 */
internal sealed interface TranscriptEntry {
    data class Single(val index: Int, val message: ChatMessage) : TranscriptEntry

    data class ToolRun(val tools: List<IndexedChatMessage>) : TranscriptEntry
}

/**
 * Fold a flat transcript into renderable entries, coalescing every maximal run
 * of adjacent `Tool` messages into a single [TranscriptEntry.ToolRun]. Original
 * message indices are preserved so per-message expansion state stays stable.
 */
internal fun coalesceTranscriptEntries(messages: List<ChatMessage>): List<TranscriptEntry> {
    val entries = mutableListOf<TranscriptEntry>()
    var run: MutableList<IndexedChatMessage>? = null
    fun flush() {
        run?.let { entries.add(TranscriptEntry.ToolRun(it.toList())) }
        run = null
    }
    messages.forEachIndexed { index, message ->
        if (message.role == ChatMessageRole.Tool) {
            (run ?: mutableListOf<IndexedChatMessage>().also { run = it })
                .add(IndexedChatMessage(index, message))
        } else {
            flush()
            entries.add(TranscriptEntry.Single(index, message))
        }
    }
    flush()
    return entries
}

/**
 * The tool name is the leading segment of a transcript tool message, before the
 * " · " context separator (see `transcriptToolText` on the ViewModel). Falls
 * back to the trimmed text when no separator is present.
 */
internal fun transcriptToolName(text: String): String =
    text.substringBefore(" · ").trim().ifEmpty { text.trim() }

/**
 * Verb bucket for a gateway tool name, or null for tools this client does not
 * recognize. Buckets merge related names ("write_file" and "patch" are both
 * edits) so the summary can read "edited 2 files" instead of listing each.
 */
private fun toolVerbBucket(name: String): String? = when (name.lowercase()) {
    "read_file", "read", "cat" -> "read"
    "write_file", "patch", "edit_file", "apply_patch", "edit", "write" -> "edit"
    "shell", "terminal", "bash", "exec", "run_command" -> "command"
    "web_search", "search_web" -> "web_search"
    "web_fetch", "fetch", "http_get" -> "fetch"
    "skill_view", "skill" -> "skill"
    "list_files", "ls", "glob" -> "list"
    "grep", "search_files", "search" -> "grep"
    else -> null
}

private fun toolVerbPhrase(bucket: String, count: Int): String = when (bucket) {
    "read" -> if (count == 1) "read a file" else "read $count files"
    "edit" -> if (count == 1) "edited a file" else "edited $count files"
    "command" -> if (count == 1) "ran a command" else "ran $count commands"
    "web_search" -> if (count == 1) "searched the web" else "searched the web ×$count"
    "fetch" -> if (count == 1) "fetched a page" else "fetched $count pages"
    "skill" -> if (count == 1) "loaded a skill" else "loaded $count skills"
    "list" -> if (count == 1) "listed files" else "listed files ×$count"
    else -> if (count == 1) "searched files" else "searched files ×$count"
}

/**
 * Claude-app style activity summary: known tools compress into verb phrases
 * ("edited 2 files, ran a command"), unknown tool names fall back to counted
 * raw names so a server-side rename degrades to less prose, never a lie.
 * Running tools lead with "running <name>" so in-flight work stays visible.
 */
internal fun toolActivitySummary(
    completedNames: List<String>,
    runningNames: List<String> = emptyList(),
): String {
    val buckets = linkedMapOf<String, Int>()
    val unknown = linkedMapOf<String, Int>()
    completedNames.forEach { name ->
        val bucket = toolVerbBucket(name)
        if (bucket != null) {
            buckets.merge(bucket, 1, Int::plus)
        } else {
            unknown.merge(name, 1, Int::plus)
        }
    }
    val phrases = buildList {
        runningNames.distinct().takeIf(List<String>::isNotEmpty)?.let { running ->
            add("running ${running.joinToString(", ")}")
        }
        buckets.entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .forEach { add(toolVerbPhrase(it.key, it.value)) }
        unknown.entries.forEach { (name, count) ->
            add(if (count > 1) "$name ×$count" else name)
        }
    }
    val visible = phrases.take(3)
    val overflow = phrases.size - visible.size
    return buildString {
        append(visible.joinToString(", "))
        if (overflow > 0) append(", +$overflow more")
    }.replaceFirstChar { it.uppercase() }
}

/**
 * One collapsible card wrapping all tool activity for the current run, in the
 * style of Hermex's tool activity group: state icon, action count, a summary of
 * unique tool names, and the per-tool rows only when expanded.
 */
@Composable
private fun ToolActivityGroup(
    tools: List<RunToolRow>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val anyRunning = tools.any { it.state == RunToolState.Running }
    val noun = if (tools.size == 1) "action" else "actions"
    val stateText = if (anyRunning) "running" else "completed"
    val summary = toolActivitySummary(
        completedNames = tools.filter { it.state == RunToolState.Completed }.map(RunToolRow::name),
        runningNames = tools.filter { it.state == RunToolState.Running }.map(RunToolRow::name),
    )
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${tools.size} $noun, $stateText, ${if (expanded) "expanded" else "collapsed"}"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (anyRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = semanticColors.active,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = semanticColors.completed,
                    )
                }
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                tools.forEachIndexed { index, tool ->
                    key(tool.toolId) {
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        RunToolRowContent(tool)
                    }
                }
            }
        }
    }
}

/**
 * Historical tool-role transcript message collapsed into the same card style as
 * the live tool activity group: a one-line preview, expanding to the full
 * markdown content inline.
 */
@Composable
private fun ToolMessageBlock(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    onOpenManagedFile: (suspend (String) -> HostFileOpenEvent)? = null,
) {
    val preview = remember(text) {
        text.replace('\n', ' ').trim().take(80)
    }
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (expanded) "Hide tool result" else "Show tool result"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalHermesSemanticColors.current.completed,
                )
                Text(
                    "Tool",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!expanded) {
                    Text(
                        preview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                MarkdownMessage(
                    text,
                    loadManagedImage = loadManagedImage,
                    onOpenManagedFile = onOpenManagedFile,
                )
            }
        }
    }
}

/**
 * A run of consecutive completed tool messages in the persisted transcript,
 * collapsed into one dropdown mirroring the live [ToolActivityGroup]: a single
 * header ("N actions, completed, <summary>") that expands to the individual
 * tool rows, each independently expandable to its full result.
 */
@Composable
private fun TranscriptToolRunGroup(
    tools: List<IndexedChatMessage>,
    expanded: Boolean,
    onToggle: () -> Unit,
    sessionKey: String,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    onOpenManagedFile: (suspend (String) -> HostFileOpenEvent)? = null,
) {
    val semanticColors = LocalHermesSemanticColors.current
    val noun = if (tools.size == 1) "action" else "actions"
    val summary = remember(tools) {
        toolActivitySummary(
            completedNames = tools.map { transcriptToolName(it.message.text) },
        )
    }
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${tools.size} $noun, completed, ${if (expanded) "expanded" else "collapsed"}"
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = semanticColors.completed,
                )
                Text(
                    summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                tools.forEach { indexed ->
                    key(indexed.index) {
                        var showToolMessage by rememberSaveable(sessionKey, indexed.index) {
                            mutableStateOf(false)
                        }
                        ToolMessageBlock(
                            text = indexed.message.text,
                            expanded = showToolMessage,
                            onToggle = { showToolMessage = !showToolMessage },
                            loadManagedImage = loadManagedImage,
                            onOpenManagedFile = onOpenManagedFile,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsed thinking row in the style of Hermex's reasoning block: label plus a
 * one-line preview of the reasoning, expanding to the full text inline.
 */
@Composable
private fun ThinkingBlock(
    reasoning: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val preview = remember(reasoning) {
        reasoning.replace('\n', ' ').trim().take(80)
    }
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (expanded) "Hide thinking" else "Show thinking"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!expanded) {
                    Text(
                        preview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarificationCard(
    durableSessionId: DurableSessionId,
    interaction: ClarificationInteraction,
    onResponse: (String, String) -> Unit,
) {
    var answer by remember(interaction.requestId) { mutableStateOf("") }
    var selectedChoices by remember(interaction.requestId) { mutableStateOf(emptySet<String>()) }
    val pending = interaction.lifecycle == RunInteractionLifecycle.Pending
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Clarification", style = MaterialTheme.typography.titleSmall)
            Text(interaction.question)
            if (pending) {
                // Grounded in the desktop clarify card: choices are shown as
                // selectable rows, an "Other" free-text field is ALWAYS offered
                // alongside them, and the card is confirmed with Skip / Continue.
                // Typing in the field and picking a choice are mutually exclusive.
                if (interaction.choices.isNotEmpty()) {
                    if (interaction.multiSelect) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            interaction.choices.forEach { choice ->
                                FilterChip(
                                    selected = choice in selectedChoices,
                                    onClick = {
                                        answer = ""
                                        selectedChoices = if (choice in selectedChoices) {
                                            selectedChoices - choice
                                        } else {
                                            selectedChoices + choice
                                        }
                                    },
                                    label = { Text(choice) },
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            interaction.choices.forEach { choice ->
                                val chosen = choice in selectedChoices
                                Surface(
                                    onClick = {
                                        answer = ""
                                        selectedChoices = setOf(choice)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (chosen) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    contentColor = if (chosen) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        choice,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = {
                        answer = it
                        // Typing is its own answer — clear any picked choice so the
                        // two inputs can't both look selected (desktop parity).
                        if (it.isNotBlank()) selectedChoices = emptySet()
                    },
                    label = {
                        Text(if (interaction.choices.isEmpty()) "Response" else "Other")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true,
                )
                val pendingAnswer: String? = when {
                    interaction.multiSelect && selectedChoices.isNotEmpty() ->
                        interaction.choices.filter { it in selectedChoices }.joinToString(", ")
                    !interaction.multiSelect && selectedChoices.isNotEmpty() ->
                        selectedChoices.first()
                    answer.isNotBlank() -> answer.trim()
                    else -> null
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Skip sends an empty answer, matching desktop: the agent
                    // treats it as "no preference / proceed".
                    TextButton(
                        onClick = {
                            onResponse(interaction.requestId, "")
                        },
                    ) {
                        Text("Skip")
                    }
                    Button(
                        enabled = pendingAnswer != null,
                        onClick = {
                            pendingAnswer?.let { onResponse(interaction.requestId, it) }
                        },
                    ) {
                        Text("Continue")
                    }
                }
            } else {
                // Settled: a responded clarification is cleared outright (see
                // publishClarificationResponse), so the only states that reach
                // here are a timeout expiry or a send failure. Show a brief,
                // non-interactive note for each.
                Text("Clarification response", style = MaterialTheme.typography.labelMedium)
                when (interaction.lifecycle) {
                    RunInteractionLifecycle.Failed -> {
                        Text(
                            "Could not send response",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        Text(
                            "Timed out",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalCard(
    durableSessionId: DurableSessionId,
    interaction: ApprovalInteraction,
    onResponse: (String, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                    "Approval pending"
                } else {
                    "Approval ${interaction.lifecycle.name}"
                }
                stateDescription = interaction.lifecycle.name
            },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Approval",
                color = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.titleSmall,
            )
            interaction.commandPreview?.takeIf(String::isNotBlank)?.let { command ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Command preview: $command" },
                ) {
                    Text(
                        command,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            interaction.descriptionPreview?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    interaction.choices.forEach { choice ->
                        FilledTonalButton(
                            onClick = dropUnlessResumed {
                                onResponse(choice, false)
                            },
                        ) {
                            Text(choice)
                        }
                    }
                }
            } else {
                Text("Approval response", style = MaterialTheme.typography.labelMedium)
                Text(interaction.lifecycle.name)
            }
        }
    }
}

@Composable
private fun SecureBlockingCard(
    interaction: UnsupportedBlockingInteraction,
    onResponse: (UnsupportedBlockingKind, String, String) -> Unit,
) {
    var value by remember(interaction.requestId) { mutableStateOf("") }
    val isSensitiveInput = interaction.kind == UnsupportedBlockingKind.Sudo ||
        interaction.kind == UnsupportedBlockingKind.Secret
    val inputLabel = if (interaction.kind == UnsupportedBlockingKind.Sudo) "Sudo password" else "Secret value"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (interaction.kind == UnsupportedBlockingKind.Sudo) "Administrator password required"
                else if (interaction.kind == UnsupportedBlockingKind.Secret) "Secret required"
                else "Client read request",
                style = MaterialTheme.typography.titleSmall,
            )
            if (interaction.kind == UnsupportedBlockingKind.Secret) {
                interaction.prompt?.takeIf(String::isNotBlank)?.let { Text(it) }
            }
            if (isSensitiveInput && interaction.lifecycle == RunInteractionLifecycle.Pending) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(4_096) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = inputLabel },
                    label = { Text(inputLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        value = ""
                        onResponse(interaction.kind, interaction.requestId, "")
                    }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val submitted = value
                            value = ""
                            onResponse(interaction.kind, interaction.requestId, submitted)
                        },
                        enabled = value.isNotEmpty(),
                    ) {
                        Text(if (interaction.kind == UnsupportedBlockingKind.Sudo) "Send password" else "Send secret")
                    }
                }
            } else {
                Text("Request status: ${interaction.lifecycle.name}")
            }
        }
    }
}

private fun sessionContextPercent(chat: ChatSessionSnapshot): Double? {
    chat.sessionUsage?.let { usage ->
        usage.contextPercent?.let { return it }
        val used = usage.contextUsedTokens
        val max = usage.contextMaxTokens
        if (used != null && max != null && max > 0) return used * 100.0 / max
    }
    return chat.contextBreakdown?.percent
}

@Composable
private fun SessionContextRing(
    percent: Double?,
    artifactCount: Int,
    onClick: () -> Unit,
) {
    val fraction = percent?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
    val ringColor = when {
        fraction == null -> MaterialTheme.colorScheme.onSurfaceVariant
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.75f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .semantics {
                contentDescription = "Open session details"
                val contextDescription = percent
                    ?.let { "Context ${formatPercent(it)} percent used" }
                    ?: "Context usage unknown"
                val artifactDescription = if (artifactCount == 1) {
                    "1 artifact"
                } else {
                    "$artifactCount artifacts"
                }
                stateDescription = "$contextDescription, $artifactDescription"
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { fraction ?: 0f },
                modifier = Modifier.size(30.dp),
                color = ringColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeWidth = 3.dp,
            )
            Text(
                percent?.let { it.coerceIn(0.0, 99.0).toInt().toString() } ?: "–",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RunStatusPill(status: RunStatus) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.semantics {
            contentDescription = "Current status: ${status.kind} — ${status.text}"
            stateDescription = "Current"
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(status.kind, style = MaterialTheme.typography.labelMedium)
            Text(
                status.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RunToolRowContent(tool: RunToolRow) {
    val semanticColors = LocalHermesSemanticColors.current
    val toolContext = tool.context?.takeIf(String::isNotBlank)
    val toolSummary = tool.summary?.takeIf(String::isNotBlank)
    val description = when (tool.state) {
        RunToolState.Running -> "Running tool ${tool.name}${toolContext?.let { ": $it" }.orEmpty()}"
        RunToolState.Completed -> "Completed tool ${tool.name}${toolSummary?.let { ": $it" }.orEmpty()}"
    }
    val hasDetail = toolContext != null || toolSummary != null
    var detailExpanded by rememberSaveable(tool.toolId) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasDetail) {
                    Modifier.clickable { detailExpanded = !detailExpanded }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp)
            .semantics {
                contentDescription = description
                stateDescription = tool.state.name
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tool.state) {
            RunToolState.Running -> CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = "Running" },
                color = semanticColors.active,
                strokeWidth = 2.dp,
            )
            RunToolState.Completed -> Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = "Completed" },
                tint = semanticColors.completed,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
            toolContext?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (detailExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            toolSummary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (detailExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hasDetail) {
            Icon(
                if (detailExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun resolvePickedAttachment(context: Context, uri: Uri): ComposerAttachment {
    require(uri.scheme == "content") { "Selected item was not a readable document" }
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    var rawName: String? = null
    var sizeBytes = -1L
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) rawName = cursor.getString(nameColumn)
            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) sizeBytes = cursor.getLong(sizeColumn)
        }
    }
    val displayName = AttachmentPolicy.sanitizeDisplayName(
        rawName ?: uri.lastPathSegment.orEmpty(),
    )
    return ComposerAttachment(
        id = uri.toString(),
        uri = uri.toString(),
        displayName = displayName,
        mimeType = context.contentResolver.getType(uri)?.takeIf(String::isNotBlank),
        sizeBytes = sizeBytes,
    )
}

@Composable
private fun SessionPlaceholder() {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("Session placeholder surface"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Select a session", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MissingSessionScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Session is no longer available")
    }
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
