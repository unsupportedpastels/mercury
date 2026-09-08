package com.unsupportedpastels.hermesandroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.gateway.*
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme

/** Explicit synthetic fixture: no transport, credentials or live task claims. */
class BackgroundTaskFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = DurableSessionId("background-task-fixture")
        val runtime = RuntimeSessionId("fixture-runtime")
        val now = System.currentTimeMillis() - 7000
        val unavailableHistory = intent.getBooleanExtra("unavailable-history", false)
        val waitingForFinal = intent.getBooleanExtra("waiting-for-final", false)
        val connectionError = intent.getBooleanExtra("connection-error", false)
        val tasks = if (unavailableHistory) BackgroundTasks(listOf(
            BackgroundTaskRow(runtime, "unknown", "Historical task with unavailable status", null,
                BackgroundTaskStatus.Unknown, 0, false),
        )) else BackgroundTasks(listOf(
            BackgroundTaskRow(runtime, "review", "Review lifecycle regressions", "Reading test results", BackgroundTaskStatus.Active, now),
            BackgroundTaskRow(runtime, "build", "Verify the native build", "Build verified", BackgroundTaskStatus.Finished, now),
            BackgroundTaskRow(runtime, "offline", "Inspect reconnect behavior", "Last observed: reading connection state", BackgroundTaskStatus.Active, now - 180000, false),
        ))
        setContent {
            var retryRequested by remember { mutableStateOf(false) }
            HermesAndroidTheme {
                HermesApp(snapshot = HermesGatewaySnapshot(
                    authenticationState = AuthenticationState.Authenticated,
                    durableSessions = listOf(SessionSummary(id, "Background tasks · fixture")),
                    chatSessions = mapOf(id to ChatSessionSnapshot(
                        messages = if (waitingForFinal) listOf(
                            ChatMessage(ChatMessageRole.User, "Synthetic request awaiting background completion"),
                        ) else listOf(ChatMessage(ChatMessageRole.Assistant, "The parent response has ended. Child tasks remain visible below. This is a synthetic UI fixture, not live task evidence.")),
                        backgroundTasks = tasks,
                        connectionRecoveryAvailable = connectionError && !retryRequested,
                        error = if (connectionError && !retryRequested) "Connection lost while receiving response" else null,
                        notice = if (retryRequested) "Synthetic connection retry requested" else null,
                        processRows = if (unavailableHistory) listOf(
                            ProcessRow("fixture-emulator", "Synthetic supporting process", "running"),
                        ) + (1..7).map { index ->
                            ProcessRow("fixture-completed-$index", "Synthetic completed build", "exited", exitCode = 0)
                        } else emptyList(),
                    )),
                ), initialRoute = SessionDetailRoute(id), onRetrySessionConnection = { requestedId ->
                    check(requestedId == id)
                    retryRequested = true
                })
            }
        }
    }
}
