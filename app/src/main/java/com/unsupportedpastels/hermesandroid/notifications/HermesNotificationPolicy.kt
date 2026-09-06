package com.unsupportedpastels.hermesandroid.notifications

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.mercury.core.notifications.NotificationTextPolicy
import com.unsupportedpastels.mercury.core.notifications.NotificationVisibilityPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification text and visibility now decide in the shared KMP core
 * (docs/plans/kmp-shared-core.md, Phase 2) so Android and iOS stay
 * identical. These wrappers keep the app's typed DurableSessionId surface.
 */
internal fun finalResponsePreview(
    text: String,
    maxLines: Int = NotificationTextPolicy.DEFAULT_PREVIEW_LINES,
): String = NotificationTextPolicy.finalResponsePreview(text, maxLines)

internal fun activeTurnTitle(count: Int): String = NotificationTextPolicy.activeTurnTitle(count)

internal data class SessionNotificationVisibility(
    val appForeground: Boolean = false,
    val windowFocused: Boolean = false,
    val visibleSessionId: DurableSessionId? = null,
)

internal fun shouldPostSessionNotification(
    sessionId: DurableSessionId,
    visibility: SessionNotificationVisibility,
): Boolean = NotificationVisibilityPolicy.shouldPost(
    sessionId.value,
    com.unsupportedpastels.mercury.core.notifications.SessionNotificationVisibility(
        appForeground = visibility.appForeground,
        windowFocused = visibility.windowFocused,
        visibleSessionId = visibility.visibleSessionId?.value,
    ),
)

internal object SessionNotificationVisibilityRegistry {
    private val mutableStates = MutableStateFlow(SessionNotificationVisibility())
    val states = mutableStates.asStateFlow()

    fun publishAppForeground(foreground: Boolean) {
        mutableStates.value = mutableStates.value.copy(appForeground = foreground)
    }

    fun publishWindowFocused(focused: Boolean) {
        mutableStates.value = mutableStates.value.copy(windowFocused = focused)
    }

    fun publishVisibleSession(sessionId: DurableSessionId?) {
        mutableStates.value = mutableStates.value.copy(visibleSessionId = sessionId)
    }
}
