package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme

@PreviewTest
@Preview(name = "Background compact", widthDp = 400, heightDp = 500, uiMode = 32)
@Preview(name = "Background medium", widthDp = 610, heightDp = 500, uiMode = 32)
@Preview(name = "Background expanded", widthDp = 900, heightDp = 500, uiMode = 32)
@Preview(name = "Background large text", widthDp = 400, heightDp = 800, uiMode = 32, fontScale = 1.5f)
@Composable
fun BackgroundTaskScreenshot() {
    HermesAndroidTheme {
        Surface {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Synthetic background-task fixture", style = MaterialTheme.typography.titleMedium)
                Text("The parent response has ended. Child tasks remain visible.")
                Spacer(Modifier.weight(1f))
                BackgroundTaskStrip(BackgroundTasks(listOf(
                    BackgroundTaskRow(RuntimeSessionId("fixture"), "child", "Review lifecycle tests", "Reading results", BackgroundTaskStatus.Active, 1000),
                )), nowOverride = 8000)
                BackgroundTaskStrip(BackgroundTasks(listOf(
                    BackgroundTaskRow(RuntimeSessionId("fixture"), "unknown", "Reconnect evidence", null, BackgroundTaskStatus.Active, 1000, available = false),
                )), nowOverride = 8000)
                BackgroundTaskStrip(BackgroundTasks(listOf(
                    BackgroundTaskRow(RuntimeSessionId("fixture"), "finished", "Verified child completion", null, BackgroundTaskStatus.Finished, 3000),
                )), nowOverride = 8000)
                OutlinedTextField(value = "", onValueChange = {}, placeholder = { Text("Message Hermes") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
