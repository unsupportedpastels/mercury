package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.unsupportedpastels.hermesandroid.app.ApprovalInteraction
import com.unsupportedpastels.hermesandroid.app.ClarificationInteraction
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.UnsupportedBlockingInteraction
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.mercury.core.interaction.ClarifyAnswerPolicy
import com.unsupportedpastels.mercury.core.interaction.ClarifyAnswerState
import com.unsupportedpastels.mercury.core.transcript.TranscriptPresentationPolicy

/**
 * Collapsed thinking row in the style of Hermex's reasoning block: label plus a
 * one-line preview of the reasoning, expanding to the full text inline.
 */
@Composable
internal fun ThinkingBlock(
    reasoning: String,
    streaming: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val preview = remember(reasoning) {
        TranscriptPresentationPolicy.reasoningPreview(reasoning)
    }
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (expanded) "Hide thinking" else "Show thinking"
            },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (streaming) "Thinking" else "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (!expanded) {
                    Text(
                        preview,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClarificationCard(
    durableSessionId: DurableSessionId,
    interaction: ClarificationInteraction,
    onResponse: (String, String) -> Unit,
) {
    // Answer semantics are the shared clarify decision (same on iOS).
    var clarifyState by remember(interaction.requestId) {
        mutableStateOf(ClarifyAnswerState(interaction.choices, interaction.multiSelect))
    }
    val pending = interaction.lifecycle == RunInteractionLifecycle.Pending
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Clarification", style = MaterialTheme.typography.titleSmall)
            Text(interaction.question)
            if (pending) {
                // Grounded in the desktop clarify card: choices are shown as
                // selectable rows, an "Other" free-text field is ALWAYS offered
                // alongside them, and the card is confirmed with Skip / Continue.
                // Typing in the field and picking a choice are mutually exclusive.
                if (interaction.choices.isNotEmpty()) {
                    if (interaction.multiSelect) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            interaction.choices.forEach { choice ->
                                FilterChip(
                                    selected = choice in clarifyState.selectedChoices,
                                    onClick = { clarifyState = clarifyState.select(choice) },
                                    label = { Text(choice) },
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            interaction.choices.forEach { choice ->
                                val chosen = choice in clarifyState.selectedChoices
                                Surface(
                                    onClick = { clarifyState = clarifyState.select(choice) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (chosen) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    contentColor = if (chosen) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        choice,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = clarifyState.answer,
                    onValueChange = { clarifyState = clarifyState.typeAnswer(it) },
                    label = {
                        Text(ClarifyAnswerPolicy.otherFieldLabel(hasChoices = interaction.choices.isNotEmpty()))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true,
                )
                val pendingAnswer = clarifyState.pendingAnswer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Skip sends an empty answer, matching desktop: the agent
                    // treats it as "no preference / proceed".
                    TextButton(
                        onClick = {
                            onResponse(interaction.requestId, ClarifyAnswerPolicy.SKIP_ANSWER)
                        },
                    ) {
                        Text("Skip")
                    }
                    Button(
                        enabled = pendingAnswer != null,
                        onClick = {
                            pendingAnswer?.let { onResponse(interaction.requestId, it) }
                        },
                    ) {
                        Text("Continue")
                    }
                }
            } else {
                // Settled: a responded clarification is cleared outright (see
                // publishClarificationResponse), so the only states that reach
                // here are a timeout expiry or a send failure. Show a brief,
                // non-interactive note for each.
                Text("Clarification response", style = MaterialTheme.typography.labelMedium)
                when (interaction.lifecycle) {
                    RunInteractionLifecycle.Failed -> {
                        Text(
                            "Could not send response",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        Text(
                            "Timed out",
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalCard(
    durableSessionId: DurableSessionId,
    interaction: ApprovalInteraction,
    onResponse: (String, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            contentColor = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                    "Approval pending"
                } else {
                    "Approval ${interaction.lifecycle.name}"
                }
                stateDescription = interaction.lifecycle.name
            },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Approval",
                color = if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.titleSmall,
            )
            interaction.commandPreview?.takeIf(String::isNotBlank)?.let { command ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Command preview: $command" },
                ) {
                    Text(
                        command,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            interaction.descriptionPreview?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (interaction.lifecycle == RunInteractionLifecycle.Pending) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    interaction.choices.forEach { choice ->
                        FilledTonalButton(
                            onClick = dropUnlessResumed {
                                onResponse(choice, false)
                            },
                        ) {
                            Text(choice)
                        }
                    }
                }
            } else {
                Text("Approval response", style = MaterialTheme.typography.labelMedium)
                Text(interaction.lifecycle.name)
            }
        }
    }
}

@Composable
internal fun SecureBlockingCard(
    interaction: UnsupportedBlockingInteraction,
    onResponse: (UnsupportedBlockingKind, String, String) -> Unit,
) {
    var value by remember(interaction.requestId) { mutableStateOf("") }
    val isSensitiveInput = interaction.kind == UnsupportedBlockingKind.Sudo ||
        interaction.kind == UnsupportedBlockingKind.Secret
    val inputLabel = if (interaction.kind == UnsupportedBlockingKind.Sudo) "Sudo password" else "Secret value"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (interaction.kind == UnsupportedBlockingKind.Sudo) "Administrator password required"
                else if (interaction.kind == UnsupportedBlockingKind.Secret) "Secret required"
                else "Client read request",
                style = MaterialTheme.typography.titleSmall,
            )
            if (interaction.kind == UnsupportedBlockingKind.Secret) {
                interaction.prompt?.takeIf(String::isNotBlank)?.let { Text(it) }
            }
            if (isSensitiveInput && interaction.lifecycle == RunInteractionLifecycle.Pending) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(4_096) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = inputLabel },
                    label = { Text(inputLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        value = ""
                        onResponse(interaction.kind, interaction.requestId, "")
                    }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val submitted = value
                            value = ""
                            onResponse(interaction.kind, interaction.requestId, submitted)
                        },
                        enabled = value.isNotEmpty(),
                    ) {
                        Text(if (interaction.kind == UnsupportedBlockingKind.Sudo) "Send password" else "Send secret")
                    }
                }
            } else {
                Text("Request status: ${interaction.lifecycle.name}")
            }
        }
    }
}
