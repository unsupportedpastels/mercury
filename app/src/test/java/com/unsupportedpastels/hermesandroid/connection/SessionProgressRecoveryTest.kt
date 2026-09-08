package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.*
import com.unsupportedpastels.hermesandroid.gateway.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionProgressRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = ServerOrigin.parse("https://progress.example")
    private val id = DurableSessionId("stored-progress")

    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun coldOpenWithoutCacheRecoversServerResultsAndRefreshIsReadOnly() = runTest(dispatcher) {
        val client = ProgressClient()
        var socketConnections = 0
        val connector = HermesChatConnector { _, _ -> socketConnections++; error("No controller allowed") }
        fun newModel() = HermesConnectionViewModel(
            MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)), client, chatConnector = connector,
        )
        val first = newModel()
        runCurrent()
        assertEquals(AuthenticationState.NotRequired, first.snapshots.value.authenticationState)
        first.openSession(id).join()
        assertEquals("Check implementation", first.snapshots.value.chatSessions[id]!!.progress.milestones.single().content)
        val second = newModel()
        runCurrent()
        second.openSession(id).join()
        val before = second.snapshots.value.chatSessions[id]!!
        assertTrue(before.progress.restored)
        second.refreshSessionProgress(id).join()
        val after = second.snapshots.value.chatSessions[id]!!
        assertEquals(before.messages, after.messages)
        assertEquals(before.progress.lastObservedAtEpochMillis, after.progress.lastObservedAtEpochMillis)
        assertEquals(0, socketConnections)
        assertEquals(3, client.reads.size)
        assertTrue(client.reads.all { it == Triple(origin, id, "default") })
    }

    @Test fun failedRefreshRetainsHistoryAndDoesNotEchoServerErrors() = runTest(dispatcher) {
        val client = ProgressClient()
        val vm = HermesConnectionViewModel(MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)), client)
        runCurrent()
        vm.refreshSessionProgress(id).join()
        val before = vm.snapshots.value.chatSessions[id]!!.progress
        client.fail = true
        vm.refreshSessionProgress(id).join()
        val after = vm.snapshots.value.chatSessions[id]!!.progress
        assertEquals(before.milestones, after.milestones)
        assertEquals(before.evidence, after.evidence)
        assertEquals(before.lastObservedAtEpochMillis, after.lastObservedAtEpochMillis)
        assertFalse(after.refreshing)
        assertNotNull(after.refreshError)
        assertFalse(after.refreshError!!.contains("private-response"))
        client.fail = false
        vm.refreshSessionProgress(id).join()
        assertNull(vm.snapshots.value.chatSessions[id]!!.progress.refreshError)
    }

    @Test fun staleOriginResponseAndDuplicateTapCannotPublishOrTakeOver() = runTest(dispatcher) {
        val client = ProgressClient()
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val vm = HermesConnectionViewModel(settings, client)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val pending = vm.refreshSessionProgress(id)
        runCurrent()
        vm.refreshSessionProgress(id).join()
        assertEquals(1, client.reads.size)
        settings.value = ServerSettingsState.Ready(ServerOrigin.parse("https://other-progress.example"))
        runCurrent()
        gate.complete(Unit)
        pending.join()
        assertTrue(vm.snapshots.value.chatSessions[id]?.progress?.milestones.isNullOrEmpty())
    }

    @Test fun openDuringPendingRefreshReleasesOnlyItsOwnSpinner() = runTest(dispatcher) {
        val client = ProgressClient()
        val vm = HermesConnectionViewModel(MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)), client)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        client.gate = gate
        val pending = vm.refreshSessionProgress(id)
        runCurrent()
        assertTrue(vm.snapshots.value.chatSessions[id]!!.progress.refreshing)
        // openSession advances operation generation; its own read is separately blocked.
        val openingGate = CompletableDeferred<Unit>()
        client.gate = openingGate
        val opening = vm.openSession(id)
        runCurrent()
        gate.complete(Unit)
        pending.join()
        assertFalse(vm.snapshots.value.chatSessions[id]!!.progress.refreshing)
        openingGate.complete(Unit)
        opening.join()
        client.gate = null
        val count = client.reads.size
        vm.refreshSessionProgress(id).join()
        assertEquals(count + 1, client.reads.size)
    }

    @Test fun foregroundReturnReadsPersistedProgressWithoutAHeartbeatOrPrompt() = runTest(dispatcher) {
        val foreground = MutableStateFlow(true)
        val client = ProgressClient()
        val vm = HermesConnectionViewModel(
            MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)), client,
            appForegroundStates = foreground,
        )
        runCurrent()
        vm.openSession(id).join()
        val before = vm.snapshots.value.chatSessions[id]!!.progress
        foreground.value = false
        runCurrent()
        assertEquals(1, client.reads.size)
        foreground.value = true
        runCurrent()
        assertEquals(2, client.reads.size)
        assertEquals(before.lastObservedAtEpochMillis, vm.snapshots.value.chatSessions[id]!!.progress.lastObservedAtEpochMillis)
    }

    @Test fun restEnvelopeUsesOfficialBoundedLatestProfileReadAndMatchesRelayParser() = runTest {
        var requests = 0
        val body = buildJsonObject { put("messages", JsonArray(rows())) }.toString()
        val engine = MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/sessions/stored-progress/messages", request.url.encodedPath)
            assertEquals("latest", request.url.parameters["order"])
            assertEquals("work", request.url.parameters["profile"])
            assertNotNull(request.url.parameters["limit"])
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine)
        try {
            val envelope = HttpHermesConnectionClient(http).loadTranscriptEnvelope(origin, null, id, "work")
            assertTrue(envelope.progress.hasMilestoneSnapshot)
            assertEquals(1700000000250L, envelope.progress.lastObservedAtEpochMillis)
            assertEquals(envelope.progress, parseRelayTranscriptEnvelope(Json.parseToJsonElement(body).jsonObject).progress)
            assertEquals(1, requests)
        } finally { http.close() }
    }

    private inner class ProgressClient : HermesConnectionClient {
        val reads = mutableListOf<Triple<ServerOrigin, DurableSessionId, String>>()
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
            "0.20.0", false, false, emptyList(),
        )
        override suspend fun loadTranscriptEnvelope(serverOrigin: ServerOrigin, accessToken: String?, durableSessionId: DurableSessionId, profile: String): TranscriptEnvelope {
            reads += Triple(serverOrigin, durableSessionId, profile)
            gate?.let { withContext(NonCancellable) { it.await() } }
            if (fail) throw HermesConnectionException("private-response")
            return TranscriptEnvelope(listOf(ChatMessage(ChatMessageRole.Assistant, "Saved answer")), DurableProgressParser.parse(rows()))
        }
    }

    private fun rows() = listOf(Json.parseToJsonElement("""{"role":"tool","tool_name":"todo_list","tool_call_id":"todo-1","timestamp":1700000000.25,"content":{"todos":[{"id":"one","content":"Check implementation","status":"in_progress"}],"revision":7}}""").jsonObject)
}
