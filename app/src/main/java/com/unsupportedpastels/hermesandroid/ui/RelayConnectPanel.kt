package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.relay.RelayPairingPhase
import com.unsupportedpastels.hermesandroid.relay.RelayUiState
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus

@Composable
internal fun RelayConnectPanel(
    modifier: Modifier = Modifier,
    state: RelayUiState,
    onScan: () -> Unit,
    onPair: (String) -> Unit,
    onConnect: (RelayPairedTarget) -> Unit,
    onRemove: (RelayPairedTarget) -> Unit,
    onCancelPairing: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    var pastedCode by rememberSaveable { mutableStateOf("") }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Text(
            "Pair end-to-end with the Mercury Relay running beside your Hermes host.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Text("Scan QR code", modifier = Modifier.padding(start = 8.dp))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = pastedCode,
                onValueChange = { pastedCode = it.take(1_024) },
                label = { Text("Paste pairing code") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Relay pairing code input" },
            )
            Button(
                enabled = pastedCode.isNotBlank() && state.phase !is RelayPairingPhase.Pairing,
                modifier = Modifier.heightIn(min = 56.dp),
                onClick = {
                    val code = pastedCode
                    pastedCode = ""
                    onPair(code)
                },
            ) { Text("Pair") }
        }

        when (val phase = state.phase) {
            RelayPairingPhase.Idle -> Unit
            RelayPairingPhase.Pairing -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Pairing securely…")
            }
            is RelayPairingPhase.AwaitingApproval -> {
                Text("Waiting for host approval", style = MaterialTheme.typography.titleSmall)
                Text(
                    formatRelayFingerprint(phase.fingerprint),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    "Confirm that the host shows exactly this code before approving the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancelPairing) { Text("Cancel") }
            }
            is RelayPairingPhase.Approved -> Text(
                "Device approved. Select the paired host below.",
                color = MaterialTheme.colorScheme.primary,
            )
            is RelayPairingPhase.Failed -> {
                Text(phase.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
        }

        state.targetsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.targets.isNotEmpty()) {
            HorizontalDivider()
            Text("Paired hosts", style = MaterialTheme.typography.titleSmall)
            state.targets.forEach { target ->
                val approved = target.status == RelayTargetStatus.Approved
                ListItem(
                    headlineContent = {
                        Text(target.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(if (approved) "Approved" else "Pending host approval")
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { onRemove(target) },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove relay ${target.displayLabel}"
                            },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = approved) { onConnect(target) },
                )
            }
        }
    }
}

}

private fun formatRelayFingerprint(value: String): String =
    value.chunked(4).joinToString(" ")
