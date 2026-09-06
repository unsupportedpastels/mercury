package com.unsupportedpastels.hermesandroid.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** One row from `GET /api/audio/elevenlabs/voices`. */
data class ElevenLabsVoice(
    val voiceId: String,
    val name: String,
    val label: String,
)

/** Identity of server-owned voice settings; never displayed or logged. */
data class VoiceSettingsIdentity(
    val origin: com.unsupportedpastels.hermesandroid.connection.ServerOrigin?,
    val relayTargetId: String?,
    val profile: String,
)

/**
 * Everything the Voice settings section needs. Null when the connected server
 * has no audio routes — the section is hidden entirely (fail-closed), never
 * shown as an error. Writes are server-authoritative: the callbacks return
 * false on failure and the UI rolls its optimistic state back.
 */
data class VoiceSettings(
    val capabilities: VoiceCapabilities,
    val config: VoiceServerConfig,
    val setAutoTts: suspend (Boolean) -> Boolean,
    val setElevenLabsVoice: suspend (String) -> Boolean,
    val loadVoices: suspend () -> List<ElevenLabsVoice>,
    /** Client-side opt-in: keep an active voice conversation running screen-off. */
    val screenOffContinuationEnabled: Boolean = false,
    val setScreenOffContinuation: (Boolean) -> Unit = {},
    val identity: VoiceSettingsIdentity? = null,
)

/**
 * Voice section for the settings screen: truthful contract/provider status,
 * the server-backed auto-speak toggle, and the ElevenLabs voice picker (shown
 * only when the route exists, a key is configured, and ElevenLabs is the
 * active TTS provider).
 */
@Composable
fun VoiceSettingsSection(
    settings: VoiceSettings,
    modifier: Modifier = Modifier,
) {
    if (!settings.capabilities.audioRoutesPresent) return
    key(settings.identity) {
        VoiceSettingsContent(settings, modifier)
    }
}

@Composable
private fun VoiceSettingsContent(settings: VoiceSettings, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val config = settings.config

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Voice", style = MaterialTheme.typography.titleMedium)

        StatusRow(
            label = "Server dictation",
            value = when {
                !config.sttEnabled -> "Disabled on server"
                config.sttProvider != null -> "Via ${config.sttProvider}"
                else -> "Available"
            },
        )
        StatusRow(
            label = "Read aloud",
            value = config.ttsProvider?.let { "Via $it" } ?: "Available",
        )

        // Server-backed auto-speak (voice.auto_tts) with optimistic rollback.
        var autoTts by remember(config.autoTts) { mutableStateOf(config.autoTts) }
        var autoTtsError by remember { mutableStateOf(false) }
        var autoTtsPending by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Speak replies automatically", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (autoTtsError) "Couldn't update the server setting" else "New replies in the open session are read aloud",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (autoTtsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = autoTts,
                enabled = !autoTtsPending,
                onCheckedChange = { enabled ->
                    autoTtsPending = true
                    autoTtsError = false
                    autoTts = enabled
                    scope.launch {
                        try {
                            if (!settings.setAutoTts(enabled)) {
                                autoTts = !enabled
                                autoTtsError = true
                            }
                        } finally {
                            autoTtsPending = false
                        }
                    }
                },
                modifier = Modifier.semantics {
                    contentDescription = "Speak replies automatically"
                },
            )
        }

        if (settings.capabilities.canPickElevenLabsVoice &&
            config.ttsProvider == "elevenlabs"
        ) {
            ElevenLabsVoicePicker(settings)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Continue voice with screen off", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Keeps the microphone and a persistent notification active while a " +
                        "voice conversation runs, using extra battery. Android's mic " +
                        "indicator stays visible; Stop in the notification always ends it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.screenOffContinuationEnabled,
                onCheckedChange = settings.setScreenOffContinuation,
                modifier = Modifier.semantics {
                    contentDescription = "Continue voice with screen off"
                },
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ElevenLabsVoicePicker(settings: VoiceSettings) {
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<ElevenLabsVoice>>(emptyList()) }
    var selectedId by remember(settings.config.elevenLabsVoiceId) {
        mutableStateOf(settings.config.elevenLabsVoiceId)
    }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        voices = settings.loadVoices()
    }
    if (voices.isEmpty()) return

    val selectedLabel = voices.firstOrNull { it.voiceId == selectedId }
        ?.let { it.label.ifBlank { it.name } }
        ?: selectedId.orEmpty()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (!pending) expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = !pending,
            label = { Text("ElevenLabs voice") },
            isError = error,
            supportingText = if (error) {
                { Text("Couldn't update the server setting") }
            } else {
                null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .semantics { contentDescription = "ElevenLabs voice" },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.label.ifBlank { voice.name }) },
                    onClick = {
                        if (pending) return@DropdownMenuItem
                        pending = true
                        expanded = false
                        error = false
                        val previous = selectedId
                        selectedId = voice.voiceId
                        scope.launch {
                            try {
                                if (!settings.setElevenLabsVoice(voice.voiceId)) {
                                    selectedId = previous
                                    error = true
                                }
                            } finally {
                                pending = false
                            }
                        }
                    },
                )
            }
        }
    }
}
