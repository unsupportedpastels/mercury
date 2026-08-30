package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedVideoCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val origin = ServerOrigin.parse("https://hermes.example")
    private val otherOrigin = ServerOrigin.parse("https://other.example")

    @Test
    fun destinationsAreDeterministicOriginScopedAndExtensionTyped() {
        val cache = ManagedVideoCache(tempFolder.root)

        val first = cache.destinationFor(origin, "/workspace/clip.mp4")
        val again = cache.destinationFor(origin, "/workspace/clip.mp4")
        val other = cache.destinationFor(otherOrigin, "/workspace/clip.mp4")

        assertEquals(first.absolutePath, again.absolutePath)
        assertNotEquals(first.parentFile.absolutePath, other.parentFile.absolutePath)
        assertTrue(first.name.endsWith(".mp4"))
        assertEquals(ManagedVideoCache.sha256Hex("/workspace/clip.mp4") + ".mp4", first.name)
    }

    @Test
    fun sanitizesHostileCacheExtensions() {
        val cache = ManagedVideoCache(tempFolder.root)

        val destination = cache.destinationFor(origin, "/workspace/clip.ELABORATE")

        assertFalse(destination.name.endsWith(".ELABORATE"))
        assertEquals(ManagedVideoCache.sha256Hex("/workspace/clip.ELABORATE"), destination.name)
    }

    @Test
    fun cachedReturnsExistingCompleteDownloadOnly() {
        val cache = ManagedVideoCache(tempFolder.root)
        val destination = cache.destinationFor(origin, "/workspace/clip.mp4")
        assertNull(cache.cached(origin, "/workspace/clip.mp4"))

        destination.writeBytes(byteArrayOf(1, 2, 3))

        val media = cache.cached(origin, "/workspace/clip.mp4")
        assertEquals("video/mp4", media?.mimeType)
        assertEquals(destination.absolutePath, media?.file?.absolutePath)
        assertNull(cache.cached(otherOrigin, "/workspace/clip.mp4"))
    }

    @Test
    fun pruneKeepsMostRecentDownloadWithinBudget() {
        val cache = ManagedVideoCache(tempFolder.root)
        val old = cache.destinationFor(origin, "/a/old.mp4")
        val keep = cache.destinationFor(origin, "/b/keep.mp4")
        old.writeBytes(ByteArray(600))
        keep.writeBytes(ByteArray(400))
        old.setLastModified(1_000L)
        keep.setLastModified(2_000L)

        cache.prune(origin, keep = keep, budgetBytes = 500L)

        assertFalse(old.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun pruneRemovesStalePartialDownloads() {
        val cache = ManagedVideoCache(tempFolder.root)
        val part = File(cache.directoryFor(origin), "pending.mp4.part")
        part.parentFile.mkdirs()
        part.writeBytes(ByteArray(16))
        part.setLastModified(1_000L)

        cache.prune(origin, budgetBytes = Long.MAX_VALUE)

        assertFalse(part.exists())
    }

    @Test
    fun cacheWithoutRootIsUnavailable() {
        val cache = ManagedVideoCache(null)

        assertNull(cache.directoryFor(origin))
    }
}
