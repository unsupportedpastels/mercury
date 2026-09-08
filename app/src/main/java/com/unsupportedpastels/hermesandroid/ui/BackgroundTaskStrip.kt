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
    // A dismissed row is hidden only while the exact observed evidence remains
    // dismissible. New evidence for the same child gets a different key and is
    // visible again; a row that becomes fresh/available is never suppressed.
    val visible = tasks.rows.filterNot { row ->
        row.isDismissible(now) && row.dismissalKey() in dismissed
    }
    if (visible.isEmpty()) return
    val presentation = tasks.presentation(visible, now)
    val completedOnly = presentation.terminalOnly
    val secondaryLabel = tasks.secondaryLabel(visible, now)
    var expanded by rememberSaveable(completedOnly) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("Background task strip"),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(presentation.headline, style = MaterialTheme.typography.labelLarge)
                    Text(
                        secondaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            Text(
                                row.timeLabel(now),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            row.action?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    Text("Only observed child events are shown. Silence does not mean finished or needs input.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val unavailable = visible.filter { !it.terminal && it.isDismissible(now) }
                    if (unavailable.isNotEmpty()) TextButton(onClick = {
                        dismissed = (dismissed + unavailable.map { it.dismissalKey() }).takeLast(64)
                    }) { Text("Dismiss unavailable") }
                    val completed = visible.filter { it.terminal }
                    if (completed.isNotEmpty()) TextButton(onClick = {
                        dismissed = (dismissed + completed.map { it.dismissalKey() }).takeLast(64)
                    }) { Text("Dismiss completed") }
                }
            }
        }
    }
}
