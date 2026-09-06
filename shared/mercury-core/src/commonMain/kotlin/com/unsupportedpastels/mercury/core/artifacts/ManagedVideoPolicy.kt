package com.unsupportedpastels.mercury.core.artifacts

/** Transport-free managed-video rules shared by both native clients. */
object ManagedVideoPolicy {
    const val MAX_DOWNLOAD_BYTES: Long = 256L * 1024 * 1024
    const val MAX_CACHE_BYTES: Long = 512L * 1024 * 1024
    internal val extensions = setOf("mp4", "webm", "mov", "m4v", "mkv")

    fun isVideoMimeType(contentType: String): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return mime.startsWith("video/") && mime.substringAfter('/').isNotEmpty() &&
            mime.substringAfter('/').all { it.isLetterOrDigit() || it in "!#$&^_.+-" }
    }

    fun isManagedVideoPath(path: String): Boolean =
        path.length in 2..4096 && path.startsWith('/') && !path.startsWith("//") &&
            path.none { it.isISOControl() || it == '\\' } &&
            path.split('/').none { it == "." || it == ".." } &&
            path.substringAfterLast('.', "").lowercase() in extensions
}
