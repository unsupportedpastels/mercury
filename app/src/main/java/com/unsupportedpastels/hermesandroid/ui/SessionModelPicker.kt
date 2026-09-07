package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    state: ModelPickerState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (ModelSelection) -> Unit,
    onConfirm: () -> Unit,
) {
    if (state == ModelPickerState.Closed) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose model", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Applies to this session only",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            when (state) {
                ModelPickerState.Closed -> Unit
                is ModelPickerState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text("Loading models…")
                    }
                }
                is ModelPickerState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                    }
                }
                is ModelPickerState.Ready -> {
                    ModelPickerReadyContent(
                        state = state,
                        onDismiss = onDismiss,
                        onRetry = onRetry,
                        onSelected = onSelected,
                        onConfirm = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelPickerReadyContent(
    state: ModelPickerState.Ready,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSelected: (ModelSelection) -> Unit,
    onConfirm: () -> Unit,
) {
    val providers = state.options.providers
    state.confirmationMessage?.let { confirmation ->
        Text(confirmation, color = MaterialTheme.colorScheme.onSurface)
        state.pendingSelection?.let { selection ->
            Text(
                "${selection.provider} · ${selection.model}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.applying, onClick = onConfirm) {
                Text(if (state.applying) "Applying…" else "Use model")
            }
            TextButton(enabled = !state.applying, onClick = onDismiss) { Text("Cancel") }
        }
        return
    }
    if (providers.isEmpty()) {
        Text("No configured models are available for this profile.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
        return
    }

    val initialProvider = state.options.current
        ?.provider
        ?.takeIf { current -> providers.any { it.slug == current } }
        ?: providers.first().slug
    var selectedProviderSlug by remember(state.durableSessionId, providers) {
        mutableStateOf(initialProvider)
    }
    var query by rememberSaveable(state.durableSessionId.value) { mutableStateOf("") }
    val selectedProvider = providers.firstOrNull { it.slug == selectedProviderSlug }
        ?: providers.first()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        providers.forEach { provider ->
            FilterChip(
                selected = provider.slug == selectedProvider.slug,
                onClick = {
                    selectedProviderSlug = provider.slug
                    query = ""
                },
                enabled = !state.applying,
                label = { Text(provider.name) },
                modifier = Modifier.semantics {
                    contentDescription = "Provider ${provider.name}"
                },
            )
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(128) },
        label = { Text("Search ${selectedProvider.name} models") },
        singleLine = true,
        enabled = !state.applying,
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let { error ->
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    val matchingModels = selectedProvider.models.filter { model ->
        query.isBlank() || model.contains(query.trim(), ignoreCase = true)
    }
    if (matchingModels.isEmpty()) {
        Text("No models match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            items(matchingModels, key = { model -> "${selectedProvider.slug}:$model" }) { model ->
                val selection = ModelSelection(selectedProvider.slug, model)
                val isCurrent = selection == state.options.current
                ModelPickerRow(
                    provider = selectedProvider,
                    model = model,
                    current = isCurrent,
                    enabled = !state.applying,
                    onClick = { onSelected(selection) },
                )
            }
        }
    }
    if (state.applying) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text("Applying model…")
        }
    }
}

@Composable
private fun ModelPickerRow(
    provider: ModelProviderOption,
    model: String,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(model) },
        supportingContent = { Text(provider.name) },
        trailingContent = {
            if (current) {
                Text("Current", color = MaterialTheme.colorScheme.primary)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = current }
            .clickable(enabled = enabled, onClick = onClick),
    )
    HorizontalDivider()
}
