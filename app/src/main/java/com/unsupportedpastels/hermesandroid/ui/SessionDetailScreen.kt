package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import com.unsupportedpastels.mercury.core.activity.ActivityLineInput
import com.unsupportedpastels.mercury.core.activity.ActivityLinePolicy
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.isNoProjectBucket
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactExtractor
import com.unsupportedpastels.hermesandroid.voice.AutoSpeakEffect
import com.unsupportedpastels.hermesandroid.voice.ComposerVoiceConversation
import com.unsupportedpastels.hermesandroid.voice.MessageReadAloud
import com.unsupportedpastels.hermesandroid.voice.VoiceConversationBar
import com.unsupportedpastels.hermesandroid.voice.VoiceConversationState
import com.unsupportedpastels.hermesandroid.voice.VoiceConversationToggleButton
import com.unsupportedpastels.hermesandroid.voice.rememberReadAloudSession
import com.unsupportedpastels.hermesandroid.voice.rememberVoiceConversationHost
import com.unsupportedpastels.hermesandroid.voice.DeviceSpeechRecognizerController
import com.unsupportedpastels.hermesandroid.voice.DeviceSpeechInputButton
import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.CacheSource
import com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.gateway.ValidReasoningEfforts
import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import kotlinx.coroutines.launch
import com.unsupportedpastels.mercury.core.composer.ComposerAction
import com.unsupportedpastels.mercury.core.composer.ComposerRejection
import com.unsupportedpastels.mercury.core.composer.ComposerRoutingPolicy

private data class PendingComposerSend(
    val draft: String,
    val prompt: String,
    val acceptedCount: Long,
    val rejectedCount: Long,
)

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
internal fun SessionDetailScreen(
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
    onSteer: (String) -> Unit,
    onDiscardUncertainQueue: () -> Unit = {},
    onReasoningSelected: (String) -> Unit,
    onFastSelected: (Boolean) -> Unit,
    onOpenModelPicker: () -> Unit,
    onClarificationResponse: (String, String?, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
    onBlockingResponse: (UnsupportedBlockingKind, String, String) -> Unit,
    showStop: Boolean,
    stopping: Boolean,
    onStop: () -> Unit,
    onRetryConnection: () -> Unit = {},
    onGetProgress: () -> Unit = {},
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
    onLoadManagedImage: suspend (String) -> Result<ByteArray>,
    onLoadManagedVideo: (suspend (String) -> Result<ManagedVideoMedia>)? = null,
    onPeekManagedVideo: (suspend (String) -> ManagedVideoMedia?)? = null,
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
    // Keep the exact draft (including whitespace) until the controller acknowledges
    // this submission. Optimistic transcript rows are not acceptance evidence.
    var pendingSend by remember(session.id, voiceInputScopeKey) { mutableStateOf<PendingComposerSend?>(null) }
    val connectionBusy = chat.connectionPhase != ChatConnectionPhase.Idle
    val controlledTurn = showStop && chat.isSending && !connectionBusy
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
    val hasRunStateContent = chat.runState.hasVisibleContent()
    val turnActive = chat.isSending || connectionBusy || pendingSend != null
    val transcriptEntries = remember(chat.messages, turnActive) { foldTranscriptTurns(chat.messages, turnActive) }
    var showActivity by remember(session.id) { mutableStateOf(false) }
    var activityNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.id) {
        while (true) { activityNow = System.currentTimeMillis(); delay(1_000) }
    }
    var dismissedBackground by rememberSaveable(session.id.value) { mutableStateOf(emptyList<String>()) }
    val visibleBackground = chat.backgroundTasks.rows.filterNot {
        it.isDismissible(activityNow) && it.dismissalKey() in dismissedBackground
    }
    val connectionLost = chat.connectionRecoveryAvailable && !connectionBusy
    val observedRun = chat.runState.copy(
        todos = if (chat.progress.hasMilestoneSnapshot) chat.progress.milestones else chat.runState.todos,
        status = chat.runState.status.takeUnless { chat.progress.restored },
    )
    val summary = SessionProgressPolicy.summarize(observedRun, chat.isSending)
    val currentMessages = chat.messages.drop(chat.messages.indexOfLast { it.role == ChatMessageRole.User }.coerceAtLeast(0))
    val streaming = currentMessages.lastOrNull { it.role == ChatMessageRole.Assistant && it.isStreaming }
    val runningTools = observedRun.tools.filter { it.state == RunToolState.Running }
    // isSending is authoritative (live send or resume `running`). `restored` only
    // labels recovered evidence as saved; it must never hide a live turn after a
    // reopen or foreground reconnect, whose history refresh always sets it.
    val line = rememberHeldActivityLine(ActivityLinePolicy.decide(ActivityLineInput(
        isSending = chat.isSending,
        isStopping = stopping,
        connectionPhase = chat.connectionPhase.name.lowercase(),
        pendingSubmission = pendingSend != null,
        connectionLost = connectionLost,
        awaitingUser = hasPendingTailInteraction(chat.runState),
        runningToolNames = runningTools.map { it.name },
        runningToolContext = runningTools.firstOrNull()?.context,
        statusText = observedRun.status?.text,
        statusKind = observedRun.status?.kind,
        streamingAnswer = streaming?.text?.isNotBlank() == true,
        streamingReasoning = streaming?.reasoningText?.isNotBlank() == true,
        inProgressTodo = summary.inProgress.firstOrNull()?.label,
        // Child evidence is live run state too: a reopen's history refresh sets
        // `restored`, which must not hide children the host still reports running.
        activeChildCount = if (connectionLost || connectionBusy) 0
            else chat.backgroundTasks.presentation(visibleBackground, activityNow).activeCount,
    )))

    val timelineLastIndex = (
        transcriptEntries.size + if (hasRunStateContent) 1 else 0
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
        transcriptEntries.size,
        turnActive,
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
    LaunchedEffect(session.id, pendingSend, chat.acceptedSubmissionCount, chat.rejectedSubmissionCount) {
        val pending = pendingSend ?: return@LaunchedEffect
        when {
            chat.acceptedSubmissionCount > pending.acceptedCount &&
                chat.acceptedSubmissionText == pending.prompt -> {
                if (draft == pending.draft) onDraftChanged("")
                pendingSend = null
            }
            chat.rejectedSubmissionCount > pending.rejectedCount &&
                chat.rejectedSubmissionText == pending.prompt -> pendingSend = null
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
                                foldedEntryKey(entry, chat)
                            },
                        ) { entry ->
                            if (entry is FoldedEntry.TurnActivity) {
                                var expanded by rememberSaveable(session.id.value, foldedEntryKey(entry, chat)) { mutableStateOf(false) }
                                TurnActivityGroup(entry, expanded, { expanded = !expanded }, session.id.value,
                                    loadManagedImage = { path -> onLoadManagedImage(path).getOrThrow() },
                                    loadManagedVideo = onLoadManagedVideo, peekManagedVideo = onPeekManagedVideo)
                                return@items
                            }
                            entry as FoldedEntry.Single
                            val message = entry.message
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (message.role == ChatMessageRole.System) {
                                    Text(
                                        "System",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val renderedText = message.text.ifEmpty {
                                    if (message.isStreaming) "…" else ""
                                }
                                when {
                                    message.role == ChatMessageRole.Tool -> {
                                        var showToolMessage by rememberSaveable(session.id.value, foldedEntryKey(entry, chat)) {
                                            mutableStateOf(false)
                                        }
                                        ToolMessageBlock(
                                            text = renderedText,
                                            expanded = showToolMessage,
                                            onToggle = { showToolMessage = !showToolMessage },
                                            loadManagedImage = { path ->
                                                onLoadManagedImage(path).getOrThrow()
                                            },
                                            loadManagedVideo = onLoadManagedVideo,
                                            peekManagedVideo = onPeekManagedVideo,
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
                                                    loadManagedVideo = onLoadManagedVideo,
                                                    peekManagedVideo = onPeekManagedVideo,
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
                                                loadManagedVideo = onLoadManagedVideo,
                                                peekManagedVideo = onPeekManagedVideo,
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
                                            loadManagedVideo = onLoadManagedVideo,
                                            peekManagedVideo = onPeekManagedVideo,
                                        )
                                    }
                                }
                            }
                        }
                        if (hasRunStateContent) {
                            item(key = "run-state") {
                                RunStateContent(
                                    runState = chat.runState,
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
                    if (chat.connectionRecoveryAvailable && !chat.isSending && !connectionBusy) {
                        TextButton(
                            onClick = dropUnlessResumed { onRetryConnection() },
                            modifier = Modifier.semantics {
                                contentDescription = "Retry session connection"
                            },
                        ) {
                            Text("Retry connection")
                        }
                    }
                }
                    if (chat.queueAcknowledgementUncertain) {
                        TextButton(onClick = {
                            pendingSend?.let { if (draft == it.draft) onDraftChanged("") }
                            pendingSend = null
                            onDiscardUncertainQueue()
                        }) { Text("Discard local queue attempt") }
                        Text("Does not cancel or resend server work. Check the transcript before repeating the message.",
                            style = MaterialTheme.typography.bodySmall)
                    }
            chat.notice?.let { notice ->
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
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
            val composerEnabled = true
            val submissionEnabled = canSend && !chat.isLoading && !connectionBusy && pendingSend == null
            val attachmentsEnabled = submissionEnabled && !chat.isSending
            // Ordinary active-turn text queues; explicit /steer remains guidance.
            // Empty input or attachments retain the existing Stop affordance.
            val canSubmitDuringActiveTurn = !chat.isSending || (controlledTurn && attachments.isEmpty())
            val showStopControl = controlledTurn && (
                attachments.isNotEmpty() ||
                    ComposerRoutingPolicy.shouldShowStopButton(
                        isSending = chat.isSending,
                        turnActive = controlledTurn,
                        draft = draft,
                    )
                )
            val voiceHost = rememberVoiceConversationHost(
                conversation = voiceConversation,
                sessionId = session.id.value,
                chat = chat,
                onSubmit = onVoiceSubmit,
                // Barge-in cuts the running turn through the same seam as the
                // composer Stop button.
                onStopTurn = { if (controlledTurn && !stopping) onStop() },
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
                    .testTag("Message composer")
                    .animateContentSize(tween(150)),
                shape = RoundedCornerShape(30.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column {
                ComposerActivityLine(line, chat.progress.turnStartedAtEpochMillis, { showActivity = true })
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
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
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
                            requestIdentity = voiceInputScopeKey,
                        )
                        if (voiceHost != null) {
                            VoiceConversationToggleButton(
                                host = voiceHost,
                                enabled = submissionEnabled,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    if (showStopControl) {
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
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            val action = ComposerRoutingPolicy.route(
                                draft = draft,
                                turnActive = chat.isSending && controlledTurn,
                                hasAttachments = attachments.isNotEmpty() || hostReferences.isNotEmpty(),
                            )
                            when (action) {
                                ComposerAction.OpenModelPicker -> {
                                    pendingSend = null
                                    onDraftChanged("")
                                    onOpenModelPicker()
                                }
                                is ComposerAction.SetReasoning -> {
                                    pendingSend = null
                                    onDraftChanged("")
                                    onReasoningSelected(action.effort)
                                }
                                is ComposerAction.Steer -> {
                                    pendingSend = null
                                    onDraftChanged("")
                                    onSteer(action.text)
                                }
                                is ComposerAction.Reject -> {
                                    // Same wording as iOS: the reason is shown, never swallowed.
                                    attachmentError = when (action.reason) {
                                        ComposerRejection.BlankPrompt -> null
                                        ComposerRejection.BlankSteer -> "Enter guidance after /steer."
                                        ComposerRejection.NoActiveTurnToSteer -> "There is no active turn to steer."
                                        ComposerRejection.AttachmentsUnavailableWhileSteering ->
                                            "Attachments are unavailable while steering an active turn."
                                    }
                                }
                                is ComposerAction.Submit, is ComposerAction.Queue -> {
                                    val message = when (action) {
                                        is ComposerAction.Submit -> action.text
                                        is ComposerAction.Queue -> action.text
                                    }
                                    // Match the host's reference-prefixed prompt, but
                                    // only clear the unchanged local text draft.
                                    val submittedText = (hostReferences + message.takeIf(String::isNotBlank))
                                        .filterNotNull().joinToString("\n").trim()
                                    pendingSend = PendingComposerSend(
                                        draft, submittedText, chat.acceptedSubmissionCount,
                                        chat.rejectedSubmissionCount,
                                    )
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
                        enabled = submissionEnabled &&
                            !chat.isQueueSubmitting &&
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
                            .semantics {
                                contentDescription = when (ComposerRoutingPolicy.route(
                                    draft, chat.isSending && controlledTurn,
                                    attachments.isNotEmpty() || hostReferences.isNotEmpty(),
                                )) {
                                    is ComposerAction.Queue -> "Queue message"
                                    is ComposerAction.Steer -> "Steer active turn"
                                    else -> "Send message"
                                }
                            },
                    ) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = null)
                    }
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
    if (showActivity) {
        SessionActivitySheet(
            summary = summary, connectionLost = connectionLost,
            lastObservedAt = chat.progress.lastObservedAtEpochMillis, now = activityNow,
            onDismiss = { showActivity = false }, onRetryConnection = onRetryConnection,
            onGetUpdate = onGetProgress, refreshing = chat.progress.refreshing,
            refreshError = chat.progress.refreshError, restored = chat.progress.restored || connectionBusy,
            partialHistory = chat.progress.restored && chat.progress.partialHistory,
            backgroundRows = visibleBackground, processRows = chat.processRows,
            onDismissBackground = { rows -> dismissedBackground = (dismissedBackground + rows.map { it.dismissalKey() }).takeLast(64) },
            evidence = chat.progress.evidence.filter { it.completed && !it.summary.isNullOrBlank() && it.toolName !in setOf("todo", "todo_list") }
                .map { SessionProgressItem(it.toolName, it.summary) },
            // Live only while attached: a reconnecting socket cannot vouch for a tool row.
            tools = observedRun.tools, status = observedRun.status?.text, isSending = chat.isSending && !connectionBusy,
            currentMessages = if (turnActive) currentMessages else emptyList(),
            loadManagedImage = { path -> onLoadManagedImage(path).getOrThrow() },
            loadManagedVideo = onLoadManagedVideo, peekManagedVideo = onPeekManagedVideo,
        )
    }
    if (showSessionInsights) {
        SessionInsightsSheet(
            onOpenActivity = { showSessionInsights = false; showActivity = true },
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
            onLoadManagedVideo = onLoadManagedVideo,
            onPeekManagedVideo = onPeekManagedVideo,
            onLoadManagedFile = onLoadManagedFile,
        )
    }
}

@Composable
private fun RunStateContent(
    runState: RunEventState,
    durableSessionId: DurableSessionId,
    onClarificationResponse: (String, String?, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
    onBlockingResponse: (UnsupportedBlockingKind, String, String) -> Unit,
) {
    if (!runState.hasVisibleContent()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
    clarification != null ||
        approval != null ||
        unsupportedBlocking != null

/**
 * Whether a still-pending interactive card (clarification, approval, or secure
 * blocking prompt) occupies the transcript tail. The jump-to-bottom FAB hides
 * while one is present so it never overlaps that card's own action buttons.
 */
private fun hasPendingTailInteraction(runState: RunEventState): Boolean =
    listOf(runState.clarification?.lifecycle, runState.approval?.lifecycle,
        runState.unsupportedBlocking?.lifecycle).any {
        it == RunInteractionLifecycle.Pending || it == RunInteractionLifecycle.Responding
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
