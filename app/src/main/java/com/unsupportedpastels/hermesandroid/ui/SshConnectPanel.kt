package com.unsupportedpastels.hermesandroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unsupportedpastels.hermesandroid.ssh.SshAuthMode
import com.unsupportedpastels.hermesandroid.ssh.SshConnectPhase
import com.unsupportedpastels.hermesandroid.ssh.SshConnectState
import com.unsupportedpastels.hermesandroid.ssh.SshCredentials

/**
 * "Connect over SSH" card.
 *
 * Reaches a Hermes install that is not exposed on any network: the app opens an
 * SSH tunnel, starts (or reuses) a detached `hermes serve --isolated` on the
 * remote host, and registers the tunneled loopback origin as a normal server —
 * so nothing else in the app needs to know SSH exists.
 *
 * A key can be pasted or picked from a file. Android gives the app no access to
 * `~/.ssh`, so the key is read as bytes here and handed to the SSH library;
 * after a successful connection it is remembered encrypted (see
 * [com.unsupportedpastels.hermesandroid.ssh.EncryptedSshKeyStore]), which is why
 * the fields may be left empty on later connects.
 */
@Composable
internal fun SshConnectPanel(
    state: SshConnectState,
    onConnect: (target: String, credentials: SshCredentials) -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var target by rememberSaveable { mutableStateOf(state.target) }
    var mode by rememberSaveable { mutableStateOf(SshAuthMode.PrivateKey) }
    var password by rememberSaveable { mutableStateOf("") }
    var privateKey by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var pickError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val busy = state.phase == SshConnectPhase.Connecting
    val editable = !busy && state.phase != SshConnectPhase.Ready

    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            pickError = "Could not read that file. Pick the private key file itself."
        } else {
            privateKey = text
            pickError = null
        }
    }

    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Connect over SSH", style = MaterialTheme.typography.titleMedium)
            Text(
                "The remote host needs Hermes installed and your SSH account must be able to run it. " +
                    "The app opens the tunnel and starts the backend for you — no port has to be exposed. " +
                    "Nothing here needs the Server URL field below: that one is for the manual URL / Cloud " +
                    "modes, and SSH fills the address in itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text("SSH host") },
                placeholder = { Text("user@host:22") },
                singleLine = true,
                enabled = editable,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = mode == SshAuthMode.PrivateKey,
                    onClick = { mode = SshAuthMode.PrivateKey },
                    enabled = editable,
                    label = { Text("Private key") },
                )
                FilterChip(
                    selected = mode == SshAuthMode.Password,
                    onClick = { mode = SshAuthMode.Password },
                    enabled = editable,
                    label = { Text("Password") },
                )
            }

            when (mode) {
                SshAuthMode.PrivateKey -> {
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = { Text("Private key (id_ed25519 / id_rsa)") },
                        placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                        enabled = editable,
                        minLines = 3,
                        maxLines = 6,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickKey.launch(arrayOf("*/*")) }, enabled = editable) {
                            Text("Pick key file")
                        }
                        if (privateKey.isNotBlank()) {
                            TextButton(onClick = { privateKey = "" }, enabled = editable) { Text("Clear") }
                        }
                    }
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase (only if the key has one)") },
                        singleLine = true,
                        enabled = editable,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Leave these empty to reuse the key already saved on this device for the host. " +
                            "The key is stored encrypted (Android Keystore) and never written to ~/.ssh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SshAuthMode.Password -> OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("SSH password") },
                    singleLine = true,
                    enabled = editable,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state.phase) {
                    SshConnectPhase.Idle, SshConnectPhase.Failed -> Button(
                        onClick = {
                            pickError = null
                            onConnect(
                                target.trim(),
                                SshCredentials(
                                    mode = mode,
                                    password = password,
                                    privateKeyPem = privateKey,
                                    passphrase = passphrase,
                                ),
                            )
                        },
                        enabled = target.isNotBlank(),
                    ) { Text("Connect") }

                    SshConnectPhase.Connecting -> {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        TextButton(onClick = { onDisconnect() }) { Text("Cancel") }
                    }

                    SshConnectPhase.Ready -> TextButton(onClick = { onDisconnect() }) { Text("Disconnect") }
                }
                if (state.phase != SshConnectPhase.Ready && !busy) {
                    // A phone freezes the app in the background, killing the tunnel while the
                    // remote backend keeps running; this rebuilds the tunnel (reusing that
                    // backend) instead of leaving the saved loopback server unreachable.
                    TextButton(onClick = onReconnect) { Text("Rebuild tunnel") }
                }
            }

            if (state.detail.isNotBlank()) {
                Text(state.detail, style = MaterialTheme.typography.bodySmall)
            }
            pickError?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.phase == SshConnectPhase.Ready && state.localPort > 0) {
                Text(
                    "Tunneled on 127.0.0.1:${state.localPort} · remote backend pid ${state.backendPid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}