package com.unsupportedpastels.hermesandroid.cache

import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A launch may show the cache, but only a recent window of it: the store keeps up to a hundred rows for
 * offline use, and replaying all of them is what made the app open onto a wall of old sessions.
 */
class OfflineCacheLaunchRestoreTest {

    @Test
    fun launchShowsTheMostRecentSessionsOnly() {
        val cached = (1..40).map { index ->
            CachedSession(
                summary = SessionSummary(
                    id = DurableSessionId("s$index"),
                    title = "Session $index",
                    lastActiveEpochSeconds = index.toDouble(),
                ),
                updatedAtEpochSeconds = index.toLong(),
            )
        }

        val restored = cached.forLaunchRestore()

        assertEquals(LAUNCH_RESTORE_MAX, restored.size)
        // Newest first; the oldest rows are the ones left out.
        assertEquals("s40", restored.first().summary.id.value)
        assertEquals("s16", restored.last().summary.id.value)
    }

    @Test
    fun launchRestoreKeepsEverythingWhenTheCacheIsSmall() {
        val cached = listOf(
            CachedSession(
                summary = SessionSummary(DurableSessionId("only"), "Only session"),
                updatedAtEpochSeconds = 1,
            ),
        )

        assertEquals(1, cached.forLaunchRestore().size)
    }

    @Test
    fun scheduledRunsInTheCacheNeverReachTheLaunchList() {
        val cached = listOf(
            CachedSession(
                summary = SessionSummary(
                    DurableSessionId("cron-run"),
                    "Hourly digest",
                    lastActiveEpochSeconds = 999.0,
                    source = "cron",
                ),
                updatedAtEpochSeconds = 999,
            ),
            CachedSession(
                summary = SessionSummary(
                    DurableSessionId("real-chat"),
                    "A conversation",
                    lastActiveEpochSeconds = 1.0,
                    source = "weixin",
                ),
                updatedAtEpochSeconds = 1,
            ),
        )

        val restored = cached.forLaunchRestore()

        // The newest row is a scheduled run; it must not take a slot, let alone lead the list.
        assertEquals(listOf("real-chat"), restored.map { it.summary.id.value })
    }
}
