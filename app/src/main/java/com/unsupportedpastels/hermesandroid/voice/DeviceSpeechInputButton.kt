package com.unsupportedpastels.hermesandroid.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat

/** Composer mic for app-owned, pause-tolerant device speech recognition. */
@Composable
fun DeviceSpeechInputButton(
    controller: DeviceSpeechRecognizerController,
    available: Boolean,
    enabled: Boolean,
    currentDraft: String,
    onDraftChanged: (String) -> Unit,
    onError: (String) -> Unit,
    requestIdentity: String,
    modifier: Modifier = Modifier,
) {
    // A changed session/origin gets a new permission continuation. Any result
    // delivered to the disposed scope is ignored by its coordinator.
    key(requestIdentity) {
        DeviceSpeechInputButtonForRequestScope(
            controller = controller,
            available = available,
            enabled = enabled,
            currentDraft = currentDraft,
            onDraftChanged = onDraftChanged,
            onError = onError,
            modifier = modifier,
        )
    }
}

@Composable
private fun DeviceSpeechInputButtonForRequestScope(
    controller: DeviceSpeechRecognizerController,
    available: Boolean,
    enabled: Boolean,
    currentDraft: String,
    onDraftChanged: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    val active = state != DeviceSpeechRecognizerState.Idle
    val description = if (active) "Stop voice input" else "Voice input"
    val coordinator = remember(controller) { DeviceSpeechPermissionCoordinator(controller) }
    SideEffect {
        coordinator.update(available, enabled, currentDraft, onDraftChanged, onError)
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.dispose() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        coordinator.onPermissionResult(granted)
    }

    IconButton(
        onClick = {
            coordinator.onClick(
                hasRecordAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
                requestRecordAudioPermission = {
                    runCatching { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                        .onFailure { coordinator.onPermissionRequestFailed() }
                },
            )
        },
        enabled = enabled && (available || active),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = if (active) Icons.Outlined.Stop else Icons.Outlined.Mic,
            contentDescription = null,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else if (available && enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

internal class DeviceSpeechPermissionCoordinator(
    private val controller: DeviceSpeechRecognizer,
) {
    private var alive = true
    private var permissionPending = false
    private var available = false
    private var enabled = false
    private var currentDraft = ""
    private var onDraftChanged: (String) -> Unit = {}
    private var onError: (String) -> Unit = {}

    fun update(
        available: Boolean,
        enabled: Boolean,
        currentDraft: String,
        onDraftChanged: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        this.available = available
        this.enabled = enabled
        this.currentDraft = currentDraft
        this.onDraftChanged = onDraftChanged
        this.onError = onError
    }

    fun onClick(
        hasRecordAudioPermission: Boolean,
        requestRecordAudioPermission: () -> Unit,
    ) {
        if (controller.isActive) {
            controller.finish()
            return
        }
        if (!alive || !available || !enabled) return
        if (hasRecordAudioPermission) {
            start()
        } else if (!permissionPending) {
            permissionPending = true
            requestRecordAudioPermission()
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (!permissionPending) return
        permissionPending = false
        if (!alive || !available || !enabled || controller.isActive) return
        if (granted) {
            start()
        } else {
            onError(RECORD_AUDIO_DENIED_MESSAGE)
        }
    }

    fun onPermissionRequestFailed() {
        if (!permissionPending) return
        permissionPending = false
        if (alive && available && enabled) onError(RECORD_AUDIO_DENIED_MESSAGE)
    }

    fun dispose() {
        alive = false
        permissionPending = false
    }

    private fun start() {
        if (!controller.start(currentDraft, onDraftChanged, onError)) {
            onError("Voice input is unavailable")
        }
    }

    private companion object {
        const val RECORD_AUDIO_DENIED_MESSAGE =
            "Microphone permission is required for voice input"
    }
}
