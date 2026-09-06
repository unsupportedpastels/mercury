package com.unsupportedpastels.hermesandroid.connection

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.voice.SpeechSocketFrame
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamConnector
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamSocket
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeLifecycleOwnersTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun directTransitionCancelsConnectionAndProjectWorkBeforeNewScopeCanPublish() = runTest {
        val firstOrigin = ServerOrigin.parse("https://first.example")
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val coordinator = ConnectionCoordinator()

        val first = coordinator.beginDirectTransition(firstOrigin)
        val connectionJob = backgroundJob()
        val projectJob = backgroundJob()
        coordinator.connectionJob = connectionJob
        val projectRequest = coordinator.nextProjectSessionGeneration(ProjectId("project"))
        coordinator.setProjectSessionJob(ProjectId("project"), projectJob)

        val second = coordinator.beginDirectTransition(secondOrigin)
        advanceUntilIdle()

        assertEquals(first.generation + 1L, second.generation)
        assertEquals(secondOrigin, coordinator.activeOrigin)
        assertFalse(connectionJob.isActive)
        assertFalse(projectJob.isActive)
        assertNull(coordinator.projectSessionGeneration(ProjectId("project")))
        assertTrue(projectRequest > 0L)
        assertFalse(
            ConnectionOperationGuard {
                coordinator.currentScope("default", authenticated = true)
            }.isCurrent(
                ConnectionOperationScope(
                    origin = firstOrigin,
                    relayTargetId = null,
                    profile = "default",
                    generation = first.generation,
                    profileGeneration = first.profileGeneration,
                ),
            ),
        )
    }

    @Test
    fun sessionOperationGenerationReplacementCancelsOnlyThatDurableSession() = runTest {
        val registry = PerSessionControllerRegistry()
        val firstId = DurableSessionId("durable-first")
        val secondId = DurableSessionId("durable-second")
        val firstJob = backgroundJob()
        val secondJob = backgroundJob()
        val firstGeneration = registry.nextOperationGeneration(firstId)
        val secondGeneration = registry.nextOperationGeneration(secondId)
        registry.setOperationJob(firstId, firstJob)
        registry.setOperationJob(secondId, secondJob)
        advanceUntilIdle()

        val replacementGeneration = registry.nextOperationGeneration(firstId)
        advanceUntilIdle()

        assertTrue(replacementGeneration > secondGeneration)
        assertFalse(firstJob.isActive)
        assertTrue(secondJob.isActive)
        assertTrue(
            registry.isCurrentChatOperation(
                durableSessionId = secondId,
                origin = ServerOrigin.parse("https://hermes.example"),
                currentOrigin = ServerOrigin.parse("https://hermes.example"),
                originGeneration = 4L,
                currentGeneration = 4L,
                operationGeneration = secondGeneration,
            ),
        )
        assertFalse(
            registry.isCurrentChatOperation(
                durableSessionId = firstId,
                origin = ServerOrigin.parse("https://hermes.example"),
                currentOrigin = ServerOrigin.parse("https://hermes.example"),
                originGeneration = 4L,
                currentGeneration = 4L,
                operationGeneration = firstGeneration,
            ),
        )
        secondJob.cancel()
    }

    @Test
    fun recoveryIsBoundedAndConcurrentAttemptCannotBeStartedTwice() = runTest {
        val registry = PerSessionControllerRegistry()
        val durableId = DurableSessionId("durable")
        val session = FakeOwnerSession()
        val operationGeneration = registry.nextOperationGeneration(durableId)
        registry.activateController(
            PerSessionController(
                durableSessionId = durableId,
                session = session,
                runtimeSessionId = RuntimeSessionId("runtime"),
                operationGeneration = operationGeneration,
                recoveryState = ChatRecoveryState(operationGeneration),
            ),
        )

        val first = registry.startRecovery(durableId, operationGeneration)
        assertNotNull(first)
        assertNull(registry.startRecovery(durableId, operationGeneration))
        assertTrue(registry.isRecoveryInProgress(durableId, operationGeneration))
        registry.finishRecovery(checkNotNull(first))

        val second = registry.startRecovery(durableId, operationGeneration)
        assertNotNull(second)
        registry.finishRecovery(checkNotNull(second))
        assertNull(registry.startRecovery(durableId, operationGeneration))
    }

    @Test
    fun detachRequiresExactSessionIdentityAndLeavesReplacementUntouched() = runTest {
        val registry = PerSessionControllerRegistry()
        val durableId = DurableSessionId("durable")
        val owner = FakeOwnerSession()
        val wrong = FakeOwnerSession()
        registry.activateController(
            PerSessionController(
                durableSessionId = durableId,
                session = owner,
                runtimeSessionId = RuntimeSessionId("runtime"),
                operationGeneration = registry.nextOperationGeneration(durableId),
            ),
        )

        assertNull(registry.detachController(durableId, wrong))
        assertTrue(registry.controller(durableId)?.session === owner)
        assertNotNull(registry.detachController(durableId, owner))
        assertNull(registry.controller(durableId))
    }

    @Test
    fun staleSpeechSocketCandidateIsClosedWhenProfileScopeChangesBeforeHandoff() = runTest {
        val firstOrigin = ServerOrigin.parse("https://first.example")
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(firstOrigin))
        val socketReady = CompletableDeferred<Unit>()
        val releaseSocket = CompletableDeferred<Unit>()
        var closeCalls = 0
        val socket = object : SpeechStreamSocket {
            override suspend fun sendText(text: String) = Unit
            override suspend fun receiveFrame(): SpeechSocketFrame? = null
            override suspend fun close() {
                closeCalls += 1
            }
        }
        val client = object : HermesConnectionClient {
            override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
                HermesConnectionInfo(
                    version = "test",
                    authRequired = false,
                    nativeOAuthSupported = false,
                    providers = emptyList(),
                )

            override suspend fun probeVoiceCapabilities(
                serverOrigin: ServerOrigin,
                accessToken: String,
                profile: String,
            ): VoiceCapabilities = VoiceCapabilities(true, false)
        }
        val speechConnector = SpeechStreamConnector { _, _, _ ->
            socketReady.complete(Unit)
            releaseSocket.await()
            socket
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            speechStreamConnector = speechConnector,
        )
        advanceUntilIdle()
        viewModel.refreshVoiceCapabilities()
        assertTrue(viewModel.voiceCapabilities.value.canStreamSpeech)

        val opened = async { runCatching { viewModel.openSpeechStream() } }
        runCurrent()
        socketReady.await()
        settings.value = ServerSettingsState.Ready(secondOrigin)
        runCurrent()
        releaseSocket.complete(Unit)

        val failure = opened.await().exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(1, closeCalls)
    }
    private fun CoroutineScope.backgroundJob(): Job = launch {
        awaitCancellation()
    }

    private class FakeOwnerSession : HermesChatSession {
        override val events: Flow<HermesChatEvent> = emptyFlow()

        override suspend fun resume(
            durableSessionId: DurableSessionId,
            profile: String?,
        ): ResumedChatSession = ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime"),
            durableSessionId = durableSessionId,
            resumed = true,
            messages = emptyList(),
            running = false,
            inflight = null,
        )

        override suspend fun submitPrompt(
            runtimeSessionId: RuntimeSessionId,
            text: String,
        ): PromptSubmission = PromptSubmission("ok")

        override suspend fun close() = Unit
    }
}
