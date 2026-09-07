package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.validHostFolderName
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import kotlinx.coroutines.launch

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
