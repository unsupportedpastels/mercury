package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.connection.TunnelRecoveryAction
import com.unsupportedpastels.hermesandroid.connection.tunnelRecoveryCopy
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectionRecoveryBanner(
    snapshot: HermesGatewaySnapshot,
    onRetry: () -> Unit,
    onConnectionSetup: () -> Unit,
    onCancel: () -> Unit,
    onAcceptNewServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = tunnelRecoveryCopy(
        failure = snapshot.tunnelConnectionFailure,
        connectionError = snapshot.connectionError,
    ) ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Connection recovery: ${copy.title}" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(copy.title, style = MaterialTheme.typography.titleSmall)
        Text(
            copy.body,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            copy.actions.forEach { action ->
                when (action) {
                    TunnelRecoveryAction.Retry -> Button(onClick = onRetry) { Text("Retry") }
                    TunnelRecoveryAction.ConnectionSetup -> TextButton(onClick = onConnectionSetup) {
                        Text("Connection setup")
                    }
                    TunnelRecoveryAction.Cancel -> TextButton(onClick = onCancel) { Text("Cancel") }
                    TunnelRecoveryAction.AcceptNewServer -> Button(onClick = onAcceptNewServer) {
                        Text("Accept new server")
                    }
                }
            }
        }
    }
}
