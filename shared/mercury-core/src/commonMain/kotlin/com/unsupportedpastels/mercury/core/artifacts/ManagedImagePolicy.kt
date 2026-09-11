package com.unsupportedpastels.mercury.core.artifacts

/** Transport-free managed-image path and native-format rules. */
object ManagedImagePolicy {
    internal val androidExtensions = setOf("bmp", "gif", "jpeg", "jpg", "png", "webp")
    internal val iosExtensions = androidExtensions + setOf("heic", "tif", "tiff")

    fun isCanonicalManagedPath(path: String): Boolean =
        path.length in 2..4096 && path.startsWith('/') && !path.startsWith("//") &&
            path.none { it.isISOControl() || it == '\\' } &&
            path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }

    fun isManagedImagePath(
        path: String,
        formatPolicy: ManagedImageFormatPolicy = ManagedImageFormatPolicy.Android,
    ): Boolean = isCanonicalManagedPath(path) && path.substringAfterLast('.', "").lowercase() in when (formatPolicy) {
        ManagedImageFormatPolicy.Android -> androidExtensions
        ManagedImageFormatPolicy.Ios -> iosExtensions
    }
}
