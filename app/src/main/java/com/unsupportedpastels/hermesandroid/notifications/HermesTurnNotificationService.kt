package com.unsupportedpastels.hermesandroid.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import androidx.core.app.NotificationCompat
import com.unsupportedpastels.hermesandroid.MainActivity
import com.unsupportedpastels.hermesandroid.R
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.mercury.core.notifications.NotificationInputKind
import com.unsupportedpastels.mercury.core.notifications.NotificationTextPolicy

interface TurnNotificationController {
    fun turnStarted(sessionId: DurableSessionId, title: String, activeCount: Int)
    fun activeCountChanged(activeCount: Int)
    fun approvalRequired(sessionId: DurableSessionId, title: String, preview: String)
    fun clarificationRequired(sessionId: DurableSessionId, title: String, preview: String)
    fun unsupportedInputRequired(sessionId: DurableSessionId, title: String, preview: String)
    fun turnCompleted(sessionId: DurableSessionId, title: String, text: String, status: String?)
}

internal object NoOpTurnNotificationController : TurnNotificationController {
    override fun turnStarted(sessionId: DurableSessionId, title: String, activeCount: Int) = Unit
    override fun activeCountChanged(activeCount: Int) = Unit
    override fun approvalRequired(sessionId: DurableSessionId, title: String, preview: String) = Unit
    override fun clarificationRequired(sessionId: DurableSessionId, title: String, preview: String) = Unit
    override fun unsupportedInputRequired(sessionId: DurableSessionId, title: String, preview: String) = Unit
    override fun turnCompleted(sessionId: DurableSessionId, title: String, text: String, status: String?) = Unit
}

internal class AndroidTurnNotificationController(
    private val context: Context,
) : TurnNotificationController {
    override fun turnStarted(sessionId: DurableSessionId, title: String, activeCount: Int) =
        publishActiveCount(activeCount)

    override fun activeCountChanged(activeCount: Int) = publishActiveCount(activeCount)

    override fun approvalRequired(sessionId: DurableSessionId, title: String, preview: String) =
        SessionNotificationPoster.postInput(
            context, NotificationTextPolicy.inputHeading(NotificationInputKind.APPROVAL), sessionId, title, preview)

    override fun clarificationRequired(sessionId: DurableSessionId, title: String, preview: String) =
        SessionNotificationPoster.postInput(
            context, NotificationTextPolicy.inputHeading(NotificationInputKind.CLARIFICATION), sessionId, title, preview)

    override fun unsupportedInputRequired(sessionId: DurableSessionId, title: String, preview: String) =
        SessionNotificationPoster.postInput(
            context, NotificationTextPolicy.inputHeading(NotificationInputKind.SECURE_INPUT), sessionId, title, preview)

    override fun turnCompleted(sessionId: DurableSessionId, title: String, text: String, status: String?) =
        SessionNotificationPoster.postCompletion(context, sessionId, title, text, status)

    /**
     * Starts, updates, or stops the foreground service that anchors the ongoing
     * "Hermes is working" notification. Turns can begin while the app is a cached
     * background process (resume of a turn started elsewhere, `/loop` iterations),
     * where a service start is denied on API 31+. That denial must degrade to
     * "no ongoing notification" — never crash the event collector; the next
     * count publish from the foreground re-establishes the service.
     */
    private fun publishActiveCount(activeCount: Int) {
        val intent = serviceIntent(HermesTurnNotificationService.ACTION_COUNT)
            .putExtra(HermesTurnNotificationService.EXTRA_COUNT, activeCount)
        try {
            if (activeCount > 0) {
                context.startForegroundService(intent)
            } else {
                // Stop path: if the foreground service is running, plain starts are
                // permitted; if it is not running there is nothing to stop.
                context.startService(intent)
            }
        } catch (_: IllegalStateException) {
            // Background start denied (includes ForegroundServiceStartNotAllowedException).
        }
    }

    private fun serviceIntent(action: String) =
        Intent(context, HermesTurnNotificationService::class.java).setAction(action)
}

/**
 * Posts per-session attention/completion notifications directly from the app
 * process. Posting a notification does not require a service, so these paths
 * work identically from foreground and background.
 */
internal object SessionNotificationPoster {
    fun postInput(
        context: Context,
        heading: String,
        sessionId: DurableSessionId,
        title: String,
        preview: String,
    ) {
        val manager = notificationManager(context)
        if (!shouldPostSessionNotification(sessionId, SessionNotificationVisibilityRegistry.states.value)) {
            manager.cancel(notificationId(sessionId.value, INPUT_KIND))
            return
        }
        val text = NotificationTextPolicy.inputPreview(preview)
        manager.notify(notificationId(sessionId.value, INPUT_KIND), NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(heading)
            .setSubText(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, sessionId.value))
            .addAction(0, "Review in Mercury", openAppIntent(context, sessionId.value))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build())
    }

    fun postCompletion(
        context: Context,
        sessionId: DurableSessionId,
        title: String,
        text: String,
        status: String?,
    ) {
        val manager = notificationManager(context)
        val preview = finalResponsePreview(text)
        val heading = NotificationTextPolicy.completionHeading(
            NotificationTextPolicy.completionStatusFromWire(status),
        )
        manager.cancel(notificationId(sessionId.value, INPUT_KIND))
        if (!shouldPostSessionNotification(sessionId, SessionNotificationVisibilityRegistry.states.value)) {
            manager.cancel(notificationId(sessionId.value, COMPLETE_KIND))
            return
        }
        manager.notify(notificationId(sessionId.value, COMPLETE_KIND), NotificationCompat.Builder(context, CHANNEL_COMPLETE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(heading)
            .setSubText(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(openAppIntent(context, sessionId.value))
            .addAction(0, "Open session", openAppIntent(context, sessionId.value))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build())
    }

    fun cancelSession(context: Context, sessionId: DurableSessionId) {
        val manager = notificationManager(context)
        manager.cancel(notificationId(sessionId.value, INPUT_KIND))
        manager.cancel(notificationId(sessionId.value, COMPLETE_KIND))
    }

    internal fun ongoingNotification(context: Context, count: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(activeTurnTitle(count))
            .setContentText("Mercury is keeping live responses connected")
            .setContentIntent(openAppIntent(context, null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    internal fun notificationManager(context: Context): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannels(manager)
        return manager
    }

    private fun ensureChannels(manager: NotificationManager) {
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ACTIVE, "Active Hermes tasks", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CHANNEL_ATTENTION, "Hermes needs attention", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CHANNEL_COMPLETE, "Completed Hermes tasks", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun notificationId(sessionId: String, kind: Int): Int = 31 * sessionId.hashCode() + kind

    private fun openAppIntent(context: Context, sessionId: String?): PendingIntent {
        if (sessionId == null) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            notificationTapIntent(context, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private const val CHANNEL_ACTIVE = "hermes_active_tasks"
    private const val CHANNEL_ATTENTION = "hermes_task_attention"
    private const val CHANNEL_COMPLETE = "hermes_task_complete"
    private const val INPUT_KIND = 1
    private const val COMPLETE_KIND = 2
}

/**
 * Foreground-service anchor for the ongoing "Hermes is working" notification.
 * Per-session notifications are posted by [SessionNotificationPoster] without
 * going through this service.
 */
class HermesTurnNotificationService : Service() {
    private var activeCount = 0

    override fun onCreate() {
        super.onCreate()
        SessionNotificationPoster.notificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_COUNT -> updateActiveCount(intent.getIntExtra(EXTRA_COUNT, 0))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    private fun updateActiveCount(count: Int) {
        activeCount = count.coerceAtLeast(0)
        if (activeCount == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        startForeground(
            ONGOING_NOTIFICATION_ID,
            SessionNotificationPoster.ongoingNotification(this, activeCount),
        )
    }

    companion object {
        private const val ONGOING_NOTIFICATION_ID = 4100

        internal const val EXTRA_COUNT = "active_count"
        internal const val ACTION_START = "com.unsupportedpastels.hermesandroid.notifications.START"
        internal const val ACTION_COUNT = "com.unsupportedpastels.hermesandroid.notifications.COUNT"
    }
}

internal fun synchronizeVisibleSessionNotifications(context: Context) {
    val visibility = SessionNotificationVisibilityRegistry.states.value
    val sessionId = visibility.visibleSessionId ?: return
    if (!shouldPostSessionNotification(sessionId, visibility)) {
        SessionNotificationPoster.cancelSession(context, sessionId)
    }
}
