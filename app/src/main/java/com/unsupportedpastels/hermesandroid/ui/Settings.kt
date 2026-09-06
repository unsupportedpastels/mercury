package com.unsupportedpastels.hermesandroid.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard


import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import com.unsupportedpastels.hermesandroid.voice.VoiceSettings
import com.unsupportedpastels.hermesandroid.voice.VoiceSettingsSection
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerCatalog
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.MAX_SERVER_LABEL_CHARS

import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.relay.RelayUiState
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.hermesandroid.navigation.ServerSettingsRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsServersRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsConnectionRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsModelRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsVoiceRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsOfflineRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsJobsRoute
import com.unsupportedpastels.hermesandroid.navigation.SettingsAccountRoute
import kotlinx.coroutines.launch
/**
 * The distinct areas of the settings surface. Each maps to a hub row and a
 * section route so the settings screen renders one focused area at a time
 * instead of a single long scroll.
 */
internal enum class SettingsSection(val title: String, val summary: String) {
    Servers("Servers", "Add, switch, or remove Hermes servers"),
    Connection("Connection & profile", "Version, sign-in, and active profile"),
    Model("Default model", "Model and reasoning for new chats"),
    Voice("Voice", "Dictation and hands-free conversation"),
    Offline("Offline & privacy", "Save conversations for offline reading"),
    Jobs("Scheduled jobs", "Cron jobs running on this server"),
    Account("Account", "Sign out of this server"),
}

/** True for the settings hub or any of its section routes. */
internal fun NavKey?.isSettingsRoute(): Boolean = when (this) {
    ServerSettingsRoute,
    SettingsServersRoute,
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
 * (Connection/Model/Voice/Offline/Jobs/Account) that require an authenticated
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
    isInitialOnboarding: Boolean = false,
    title: String = "Hermes server",
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
    LaunchedEffect(serverOrigin, snapshot.authenticationState) {
        if (serverOrigin != null && snapshot.relayTargetId == null &&
            snapshot.authenticationState == AuthenticationState.Authenticated
        ) {
            onLoadManagementSettings(snapshot.selectedProfile)
            onRefreshCronJobs()
        }
    }
    var useTls by rememberSaveable { mutableStateOf(true) }
    val parsedOrigin = remember(value, useTls) {
        runCatching { ServerOrigin.parse(value, useTls) }.getOrNull()
    }
    val validationMessage = remember(value, useTls) {
        if (value.isBlank()) {
            null
        } else {
            runCatching { ServerOrigin.parse(value, useTls) }
                .exceptionOrNull()
                ?.message
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (!isInitialOnboarding) {
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
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = if (isInitialOnboarding) androidx.compose.ui.BiasAlignment(0f, -0.2f) else Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = if (isInitialOnboarding) 560.dp else 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (SettingsSection.Servers in visibleSections) {
                    if (isInitialOnboarding) {
                        Text("Connect to Hermes", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Connect to the Hermes agent you already run.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Servers", style = MaterialTheme.typography.titleMedium)
                    }
                    // When a Cloud view-model is wired in, offer the two connect
                    // modes: Hermes Cloud (sign in → pick agent) and Server URL
                    // (manual origin entry). Selecting a Cloud agent saves its
                    // dashboard origin, so the manual catalog stays authoritative
                    // once connected.
                    val cloudActive = cloudState != null &&
                        cloudState !is com.unsupportedpastels.hermesandroid.connection.CloudConnectState.SignedOut
                    var connectMode by rememberConnectMode(initialCloud = cloudActive)
                    if (cloudState != null) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = connectMode == ConnectMode.ServerUrl,
                                onClick = { connectMode = ConnectMode.ServerUrl },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                                icon = {},
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Text("Self-hosted") }
                            SegmentedButton(
                                selected = connectMode == ConnectMode.Cloud,
                                onClick = { connectMode = ConnectMode.Cloud },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                                icon = {},
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Text("Hermes Cloud") }
                            SegmentedButton(
                                selected = connectMode == ConnectMode.Relay,
                                onClick = { connectMode = ConnectMode.Relay },
                                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                                icon = {},
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = MaterialTheme.colorScheme.primary,
                                    activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            ) { Text("Relay") }
                        }
                    }
                    if (isInitialOnboarding) {
                        Text(
                            "Relay pairs with your Hermes host by scanning a QR code.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (connectMode == ConnectMode.Relay) {
                        RelayConnectPanel(
                            state = relayState,
                            onScan = onRelayScan,
                            onPair = onRelayPair,
                            onConnect = { target ->
                                onRelayConnect(target)
                                onBack()
                            },
                            onRemove = onRelayRemove,
                            onCancelPairing = onRelayCancelPairing,
                            onRetry = onRelayRetry,
                        )
                    } else if (cloudState != null && connectMode == ConnectMode.Cloud) {
                        HermesCloudConnectPanel(
                            state = cloudState,
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
                    if (serverCatalog.entries.isEmpty() && !isInitialOnboarding) {
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
                                                    saveError = null
                                                },
                                                modifier = Modifier.semantics {
                                                    contentDescription = "Edit ${entry.origin.value}"
                                                },
                                            ) {
                                                Text("Edit")
                                            }
                                            IconButton(
                                                onClick = { pendingRemoval = entry },
                                                enabled = !isSaving,
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
                                value = ""
                                label = ""
                                saveError = null
                            },
                            enabled = !isSaving,
                        ) {
                            Text("Add server")
                        }
                    }
                    if (!isInitialOnboarding) {
                        Text(
                            if (editingOrigin == null) "Add a server or edit its local label." else "Edit server label",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (!isInitialOnboarding) {
                        Text(
                            "Enter the address of your unchanged Hermes Serve instance — " +
                                "hermes.example.com or 192.168.1.20:8080 both work.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            saveError = null
                        },
                        label = { Text(if (isInitialOnboarding) "Server address" else "Server origin") },
                        supportingText = {
                            Text(
                                validationMessage
                                    ?: if (isInitialOnboarding) {
                                        "Example: hermes.example.com or host:port"
                                    } else {
                                        "Server address only — no path, credentials, query, or ticket."
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = useTls,
                            onCheckedChange = {
                                useTls = it
                                saveError = null
                            },
                            enabled = !isSaving && editingOrigin == null,
                            modifier = Modifier.semantics { contentDescription = "Use HTTPS checkbox" },
                        )
                        Column {
                            Text("Use HTTPS", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (isInitialOnboarding) {
                                    "Turn off only for a plain HTTP server"
                                } else {
                                    "Connect securely — turn off only for plain-HTTP servers"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!isInitialOnboarding) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = {
                                label = it.take(MAX_SERVER_LABEL_CHARS)
                                saveError = null
                            },
                            label = { Text("Display label (optional)") },
                            supportingText = { Text("Stored only on this device") },
                            enabled = !isSaving,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Display label input" },
                        )
                    }
                    saveError?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            enabled = parsedOrigin != null && !isSaving,
                            onClick = {
                                val origin = editingOrigin ?: parsedOrigin ?: return@Button
                                val entry = runCatching {
                                    ServerCatalogEntry(origin = origin, label = label.trim())
                                }.getOrElse {
                                    saveError = "That server label is not valid."
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
                            modifier = if (isInitialOnboarding) Modifier.fillMaxWidth() else Modifier,
                        ) {
                            Text(
                                if (isSaving) "Connecting…" else if (isInitialOnboarding) "Continue" else "Save",
                            )
                        }
                        if (!isInitialOnboarding) {
                            TextButton(
                                enabled = !isSaving,
                                onClick = dropUnlessResumed { onBack() },
                            ) {
                                Text("Cancel")
                            }
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
                                saveError = "Could not remove server. Try again."
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
