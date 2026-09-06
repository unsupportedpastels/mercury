package com.unsupportedpastels.hermesandroid.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
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
        val tasks = BackgroundTasks(listOf(
            BackgroundTaskRow(runtime, "review", "Review lifecycle regressions", "Reading test results", BackgroundTaskStatus.Active, now),
            BackgroundTaskRow(runtime, "build", "Verify the native build", "Build verified", BackgroundTaskStatus.Finished, now),
            BackgroundTaskRow(runtime, "offline", "Inspect reconnect behavior", "Last observed: reading connection state", BackgroundTaskStatus.Active, now - 180000, false),
        ))
        setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = HermesGatewaySnapshot(
                    authenticationState = AuthenticationState.Authenticated,
                    durableSessions = listOf(SessionSummary(id, "Background tasks · fixture")),
                    chatSessions = mapOf(id to ChatSessionSnapshot(
                        messages = listOf(ChatMessage(ChatMessageRole.Assistant, "The parent response has ended. Child tasks remain visible below. This is a synthetic UI fixture, not live task evidence.")),
                        backgroundTasks = tasks,
                    )),
                ), initialRoute = SessionDetailRoute(id))
            }
        }
    }
}
