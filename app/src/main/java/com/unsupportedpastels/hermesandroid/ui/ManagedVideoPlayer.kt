package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.unsupportedpastels.hermesandroid.files.ManagedVideoMedia
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_VIDEO_ASPECT = 16f / 9f
private const val MIN_VIDEO_ASPECT = 0.4f
private const val MAX_VIDEO_ASPECT = 2.5f
private const val MAX_POSTER_DIMENSION = 720
private const val MAX_POSTER_CACHE_ENTRIES = 8

/** Small in-memory LRU of poster frames keyed by the MEDIA source. */
private object ManagedVideoPosterRuntime {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(
        MAX_POSTER_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_POSTER_CACHE_ENTRIES
    }

    @Synchronized
    fun get(source: String): ImageBitmap? = cache[source]

    @Synchronized
    fun put(source: String, bitmap: ImageBitmap) {
        cache[source] = bitmap
    }
}

private fun posterFileFor(media: ManagedVideoMedia): File =
    File(media.file.parentFile, media.file.nameWithoutExtension + ".jpg")

private fun decodePosterFile(file: File): ImageBitmap? =
    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()

/**
 * Extracts a downscaled first frame from the downloaded video and, when
 * [destination] is given, persists it as a JPEG poster next to the video.
 */
private suspend fun extractPosterFrame(file: File, destination: File?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@runCatching null
                val scale = minOf(
                    1f,
                    MAX_POSTER_DIMENSION.toFloat() / maxOf(frame.width, frame.height),
                )
                val bitmap = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        frame,
                        (frame.width * scale).toInt().coerceAtLeast(1),
                        (frame.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    frame
                }
                destination?.let { poster ->
                    runCatching {
                        FileOutputStream(poster).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        }
                    }
                }
                bitmap.asImageBitmap()
            }
        }.getOrNull()
    }

/**
 * Inline managed-video player for chat MEDIA blocks and the artifact browser.
 *
 * Playback is always fed from a fully downloaded, origin-scoped cache file: the
 * bearer token never reaches the player and no streaming URL is embedded in the
 * view hierarchy. Downloaded videos start playing immediately; a poster frame
 * extracted from the file is shown while idle once the video is in cache.
 */
// PlayerView.switchTargetView (fullscreen hand-off) is UnstableApi in media3.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ManagedVideoBlock(
    source: String,
    modifier: Modifier = Modifier,
    onLoadManagedVideo: (suspend (String) -> Result<ManagedVideoMedia>)? = null,
    onPeekManagedVideo: (suspend (String) -> ManagedVideoMedia?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var media by remember(source) { mutableStateOf<ManagedVideoMedia?>(null) }
    var loading by remember(source) { mutableStateOf(false) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var playbackError by remember(media) { mutableStateOf<String?>(null) }
    var fullscreen by remember(media) { mutableStateOf(false) }
    var poster by remember(source) { mutableStateOf<ImageBitmap?>(null) }

    // Poster cache entries are keyed by the origin-scoped cache file, never by
    // the raw source path: different servers may reference the same path, and
    // the frame must always come from the file this server's cache owns.
    fun posterKey(media: ManagedVideoMedia): String = media.file.absolutePath

    LaunchedEffect(source, onPeekManagedVideo) {
        val peek = onPeekManagedVideo ?: return@LaunchedEffect
        val cached = runCatching { peek(source) }.getOrNull() ?: return@LaunchedEffect
        val key = posterKey(cached)
        val existing = ManagedVideoPosterRuntime.get(key)
        if (existing != null) {
            poster = existing
            return@LaunchedEffect
        }
        val posterFile = posterFileFor(cached)
        val frame = if (posterFile.isFile) {
            decodePosterFile(posterFile)
        } else {
            extractPosterFrame(cached.file, posterFile)
        }
        if (frame != null) {
            ManagedVideoPosterRuntime.put(key, frame)
            poster = frame
        }
    }

    val player = remember(media) {
        media?.let { loaded ->
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                setMediaItem(MediaItem.fromUri(Uri.fromFile(loaded.file)))
                addListener(
                    object : Player.Listener {
                        override fun onPlayerError(failure: PlaybackException) {
                            playbackError = failure.message?.take(120) ?: "Playback failed"
                        }
                    },
                )
                prepare()
                // One tap starts the whole flow: download, then playback.
                playWhenReady = true
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    fun startLoading() {
        val loader = onLoadManagedVideo
        if (loader == null) {
            error = "Video playback is unavailable"
            return
        }
        scope.launch {
            loading = true
            error = null
            loader(source).fold(
                onSuccess = { loaded ->
                    media = loaded
                    val key = posterKey(loaded)
                    if (ManagedVideoPosterRuntime.get(key) == null) {
                        val frame = extractPosterFrame(loaded.file, posterFileFor(loaded))
                        if (frame != null) {
                            ManagedVideoPosterRuntime.put(key, frame)
                            poster = frame
                        }
                    }
                },
                onFailure = { failure ->
                    error = failure.message?.take(120) ?: "Could not load video"
                },
            )
            loading = false
        }
    }

    val aspect = poster?.let { bitmap ->
        (bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
            .coerceIn(MIN_VIDEO_ASPECT, MAX_VIDEO_ASPECT)
    } ?: DEFAULT_VIDEO_ASPECT
    val surfaceModifier = modifier
        .fillMaxWidth()
        .aspectRatio(aspect)
        .clip(RoundedCornerShape(8.dp))
    if (media == null) {
        Box(
            modifier = surfaceModifier.background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            poster?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            when {
                loading -> Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text("Loading video…", color = Color.White)
                    }
                }
                error != null -> Column(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        error.orEmpty(),
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { startLoading() }) { Text("Retry") }
                }
                else -> PlayOverlayButton(
                    description = "Play video",
                    onClick = { startLoading() },
                )
            }
        }
    } else {
        val inlineView = remember { mutableStateOf<PlayerView?>(null) }
        Box(modifier = surfaceModifier) {
            AndroidView(
                factory = { holder -> PlayerView(holder).apply { useController = true } },
                update = { view ->
                    inlineView.value = view
                    if (!fullscreen && view.player !== player) {
                        view.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = { fullscreen = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .semantics { contentDescription = "Open fullscreen video" },
            ) {
                Icon(
                    Icons.Rounded.Fullscreen,
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }

        if (fullscreen && player != null) {
            var dialogView by remember { mutableStateOf<PlayerView?>(null) }
            fun closeFullscreen() {
                val dialog = dialogView
                val inline = inlineView.value
                if (dialog != null && inline != null) {
                    PlayerView.switchTargetView(player, dialog, inline)
                }
                fullscreen = false
            }
            Dialog(
                onDismissRequest = { closeFullscreen() },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .safeDrawingPadding(),
                ) {
                    AndroidView(
                        factory = { holder ->
                            PlayerView(holder).apply {
                                useController = true
                                val previous = inlineView.value
                                if (previous != null) {
                                    PlayerView.switchTargetView(player, previous, this)
                                } else {
                                    this.player = player
                                }
                            }
                        },
                        update = { view -> dialogView = view },
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(
                        onClick = { closeFullscreen() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close fullscreen video",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }

    playbackError?.let { message ->
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun BoxScope.PlayOverlayButton(
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier
            .align(Alignment.Center)
            .size(56.dp)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
