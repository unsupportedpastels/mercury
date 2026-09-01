package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.artifacts.ArtifactOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFileOpenPolicyTest {
    @Test
    fun markdownHttpAndHttpsStayRemoteWebAndHostPathsBecomeManagedOpen() {
        assertEquals(
            MarkdownLinkTarget.RemoteWeb("https://example.invalid/doc"),
            HostFileOpenPolicy.markdownLinkTarget("https://example.invalid/doc"),
        )
        assertEquals(
            MarkdownLinkTarget.RemoteWeb("http://127.0.0.1:9119/readme"),
            HostFileOpenPolicy.markdownLinkTarget("http://127.0.0.1:9119/readme"),
        )
        assertEquals(
            MarkdownLinkTarget.ManagedHostPath("/home/mark/out/report.pdf"),
            HostFileOpenPolicy.markdownLinkTarget("/home/mark/out/report.pdf"),
        )
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("file:///etc/passwd"))
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("javascript:alert(1)"))
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("relative/report.pdf"))
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("/home/mark/../secret.pdf"))
    }

    @Test
    fun mediaPictureStaysInAppImageAndNonPictureHostPathBecomesFilenameChip() {
        val picture = HostFileOpenPolicy.mediaLineKind("/home/mark/design/mockup.png")
        assertEquals(MediaLineKind.InAppImage("/home/mark/design/mockup.png"), picture)

        val pdf = HostFileOpenPolicy.mediaLineKind("/home/mark/out/report.pdf")
        assertEquals(
            MediaLineKind.FileChip("/home/mark/out/report.pdf", "report.pdf"),
            pdf,
        )

        val svg = HostFileOpenPolicy.mediaLineKind("/tmp/chart.svg")
        assertTrue(svg is MediaLineKind.FileChip)
        assertEquals("chart.svg", (svg as MediaLineKind.FileChip).displayName)

        assertEquals(MediaLineKind.Ignore, HostFileOpenPolicy.mediaLineKind("not-a-path"))
        assertEquals(MediaLineKind.Ignore, HostFileOpenPolicy.mediaLineKind("/home/mark/../secret.html"))
    }

    @Test
    fun extensionlessRemoteImageUrlsStayInAppImagesAndDoNotBecomeChips() {
        // Regression pin: validateRemoteMediaUrl has no extension check today.
        // Adding one here would silently demote signed/CDN image URLs that
        // currently preview in-app back to plain text.
        assertEquals(
            MediaLineKind.InAppImage("https://images.example/render?id=42"),
            HostFileOpenPolicy.mediaLineKind("https://images.example/render?id=42"),
        )
        assertEquals(
            MediaLineKind.InAppImage("https://images.example/photo.png"),
            HostFileOpenPolicy.mediaLineKind("https://images.example/photo.png"),
        )
    }

    @Test
    fun artifactOpenIsAvailableForManagedHostFilesAndRemoteOpenAlreadyExists() {
        assertTrue(HostFileOpenPolicy.artifactOpenAvailable(ArtifactOrigin.ManagedPath))
        assertFalse(HostFileOpenPolicy.artifactOpenAvailable(ArtifactOrigin.RemoteUrl))
    }

    @Test
    fun hostFileRowsOpenFilesAndStillDrillFolders() {
        val folder = HostFileEntry("docs", "/srv/project/docs", isDirectory = true)
        val file = HostFileEntry("notes.txt", "/srv/project/notes.txt", isDirectory = false)
        assertEquals(HostFileRowAction.DrillFolder, HostFileOpenPolicy.hostFileRowAction(folder))
        assertEquals(
            HostFileRowAction.OpenFile("/srv/project/notes.txt"),
            HostFileOpenPolicy.hostFileRowAction(file),
        )
    }

    @Test
    fun openAttemptReducerShowsOpeningThenRowErrorWhenNoAppCanHandleTheFile() {
        val opening = HostFileOpenPolicy.reduce(HostFileOpenUiState.Idle, HostFileOpenEvent.Requested)
        assertEquals(HostFileOpenUiState.Opening, opening)
        assertEquals("Opening…", HostFileOpenPolicy.OPENING_LABEL)

        val noHandler = HostFileOpenPolicy.reduce(opening, HostFileOpenEvent.NoAppHandler)
        assertEquals(
            HostFileOpenUiState.Failed("No app on this phone can open this file"),
            noHandler,
        )
        assertEquals(
            "No app on this phone can open this file",
            HostFileOpenPolicy.NO_HANDLER_MESSAGE,
        )

        val recovered = HostFileOpenPolicy.reduce(noHandler, HostFileOpenEvent.Requested)
        assertEquals(HostFileOpenUiState.Opening, recovered)

        val success = HostFileOpenPolicy.reduce(recovered, HostFileOpenEvent.LaunchSucceeded)
        assertEquals(HostFileOpenUiState.Idle, success)

        val failed = HostFileOpenPolicy.reduce(
            HostFileOpenUiState.Opening,
            HostFileOpenEvent.Failed("Could not download file"),
        )
        assertEquals(HostFileOpenUiState.Failed("Could not download file"), failed)
    }

    @Test
    fun activityNotFoundBecomesNoAppHandler() {
        assertEquals(
            HostFileOpenEvent.NoAppHandler,
            HostFileOpenPolicy.eventForLaunchFailure(HostFileLaunchFailure.NoHandler),
        )
    }

    @Test
    fun otherLaunchFailureUsesBoundedOpenFailedMessage() {
        assertEquals(
            HostFileOpenEvent.Failed(HostFileOpenPolicy.OPEN_FAILED_MESSAGE),
            HostFileOpenPolicy.eventForLaunchFailure(HostFileLaunchFailure.Other(null)),
        )
        assertEquals(
            HostFileOpenEvent.Failed(HostFileOpenPolicy.OPEN_FAILED_MESSAGE),
            HostFileOpenPolicy.eventForLaunchFailure(HostFileLaunchFailure.Other("   ")),
        )
        assertEquals(
            HostFileOpenEvent.Failed("disk full"),
            HostFileOpenPolicy.eventForLaunchFailure(HostFileLaunchFailure.Other("disk full")),
        )
    }
}
