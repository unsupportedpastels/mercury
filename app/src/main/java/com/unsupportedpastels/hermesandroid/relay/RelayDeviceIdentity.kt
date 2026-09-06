package com.unsupportedpastels.hermesandroid.relay

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name this phone reports to a relay host in its admission envelope, so
 * the host's device list can show "Mark's Fold" instead of a fingerprint.
 * The user's own device name wins; the model is the fallback.
 */
object RelayDeviceIdentity {
    @Volatile
    var name: String? = null
        private set

    fun initialize(context: Context) {
        val userName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim().orEmpty()
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .trim()
        name = userName.ifEmpty { model }.ifEmpty { null }
    }
}
