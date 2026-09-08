package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.unsupportedpastels.hermesandroid.gateway.BackgroundTasks
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import kotlinx.coroutines.delay

/** A clock-scoped status notice, never synthetic assistant transcript content. */
@Composable
internal fun BackgroundResponseNotice(
    messages: List<ChatMessage>,
    tasks: BackgroundTasks,
    parentTurnSending: Boolean,
    nowOverride: Long? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nowOverride, tasks.rows) {
        if (nowOverride == null && tasks.rows.any { !it.terminal }) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }
    missingFinalResponseNotice(
        messages = messages,
        activeChildCount = tasks.activeCount(nowOverride ?: now),
        parentTurnSending = parentTurnSending,
    )?.let { notice ->
        Text(notice, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
    }
}
