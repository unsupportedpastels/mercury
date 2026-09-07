package com.unsupportedpastels.mercury.core.files

import com.unsupportedpastels.mercury.core.profiles.ProfileCatalogPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A directory entry returned by the Mercury Relay folder contract. */
data class HostFolderEntry(
    val name: String,
    val path: String,
)

/**
 * The bounded, server-canonical directory listing shared by Android and iOS.
 *
 * Entries are directory rows from the `/api/files`-compatible response. File
 * metadata is intentionally not projected into this folder-only DTO.
 */
data class HostFolderListing(
    val path: String,
    val entries: List<HostFolderEntry>,
    val parentPath: String? = null,
    val root: String? = null,
    val lockedRoot: String? = null,
    val canChangePath: Boolean = true,
)

/**
 * Versioned Mercury-owned folder operations carried over a Relay connection.
 *
 * All public methods use strings and nullable results so the generated Swift
 * API does not require Kotlin exception translation for malformed input or
 * untrusted responses.
 */
object RelayFoldersContract {
    const val capabilityVersion = 1
    const val listMethod = "relay.folders.list"
    const val createMethod = "relay.folders.create"
    const val maxEntries = 500
    const val maxPathLength = 1_024
    const val maxNameLength = 255
    const val maxRelayNameBytes = 255
    const val maxListingJsonBytes = 1_024 * 1_024

    const val unsupportedMessage =
        "Update the Mercury Relay host plugin to browse and create folders, or enter an existing server folder path manually."

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns true only for the exact supported versioned capability. */
    fun supports(statusJson: String): Boolean {
        val status = parseObject(statusJson) ?: return false
        val capability = status["capabilities"]
            ?.let { it as? JsonObject }
            ?.get("folders")
            ?.let { it as? JsonObject }
            ?: return false
        val version = capability["version"] as? JsonPrimitive
        if (version == null || version.isString || version.longOrNull != capabilityVersion.toLong()) {
            return false
        }
        return capability.stringValue("list_method") == listMethod &&
            capability.stringValue("create_method") == createMethod
    }

    /** Maps only documented Relay folder reasons to fixed, non-sensitive text. */
    fun safeErrorMessage(reason: String?): String? = when (reason?.trim()) {
        "invalid_params" -> "The Relay folder request was invalid."
        "folders_unavailable" -> "Folder browsing and creation are unavailable through Mercury Relay."
        "folder_not_available" -> "This folder is not available through Mercury Relay."
        "folder_exists" -> "A folder with that name already exists."
        "response_too_large" -> "The Relay folder listing is too large."
        "rate_limited" -> "The Relay host is busy. Try again later."
        "folder_create_failed" -> "The Relay host could not create that folder."
        else -> null
    }

    /** Builds `{profile,path?}` for `relay.folders.list`. */
    fun listParams(profile: String, path: String?): String? {
        val safeProfile = validProfile(profile) ?: return null
        val safePath = if (path == null) null else validPath(path) ?: return null
        return buildJsonObject {
            put("profile", safeProfile)
            safePath?.let { put("path", it) }
        }.toString()
    }

    /** Builds `{profile,parent_path,name}` for `relay.folders.create`. */
    fun createParams(profile: String, parentPath: String, name: String): String? {
        val safeProfile = validProfile(profile) ?: return null
        val safeParentPath = validPath(parentPath) ?: return null
        val safeName = validName(name) ?: return null
        // The POSIX Relay service's component bound is UTF-8 bytes. Keep this
        // transport-specific bound out of generic Direct/Windows name validation.
        if (safeName.encodeToByteArray().size > maxRelayNameBytes) return null
        return buildJsonObject {
            put("profile", safeProfile)
            put("parent_path", safeParentPath)
            put("name", safeName)
        }.toString()
    }

    /** Decodes a bounded `/api/files`-shaped directory response. */
    fun decodeListing(json: String): HostFolderListing? {
        if (json.encodeToByteArray().size > maxListingJsonBytes) return null
        val root = parseObject(json) ?: return null
        val path = validPath(root.stringValue("path")) ?: return null
        val rawEntries = root["entries"] as? JsonArray ?: return null
        if (rawEntries.size > maxEntries) return null
        for (key in listOf("parent", "root", "locked_root")) {
            val value = root[key]
            if (value != null && value != JsonNull && validPath(root.stringValue(key)) == null) return null
        }
        val canChangePath = if (root.containsKey("can_change_path")) {
            root.strictBoolean("can_change_path") ?: return null
        } else false
        val seenPaths = LinkedHashSet<String>()
        val entries = rawEntries.mapNotNull { element ->
            decodeEntry(element)?.takeIf { seenPaths.add(it.path) }
        }
        return HostFolderListing(
            path = path,
            entries = entries,
            parentPath = root.stringValue("parent")?.let(::validPath),
            root = root.stringValue("root")?.let(::validPath),
            lockedRoot = root.stringValue("locked_root")?.let(::validPath),
            canChangePath = canChangePath,
        )
    }

    /** Validates and preserves a canonical POSIX or supported Windows path. */
    fun validPath(path: String?): String? {
        val value = path?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (value.length > maxPathLength || value.any(Char::isISOControl)) return null
        val isPosixAbsolute = value.startsWith('/')
        val isWindowsAbsolute = value.length >= 3 &&
            isAsciiLetter(value[0]) &&
            value[1] == ':' &&
            (value[2] == '/' || value[2] == '\\')
        if (!isPosixAbsolute && !isWindowsAbsolute) return null
        if (value.split('/', '\\').any { it == "." || it == ".." }) return null
        return value
    }

    /** Validates a single server-returned or user-entered folder name. */
    fun validName(name: String?): String? {
        val value = name?.takeIf(String::isNotEmpty) ?: return null
        if (value.length > maxNameLength || value == "." || value == "..") return null
        if (value.any(Char::isISOControl) || '/' in value || '\\' in value) return null
        return value
    }

    private fun decodeEntry(element: kotlinx.serialization.json.JsonElement): HostFolderEntry? {
        val row = element as? JsonObject ?: return null
        val name = validName(row.stringValue("name")) ?: return null
        val path = validPath(row.stringValue("path")) ?: return null
        if (row.directoryFlag() != true) return null
        return HostFolderEntry(name = name, path = path)
    }

    private fun JsonObject.directoryFlag(): Boolean? {
        val hasLongKey = containsKey("is_directory")
        val hasShortKey = containsKey("is_dir")
        if (!hasLongKey && !hasShortKey) return null
        val longValue = if (hasLongKey) strictBoolean("is_directory") ?: return null else null
        val shortValue = if (hasShortKey) strictBoolean("is_dir") ?: return null else null
        if (longValue != null && shortValue != null && longValue != shortValue) return null
        return longValue ?: shortValue
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun JsonObject.strictBoolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull

    private fun validProfile(profile: String): String? = profile.takeIf {
        ProfileCatalogPolicy.isValidRpcName(it)
    }

    private fun parseObject(value: String): JsonObject? =
        runCatching { json.parseToJsonElement(value) as? JsonObject }.getOrNull()

    private fun isAsciiLetter(value: Char): Boolean =
        value in 'A'..'Z' || value in 'a'..'z'
}
