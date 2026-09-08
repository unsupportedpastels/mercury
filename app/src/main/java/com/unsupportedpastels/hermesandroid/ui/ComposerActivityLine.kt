package com.unsupportedpastels.hermesandroid.ui

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.unsupportedpastels.hermesandroid.theme.LocalHermesSemanticColors
import com.unsupportedpastels.mercury.core.activity.ActivityLineHold
import com.unsupportedpastels.mercury.core.activity.ActivityLineHoldState
import com.unsupportedpastels.mercury.core.activity.ActivityLineKind
import com.unsupportedpastels.mercury.core.activity.ActivityLinePolicy
import com.unsupportedpastels.mercury.core.activity.ActivityLineState
import kotlinx.coroutines.delay

/** Animator preference is read once; lifecycle changes stop decorative work. */
@Composable
internal fun rememberActivityLineMotionAllowed(): Boolean {
    val context = LocalContext.current
    val scaleAllowsMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState == Lifecycle.State.RESUMED }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return scaleAllowsMotion && resumed
}

@Composable
internal fun rememberHeldActivityLine(candidate: ActivityLineState, nowOverride: Long? = null): ActivityLineState {
    var hold by remember { mutableStateOf(ActivityLineHoldState(null, null, 0)) }
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    // Cache per input/tick, not per hold publication: SideEffect must not create
    // a recomposition loop by repeatedly stamping an already adopted state.
    val next = remember(candidate, nowOverride, tick) {
        ActivityLineHold.step(hold, candidate, nowOverride ?: SystemClock.elapsedRealtime())
    }
    SideEffect { hold = next }
    LaunchedEffect(candidate, nowOverride, next.candidate != null) {
        if (nowOverride == null && next.candidate != null) {
            while (true) {
                delay(200)
                tick = SystemClock.elapsedRealtime()
            }
        }
    }
    return next.shown ?: candidate
}

@Composable
internal fun ComposerActivityLine(
    state: ActivityLineState,
    turnStartedAtEpochMillis: Long?,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
    nowOverride: Long? = null,
    motionAllowedOverride: Boolean? = null,
) {
    // Hidden is immediate, including its semantics and parent layout spacing.
    if (state.kind == ActivityLineKind.Hidden) return
    val motionAllowed = motionAllowedOverride ?: rememberActivityLineMotionAllowed()
    val animate = state.animated && motionAllowed
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nowOverride, state.showTimer, turnStartedAtEpochMillis) {
        if (nowOverride == null && state.showTimer && turnStartedAtEpochMillis != null) {
            while (true) {
                clockNow = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    val now = nowOverride ?: clockNow
    val colors = MaterialTheme.colorScheme
    val markerColor = when (state.kind) {
        ActivityLineKind.NeedsYou -> colors.tertiary
        ActivityLineKind.ConnectionLost -> colors.error
        else -> LocalHermesSemanticColors.current.active
    }
    var labelWidth by remember { mutableStateOf(1f) }
    var alpha = 1f
    var sweep = 0f
    if (animate) {
        val transition = rememberInfiniteTransition(label = "Activity work")
        val pulse by transition.animateFloat(1f, 0.35f,
            infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Marker pulse")
        val shimmer by transition.animateFloat(-1f, 1f,
            infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "Label sweep")
        alpha = pulse
        sweep = shimmer
    }
    val baseLabelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal)
    val labelStyle = if (animate) baseLabelStyle.copy(
        brush = Brush.linearGradient(
            listOf(colors.onSurfaceVariant, colors.onSurface, colors.onSurfaceVariant),
            start = Offset(sweep * labelWidth, 0f), end = Offset((sweep + 1f) * labelWidth, 0f),
        ),
    ) else baseLabelStyle.copy(color = colors.onSurfaceVariant)
    // The composer animates its size; Hidden removes semantics immediately.
    androidx.compose.runtime.key(state.kind) {
        Row(
            modifier = modifier.fillMaxWidth().height(32.dp)
                .testTag("Composer activity line")
                .clickable(role = Role.Button, onClick = onOpenDetails)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Activity: ${state.label}"
                    stateDescription = state.kind.name
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }
                .background(markerColor, RoundedCornerShape(2.dp)))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.label, modifier = Modifier.weight(1f, fill = false)
                    .testTag("Activity label")
                    .onSizeChanged { labelWidth = it.width.coerceAtLeast(1).toFloat() },
                    style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.ExpandMore, contentDescription = null,
                    modifier = Modifier.size(16.dp).testTag("Activity disclosure"), tint = colors.onSurfaceVariant)
            }
            if (state.showTimer && turnStartedAtEpochMillis != null && turnStartedAtEpochMillis > 0 && turnStartedAtEpochMillis <= now) {
                Text(ActivityLinePolicy.formatElapsed((now - turnStartedAtEpochMillis) / 1_000),
                    modifier = Modifier.widthIn(min = 48.dp).testTag("Activity elapsed"),
                    style = baseLabelStyle.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.End, maxLines = 1, color = colors.onSurfaceVariant)
            }
        }
    }
}
