package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.connection.SessionSearchResult

import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.CacheSource
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.session.SavedSessionFilter
import com.unsupportedpastels.hermesandroid.session.SessionListFilter
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val HOME_RECENT_SESSION_PREVIEW_LIMIT = 10

/** Silent working-presence poll cadence for the Home session list. */
internal const val WORKING_PRESENCE_POLL_MILLIS = 3_000L

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

/**
 * Session collections are metadata projections of the gateway snapshot. Keeping the
 * keys explicit prevents transcript/chat deltas from re-running merge, workspace
 * attribution, and home sorting when the inbox inputs did not change.
 */
internal data class SessionInboxMetadata(
    val sessions: List<SessionSummary>,
    val recentSessions: List<SessionSummary>,
)

@Composable
internal fun rememberSessionInboxMetadata(
    durableSessions: List<SessionSummary>,
    projectSessions: Map<ProjectId, List<SessionSummary>>,
    recentSessionMetadata: List<SessionSummary>,
    transcriptSearchResults: List<SessionSearchResult>,
    projects: List<ProjectSummary>,
): SessionInboxMetadata = remember(
    durableSessions,
    projectSessions,
    recentSessionMetadata,
    transcriptSearchResults,
    projects,
) {
    val mergedSessions = mergeSessionCollections(
        durableSessions = durableSessions,
        projectSessions = projectSessions.values.flatten() + recentSessionMetadata,
        searchResults = transcriptSearchResults,
    )
    val sessions = mergedSessions.map { session ->
        session.copy(projectId = session.projectId ?: projectForSessionWorkspace(session, projects)?.id)
    }
    SessionInboxMetadata(
        sessions = sessions,
        recentSessions = sessions
            .sortedByDescending { it.lastActiveEpochSeconds ?: Double.NEGATIVE_INFINITY }
            .take(HOME_RECENT_SESSION_PREVIEW_LIMIT),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionListScreen(
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
    onRefreshWorkingPresence: () -> Unit = {},
    onLoadManagementSettings: (String) -> Unit = {},
    onRefreshDurableSessions: (Boolean) -> Unit = {},
    onConfigureServer: () -> Unit,
    onRetryConnection: () -> Unit = {},
    onSignIn: () -> Unit,
    onPasswordSignIn: (String, String) -> Unit = { _, _ -> },
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
    val isRelayConnection = snapshot.relayTargetId != null
    val hasConfiguredConnection = isRelayConnection || serverOrigin != null
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
    var passwordSignInOpen by rememberSaveable { mutableStateOf(false) }
    var passwordUsername by rememberSaveable { mutableStateOf("admin") }
    var passwordValue by remember { mutableStateOf("") }
    val hasPasswordProvider = snapshot.authProviders.any { it.supportsPassword }
    if (passwordSignInOpen) {
        AlertDialog(
            onDismissRequest = { if (snapshot.authenticationState != AuthenticationState.SigningIn) passwordSignInOpen = false },
            title = { Text("Sign in to Hermes") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = passwordUsername,
                        onValueChange = { passwordUsername = it.take(256) },
                        label = { Text("Username") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = passwordValue,
                        onValueChange = { passwordValue = it.take(4_096) },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = passwordUsername.isNotBlank() && passwordValue.isNotEmpty() &&
                        snapshot.authenticationState != AuthenticationState.SigningIn,
                    onClick = {
                        val submittedPassword = passwordValue
                        passwordValue = ""
                        passwordSignInOpen = false
                        onPasswordSignIn(passwordUsername, submittedPassword)
                    },
                ) { Text("Sign in") }
            },
            dismissButton = {
                TextButton(
                    enabled = snapshot.authenticationState != AuthenticationState.SigningIn,
                    onClick = { passwordSignInOpen = false },
                ) { Text("Cancel") }
            },
        )
    }
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
    val activeWorkingSessionIds = snapshot.activeWorkingSessionIds
    // Silent working-presence poll: authoritative session.active_list state
    // (joined by durable session_key) every 3s while the Home screen is
    // visible; the loop stops automatically when the composable leaves.
    LaunchedEffect(snapshot.connectionState, snapshot.authenticationState) {
        if (snapshot.connectionState != ConnectionState.Connected ||
            snapshot.authenticationState !in setOf(
                AuthenticationState.Authenticated,
                AuthenticationState.NotRequired,
            )
        ) {
            return@LaunchedEffect
        }
        onRefreshWorkingPresence()
        while (true) {
            delay(WORKING_PRESENCE_POLL_MILLIS)
            onRefreshWorkingPresence()
        }
    }
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
    LaunchedEffect(serverOrigin, snapshot.relayTargetId, snapshot.selectedProfile) {
        val connectionScope = snapshot.relayTargetId?.let { "relay:$it" }
            ?: serverOrigin?.value?.let { "direct:$it" }
            ?: "unconfigured"
        val scopeKey = "$connectionScope\u0000${snapshot.selectedProfile}"
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
                            connectionState == ConnectionState.Disconnected && hasConfiguredConnection ->
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
                                snapshot.relayTargetLabel ?: serverHostnameLabel(serverOrigin),
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
                !isRelayConnection && serverSettingsState is ServerSettingsState.Loading ->
                    "Loading server settings" to "Reading the saved server origin."
                !isRelayConnection && serverSettingsState is ServerSettingsState.Unavailable ->
                    "Server settings unavailable" to "Open Server to replace the saved origin."
                connectionState == ConnectionState.Connected &&
                    snapshot.authenticationState == AuthenticationState.SignInRequired ->
                    "Server reachable" to
                        "Hermes ${snapshot.serverVersion ?: "unknown"} · Sign in required"
                connectionState == ConnectionState.Connected &&
                    snapshot.authenticationState == AuthenticationState.SigningIn ->
                    "Signing in to Hermes" to "Complete sign-in in your browser"
                connectionState == ConnectionState.Disconnected && isRelayConnection ->
                    "Relay configured" to "Reconnect to your paired Hermes host."
                connectionState == ConnectionState.Disconnected && !hasConfiguredConnection ->
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
                        snapshot.connectionError?.let { connectionError ->
                            Text(
                                connectionError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (
                            snapshot.authenticationState == AuthenticationState.SignInRequired &&
                            snapshot.nativeOAuthSupported &&
                            snapshot.authProviders.any { it.name == "nous" }
                        ) {
                            Button(onClick = dropUnlessResumed { onSignIn() }) {
                                Text("Sign in with Nous")
                            }
                        }
                        if (
                            snapshot.authenticationState == AuthenticationState.SignInRequired &&
                            hasPasswordProvider
                        ) {
                            Button(onClick = dropUnlessResumed { passwordSignInOpen = true }) {
                                Text("Sign in with username and password")
                            }
                        } else if (
                            connectionState == ConnectionState.Disconnected &&
                            (isRelayConnection || serverSettingsState is ServerSettingsState.Ready)
                        ) {
                            if (hasConfiguredConnection) {
                                Button(onClick = dropUnlessResumed { onRetryConnection() }) {
                                    Text("Retry")
                                }
                            }
                            if (!isRelayConnection) {
                                TextButton(onClick = dropUnlessResumed { onConfigureServer() }) {
                                    Text(if (serverOrigin == null) "Configure server" else "Edit server")
                                }
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
                                isWorking = session.id in activeWorkingSessionIds,
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
internal fun SwipeSessionRow(
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
    isWorking: Boolean = false,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isWorking) {
                        Spacer(Modifier.width(8.dp))
                        WorkingIndicator()
                    }
                }
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
internal fun RecentSessionsScreen(
    snapshot: HermesGatewaySnapshot,
    projects: List<ProjectSummary>,
    showBack: Boolean,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onLoadMore: () -> Unit,
    onRefreshWorkingPresence: () -> Unit = {},
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
    val activeWorkingSessionIds = snapshot.activeWorkingSessionIds

    // Same silent presence poll as Home so the full list stays live.
    LaunchedEffect(snapshot.connectionState, snapshot.authenticationState) {
        if (snapshot.connectionState != ConnectionState.Connected ||
            snapshot.authenticationState !in setOf(
                AuthenticationState.Authenticated,
                AuthenticationState.NotRequired,
            )
        ) {
            return@LaunchedEffect
        }
        onRefreshWorkingPresence()
        while (true) {
            delay(WORKING_PRESENCE_POLL_MILLIS)
            onRefreshWorkingPresence()
        }
    }

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
                        isWorking = session.id in activeWorkingSessionIds,
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
    isWorking: Boolean = false,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isWorking) {
                    Spacer(Modifier.width(8.dp))
                    WorkingIndicator()
                }
            }
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
    ConnectionState.Recovering -> "Reconnecting"
    ConnectionState.Disconnected ->
        if (snapshot.relayTargetId == null && serverOrigin == null) "Not configured" else "Offline"
}

/**
 * Compact indeterminate "Agent is working" marker for a session row, driven by
 * the authoritative `session.active_list` working set (observer presence).
 * Accessibility announces the state explicitly; animation alone is never the
 * only signal.
 */
@Composable
internal fun WorkingIndicator(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier
            .size(14.dp)
            .semantics { contentDescription = "Agent is working" },
        strokeWidth = 2.dp,
    )
}
