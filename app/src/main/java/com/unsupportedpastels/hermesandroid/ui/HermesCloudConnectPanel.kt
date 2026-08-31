package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.connection.CloudAgent
import com.unsupportedpastels.hermesandroid.connection.CloudConnectState
import com.unsupportedpastels.hermesandroid.connection.CloudOrg

/**
 * The Hermes Cloud connect surface: sign in once to Nous Portal, then pick from
 * the agents discovered on the account. Selecting an agent hands its dashboard
 * origin back to [onSelectAgent], which persists it so the standard dashboard
 * sign-in flow connects to it — no URL to paste.
 *
 * Rendered inside [ServerSettingsScreen] only when a Cloud view-model is wired
 * in; kept in its own composable so the state machine stays self-contained.
 */
@Composable
internal fun HermesCloudConnectPanel(
    state: CloudConnectState,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onSelectOrg: (CloudOrg) -> Unit,
    onSelectAgent: (CloudAgent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Sign in once to Hermes Cloud and pick from the agents on your account — no URL to paste.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (state) {
            is CloudConnectState.SignedOut -> {
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.semantics {
                        contentDescription = "Sign in to Hermes Cloud"
                    },
                ) {
                    Icon(Icons.Outlined.Cloud, contentDescription = null)
                    Text(
                        "Sign in to Hermes Cloud",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            is CloudConnectState.SigningIn -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Column {
                        Text(
                            "Finish signing in in your browser.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Confirm the code: ${state.userCode}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(onClick = onSignOut) { Text("Cancel") }
            }

            is CloudConnectState.Discovering -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text("Loading your agents…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            is CloudConnectState.SelectOrg -> {
                Text("Choose an organization", style = MaterialTheme.typography.titleSmall)
                state.orgs.forEach { org ->
                    ListItem(
                        headlineContent = {
                            Text(org.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(if (org.isPersonal) "Personal · ${org.role}" else org.role)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectOrg(org) }
                            .semantics { contentDescription = "Select organization ${org.name}" },
                    )
                }
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }

            is CloudConnectState.Agents -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.org?.name?.let { "Your agents · $it" } ?: "Your agents",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                }
                if (state.agents.isEmpty()) {
                    Text(
                        "No agents yet. Deploy one from the Nous Portal, then refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.agents.forEach { agent ->
                        val connectable = agent.isConnectable
                        ListItem(
                            headlineContent = {
                                Text(agent.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                Text(
                                    if (connectable) {
                                        agent.dashboardUrl.orEmpty()
                                    } else {
                                        "${agent.status.lowercase()} · provisioning…"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = {
                                Icon(Icons.Outlined.Cloud, contentDescription = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = connectable) { onSelectAgent(agent) }
                                .semantics {
                                    contentDescription = if (connectable) {
                                        "Connect to ${agent.name}"
                                    } else {
                                        "${agent.name} is provisioning"
                                    }
                                },
                        )
                    }
                }
                TextButton(onClick = onSignOut) { Text("Sign out of Hermes Cloud") }
            }

            is CloudConnectState.Error -> {
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.signedOut) {
                        Button(onClick = onSignIn) { Text("Sign in to Hermes Cloud") }
                    } else {
                        Button(onClick = onRefresh) { Text("Retry") }
                        OutlinedButton(onClick = onSignOut) { Text("Sign out") }
                    }
                }
            }
        }
    }
}

/** The three connect modes surfaced at the top of the Servers settings section. */
internal enum class ConnectMode { Cloud, ServerUrl, ExternalSshTunnel }

/**
 * Remembers which connect mode is showing. Survives process recreation. Callers
 * supply the initial mode from saved catalog metadata or an active Cloud session.
 */
@Composable
internal fun rememberConnectMode(
    initial: ConnectMode,
): androidx.compose.runtime.MutableState<ConnectMode> {
    return androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }
}

internal val CONNECTION_TYPE_OPTIONS = listOf(
    ConnectMode.Cloud to "Hermes Cloud",
    ConnectMode.ServerUrl to "Server URL",
    ConnectMode.ExternalSshTunnel to "External SSH tunnel",
)
