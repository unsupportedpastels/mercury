package com.unsupportedpastels.mercury.core.notifications

/**
 * What the user can currently see, as policy input. [windowFocused] exists
 * for Android multi-window; iOS has no separate window-focus state and pins
 * it to the default `true`, which preserves its two-field semantics exactly.
 */
data class SessionNotificationVisibility(
    val appForeground: Boolean = false,
    val windowFocused: Boolean = true,
    val visibleSessionId: String? = null,
)

object NotificationVisibilityPolicy {
    /**
     * Post unless the user is actively looking at this session: app in the
     * foreground, window focused, and the same durable session on screen.
     */
    fun shouldPost(sessionId: String, visibility: SessionNotificationVisibility): Boolean = !(
        visibility.appForeground &&
            visibility.windowFocused &&
            visibility.visibleSessionId == sessionId
        )
}
