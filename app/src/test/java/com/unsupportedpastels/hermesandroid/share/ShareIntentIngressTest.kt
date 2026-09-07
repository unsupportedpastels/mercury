package com.unsupportedpastels.hermesandroid.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.unsupportedpastels.hermesandroid.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareIntentIngressTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun manifestExportsOnlyTheLauncherForSendActions() {
        val component = ComponentName(context, MainActivity::class.java)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(component, 0)
        assertTrue(info.exported)

        @Suppress("DEPRECATION")
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("text/plain"),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name })
        assertEquals(
            listOf(MainActivity::class.java.name),
            matches.filter { it.activityInfo.packageName == context.packageName }
                .map { it.activityInfo.name },
        )
    }

    @Test
    fun parserAcceptsOnlyBoundedTextAndDistinctContentUrisThenConsumesTheIntent() {
        val incoming = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_TEXT, "Review")
            clipData = ClipData.newUri(context.contentResolver, "items", Uri.parse("content://provider/one")).also {
                it.addItem(ClipData.Item(Uri.parse("file:///private/secret")))
                it.addItem(ClipData.Item(Uri.parse("content://provider/one")))
                it.addItem(ClipData.Item(Uri.parse("content://provider/two")))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val payload = requireNotNull(parseIncomingShare(context, incoming, requestId = 7))

        assertEquals(7, payload.requestId)
        assertEquals("Review", payload.text)
        assertEquals(2, payload.attachments.size)
        assertNull(incoming.action)
        assertNull(parseIncomingShare(context, incoming, requestId = 8))
        assertNull(parseIncomingShare(context, Intent(Intent.ACTION_VIEW), requestId = 9))
    }
}
