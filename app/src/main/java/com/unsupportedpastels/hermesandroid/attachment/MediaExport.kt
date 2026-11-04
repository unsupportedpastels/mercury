package com.unsupportedpastels.hermesandroid.attachment

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saving a message's media into the device's public Downloads collection.
 *
 * `minSdk` is 29, so `MediaStore.Downloads` needs no storage permission and the result is visible in
 * Files/Downloads immediately — no legacy `WRITE_EXTERNAL_STORAGE` path, and nothing written into
 * another app's directory. The bytes are already in hand by the time a user can ask to save (the
 * image loader fetched them to display, and a video only plays from its fully downloaded cache file),
 * so this is a copy, never a second download.
 */
internal object MediaExport {

    /**
     * Writes [bytes] under [displayName]. Returns the published uri, or null when the provider
     * refused the insert — callers report failure rather than pretending it worked.
     */
    suspend fun saveBytes(
        context: Context,
        displayName: String,
        mimeType: String,
        bytes: ByteArray,
    ): Uri? = withContext(Dispatchers.IO) {
        save(context, displayName, mimeType) { output -> output.write(bytes) }
    }

    /** Copies [source] (a downloaded cache file) under [displayName]. */
    suspend fun saveFile(
        context: Context,
        displayName: String,
        mimeType: String,
        source: File,
    ): Uri? = withContext(Dispatchers.IO) {
        if (!source.isFile) return@withContext null
        save(context, displayName, mimeType) { output -> source.inputStream().use { it.copyTo(output) } }
    }

    private inline fun save(
        context: Context,
        displayName: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, uniqueName(context, displayName))
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use(write)
                ?: return null.also { runCatching { resolver.delete(uri, null, null) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (error: Exception) {
            // A half-written row would show up in Downloads as a truncated file; drop it.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** MediaStore does not overwrite; a second save of the same name gets a distinct one. */
    private fun uniqueName(context: Context, displayName: String): String {
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        val suffix = extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
        if (!exists(context, displayName)) return displayName
        for (index in 1..MAX_COLLISION_ATTEMPTS) {
            val candidate = "${base}_$index$suffix"
            if (!exists(context, candidate)) return candidate
        }
        return "${base}_${System.currentTimeMillis()}$suffix"
    }

    private fun exists(context: Context, displayName: String): Boolean = runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private const val MAX_COLLISION_ATTEMPTS = 50

    /** A filename that survives a hostile path: layout-free, bounded, always with an extension. */
    fun fileNameFor(path: String, fallbackExtension: String): String {
        var name = path.substringAfterLast('/').substringBefore('?').trim()
        if (name.isEmpty() || name.length > MAX_NAME_CHARS) {
            name = "hermes-media-${System.currentTimeMillis()}"
        }
        name = name.map { character ->
            if (character.isLetterOrDigit() || character in "-_.() ") character else '_'
        }.joinToString("").trim(' ', '.')
        if (name.isEmpty()) name = "hermes-media-${System.currentTimeMillis()}"
        return if (name.contains('.')) name else "$name.$fallbackExtension"
    }

    private const val MAX_NAME_CHARS = 96

    /** Best-effort mime from the name, defaulting to a byte stream (never a lie about the type). */
    fun mimeTypeFor(fileName: String, fallback: String = "application/octet-stream"): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            else -> fallback
        }
}