package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import java.io.File

const val MAX_HOST_FILE_ENTRIES = 500
const val MAX_HOST_FILE_PATH_LENGTH = 1_024
const val MAX_HOST_FILE_NAME_LENGTH = 255
const val MAX_HOST_FILE_BYTES = 10 * 1024 * 1024

/** Metadata returned by the official managed-files listing. */
data class HostFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val mimeType: String? = null,
    val modifiedEpochSeconds: Double? = null,
) {
    val reference: String get() = formatHostFileReference(this)
}

data class HostFileListing(
    val path: String,
    val entries: List<HostFileEntry>,
    val parentPath: String? = null,
    val root: String? = null,
    val lockedRoot: String? = null,
    val canChangePath: Boolean = true,
)

data class HostFileContent(
    val name: String,
    val path: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    val size: Int get() = bytes.size

    override fun equals(other: Any?): Boolean = other is HostFileContent &&
        name == other.name && path == other.path && mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * (31 * name.hashCode() + path.hashCode()) + mimeType.hashCode()) + bytes.contentHashCode()
}

/** A fully downloaded managed video cached on disk for local playback. */
data class ManagedVideoMedia(
    val file: File,
    val mimeType: String,
)

data class HostFileScope(val origin: ServerOrigin, val profile: String) {
    init {
        require(profile.isNotBlank() && profile.length <= 128) { "Host-file profile is invalid" }
    }
}

data class HostFilesSnapshot(
    val scope: HostFileScope,
    val listing: HostFileListing? = null,
    val filter: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** Always false: content is deliberately never retained by this repository. */
    val contentsPersisted: Boolean = false,
)

/** Validate a path supplied by Hermes Serve; never derive a child path locally. */
fun validCanonicalHostFilePath(path: String?): String? {
    val value = path?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (value.length > MAX_HOST_FILE_PATH_LENGTH || value.any(Char::isISOControl)) return null
    val absolute = value.startsWith('/') || value.matches(Regex("^[A-Za-z]:[/\\\\].*"))
    if (!absolute) return null
    val components = value.split('/', '\\')
    if (components.any { it == "." || it == ".." }) return null
    return value
}

fun validHostFileName(name: String?): String? {
    val value = name?.takeIf(String::isNotEmpty) ?: return null
    if (value.length > MAX_HOST_FILE_NAME_LENGTH || value in setOf(".", "..")) return null
    if (value.any(Char::isISOControl) || '/' in value || '\\' in value) return null
    return value
}

fun validHostFileMimeType(value: String?): String? {
    val mime = value?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
    if (mime.length > 128 || !mime.matches(Regex("^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$"))) return null
    return mime
}

/** Official Desktop-compatible @file/@folder reference formatting. */
fun formatHostFileReference(entry: HostFileEntry): String {
    val path = validCanonicalHostFilePath(entry.path)
        ?: throw IllegalArgumentException("Host-file path is not canonical")
    val kind = if (entry.isDirectory) "folder" else "file"
    val safeBare = path.isNotEmpty() && path.all { char ->
        char.isLetterOrDigit() || char in setOf('/', '\\', '.', '-', '_', ':', '+', '=', '@')
    }
    val value = when {
        safeBare -> path
        '`' !in path -> "`$path`"
        '"' !in path -> "\"$path\""
        '\'' !in path -> "'$path'"
        else -> throw IllegalArgumentException("Host-file path cannot be safely quoted")
    }
    return "@$kind:$value"
}
