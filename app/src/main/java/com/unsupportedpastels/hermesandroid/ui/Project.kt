package com.unsupportedpastels.hermesandroid.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validHostFolderName
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath

import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import kotlinx.coroutines.launch

internal val SessionStatusPulseAlpha = SemanticsPropertyKey<Float>("SessionStatusPulseAlpha")
private var SemanticsPropertyReceiver.sessionStatusPulseAlpha by SessionStatusPulseAlpha

private const val SESSION_STATUS_PULSE_MILLIS = 900

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectCreationSheet(
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
internal fun ProjectDock(
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
internal fun ProjectIconPickerSheet(
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
internal fun ProjectDockEdgeTab(
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectDetailScreen(
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
internal fun MissingProjectScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Project is no longer available")
    }
}
