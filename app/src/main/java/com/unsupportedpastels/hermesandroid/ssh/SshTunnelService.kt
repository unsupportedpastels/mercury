package com.unsupportedpastels.hermesandroid.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.unsupportedpastels.hermesandroid.MainActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the SSH tunnel alive while the app is not in the foreground.
 *
 * Android freezes a cached background process: its sockets stop being serviced and the
 * coroutines holding the tunnel cannot run, so a session that works in the foreground dies
 * the moment the user switches away — measured on a device: the keep-alive WebSocket was
 * accepted at 01:11:52, dropped with code 1006 at 01:12:09 when the app went to the
 * background, and the spawned backend was gone by 01:13 (it retires when no socket is
 * attached). A foreground service is the platform's answer, and it is what the desktop
 * client gets for free from `ssh -M -N -f`: a process that outlives the UI.
 *
 * The service performs no SSH work of its own — it anchors the process, plus a partial wake
 * lock so the tunnel's sockets are serviced with the screen off.
 */
class SshTunnelService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            lastStatus = "service=stopped"
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        val notificationsAllowed = NotificationManagerCompat.from(this).areNotificationsEnabled()
        lastStatus = "service=started sdk=${android.os.Build.VERSION.SDK_INT} " +
            "notifications=${if (notificationsAllowed) "on" else "off"}"
        startForeground(NOTIFICATION_ID, buildNotification(intent?.getStringExtra(EXTRA_DETAIL).orEmpty()))
        acquireWakeLock()
        // Not sticky on purpose: this service only exists to hold a tunnel the UI is using.
        // A sticky service gets resurrected after the user swipes the app away, which leaves an
        // unnotifiable process holding the tunnel — reported as "the app cannot be killed".
        return START_NOT_STICKY
    }

    /**
     * Swiping the app out of recents means the user wants it gone. Stop the foreground service (which
     * takes the notification with it), release the wake lock, and mark the session user-disconnected so
     * the automatic restore does not immediately re-arm it. Nothing else is left running, so Android
     * can reap the process and the SSH session with it; the backend on the host is a separate process
     * that retires on its own.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        SshTunnelRuntime.markUserDisconnected()
        // Clean up the host while the process is still alive: kill the backend this app spawned and
        // remove its ownership directory. The service stays foreground until that finishes, otherwise
        // the process can be reaped mid-cleanup and the cleanup silently never happens.
        SshTunnelRuntime.scope.launch {
            withTimeoutOrNull(RELEASE_TIMEOUT_MS) { SshTunnelRuntime.runRelease() }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(detail: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes SSH tunnel connected")
            .setContentText(detail.ifBlank { "Keeping the remote session alive" })
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Hermes SSH tunnel", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the SSH tunnel to your own machine alive in the background."
                setShowBadge(false)
            },
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "hermes_ssh_tunnel"

    /** Cap on the host-side cleanup after the task is removed; an SSH exec is normally well under 1s. */
    private const val RELEASE_TIMEOUT_MS = 8_000L
        private const val NOTIFICATION_ID = 8411
        private const val WAKE_LOCK_TAG = "hermes:ssh-tunnel"
        private const val ACTION_STOP = "com.unsupportedpastels.hermesandroid.ssh.TUNNEL_STOP"
        private const val EXTRA_DETAIL = "detail"

        /**
         * What the service last did, as text the client appends to its host-side report:
         * whether the anchor actually started (and on which platform/notification state) is
         * the one fact the host cannot observe.
         */
        var lastStatus: String = "service=never-requested"
            private set

        /**
         * Best effort by design: a start from the background is denied on API 31+, which
         * degrades to "no anchor" rather than crashing the connect flow.
         */
        fun start(context: Context, detail: String) {
            val intent = Intent(context, SshTunnelService::class.java).putExtra(EXTRA_DETAIL, detail)
            runCatching { context.startForegroundService(intent) }.onFailure { error ->
                lastStatus = "service=denied(${error::class.simpleName})"
            }
            if (lastStatus == "service=never-requested") lastStatus = "service=requested"
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SshTunnelService::class.java)) }
        }
    }
}