package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

internal val SessionStatusPulseAlpha = SemanticsPropertyKey<Float>("SessionStatusPulseAlpha")
internal var SemanticsPropertyReceiver.sessionStatusPulseAlpha by SessionStatusPulseAlpha

internal const val SESSION_STATUS_PULSE_MILLIS = 900

internal fun sessionStatusPulseAlphaAt(playTimeMillis: Long): Float {
    val boundedTime = playTimeMillis.coerceAtLeast(0L) % (SESSION_STATUS_PULSE_MILLIS * 2L)
    val phase = if (boundedTime <= SESSION_STATUS_PULSE_MILLIS) {
        boundedTime.toFloat() / SESSION_STATUS_PULSE_MILLIS
    } else {
        (SESSION_STATUS_PULSE_MILLIS * 2L - boundedTime).toFloat() / SESSION_STATUS_PULSE_MILLIS
    }
    val easedPhase = FastOutSlowInEasing.transform(phase)
    return 1f + (0.35f - 1f) * easedPhase
}
