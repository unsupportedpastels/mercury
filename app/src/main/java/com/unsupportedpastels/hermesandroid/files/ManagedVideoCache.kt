package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import java.io.File
import java.security.MessageDigest

/** Per-file download bound for managed videos; videos stream to disk, never RAM. */
const val MAX_MANAGED_VIDEO_BYTES = 256L * 1024 * 1024

/** Total disk budget for cached managed videos per server origin. */
const val MAX_MANAGED_VIDEO_CACHE_BYTES = 512L * 1024 * 1024

private val MANAGED_VIDEO_CACHE_EXTENSION = Regex("^[a-z0-9]{1,5}$")

/**
 * Origin-scoped disk cache for downloaded managed videos.
 *
 * Entries are keyed by a SHA-256 of the server path inside a directory derived
 * from the normalized server origin, so different servers never share cache
 * entries and a server path can never escape or collide across directories.
 * Downloads land through a `.part` file renamed into place, so an existing
 * destination always denotes a complete download.
 */
class ManagedVideoCache(private val root: File?) {

    fun directoryFor(origin: ServerOrigin): File? {
        root ?: return null
        return File(root, sha256Hex(origin.value))
    }

    /** Returns the previously downloaded media for [path], if still present. */
    fun cached(origin: ServerOrigin, path: String): ManagedVideoMedia? {
        directoryFor(origin) ?: return null
        val file = destinationFor(origin, path)
        if (!file.isFile || file.length() <= 0L) return null
        return ManagedVideoMedia(file, mimeTypeFor(file))
    }

    fun destinationFor(origin: ServerOrigin, path: String): File {
        val directory = requireNotNull(directoryFor(origin)) {
            "Managed video cache root is unavailable"
        }
        directory.mkdirs()
        val extension = path.substringAfterLast('.', "").lowercase()
        val suffix = if (extension.matches(MANAGED_VIDEO_CACHE_EXTENSION)) ".$extension" else ""
        return File(directory, sha256Hex(path) + suffix)
    }

    /**
     * Keeps the origin directory within [budgetBytes]: stale `.part` leftovers are
     * removed first, then the oldest entries are deleted (never [keep]) until the
     * directory fits the budget.
     */
    fun prune(origin: ServerOrigin, keep: File? = null, budgetBytes: Long = MAX_MANAGED_VIDEO_CACHE_BYTES) {
        val directory = directoryFor(origin) ?: return
        val staleCutoff = System.currentTimeMillis() - STALE_PART_MILLIS
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) && it.lastModified() < staleCutoff }
            .forEach(File::delete)

        var total = directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length)
        if (total <= budgetBytes) return
        directory.listFiles().orEmpty()
            .filter { it.isFile && it != keep }
            .sortedBy(File::lastModified)
            .forEach { file ->
                if (total <= budgetBytes) return
                total -= file.length()
                file.delete()
            }
    }

    private fun mimeTypeFor(file: File): String = when (file.extension) {
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val STALE_PART_MILLIS = 24 * 60 * 60 * 1000L

        internal fun sha256Hex(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
