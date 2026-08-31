package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.connection.EXPERIMENTAL_FEATURE_LABEL
import com.unsupportedpastels.hermesandroid.connection.EXPERIMENTAL_FEATURE_REASONS
import com.unsupportedpastels.hermesandroid.connection.LOOPBACK_SECURITY_WARNING
import com.unsupportedpastels.hermesandroid.connection.MAX_SERVER_LABEL_CHARS
import com.unsupportedpastels.hermesandroid.connection.TunnelTestResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionTypeSelector(
    selected: ConnectMode,
    onSelect: (ConnectMode) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (compact) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CONNECTION_TYPE_OPTIONS.forEach { (mode, label) ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = "Connection type $label" },
                )
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            CONNECTION_TYPE_OPTIONS.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = CONNECTION_TYPE_OPTIONS.size,
                    ),
                    modifier = Modifier.semantics { contentDescription = "Connection type $label" },
                ) { Text(label) }
            }
        }
    }
}
@Composable
internal fun ExternalSshTunnelSetup(
    testing: Boolean,
    testResult: TunnelTestResult?,
    showExperimentalReasons: Boolean,
    onToggleExperimentalReasons: () -> Unit,
    onTestTunnel: () -> Unit,
    enabled: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                EXPERIMENTAL_FEATURE_LABEL,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.semantics { contentDescription = "Experimental feature" },
            )
            TextButton(
                onClick = onToggleExperimentalReasons,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Why is External SSH tunnel experimental?"
                },
            ) {
                Text(if (showExperimentalReasons) "Hide experimental reasons" else "Why experimental?")
            }
        }
        if (showExperimentalReasons) {
            Text(
                EXPERIMENTAL_FEATURE_REASONS,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Shared loopback warning" },
        ) {
            Text(
                LOOPBACK_SECURITY_WARNING,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            )
        }
        OutlinedButton(
            onClick = onTestTunnel,
            enabled = enabled && !testing,
            modifier = Modifier.semantics { contentDescription = "Test tunnel" },
        ) {
            Text(if (testing) "Testing tunnel…" else "Test tunnel")
        }
        when (testResult) {
            TunnelTestResult.Success -> Text(
                "Tunnel handshake succeeded. The local forward reached Hermes without changing " +
                    "any server state.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
            is TunnelTestResult.Failure -> Text(
                testResult.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            null -> Unit
        }
    }
}

@Composable
internal fun ExternalSshTunnelSetupGuide(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Configure the local port forward outside this app. An SSH app such as Termius must " +
                "already have an active local forward. This app never starts SSH, never stores " +
                "SSH credentials, and never asks you to paste a session token.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Setup checklist", style = MaterialTheme.typography.titleSmall)
        Text(
            "• SSH is connected in your SSH app\n" +
                "• The SSH host key is reviewed and pinned there\n" +
                "• The local forwarding rule is active\n" +
                "• A browser check of http://127.0.0.1:<port>/api/status returns Hermes JSON\n" +
                "• Battery or background restrictions are configured for the SSH app if you " +
                "expect persistent access",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Default origin is http://127.0.0.1:9119. Use 127.0.0.1, not localhost. Any unused " +
                "local port works if 9119 is taken — set that port in the SSH app.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun ServerDisplayLabelField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_SERVER_LABEL_CHARS)) },
        label = { Text("Display label (optional)") },
        supportingText = { Text("Stored only on this device") },
        enabled = enabled,
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Display label input" },
    )
}
