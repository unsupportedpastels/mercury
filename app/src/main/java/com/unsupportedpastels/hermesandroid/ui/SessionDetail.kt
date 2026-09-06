package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.core.content.FileProvider
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
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.isNoProjectBucket
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.attachment.AttachmentPolicy
import com.unsupportedpastels.hermesandroid.artifacts.Artifact
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactExtractor
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactOrigin
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactType
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
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState

import com.unsupportedpastels.hermesandroid.connection.SlashCompletionState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.CacheSource
import com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ContextBreakdownCategory
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.gateway.ValidReasoningEfforts
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class PendingComposerSend(
    val draft: String,
    val prompt: String,
    val acceptedCount: Long,
    val rejectedCount: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
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
    onReasoningSelected: (String) -> Unit,
    onFastSelected: (Boolean) -> Unit,
    onOpenModelPicker: () -> Unit,
    onClarificationResponse: (String, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
    onBlockingResponse: (UnsupportedBlockingKind, String, String) -> Unit,
    showStop: Boolean,
    stopping: Boolean,
    onStop: () -> Unit,
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
    val connectionProgress = when (chat.connectionPhase) {
        ChatConnectionPhase.Connecting -> "Connecting…"
        ChatConnectionPhase.Reconnecting -> "Reconnecting…"
        ChatConnectionPhase.Submitting -> "Sending…"
        ChatConnectionPhase.Idle -> null
    }
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
    val hasRunStateContent = chat.runState.hasVisibleContent() ||
        chat.processRows.isNotEmpty()
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
                                transcriptEntryKey(entry, chat)
                            },
                        ) { entry ->
                            if (entry is TranscriptEntry.ToolRun) {
                                var toolsExpanded by rememberSaveable(
                                    session.id.value,
                                    transcriptEntryKey(entry, chat),
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
                                        streaming = message.isStreaming,
                                        expanded = showReasoning,
                                        onToggle = { showReasoning = !showReasoning },
                                    )
                                }
                                val renderedText = message.text.ifEmpty {
                                    if (message.isStreaming) "…" else ""
                                }
                                when {
                                    message.role == ChatMessageRole.Tool -> {
                                        var showToolMessage by rememberSaveable(session.id.value, transcriptEntryKey(entry, chat)) {
                                            mutableStateOf(false)
                                        }
                                        ToolMessageBlock(
                                            text = renderedText,
                                            expanded = showToolMessage,
                                            onToggle = { showToolMessage = !showToolMessage },
                                            loadManagedImage = { path ->
                                                onLoadManagedImage(path).getOrThrow()
                                            },
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
            if (connectionProgress != null || pendingSend != null || chat.isSending) {
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
                        connectionProgress ?: if (pendingSend != null) "Sending…" else "Hermes is responding…",
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
            BackgroundTaskStrip(chat.backgroundTasks)
            // Keep the composer available during a controlled turn so the user can
            // issue the server's /steer command through the normal send path.
            val composerEnabled = true
            val submissionEnabled = canSend && !chat.isLoading && !connectionBusy && pendingSend == null
            val attachmentsEnabled = submissionEnabled && !chat.isSending
            val canSubmitDuringActiveTurn = !chat.isSending ||
                (controlledTurn && attachments.isEmpty() && isSteerCommand(draft))
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
                                enabled = submissionEnabled,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    if (controlledTurn && !canSubmitDuringActiveTurn) {
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
    durableSessionId: DurableSessionId,
    onClarificationResponse: (String, String) -> Unit,
    onApprovalResponse: (String, Boolean) -> Unit,
    onBlockingResponse: (UnsupportedBlockingKind, String, String) -> Unit,
) {
    if (!runState.hasVisibleContent() && processRows.isEmpty()) return
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
        if (runState.todos.isNotEmpty() || processRows.isNotEmpty()) {
            ActivityStack(
                runState = runState,
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

/** Retained reducer IDs survive streaming and removal of earlier rows. */
internal fun transcriptEntryKey(entry: TranscriptEntry, chat: ChatSessionSnapshot): String {
    val index = when (entry) {
        is TranscriptEntry.Single -> entry.index
        is TranscriptEntry.ToolRun -> entry.tools.first().index
    }
    val presentation = chat.transcriptPresentation?.takeIf { it.messages === chat.messages }
    val identity = presentation?.state?.rows?.getOrNull(index)?.id?.let { "row:$it" } ?: "index:$index"
    return if (entry is TranscriptEntry.ToolRun) "tool-run:$identity" else "message:$identity"
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
    streaming: Boolean,
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
                    if (streaming) "Thinking" else "Reasoning",
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
internal fun SessionPlaceholder() {
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
internal fun MissingSessionScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Session is no longer available")
    }
}
