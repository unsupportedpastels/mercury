package com.unsupportedpastels.hermesandroid.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_REMOTE_IMAGE_BYTES = 10 * 1024 * 1024
private const val MAX_REMOTE_IMAGE_SOURCE_DIMENSION = 16_384
private const val MAX_REMOTE_IMAGE_RENDER_DIMENSION = 2_048
private const val MAX_REMOTE_IMAGE_CACHE_ENTRIES = 4
private const val REMOTE_IMAGE_TIMEOUT_MILLIS = 15_000L

internal fun validateRemoteMediaUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return false
    if (uri.port != -1 && uri.port != 443) return false
    val host = uri.host.lowercase()
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
    if (host.contains(':') || host.all { it.isDigit() || it == '.' }) return false
    return true
}

/** True when [address] is reachable only from a public (non-private/non-loopback) network. */
internal fun isPublicRemoteAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress) return false
    if (address.isLinkLocalAddress || address.isSiteLocalAddress) return false
    if (address.isMulticastAddress) return false
    if (address is Inet4Address) {
        val raw = address.address
        val first = raw[0].toInt() and 0xff
        val second = raw[1].toInt() and 0xff
        // Carrier-grade NAT (CGNAT) 100.64.0.0/10 and documentation/test ranges.
        if (first == 100 && second in 64..127) return false
        if (first == 192 && second == 0) return false // 192.0.0.0/24, 192.0.2.0/24
        if (first == 198 && second == 18) return false // 198.18.0.0/15 benchmarking
    }
    return true
}

/**
 * Resolves [host] and returns true only if every resolved address is public.
 * Empty resolution (unresolvable host) is treated as not safe.
 */
internal fun hostResolvesToPublicNetwork(
    host: String,
    resolve: (String) -> List<InetAddress> = { resolveHostToAddresses(it) },
): Boolean {
    val addresses = resolve(host)
    if (addresses.isEmpty()) return false
    return addresses.all(::isPublicRemoteAddress)
}

internal fun resolveHostToAddresses(host: String): List<InetAddress> =
    runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { emptyList() }

internal val gatewayImageMediaExtensions = setOf("png", "jpg", "jpeg", "webp", "gif")

// Mirrors artifacts/ArtifactExtractor.kt so the artifact browser and the inline
// chat player agree on which MEDIA sources count as video.
internal val gatewayVideoMediaExtensions = setOf("m4v", "mkv", "mov", "mp4", "webm")

internal fun validateGatewayMediaPath(value: String): Boolean =
    value.startsWith('/') &&
        '\u0000' !in value &&
        value.length in 2..4_096 &&
        value.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase() in gatewayImageMediaExtensions

internal fun validateGatewayVideoPath(value: String): Boolean =
    value.startsWith('/') &&
        '\u0000' !in value &&
        value.length in 2..4_096 &&
        value.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase() in gatewayVideoMediaExtensions

internal fun HttpClientConfig<*>.configureRemoteImageHttpClient() {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = REMOTE_IMAGE_TIMEOUT_MILLIS
        requestTimeoutMillis = REMOTE_IMAGE_TIMEOUT_MILLIS
        socketTimeoutMillis = REMOTE_IMAGE_TIMEOUT_MILLIS
    }
}

internal sealed interface RemoteImageDownloadResult {
    data class Success(val bytes: ByteArray) : RemoteImageDownloadResult
    data object InvalidUrl : RemoteImageDownloadResult
    data object TooLarge : RemoteImageDownloadResult
    data object InvalidContentType : RemoteImageDownloadResult
    data class HttpFailure(val statusCode: Int) : RemoteImageDownloadResult
    data object TransportFailure : RemoteImageDownloadResult
}

internal class RemoteImageDownloader(
    private val client: HttpClient,
    private val maxBytes: Int = MAX_REMOTE_IMAGE_BYTES,
    private val resolveHost: (String) -> List<InetAddress> = ::resolveHostToAddresses,
) {
    init {
        require(maxBytes > 0)
    }

    suspend fun download(url: String): RemoteImageDownloadResult {
        if (!validateRemoteMediaUrl(url)) return RemoteImageDownloadResult.InvalidUrl
        // Reject hosts whose resolved addresses are non-public (loopback, link-local,
        // RFC1918, CGNAT) so untrusted response content cannot reach internal networks.
        val host = runCatching { URI(url).host }.getOrNull()
            ?: return RemoteImageDownloadResult.InvalidUrl
        if (!hostResolvesToPublicNetwork(host, resolveHost)) {
            return RemoteImageDownloadResult.InvalidUrl
        }
        return try {
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                response.bodyAsChannel().cancel(null)
                return RemoteImageDownloadResult.HttpFailure(response.status.value)
            }
            val contentType = response.headers[HttpHeaders.ContentType]
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (contentType?.startsWith("image/") != true) {
                response.bodyAsChannel().cancel(null)
                return RemoteImageDownloadResult.InvalidContentType
            }
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > maxBytes) {
                response.bodyAsChannel().cancel(null)
                return RemoteImageDownloadResult.TooLarge
            }
            response.readImageBodyBounded(maxBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteImageDownloadResult.TransportFailure
        }
    }
}

private suspend fun io.ktor.client.statement.HttpResponse.readImageBodyBounded(
    maxBytes: Int,
): RemoteImageDownloadResult {
    val channel = bodyAsChannel()
    return try {
        val source = channel.readRemaining(maxBytes + 1L)
        try {
            val bytes = ByteArray(maxBytes + 1)
            var count = 0
            while (!source.exhausted()) {
                val read = source.readAtMostTo(bytes, count, bytes.size)
                if (read <= 0) break
                count += read
                if (count > maxBytes) return RemoteImageDownloadResult.TooLarge
            }
            RemoteImageDownloadResult.Success(bytes.copyOf(count))
        } finally {
            source.close()
        }
    } finally {
        channel.cancel(null)
    }
}

private sealed interface RemoteImageUiState {
    data object Loading : RemoteImageUiState
    data class Loaded(val bitmap: ImageBitmap) : RemoteImageUiState
    data object Failed : RemoteImageUiState
}

private object RemoteImageRuntime {
    private val client = HttpClient(CIO) { configureRemoteImageHttpClient() }
    val downloader = RemoteImageDownloader(client)
    private val cache = object : LinkedHashMap<String, ImageBitmap>(
        MAX_REMOTE_IMAGE_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_REMOTE_IMAGE_CACHE_ENTRIES
    }

    @Synchronized
    fun cached(url: String): ImageBitmap? = cache[url]

    @Synchronized
    fun cache(url: String, bitmap: ImageBitmap) {
        cache[url] = bitmap
    }
}

@Composable
internal fun RemoteMediaImage(
    source: String,
    modifier: Modifier = Modifier,
    loadManagedImage: (suspend (String) -> ByteArray)? = null,
    onImageClick: (() -> Unit)? = null,
) {
    val state by produceState<RemoteImageUiState>(
        initialValue = RemoteImageRuntime.cached(source)
            ?.let(RemoteImageUiState::Loaded)
            ?: RemoteImageUiState.Loading,
        key1 = source,
        key2 = loadManagedImage,
    ) {
        RemoteImageRuntime.cached(source)?.let {
            value = RemoteImageUiState.Loaded(it)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val result = if (validateGatewayMediaPath(source)) {
                val loader = loadManagedImage ?: return@withContext RemoteImageUiState.Failed
                try {
                    RemoteImageDownloadResult.Success(loader(source))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    RemoteImageDownloadResult.TransportFailure
                }
            } else {
                RemoteImageRuntime.downloader.download(source)
            }
            when (result) {
                is RemoteImageDownloadResult.Success -> {
                    val bitmap = decodeRemoteImage(result.bytes)
                    if (bitmap == null) {
                        RemoteImageUiState.Failed
                    } else {
                        RemoteImageRuntime.cache(source, bitmap)
                        RemoteImageUiState.Loaded(bitmap)
                    }
                }
                else -> RemoteImageUiState.Failed
            }
        }
    }

    val fallbackModifier = if (onImageClick == null) {
        modifier
    } else {
        modifier.clickable(onClick = onImageClick)
    }
    when (val current = state) {
        RemoteImageUiState.Loading -> {
            Box(
                modifier = fallbackModifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Loading image…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is RemoteImageUiState.Loaded -> {
            LoadedRemoteMediaImage(current.bitmap, modifier, onClick = onImageClick)
        }
        RemoteImageUiState.Failed -> {
            Text(
                text = "Image unavailable",
                modifier = fallbackModifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun LoadedRemoteMediaImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var enlarged by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    val imageClickModifier = if (onClick == null) {
        Modifier.clickable {
            zoom = 1f
            pan = Offset.Zero
            enlarged = true
        }
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Image(
        bitmap = bitmap,
        contentDescription = "Generated image; tap to enlarge",
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
            .clip(RoundedCornerShape(8.dp))
            .then(imageClickModifier),
        contentScale = ContentScale.Fit,
    )

    if (enlarged && onClick == null) {
        Dialog(
            onDismissRequest = { enlarged = false },
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                val updatedZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                                zoom = updatedZoom
                                pan = if (updatedZoom == 1f) Offset.Zero else pan + panChange
                            }
                        },
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Enlarged generated image",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            }
                            .semantics {
                                stateDescription = "Zoom ${"%.2f".format(zoom)}x"
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
                IconButton(
                    onClick = { enlarged = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close enlarged image",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private fun decodeRemoteImage(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (
        width <= 0 || height <= 0 ||
        width > MAX_REMOTE_IMAGE_SOURCE_DIMENSION ||
        height > MAX_REMOTE_IMAGE_SOURCE_DIMENSION
    ) return null

    var sampleSize = 1
    while (
        width / sampleSize > MAX_REMOTE_IMAGE_RENDER_DIMENSION ||
        height / sampleSize > MAX_REMOTE_IMAGE_RENDER_DIMENSION
    ) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}
