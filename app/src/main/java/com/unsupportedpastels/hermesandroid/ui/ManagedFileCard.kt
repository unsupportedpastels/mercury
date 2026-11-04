package com.unsupportedpastels.hermesandroid.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.unsupportedpastels.hermesandroid.artifacts.Artifact
import com.unsupportedpastels.hermesandroid.artifacts.ArtifactType
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One delivered file (an `.apk`, a `.md`, a bundle — anything that is neither image nor video) shown
 * where the user actually reads it: under the message that delivered it.
 *
 * Nothing is fetched until the file is used, so a transcript full of attachments costs no traffic
 * until one is opened. The two actions are deliberately the same pair the artifact sheet offers —
 * save through the system picker, or hand it to another app — so a file behaves identically however
 * it is reached. Failures are shown on the card, never swallowed.
 */
@Composable
internal fun ManagedFileCard(
    artifact: Artifact,
    onLoadManagedFile: suspend (String) -> Result<HostFileContent>,
    /**
     * Preferred save path: streams the file into the chosen document, so an attachment larger than
     * the in-memory reader accepts still lands and the card can show real progress. Falls back to
     * [onLoadManagedFile] when a caller has not wired it.
     */
    onStreamManagedFile: (
        suspend (String, java.io.OutputStream, (Long, Long?) -> Unit) -> Long
    )? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Null = idle. Non-null = a save is in flight, and this is how far along it is.
    var progress by remember { mutableStateOf<Float?>(null) }
    var writtenBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf<Long?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { destination ->
        if (destination == null || !pendingSave) return@rememberLauncherForActivityResult
        pendingSave = false
        scope.launch {
            progress = 0f
            writtenBytes = 0L
            val saved = runCatching {
                if (onStreamManagedFile != null) {
                    // "wt" (write + truncate), not "w": the picker hands back a document that already
                    // exists, and some providers ignore a bare "w".
                    context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                        onStreamManagedFile(artifact.source, output) { written, total ->
                            writtenBytes = written
                            totalBytes = total
                            progress = total?.takeIf { it > 0 }?.let { written.toFloat() / it } ?: 0f
                        }
                    } ?: error("Destination could not be opened")
                } else {
                    val bytes = onLoadManagedFile(artifact.source).getOrThrow().bytes
                    context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                        output.write(bytes)
                    } ?: error("Destination could not be opened")
                    bytes.size.toLong()
                }
            }
            progress = null
            saved.fold(
                onSuccess = { written ->
                    if (written <= 0L) {
                        // Never report success for an empty result: the picker has already left a
                        // 0-byte file in the user's Downloads, and that is worse than saying so.
                        discardDocument(context, destination)
                        totalBytes = null
                        error = "The host file is empty (0 bytes) — nothing was saved"
                    } else {
                        Toast.makeText(
                            context,
                            "Saved ${artifact.displayName} (${formatByteSize(written)})",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onFailure = { failure ->
                    // Leaving the empty document behind is how a failed save reads as a 0-byte
                    // "success" in the file manager.
                    discardDocument(context, destination)
                    totalBytes = null
                    error = failure.message?.take(160) ?: "Could not download file"
                },
            )
        }
    }

    fun share() {
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
                                    type = content.mimeType.ifBlank { "application/octet-stream" }
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "Share file",
                            ),
                        )
                    }.onFailure { failure ->
                        error = failure.message?.take(160) ?: "Could not share file"
                    }
                },
                onFailure = { failure ->
                    error = failure.message?.take(160) ?: "Could not download file"
                },
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("File artifact card: ${artifact.displayName}")
            .semantics { contentDescription = "Delivered file ${artifact.displayName}" },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (artifact.type == ArtifactType.Audio) {
                    Icons.Rounded.AudioFile
                } else {
                    Icons.AutoMirrored.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    artifact.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (artifact.type == ArtifactType.Audio) "Audio" else "File",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress?.let { fraction ->
                    Text(
                        if (writtenBytes <= 0L) {
                            "Saving…"
                        } else if (totalBytes != null && totalBytes!! > 0) {
                            "Saving ${(fraction * 100).toInt()}% — " +
                                "${formatByteSize(writtenBytes)} / ${formatByteSize(totalBytes!!)}"
                        } else {
                            "Saving… ${formatByteSize(writtenBytes)}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(
                onClick = {
                    pendingSave = true
                    error = null
                    saveLauncher.launch(artifact.displayName)
                },
                enabled = progress == null,
            ) { Text("Save") }
            TextButton(onClick = { share() }, enabled = progress == null) { Text("Share") }
        }
    }
}

/** Removes a document the system picker already created, so a failed save leaves nothing behind. */
private fun discardDocument(context: Context, destination: Uri) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
}

/** Compact size for the sanity line and the toast: "11.4 MB", "912 KB". */
internal fun formatByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
