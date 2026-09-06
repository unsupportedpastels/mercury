package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unsupportedpastels.hermesandroid.gateway.BackgroundTasks
import kotlinx.coroutines.delay

/** Child lifecycle is not the parent response's tool timeline. No speculative controls. */
@Composable
internal fun BackgroundTaskStrip(tasks: BackgroundTasks, nowOverride: Long? = null) {
    var dismissed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var clockNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val now = nowOverride ?: clockNow
    LaunchedEffect(nowOverride, tasks.rows.isNotEmpty()) {
        if (nowOverride == null && tasks.rows.isNotEmpty()) while (true) {
            clockNow = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val visible = tasks.rows.filterNot { it.terminal && "${it.runtimeId.value}/${it.id}" in dismissed }
    if (visible.isEmpty()) return
    val unresolved = visible.filterNot { it.terminal }
    val completedOnly = unresolved.isEmpty()
    var expanded by rememberSaveable(completedOnly) { mutableStateOf(false) }
    val active = visible.count { it.recentlyActive(now) }
    val latest = (if (completedOnly) visible else unresolved).maxBy { it.observedAtMillis }
    val age = ((now - latest.observedAtMillis).coerceAtLeast(0) / 1000)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("Background task strip"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(if (completedOnly) "Background tasks · completed details"
                    else if (unresolved.any { !it.recentlyActive(now) }) {
                        if (active == 0) "Background tasks · status unavailable"
                        else "Background tasks · $active active · other status unavailable"
                    } else "Background tasks · $active active", style = MaterialTheme.typography.labelLarge)
                    Text(if (latest.observedAtMillis <= 0) {
                        if (completedOnly) "${latest.label(now)} · time unavailable" else "Activity time unavailable"
                    } else if (completedOnly) "${latest.label(now)} ${age}s ago" else "Last observed activity ${age}s ago", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Details") }
            }
            if (expanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = 176.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visible.forEach { row ->
                        Column {
                            Text(row.goal, style = MaterialTheme.typography.labelLarge)
                            Text(row.label(now), style = MaterialTheme.typography.bodySmall)
                            val rowAge = (now - row.observedAtMillis).coerceAtLeast(0) / 1000
                            Text(if (row.observedAtMillis <= 0) {
                                if (row.terminal) "${row.label(now)} · time unavailable" else "Activity time unavailable"
                            } else if (row.terminal) "${row.label(now)} ${rowAge}s ago" else "Last observed activity ${rowAge}s ago",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            row.action?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Text("Only observed child events are shown. Silence does not mean finished or needs input.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (visible.any { it.terminal }) TextButton(onClick = {
                        dismissed = (dismissed + visible.filter { it.terminal }.map { "${it.runtimeId.value}/${it.id}" }).takeLast(64)
                    }) { Text("Dismiss completed") }
                }
            }
        }
    }
}
