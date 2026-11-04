package com.unsupportedpastels.hermesandroid.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two channels that make a sound must ask for one short tick rather than inheriting the system
 * default buzz, which was reported as far too strong. Channel settings are plain framework state, so
 * this is exactly the assertion that keeps that report from coming back.
 */
@RunWith(RobolectricTestRunner::class)
// Same sandbox level as the other Robolectric tests here: 36 would need a Java 21 test JVM.
@Config(sdk = [35])
class HermesTurnNotificationServiceTest {

    @Test
    fun audibleChannelsAskForOneShortTick() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SessionNotificationPoster.notificationManager(context)

        listOf("hermes_task_complete_v2", "hermes_task_attention_v2").forEach { id ->
            val channel = manager.getNotificationChannel(id)
            assertNotNull("$id must exist", channel)
            assertTrue("$id should vibrate", channel.shouldVibrate())
            assertEquals("$id pattern", listOf(0L, 15L), channel.vibrationPattern?.toList())
        }
        // The first version's channels are retired, so their loud defaults cannot be reused.
        assertNull(manager.getNotificationChannel("hermes_task_complete"))
        assertNull(manager.getNotificationChannel("hermes_task_attention"))
    }
}
