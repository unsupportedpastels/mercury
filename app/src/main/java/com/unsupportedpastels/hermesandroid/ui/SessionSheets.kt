package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.unsupportedpastels.hermesandroid.artifacts.Artifact
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactOrigin
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactType
import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostFileBrowserSheet(
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
internal fun ArtifactBrowserSheet(
    artifacts: List<Artifact>,
    onDismiss: () -> Unit,
    onLoadManagedImage: suspend (String) -> Result<ByteArray>,
    onLoadManagedVideo: (suspend (String) -> Result<ManagedVideoMedia>)? = null,
    onPeekManagedVideo: (suspend (String) -> ManagedVideoMedia?)? = null,
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
                            if (
                                artifact.type == ArtifactType.Video &&
                                artifact.origin == ArtifactOrigin.ManagedPath
                            ) {
                                ManagedVideoBlock(
                                    source = artifact.source,
                                    onLoadManagedVideo = onLoadManagedVideo,
                                    onPeekManagedVideo = onPeekManagedVideo,
                                )
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
