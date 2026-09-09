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
import com.unsupportedpastels.hermesandroid.app.*
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
        val emptyActivity = intent.getBooleanExtra("empty-activity", false)
        val combinedHistory = intent.getBooleanExtra("combined-history", false)
        val mode = intent.getStringExtra("activity-mode") ?: "child"
        val sending = mode in setOf("working", "thinking", "streaming", "needs-you", "reconnecting")
        val tasks = if (emptyActivity || mode != "child") BackgroundTasks() else if (combinedHistory) BackgroundTasks(listOf(
            BackgroundTaskRow(runtime, "completed", "Synthetic review complete", null,
                BackgroundTaskStatus.Finished, 0, false),
        )) else if (unavailableHistory) BackgroundTasks(listOf(
            BackgroundTaskRow(runtime, "unknown", "Historical task with unavailable status", null,
                BackgroundTaskStatus.Unknown, 0, false),
        )) else BackgroundTasks(listOf(
            BackgroundTaskRow(runtime, "review", "Review lifecycle regressions", "Reading test results", BackgroundTaskStatus.Active, now),
            BackgroundTaskRow(runtime, "build", "Verify the native build", "Build verified", BackgroundTaskStatus.Finished, now),
            BackgroundTaskRow(runtime, "offline", "Inspect reconnect behavior", "Last observed: reading connection state", BackgroundTaskStatus.Active, now - 180000, false),
        ))
        setContent {
            var retryRequested by remember { mutableStateOf(false) }
            var updateRequested by remember { mutableStateOf(false) }
            var clarificationAnswered by remember { mutableStateOf(false) }
            HermesAndroidTheme {
                HermesApp(snapshot = HermesGatewaySnapshot(
                    authenticationState = AuthenticationState.Authenticated,
                    activeRuntimes = if (sending) listOf(ActiveRuntimeSession(runtime, id, "Synthetic activity", RuntimeAccess.Controller)) else emptyList(),
                    durableSessions = listOf(SessionSummary(id, "Background tasks · fixture")),
                    chatSessions = mapOf(id to ChatSessionSnapshot(
                        isSending = sending,
                        connectionPhase = if (mode == "reconnecting") ChatConnectionPhase.Reconnecting else ChatConnectionPhase.Idle,
                        runState = RunEventState(
                            tools = if (mode in setOf("working", "reconnecting")) listOf(RunToolRow("synthetic-tool", "terminal", context = if (intent.getBooleanExtra("long-activity-label", false)) "Checking the complete Android activity typography and alignment regression suite" else "Checking tests", state = RunToolState.Running)) else emptyList(),
                            clarification = if (mode == "needs-you" && !clarificationAnswered) ClarificationInteraction(runtime, "synthetic-question", "Which environment?", listOf("Staging"), false) else null,
                        ),
                        messages = if (emptyActivity) emptyList() else if (mode != "child") listOf(
                            ChatMessage(ChatMessageRole.User, "Review this synthetic change"),
                            ChatMessage(ChatMessageRole.Assistant, "Inspecting the synthetic change"),
                            ChatMessage(ChatMessageRole.Tool, "Synthetic check output"),
                            ChatMessage(ChatMessageRole.Assistant,
                                when (mode) {
                                    "thinking" -> ""
                                    "working", "needs-you", "reconnecting" -> "I am checking the synthetic change."
                                    else -> "The synthetic review is complete. The final answer stays readable."
                                },
                                isStreaming = sending, reasoningText = "Synthetic reasoning for this review"),
                        ) else if (waitingForFinal) listOf(
                            ChatMessage(ChatMessageRole.User, "Synthetic request awaiting background completion"),
                        ) else listOf(ChatMessage(ChatMessageRole.Assistant, "The parent response has ended. Child tasks remain visible below. This is a synthetic UI fixture, not live task evidence.")),
                        backgroundTasks = tasks,
                        progress = com.unsupportedpastels.hermesandroid.app.DurableProgress(
                            hasMilestoneSnapshot = combinedHistory, restored = combinedHistory,
                            turnStartedAtEpochMillis = if (sending) now else null),
                        connectionRecoveryAvailable = connectionError && !retryRequested,
                        error = if (connectionError && !retryRequested) "Connection lost while receiving response" else null,
                        notice = when {
                            clarificationAnswered -> "Synthetic Staging answer received"
                            retryRequested -> "Synthetic connection retry requested"
                            updateRequested -> "Synthetic read-only update requested"
                            else -> null
                        },
                        processRows = if (combinedHistory) listOf(
                            ProcessRow("fixture-build", "Synthetic build", "exited", exitCode = 0),
                        ) else if (unavailableHistory) listOf(
                            ProcessRow("fixture-emulator", "Synthetic supporting process", "running"),
                        ) + (1..7).map { index ->
                            ProcessRow("fixture-completed-$index", "Synthetic completed build", "exited", exitCode = 0)
                        } else emptyList(),
                    )),
                ), initialRoute = SessionDetailRoute(id), onClarificationResponse = { requestedId, request, _, answer ->
                    check(requestedId == id && request == "synthetic-question" && answer == "Staging")
                    clarificationAnswered = true
                }, onRetrySessionConnection = { requestedId ->
                    check(requestedId == id)
                    retryRequested = true
                }, onGetSessionProgress = { requestedId ->
                    check(requestedId == id)
                    updateRequested = true
                })
            }
        }
    }
}
