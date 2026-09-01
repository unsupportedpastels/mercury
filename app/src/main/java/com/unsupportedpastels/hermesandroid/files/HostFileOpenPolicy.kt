package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.artifacts.ArtifactOrigin
import com.unsupportedpastels.hermesandroid.ui.validateGatewayMediaPath
import com.unsupportedpastels.hermesandroid.ui.validateRemoteMediaUrl

sealed interface MarkdownLinkTarget {
    data class RemoteWeb(val url: String) : MarkdownLinkTarget
    data class ManagedHostPath(val path: String) : MarkdownLinkTarget
    data object Ignore : MarkdownLinkTarget
}

sealed interface MediaLineKind {
    data class InAppImage(val source: String) : MediaLineKind
    data class FileChip(val source: String, val displayName: String) : MediaLineKind
    data object Ignore : MediaLineKind
}

sealed interface HostFileRowAction {
    data object DrillFolder : HostFileRowAction
    data class OpenFile(val path: String) : HostFileRowAction
    data object Ignore : HostFileRowAction
}

sealed interface HostFileOpenUiState {
    data object Idle : HostFileOpenUiState
    data object Opening : HostFileOpenUiState
    data class Failed(val message: String) : HostFileOpenUiState
}

sealed interface HostFileOpenEvent {
    data object Requested : HostFileOpenEvent
    data object LaunchSucceeded : HostFileOpenEvent
    data object NoAppHandler : HostFileOpenEvent
    data class Failed(val message: String) : HostFileOpenEvent
}

sealed interface HostFileLaunchFailure {
    data object NoHandler : HostFileLaunchFailure
    data class Other(val message: String?) : HostFileLaunchFailure
}

object HostFileOpenPolicy {
    const val OPENING_LABEL = "Opening…"
    const val NO_HANDLER_MESSAGE = "No app on this phone can open this file"
    const val OPEN_FAILED_MESSAGE = "Could not open file"
    const val DOWNLOAD_FAILED_MESSAGE = "Could not download file"

    fun markdownLinkTarget(href: String): MarkdownLinkTarget {
        val trimmed = href.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return MarkdownLinkTarget.RemoteWeb(href)
        }
        val path = validCanonicalHostFilePath(href) ?: return MarkdownLinkTarget.Ignore
        return MarkdownLinkTarget.ManagedHostPath(path)
    }

    fun mediaLineKind(source: String): MediaLineKind {
        if (validateRemoteMediaUrl(source)) return MediaLineKind.InAppImage(source)
        if (validateGatewayMediaPath(source)) return MediaLineKind.InAppImage(source)
        val path = validCanonicalHostFilePath(source) ?: return MediaLineKind.Ignore
        return MediaLineKind.FileChip(path, displayName(path))
    }

    fun displayName(path: String): String {
        val slash = path.lastIndexOf('/')
        val backslash = path.lastIndexOf('\\')
        val separator = maxOf(slash, backslash)
        if (separator < 0 || separator == path.lastIndex) return path
        return path.substring(separator + 1).ifEmpty { path }
    }

    fun artifactOpenAvailable(origin: ArtifactOrigin): Boolean =
        origin == ArtifactOrigin.ManagedPath

    fun hostFileRowAction(entry: HostFileEntry): HostFileRowAction {
        if (entry.isDirectory) return HostFileRowAction.DrillFolder
        val path = validCanonicalHostFilePath(entry.path) ?: return HostFileRowAction.Ignore
        return HostFileRowAction.OpenFile(path)
    }

    fun reduce(state: HostFileOpenUiState, event: HostFileOpenEvent): HostFileOpenUiState = when (event) {
        HostFileOpenEvent.Requested -> HostFileOpenUiState.Opening
        HostFileOpenEvent.LaunchSucceeded -> HostFileOpenUiState.Idle
        HostFileOpenEvent.NoAppHandler -> HostFileOpenUiState.Failed(NO_HANDLER_MESSAGE)
        is HostFileOpenEvent.Failed -> HostFileOpenUiState.Failed(
            event.message.take(160).takeIf { it.isNotBlank() } ?: OPEN_FAILED_MESSAGE,
        )
    }

    fun eventForLaunchFailure(failure: HostFileLaunchFailure): HostFileOpenEvent = when (failure) {
        HostFileLaunchFailure.NoHandler -> HostFileOpenEvent.NoAppHandler
        is HostFileLaunchFailure.Other -> HostFileOpenEvent.Failed(
            failure.message?.take(160)?.takeIf { it.isNotBlank() } ?: OPEN_FAILED_MESSAGE,
        )
    }

    fun eventForDownloadFailure(message: String?): HostFileOpenEvent =
        HostFileOpenEvent.Failed(
            message?.take(160)?.takeIf { it.isNotBlank() } ?: DOWNLOAD_FAILED_MESSAGE,
        )
}
