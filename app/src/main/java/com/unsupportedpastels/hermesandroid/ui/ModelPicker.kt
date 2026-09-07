package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection

/** One selectable model with its provider label and advertised capabilities. */
internal data class ModelOption(
    val selection: ModelSelection,
    val providerName: String,
    val capabilities: ModelCapabilities = ModelCapabilities(),
)

/** A provider header plus its matching models, for the grouped picker list. */
internal data class ModelProviderGroup(
    val slug: String,
    val name: String,
    val models: List<ModelOption>,
)

/** Optional capability filters for the picker. A model must satisfy every active filter. */
internal enum class ModelCapabilityFilter { Reasoning, Fast }

/** Hermes' reasoning effort levels, ascending. `none` is thinking-off, owned by
 *  the Thinking toggle, so it is not part of this scale. Mirrors the desktop's
 *  REASONING_EFFORTS (lib/reasoning-effort.ts). */
internal val ReasoningEffortLevels = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/** Built-in effort used when neither the model override nor the profile sets one. */
internal const val DefaultReasoningEffort = "medium"

/** Compact label for an effort level, for tight chip rows. */
internal fun reasoningEffortShortLabel(effort: String): String = when (effort.trim().lowercase()) {
    "none" -> "Off"
    "minimal" -> "Min"
    "low" -> "Low"
    "medium" -> "Med"
    "high" -> "High"
    "xhigh" -> "XHigh"
    "max" -> "Max"
    "ultra" -> "Ultra"
    else -> effort
}

/** True when thinking is enabled for the given stored effort (anything but `none`). */
internal fun isThinkingEnabled(effort: String?): Boolean =
    (effort?.trim()?.lowercase() ?: return true) != "none"

/**
 * The effort level a scale control should display for a model: its stored
 * override if it is a real level, else [fallback] (the profile default), else
 * [DefaultReasoningEffort]. `none`/blank resolve through the fallback because
 * thinking-off is represented separately by the Thinking toggle.
 */
internal fun resolveReasoningEffort(effort: String?, fallback: String?): String {
    val value = effort?.trim()?.lowercase().orEmpty()
    val resolved = value.takeUnless { it.isEmpty() || it == "none" }
        ?: fallback?.trim()?.lowercase()?.takeUnless { it.isEmpty() || it == "none" }
        ?: DefaultReasoningEffort
    return if (resolved in ReasoningEffortLevels) resolved else DefaultReasoningEffort
}

/**
 * Group the profile's providers into collapsible sections, keeping only the
 * providers with at least one model matching [query] and every filter in
 * [filters]. A blank query keeps every provider; an empty [filters] set applies
 * no capability constraint. Query matching is case-insensitive against both the
 * model identifier and the provider name, so searching a provider name surfaces
 * all of its models.
 */
internal fun modelProviderGroups(
    options: ModelOptions?,
    query: String,
    filters: Set<ModelCapabilityFilter> = emptySet(),
): List<ModelProviderGroup> {
    val trimmed = query.trim()
    return options?.providers.orEmpty().mapNotNull { provider ->
        val providerMatches = trimmed.isEmpty() ||
            provider.name.contains(trimmed, ignoreCase = true) ||
            provider.slug.contains(trimmed, ignoreCase = true)
        val models = provider.models
            .filter { model ->
                providerMatches || model.contains(trimmed, ignoreCase = true)
            }
            .map { model ->
                ModelOption(
                    selection = ModelSelection(provider.slug, model),
                    providerName = provider.name,
                    capabilities = provider.capabilities[model] ?: ModelCapabilities(),
                )
            }
            .filter { option -> option.capabilities.satisfies(filters) }
        if (models.isEmpty()) null else ModelProviderGroup(provider.slug, provider.name, models)
    }
}

/** True when these capabilities meet every requested filter. */
private fun ModelCapabilities.satisfies(filters: Set<ModelCapabilityFilter>): Boolean =
    filters.all { filter ->
        when (filter) {
            ModelCapabilityFilter.Reasoning -> reasoning == true
            ModelCapabilityFilter.Fast -> fast == true
        }
    }

/**
 * Resolve recently-used selections (most-recent first) into full options,
 * dropping any that are no longer offered by the current profile and any
 * duplicates. Used to pin the models the user actually switches between at the
 * top of the picker.
 */
internal fun recentModelOptions(
    recents: List<ModelSelection>,
    options: ModelOptions?,
): List<ModelOption> {
    val available = modelProviderGroups(options, "")
        .flatMap { group -> group.models }
        .associateBy { it.selection }
    val seen = LinkedHashSet<ModelSelection>()
    return recents.mapNotNull { selection ->
        if (!seen.add(selection)) return@mapNotNull null
        available[selection]
    }
}

/** Human-readable capability chips for a model, e.g. ["Reasoning", "Fast"]. */
internal fun modelCapabilityLabels(capabilities: ModelCapabilities): List<String> = buildList {
    if (capabilities.reasoning == true) add("Reasoning")
    if (capabilities.fast == true) add("Fast")
}

/**
 * A searchable, provider-grouped model picker in a modal bottom sheet. Recently
 * used models pin to the top for one-tap re-selection; the rest are grouped by
 * provider. Tapping a row expands it inline to reveal per-model controls —
 * Thinking on/off and a reasoning-effort scale for reasoning-capable models,
 * plus a Fast toggle where supported — mirroring the desktop's model edit menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    options: ModelOptions?,
    current: ModelSelection?,
    recents: List<ModelSelection>,
    reasoningOverrides: Map<ModelSelection, String>,
    profileDefaultEffort: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSetReasoning: (ModelSelection, String) -> Unit,
    onSelect: (ModelSelection) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filters by rememberSaveable(
        stateSaver = listSaver(
            save = { it.map(ModelCapabilityFilter::name) },
            restore = { it.map(ModelCapabilityFilter::valueOf).toSet() },
        ),
    ) { mutableStateOf(emptySet<ModelCapabilityFilter>()) }
    var expandedRow by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(options, query, filters) { modelProviderGroups(options, query, filters) }
    val recentOptions = remember(recents, options, query, filters) {
        if (query.isNotBlank() || filters.isNotEmpty()) {
            emptyList()
        } else {
            recentModelOptions(recents, options)
        }
    }
    fun rowKey(selection: ModelSelection) = "${selection.provider}/${selection.model}"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Choose a model", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search models") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search models" },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelCapabilityFilter.entries.forEach { filter ->
                    val active = filter in filters
                    val label = when (filter) {
                        ModelCapabilityFilter.Reasoning -> "Reasoning"
                        ModelCapabilityFilter.Fast -> "Fast"
                    }
                    FilterChip(
                        selected = active,
                        onClick = {
                            filters = if (active) filters - filter else filters + filter
                        },
                        label = { Text(label) },
                        leadingIcon = if (active) {
                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier.semantics {
                            selected = active
                            contentDescription = "Filter by $label capability"
                        },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (recentOptions.isNotEmpty()) {
                    item(key = "recent-header") {
                        Text(
                            "Recently used",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(recentOptions, key = { "recent:${rowKey(it.selection)}" }) { option ->
                        val key = rowKey(option.selection)
                        ModelPickerRow(
                            option = option,
                            selected = option.selection == current,
                            expanded = expandedRow == key,
                            effortOverride = reasoningOverrides[option.selection],
                            profileDefaultEffort = profileDefaultEffort,
                            onToggleExpand = { expandedRow = if (expandedRow == key) null else key },
                            onSelect = { onSelect(option.selection) },
                            onSetReasoning = { effort -> onSetReasoning(option.selection, effort) },
                        )
                    }
                }
                if (groups.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No models match your search.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "provider:${group.slug}") {
                        Text(
                            group.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(group.models, key = { rowKey(it.selection) }) { option ->
                        val key = rowKey(option.selection)
                        ModelPickerRow(
                            option = option,
                            selected = option.selection == current,
                            expanded = expandedRow == key,
                            effortOverride = reasoningOverrides[option.selection],
                            profileDefaultEffort = profileDefaultEffort,
                            onToggleExpand = { expandedRow = if (expandedRow == key) null else key },
                            onSelect = { onSelect(option.selection) },
                            onSetReasoning = { effort -> onSetReasoning(option.selection, effort) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    option: ModelOption,
    selected: Boolean,
    expanded: Boolean,
    effortOverride: String?,
    profileDefaultEffort: String?,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    onSetReasoning: (String) -> Unit,
) {
    val labels = remember(option.capabilities) { modelCapabilityLabels(option.capabilities) }
    val reasoningCapable = option.capabilities.reasoning == true
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(option.selection.model) },
            supportingContent = if (labels.isEmpty()) {
                null
            } else {
                { Text(labels.joinToString(" · ")) }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = "Current model")
                    }
                    if (reasoningCapable) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) {
                                "Hide options for ${option.selection.model}"
                            } else {
                                "Show options for ${option.selection.model}"
                            },
                            modifier = Modifier
                                .size(24.dp)
                                .clickable(onClick = onToggleExpand),
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .semantics {
                    this.selected = selected
                    contentDescription = "Select ${option.providerName} ${option.selection.model}"
                },
        )
        if (expanded && reasoningCapable) {
            val thinkingOn = isThinkingEnabled(effortOverride)
            val effortValue = resolveReasoningEffort(effortOverride, profileDefaultEffort)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Thinking", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = thinkingOn,
                        onCheckedChange = { checked ->
                            onSetReasoning(if (checked) effortValue else "none")
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Thinking for ${option.selection.model}"
                        },
                    )
                }
                if (thinkingOn) {
                    Text(
                        "Reasoning effort",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReasoningEffortLevels.forEach { level ->
                            val active = level == effortValue
                            FilterChip(
                                selected = active,
                                onClick = { onSetReasoning(level) },
                                label = { Text(reasoningEffortShortLabel(level)) },
                                modifier = Modifier.semantics {
                                    this.selected = active
                                    contentDescription =
                                        "Set ${option.selection.model} reasoning to $level"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A non-interactive capability label (e.g. "Reasoning") shown on the current
 * model card. Deliberately not a chip, so it does not imply a tap target.
 */
@Composable
internal fun CapabilityBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
