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
    fun pruneDoesNotUnlinkAnotherPathsOpenPartialDownload() {
        val cache = ManagedVideoCache(tempFolder.root)
        val destination = cache.destinationFor(origin, "/active.mp4")
        val part = File(destination.path + ManagedVideoCache.PART_SUFFIX)
        val keep = cache.destinationFor(origin, "/completed.mp4")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            java.io.FileOutputStream(part).use { writer ->
                writer.write(ByteArray(60))
                keep.writeBytes(ByteArray(60))
                assertTrue(part.setLastModified(System.currentTimeMillis() - 1_000))
                executor.submit {
                    cache.prune(origin, keep = keep, budgetBytes = 100)
                }.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertTrue("Prune must not unlink an open fresh partial", part.exists())
                writer.write(7)
            }
            assertTrue(part.renameTo(destination))
            assertEquals(61L, cache.cached(origin, "/active.mp4")?.file?.length())
            assertTrue(keep.exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun pruneKeepsPinnedFinalFileUntilItsLastLeaseCloses() {
        val cache = ManagedVideoCache(tempFolder.root)
        val active = cache.destinationFor(origin, "/active.mp4")
        val other = cache.destinationFor(origin, "/other.mp4")
        active.writeBytes(ByteArray(600))
        other.writeBytes(ByteArray(400))
        active.setLastModified(1_000L)
        other.setLastModified(2_000L)

        val firstLease = cache.acquire(origin, "/active.mp4")!!
        val secondLease = cache.acquire(origin, "/active.mp4")!!
        try {
            cache.prune(origin, budgetBytes = 500L)
            assertTrue("an active final file must stay pinned", active.exists())
            assertFalse("unpinned files remain eviction candidates", other.exists())

            firstLease.close()
            firstLease.close()
            cache.prune(origin, budgetBytes = 500L)
            assertTrue("a duplicate close must not release another lease", active.exists())

            secondLease.close()
            cache.prune(origin, budgetBytes = 500L)
            assertFalse("the final file is evictable after its last close", active.exists())
        } finally {
            firstLease.close()
            secondLease.close()
        }
    }

    @Test
    fun cacheWithoutRootIsUnavailable() {
        val cache = ManagedVideoCache(null)

        assertNull(cache.directoryFor(origin))
    }
}
