package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionsResult
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProjectTreeResult
import com.unsupportedpastels.hermesandroid.connection.HermesConnectionClient
import com.unsupportedpastels.hermesandroid.connection.HermesConnectionInfo
import com.unsupportedpastels.hermesandroid.connection.HermesConnectionViewModel
import com.unsupportedpastels.hermesandroid.connection.ServerCatalog
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.mercury.core.relay.RelayPlatformCrypto
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayConnectionViewModelTest {
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
    fun retainedIdleChildCompletesOfflineAndReconnectKeepsControllerForSecondSend() = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        val viewModel = relayViewModel {
            FakeRelaySession().also {
                if (sessions.size >= 3) it.recoverySnapshot = recoverySnapshot(live = true)
                sessions += it
            }
        }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.openSession(id).join()
        viewModel.sendMessage(id, "one accepted prompt").join()
        val original = sessions.last()
        original.channel.send(com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskEvent(
            RuntimeSessionId("runtime"), com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskEventKind.Start,
            "child", "background test", null))
        original.channel.send(HermesChatEvent.MessageComplete(RuntimeSessionId("runtime"), "Dispatched", "ok"))
        runCurrent()
        assertEquals(false, viewModel.snapshots.value.chatSessions.getValue(id).isSending)
        original.channel.close()
        advanceUntilIdle()
        assertEquals(4, sessions.size)
        val recovered = viewModel.snapshots.value.chatSessions.getValue(id)
        assertEquals(1, recovered.backgroundTasks.rows.size)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskStatus.Finished,
            recovered.backgroundTasks.rows.single().status)
        assertEquals(0, sessions.last().closeCalls)
        assertEquals(1, original.submitCalls)
        viewModel.sendMessage(id, "second prompt").join()
        assertEquals(1, sessions.last().submitCalls)
        assertEquals(4, sessions.size)
    }

    @Test
    fun lostLeaseUsesRecordedTerminalWithoutImplicitRuntimeTakeover() = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        val viewModel = relayViewModel {
            FakeRelaySession().also {
                if (sessions.size >= 3) it.recoverySnapshot = recoverySnapshot(live = false)
                sessions += it
            }
        }
        advanceUntilIdle(); viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.openSession(id).join(); viewModel.sendMessage(id, "accepted").join()
        sessions.last().channel.close()
        advanceUntilIdle()
        assertEquals(4, sessions.size)
        assertEquals(0, sessions.last().resumeCalls)
        assertEquals(0, sessions.last().submitCalls)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskStatus.Finished,
            viewModel.snapshots.value.chatSessions.getValue(id).backgroundTasks.rows.single().status)
        assertEquals(false, viewModel.snapshots.value.chatSessions.getValue(id).isSending)
    }

    @Test
    fun automaticGapResumeFinalizesUnmatchedToolsBeforeAnotherSend() = automaticGapResume(false)

    @Test
    fun automaticGapResumePreservesAuthoritativelyRunningTools() = automaticGapResume(true)

    private fun automaticGapResume(running: Boolean) = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        val fullText = "authoritative full response ".repeat(3200)
        val viewModel = relayViewModel {
            FakeRelaySession().also {
                if (sessions.size >= 3) {
                    it.recoverySnapshot = recoverySnapshot(live = true).copy(gap = true)
                    it.resumeRunning = running
                    it.resumeMessages = listOf(buildJsonObject {
                        put("role", "assistant"); put("content", fullText)
                    })
                }
                sessions += it
            }
        }
        advanceUntilIdle(); viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.openSession(id).join(); viewModel.sendMessage(id, "one accepted prompt").join()
        val original = sessions.last()
        original.channel.send(HermesChatEvent.ToolStart(RuntimeSessionId("runtime"), "tool-1", "terminal", null))
        runCurrent()
        original.channel.close()
        advanceUntilIdle()
        val recovered = viewModel.snapshots.value.chatSessions.getValue(id)
        assertEquals(4, sessions.size)
        assertEquals(running, recovered.isSending)
        assertEquals(fullText, recovered.messages.last().text)
        assertEquals(running, recovered.runState.tools.single().state == com.unsupportedpastels.hermesandroid.app.RunToolState.Running)
        assertEquals(0, sessions.last().closeCalls)
        assertEquals(0, sessions.last().submitCalls)
        assertEquals(1, original.submitCalls)
    }

    private fun recoverySnapshot(live: Boolean): RelayLeaseSnapshot {
        val binding = RelayLeaseBinding("runtime", "relay-session-1", "default", live)
        val task = kotlinx.serialization.json.Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"event","params":{"session_id":"runtime","type":"subagent.complete","payload":{"subagent_id":"child","status":"completed"},"recovery_revision":2,"recovery_binding":{"runtime_session_id":"runtime","durable_session_id":"relay-session-1","profile":"default","live":$live}}}""") as JsonObject
        return RelayLeaseSnapshot("lease", 10, false, !live, listOf(binding), listOf(task), false)
    }

    @Test
    fun unansweredOptionalMetadataCannotBlockPromptSubmission() = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        val viewModel = relayViewModel { FakeRelaySession().also { sessions += it } }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.openSession(id).join()
        val controller = sessions.last()
        controller.processBlockCall = controller.processCalls + 1
        controller.processBarrier = kotlinx.coroutines.CompletableDeferred()
        val sending = viewModel.sendMessage(id, "bounded metadata")
        runCurrent()
        assertEquals(0, controller.submitCalls)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, controller.submitCalls)
        assertEquals(true, sending.isCompleted)
        assertEquals(0, controller.closeCalls)
        assertEquals(1L, viewModel.snapshots.value.chatSessions.getValue(id).acceptedSubmissionCount)
    }

    @Test
    fun competingSendOpensItsOwnChannelBesideAttachmentStagingController() = pendingSendCannotBeEvicted(false)

    @Test
    fun competingSendOpensItsOwnChannelBesideConnectingControllerDuringMetadata() = pendingSendCannotBeEvicted(true)

    /**
     * A second session on the same phone never waits for, evicts, or closes
     * the first: it opens its own lease channel and its send is accepted.
     */
    private fun pendingSendCannotBeEvicted(connecting: Boolean) = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        val viewModel = relayViewModel { FakeRelaySession().also { sessions += it } }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.openSession(id).join()
        val controller = sessions.last()
        val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        if (connecting) {
            controller.processBlockCall = controller.processCalls + 1
            controller.processBarrier = barrier
        } else {
            controller.attachBarrier = barrier
            viewModel.addAttachments(id, listOf(com.unsupportedpastels.hermesandroid.app.ComposerAttachment(
                "a1", "content://provider/report", "report.txt", "text/plain", 5,
            )))
        }
        val first = viewModel.sendMessage(id, "first")
        runCurrent()
        assertEquals(false, viewModel.snapshots.value.chatSessions.getValue(id).isSending)
        assertEquals(if (connecting) com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Connecting
            else com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Submitting,
            viewModel.snapshots.value.chatSessions.getValue(id).connectionPhase)
        val count = sessions.size
        viewModel.sendMessage(DurableSessionId("relay-session-2"), "competing").join()
        assertEquals("pending controller must not be closed", 0, controller.closeCalls)
        assertEquals("competing send opens its own channel", count + 1, sessions.size)
        assertEquals("competing", sessions.last().lastSubmitted)
        assertEquals(1L, viewModel.snapshots.value.chatSessions.getValue(DurableSessionId("relay-session-2")).acceptedSubmissionCount)
        barrier.complete(Unit)
        first.join()
        assertEquals(1, controller.submitCalls)
        assertEquals(1L, viewModel.snapshots.value.chatSessions.getValue(id).acceptedSubmissionCount)
    }

    @Test
    fun acknowledgedPromptReleasesPhaseAndAttachmentsBeforeAuxiliaryMetadata() = runTest(dispatcher) {
        val barrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        // A new draft opens its controller on Send; suspend only post-ack metadata.
        val controller = FakeRelaySession().apply {
            processBlockCall = 2
            processBarrier = barrier
        }
        // Reuse a separate ViewModel factory slot for the lazily created controller.
        val draftViewModel = relayViewModel { controller }
        advanceUntilIdle()
        draftViewModel.connectRelay(target()).join()
        val draftId = draftViewModel.createNewSession()
        advanceUntilIdle()
        draftViewModel.addAttachments(draftId, listOf(com.unsupportedpastels.hermesandroid.app.ComposerAttachment(
            "a1", "content://provider/report", "report.txt", "text/plain", 5,
        )))
        val first = draftViewModel.sendMessage(draftId, "first")
        runCurrent()
        val accepted = draftViewModel.snapshots.value.chatSessions.getValue(draftId)
        assertEquals(1L, accepted.acceptedSubmissionCount)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Idle, accepted.connectionPhase)
        assertEquals(true, draftViewModel.attachments.value[draftId].orEmpty().isEmpty())
        assertEquals(false, draftViewModel.snapshots.value.durableSessions.first { it.id == draftId }.isLocalDraft)
        draftViewModel.sendMessage(draftId, "/steer replacement").join()
        assertEquals(2, controller.submitCalls)
        assertEquals("/steer replacement", controller.lastSubmitted)
        assertEquals(2L, draftViewModel.snapshots.value.chatSessions.getValue(draftId).acceptedSubmissionCount)
        assertEquals(0L, draftViewModel.snapshots.value.chatSessions.getValue(draftId).rejectedSubmissionCount)
        barrier.complete(Unit)
        first.join()
    }

    @Test
    fun supersededRecoveryReleasesAdmissionForReplacement() = suspendedRecoveryIsCancelled(false)

    @Test
    fun supersededRecoveryReleasesAdmissionForReconnect() = suspendedRecoveryIsCancelled(true)

    private fun suspendedRecoveryIsCancelled(reconnect: Boolean) = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        var blockResume = false
        val viewModel = relayViewModel {
            FakeRelaySession().also {
                if (blockResume) it.cancellableResumeBarrier = kotlinx.coroutines.CompletableDeferred()
                sessions += it
            }
        }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.sendMessage(id, "first").join()
        val original = sessions.last()
        blockResume = true
        original.channel.close()
        runCurrent()
        advanceTimeBy(501)
        runCurrent()
        val candidate = sessions.last()
        assertEquals(1, candidate.resumeCalls)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Reconnecting,
            viewModel.snapshots.value.chatSessions.getValue(id).connectionPhase)
        blockResume = false
        val replacement = if (reconnect) viewModel.connectRelay(target()) else viewModel.sendMessage(id, "replacement")
        runCurrent()
        assertEquals("superseded resume must be cancelled without waiting for a deadline", 1, candidate.closeCalls)
        assertEquals(true, replacement.isCompleted)
        assertEquals(0, candidate.submitCalls)
        assertEquals(1, original.submitCalls)
        if (!reconnect) assertEquals("replacement", sessions.last().lastSubmitted)
        candidate.cancellableResumeBarrier!!.complete(Unit)
        runCurrent()
        assertEquals(1, candidate.closeCalls)
    }

    @Test
    fun unansweredRecoveryResumeHasBoundedAttemptsWithoutPromptReplay() = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        var blockResume = false
        val viewModel = relayViewModel {
            FakeRelaySession().also {
                if (blockResume) it.cancellableResumeBarrier = kotlinx.coroutines.CompletableDeferred()
                sessions += it
            }
        }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.sendMessage(id, "first").join()
        val original = sessions.last()
        val count = sessions.size
        blockResume = true
        original.channel.close()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        val chat = viewModel.snapshots.value.chatSessions.getValue(id)
        assertEquals("recovery must settle even when resume never replies", false, chat.isSending)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Idle, chat.connectionPhase)
        assertEquals("Connection lost while receiving response", chat.error)
        assertEquals(3, sessions.size - count)
        sessions.drop(count).forEach {
            assertEquals(1, it.closeCalls)
            assertEquals(0, it.submitCalls)
        }
        assertEquals(1, original.submitCalls)
        blockResume = false
        viewModel.loadRecentSessions().join()
        assertEquals(null, viewModel.snapshots.value.recentSessions.error)
    }

    @Test
    fun unansweredHistoricalTranscriptHasBoundedAttemptsAndReleasesNextSend() = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        var blockTranscript = false
        val viewModel = relayViewModel {
            FakeRelaySession().also {
                if (blockTranscript) {
                    it.recoverySnapshot = RelayLeaseSnapshot("lost", 0, false, true, emptyList(), emptyList(), false)
                    it.transcriptBarrier = kotlinx.coroutines.CompletableDeferred()
                }
                sessions += it
            }
        }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.sendMessage(id, "first").join()
        val original = sessions.last()
        val count = sessions.size
        blockTranscript = true
        original.channel.close()
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        val chat = viewModel.snapshots.value.chatSessions.getValue(id)
        assertEquals("historical read must settle even when transcript never replies", false, chat.isSending)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Idle, chat.connectionPhase)
        assertEquals(3, sessions.size - count)
        sessions.drop(count).forEach {
            assertEquals(1, it.closeCalls)
            assertEquals(0, it.resumeCalls)
            assertEquals(0, it.submitCalls)
        }
        blockTranscript = false
        val next = viewModel.sendMessage(id, "second")
        runCurrent()
        assertEquals("controller mutex must be released for next Send", true, next.isCompleted)
        assertEquals("second", sessions.last().lastSubmitted)
        assertEquals(1, original.submitCalls)
    }

    @Test
    fun prolongedOfflineDoesNotExhaustRelayRecoveryAndNetworkReturnResumesOnce() = runTest(dispatcher) {
        val online = MutableStateFlow(true)
        val sessions = mutableListOf<FakeRelaySession>()
        val viewModel = relayViewModel(online) {
            check(online.value) { "offline connector must not be called" }
            FakeRelaySession().also {
                if (sessions.size >= 3) it.recoverySnapshot = recoverySnapshot(live = true)
                sessions += it
            }
        }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.openSession(id).join()
        viewModel.sendMessage(id, "accepted once").join()
        val original = sessions.last()
        online.value = false
        original.channel.close()
        runCurrent()
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(3, sessions.size)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Reconnecting,
            viewModel.snapshots.value.chatSessions.getValue(id).connectionPhase)
        online.value = true
        advanceUntilIdle()
        assertEquals(4, sessions.size)
        assertEquals(1, original.submitCalls)
        assertEquals(0, sessions.last().submitCalls)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.BackgroundTaskStatus.Finished,
            viewModel.snapshots.value.chatSessions.getValue(id).backgroundTasks.rows.single().status)
        viewModel.sendMessage(id, "next explicit send").join()
        assertEquals(1, sessions.last().submitCalls)
    }

    private fun relayViewModel(
        online: MutableStateFlow<Boolean> = MutableStateFlow(true),
        factory: () -> FakeRelaySession,
    ) = HermesConnectionViewModel(
        relayNetworkAvailable = online,
        settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
        client = object : HermesConnectionClient {
            override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = error("no direct probe")
        },
        attachmentReader = com.unsupportedpastels.hermesandroid.attachment.AttachmentByteReader { "hello".toByteArray() },
        relaySessionFactory = { _, _, _ -> factory() },
    )

    @Test
    fun repeatedIdenticalSendFailuresPublishDistinctRejectionAcknowledgments() = runTest(dispatcher) {
        val session = FakeRelaySession().apply { submitFailure = IllegalStateException("same rejection") }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = error("no direct probe")
            },
            relaySessionFactory = { _, _, _ -> session },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        repeat(2) { index ->
            viewModel.sendMessage(id, "same draft").join()
            val chat = viewModel.snapshots.value.chatSessions.getValue(id)
            assertEquals(index.toLong() + 1, chat.rejectedSubmissionCount)
            assertEquals("same draft", chat.rejectedSubmissionText)
            assertEquals("same rejection", chat.error)
            assertEquals(0L, chat.acceptedSubmissionCount)
            assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Idle, chat.connectionPhase)
        }
    }

    @Test
    fun rapidSendIsSingleFlightAndAcknowledgesOnlyAfterAcceptance() = runTest(dispatcher) {
        val session = FakeRelaySession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = error("no direct probe")
            },
            relaySessionFactory = { _, _, _ -> session },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        session.submitBarrier = kotlinx.coroutines.CompletableDeferred()
        val send = viewModel.sendMessage(id, "first")
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Connecting,
            viewModel.snapshots.value.chatSessions.getValue(id).connectionPhase)
        viewModel.sendMessage(id, "duplicate")
        runCurrent()
        assertEquals(1, session.submitCalls)
        assertEquals(0L, viewModel.snapshots.value.chatSessions.getValue(id).acceptedSubmissionCount)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatConnectionPhase.Submitting,
            viewModel.snapshots.value.chatSessions.getValue(id).connectionPhase)
        session.submitBarrier!!.complete(Unit)
        send.join()
        assertEquals(1L, viewModel.snapshots.value.chatSessions.getValue(id).acceptedSubmissionCount)
        assertEquals("first", viewModel.snapshots.value.chatSessions.getValue(id).acceptedSubmissionText)
    }

    @Test
    fun exceptionalIdleStreamClosureReconnectsBeforeNextSubmit() = idleStreamClosureReconnects(exceptional = true)

    @Test
    fun normalIdleStreamClosureReconnectsBeforeNextSubmit() = idleStreamClosureReconnects(exceptional = false)

    private fun idleStreamClosureReconnects(exceptional: Boolean) = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = error("no direct probe")
            },
            relaySessionFactory = { _, _, _ -> FakeRelaySession().also { sessions += it } },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val id = DurableSessionId("relay-session-1")
        viewModel.openSession(id).join()
        runCurrent()
        val original = sessions.last()
        original.channel.close(if (exceptional) IllegalStateException("transport ended") else null)
        runCurrent()
        viewModel.sendMessage(id, "new prompt").join()
        assertEquals(null, original.lastSubmitted)
        assertEquals("new prompt", sessions.last().lastSubmitted)
        assertEquals(1, sessions.last().resumeCalls)
    }

    @Test
    fun readersBorrowTheAdmittedSocketAndSessionsGetTheirOwnChannels() = runTest(dispatcher) {
        val sessions = mutableListOf<FakeRelaySession>()
        val channels = mutableListOf<String?>()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = error("no direct probe")
            },
            relaySessionFactory = { _, _, channel -> channels += channel; FakeRelaySession().also { sessions += it } },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        viewModel.openSession(DurableSessionId("relay-session-1")).join()
        val count = sessions.size
        // Reads and metadata borrow the open controller instead of admitting again.
        viewModel.loadRecentSessions().join()
        viewModel.openProject(ProjectId("relay-project-1")).join()
        assertEquals(count, sessions.size)
        viewModel.sendMessage(DurableSessionId("relay-session-1"), "active turn").join()
        val first = sessions.last()
        // A second session opens its own lease channel and does not disturb the first.
        viewModel.openSession(DurableSessionId("relay-session-2")).join()
        viewModel.sendMessage(DurableSessionId("relay-session-2"), "second session").join()
        assertEquals(count + 1, sessions.size)
        assertEquals(0, first.closeCalls)
        assertEquals("active turn", first.lastSubmitted)
        assertEquals("second session", sessions.last().lastSubmitted)
        assertEquals(null, viewModel.snapshots.value.chatSessions.getValue(DurableSessionId("relay-session-2")).error)
        // Connect and reads use the default channel; each session its own.
        assertEquals(null, channels.first())
        assertEquals("s-relay-session-1", channels[channels.size - 2])
        assertEquals("s-relay-session-2", channels.last())
    }

    @Test
    fun reconnectWaitsForStaleCandidateAndNeverAdoptsIt() = runTest(dispatcher) {
        val blocked = FakeRelaySession().apply { resumeBarrier = kotlinx.coroutines.CompletableDeferred() }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = error("no direct probe")
            },
            relaySessionFactory = { _, _, _ ->
                connections += 1
                if (connections == 3) blocked else FakeRelaySession()
            },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        viewModel.openSession(DurableSessionId("relay-session-1"))
        runCurrent()
        assertEquals(1, blocked.resumeCalls)
        val reconnect = viewModel.connectRelay(target())
        runCurrent()
        assertEquals(3, connections)
        blocked.resumeBarrier!!.complete(Unit)
        reconnect.join()
        assertEquals(1, blocked.closeCalls)
        assertEquals(4, connections)
        assertEquals(true, viewModel.snapshots.value.activeRuntimes.isEmpty())
    }

    @Test
    fun directCatalogMetadataUpdateDoesNotOverwriteCompletedRelayConnection() = runTest(dispatcher) {
        val directOrigin = ServerOrigin.parse("https://direct.example.com")
        val initialCatalog = ServerCatalog(
            entries = listOf(ServerCatalogEntry(directOrigin, label = "Direct")),
            activeOrigin = directOrigin,
        )
        val settings = MutableStateFlow(ServerSettingsState.Ready(initialCatalog))
        val client = object : HermesConnectionClient {
            override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
                error("direct server offline")
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            relaySessionFactory = { _, _, _ -> FakeRelaySession() },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)

        settings.value = ServerSettingsState.Ready(
            initialCatalog.copy(
                entries = listOf(ServerCatalogEntry(directOrigin, label = "Renamed direct")),
            ),
        )
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(target().id, viewModel.snapshots.value.relayTargetId)
    }

    @Test
    fun transientDirectSettingsStatesDoNotReplaceTheActiveRelayConnection() = runTest(dispatcher) {
        val directOrigin = ServerOrigin.parse("https://direct.example.com")
        val directCatalog = ServerCatalog(
            entries = listOf(ServerCatalogEntry(directOrigin, label = "Direct")),
            activeOrigin = directOrigin,
        )
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(directCatalog))
        var directProbes = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo {
                    directProbes += 1
                    error("direct server offline")
                }
            },
            relaySessionFactory = { _, _, _ -> FakeRelaySession() },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        val probesBeforeTransientStates = directProbes

        settings.value = ServerSettingsState.Loading
        advanceUntilIdle()
        settings.value = ServerSettingsState.Ready(directCatalog)
        advanceUntilIdle()

        assertEquals(probesBeforeTransientStates, directProbes)
        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(target().id, viewModel.snapshots.value.relayTargetId)
    }

    @Test
    fun retryReconnectsTheActiveRelayTarget() = runTest(dispatcher) {
        var attempts = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
                    error("direct probe must not run")
            },
            relaySessionFactory = { _, _, _ ->
                attempts += 1
                if (attempts == 1) error("temporary relay failure")
                FakeRelaySession()
            },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        assertEquals(ConnectionState.Disconnected, viewModel.snapshots.value.connectionState)

        viewModel.retryConnection().join()

        assertEquals(2, attempts)
        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(target().id, viewModel.snapshots.value.relayTargetId)
    }

    @Test
    fun connectingRelayPublishesSessionsWithoutDirectProbe() = runTest(dispatcher) {
        var probes = 0
        val client = object : HermesConnectionClient {
            override suspend fun probe(serverOrigin: com.unsupportedpastels.hermesandroid.connection.ServerOrigin): HermesConnectionInfo {
                probes += 1
                error("direct probe must not run")
            }
        }
        val relaySession = FakeRelaySession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = client,
            relaySessionFactory = { _, _, _ -> relaySession },
        )
        advanceUntilIdle()

        viewModel.connectRelay(target()).join()

        val snapshot = viewModel.snapshots.value
        assertEquals(0, probes)
        assertEquals(ConnectionState.Connected, snapshot.connectionState)
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(target().id, snapshot.relayTargetId)
        assertEquals(listOf("default", "work"), snapshot.profiles)
        assertEquals(listOf("relay-session-1"), snapshot.durableSessions.map { it.id.value })
        assertEquals(listOf("relay-project-1"), snapshot.projects.map { it.id.value })
        assertEquals(
            listOf("relay-project-1"),
            (snapshot.projectState as ProjectLoadState.Loaded).projects.map { it.id.value },
        )
        assertEquals(1, relaySession.closeCalls)

        viewModel.loadRecentSessions().join()
        assertEquals(
            listOf("relay-session-1"),
            viewModel.snapshots.value.recentSessions.sessions.map { it.id.value },
        )
        assertEquals(null, viewModel.snapshots.value.recentSessions.error)
        assertEquals(true, viewModel.snapshots.value.recentSessions.hasMore)

        viewModel.loadMoreRecentSessions().join()
        assertEquals(
            listOf("relay-session-1", "relay-session-2"),
            viewModel.snapshots.value.recentSessions.sessions.map { it.id.value },
        )
        assertEquals(false, viewModel.snapshots.value.recentSessions.hasMore)

        viewModel.openProject(ProjectId("relay-project-1")).join()
        assertEquals(
            listOf("relay-project-session-1"),
            (viewModel.snapshots.value.projectSessionStates
                .getValue(ProjectId("relay-project-1")) as ProjectSessionLoadState.Loaded)
                .sessions.map { it.id.value },
        )

        viewModel.openSession(DurableSessionId("relay-session-1")).join()
        viewModel.sendMessage(DurableSessionId("relay-session-1"), "hello through relay").join()

        assertEquals("restored response", viewModel.snapshots.value.chatSessions
            .getValue(DurableSessionId("relay-session-1")).messages.first().text)
        assertEquals("hello through relay", relaySession.lastSubmitted)

        viewModel.loadManagementSettings("work").join()
        assertEquals("work", viewModel.snapshots.value.selectedProfile)
        assertEquals(
            listOf("work-session-1"),
            viewModel.snapshots.value.durableSessions.map { it.id.value },
        )

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()
        val draft = viewModel.snapshots.value.chatSessions.getValue(draftId)
        assertEquals(1, relaySession.profileOptionsCalls)
        assertEquals("gpt-relay", draft.model)
        assertEquals("test-provider", draft.provider)
        assertEquals(ModelCapabilities(reasoning = true, fast = true), draft.modelCapabilities)
        assertEquals(true, draft.draftDefaultsLoaded)

        viewModel.setReasoningEffort(draftId, "high").join()
        viewModel.setFast(draftId, true).join()
        assertEquals("high", viewModel.snapshots.value.chatSessions.getValue(draftId).reasoningEffort)
        assertEquals("fast", viewModel.snapshots.value.chatSessions.getValue(draftId).fastMode)
    }

    @Test
    fun imagesReuseAdmittedControllerAndDiscardProfileSwitchResult() = runTest(dispatcher) {
        val response = kotlinx.coroutines.CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        var factories = 0
        val imageSession = object : HermesChatSession by FakeRelaySession() {
            override val events = kotlinx.coroutines.flow.MutableSharedFlow<HermesChatEvent>()
            override suspend fun relayRequest(method: String, params: JsonObject): JsonObject {
                if (method !in listOf("relay.status", "relay.image.read")) return FakeRelaySession().relayRequest(method, params)
                received += method
                return if (method == "relay.status") {
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"capabilities":{"image_read":{"method":"relay.image.read","max_bytes":2097152,"mime_types":["image/png"]}}}""") as JsonObject
                } else {
                    assertEquals(JsonPrimitive("default"), params["profile"])
                    assertEquals(JsonPrimitive("/tmp/image.png"), params["path"])
                    response.await()
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"mime_type":"image/png","size":8,"base64":"iVBORw0KGgo="}""") as JsonObject
                }
            }
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = error("No direct HTTP")
            },
            relaySessionFactory = { _, _, _ -> factories++; imageSession },
        )
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        viewModel.openSession(DurableSessionId("relay-session-1")).join()
        val before = factories
        val download = async { runCatching { viewModel.downloadManagedImage("/tmp/image.png") } }
        runCurrent()
        assertEquals(listOf("relay.status", "relay.image.read"), received)
        assertEquals(before, factories)
        viewModel.connectRelay(target(), "work").join()
        response.complete(Unit)
        org.junit.Assert.assertTrue(download.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun folderBrowseAndCreateBorrowTheAdmittedControllerWithoutRetry() = runTest(dispatcher) {
        var factories = 0
        var createCalls = 0
        val folderSession = object : FakeRelaySession() {
            override suspend fun relayRequest(method: String, params: JsonObject): JsonObject {
                return when (method) {
                    "relay.status" -> kotlinx.serialization.json.Json.parseToJsonElement(
                        """{"capabilities":{"folders":{"version":1,"list_method":"relay.folders.list","create_method":"relay.folders.create"}}}""",
                    ) as JsonObject
                    "relay.folders.list" -> {
                        assertEquals("default", params["profile"]!!.jsonPrimitive.content)
                        assertEquals("/workspace", params["path"]!!.jsonPrimitive.content)
                        buildJsonObject {
                            put("path", "/workspace")
                            put("parent", "/")
                            put("root", "/")
                            put("locked_root", "/")
                            put("can_change_path", true)
                            put("entries", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "existing")
                                    put("path", "/workspace/existing")
                                    put("is_directory", true)
                                })
                            })
                        }
                    }
                    "relay.folders.create" -> {
                        createCalls += 1
                        assertEquals("/workspace", params["parent_path"]!!.jsonPrimitive.content)
                        assertEquals("new-child", params["name"]!!.jsonPrimitive.content)
                        buildJsonObject {
                            put("path", "/workspace/new-child")
                            put("entries", buildJsonArray {})
                        }
                    }
                    else -> super.relayRequest(method, params)
                }
            }
        }
        val viewModel = relayViewModel {
            factories += 1
            folderSession
        }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        viewModel.openSession(DurableSessionId("relay-session-1")).join()
        val factoriesAfterChat = factories

        val listing = viewModel.loadHostDirectories("/workspace")
        val created = viewModel.createHostDirectory("/workspace", "new-child")

        assertEquals(listOf(HostDirectoryEntry("existing", "/workspace/existing")), listing.directories)
        assertEquals(HostDirectoryListing("/workspace/new-child", emptyList(), canChangePath = false), created)
        assertEquals(1, createCalls)
        assertEquals(
            "folder RPCs borrowed the admitted chat controller",
            factoriesAfterChat,
            factories,
        )
    }

    @Test
    fun relayFolderCreateUsesProjectRegistrationThroughTheSameController() = runTest(dispatcher) {
        var factories = 0
        var projectCreates = 0
        val session = object : FakeRelaySession() {
            override suspend fun createProject(
                name: String,
                path: String,
                profile: String?,
            ): ProjectSummary {
                projectCreates += 1
                assertEquals("New project", name)
                assertEquals("/workspace/new-child", path)
                assertEquals("default", profile)
                return ProjectSummary(ProjectId("new-project"), name, path, 0, emptyList())
            }
        }
        val viewModel = relayViewModel {
            factories += 1
            session
        }
        advanceUntilIdle()
        viewModel.connectRelay(target()).join()
        val created = viewModel.createProject("New project", "/workspace/new-child")

        assertEquals(ProjectId("new-project"), created.id)
        assertEquals(1, projectCreates)
        assertEquals(2, factories) // initial admission plus one borrowed reader fallback
    }

    private open class FakeRelaySession : HermesChatSession {
        var transcriptBarrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var recoverySnapshot: RelayLeaseSnapshot? = null
        override val relayLeaseSnapshot get() = recoverySnapshot
        val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
        override val events = channel.receiveAsFlow()
        var resumeCalls = 0
        var resumeRunning = false
        var resumeMessages = emptyList<JsonObject>()
        var cancellableResumeBarrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var resumeBarrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var closeCalls = 0
        var profileOptionsCalls = 0
        var processCalls = 0
        var processBlockCall = -1
        var processBarrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var attachBarrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun loadProcessList(runtimeSessionId: RuntimeSessionId): List<com.unsupportedpastels.hermesandroid.app.ProcessRow> {
            processCalls += 1
            if (processCalls == processBlockCall) processBarrier?.await()
            return emptyList()
        }

        override suspend fun attachFile(runtimeSessionId: RuntimeSessionId, filename: String, mimeType: String, base64Content: String): String {
            attachBarrier?.await()
            return "@file:report.txt"
        }

        override suspend fun loadProfiles(): List<String> = listOf("default", "work")

        override suspend fun loadProfileModelOptions(): ModelOptions {
            profileOptionsCalls += 1
            return ModelOptions(
                current = ModelSelection("test-provider", "gpt-relay"),
                providers = listOf(
                    ModelProviderOption(
                        slug = "test-provider",
                        name = "Test Provider",
                        models = listOf("gpt-relay"),
                        capabilities = mapOf(
                            "gpt-relay" to ModelCapabilities(reasoning = true, fast = true),
                        ),
                    ),
                ),
            )
        }

        open override suspend fun relayRequest(method: String, params: JsonObject): JsonObject {
            if (method == "relay.session.transcript") transcriptBarrier?.await()
            return when (method) {
                "relay.sessions.list" -> buildJsonObject {
                    val offset = (params["offset"] as? JsonPrimitive)?.intOrNull ?: 0
                    val profile = (params["profile"] as? JsonPrimitive)?.content ?: "default"
                    put("sessions", buildJsonArray {
                        add(buildJsonObject {
                            val id = if (profile == "default") {
                                if (offset == 0) "relay-session-1" else "relay-session-2"
                            } else {
                                "$profile-session-${offset + 1}"
                            }
                            put("id", id)
                            put("title", "Relay session")
                            put("profile", "default")
                        })
                    })
                    put("total", 2)
                }
                "relay.session.transcript" -> buildJsonObject {
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "assistant")
                            put("content", "restored response")
                        })
                    })
                }
                else -> error("unexpected relay method $method")
            }
        }

        override suspend fun loadProjectTree(
            profile: String?,
            previewLimit: Int,
            sessionLimit: Int,
        ) = ProjectTreeResult(
            projects = listOf(
                ProjectSummary(
                    id = ProjectId("relay-project-1"),
                    label = "Relay project",
                    primaryPath = "/workspace/relay",
                    sessionCount = 1,
                    previewSessions = emptyList(),
                ),
            ),
        )

        override suspend fun loadProjectSessions(
            projectId: ProjectId,
            profile: String?,
            sessionLimit: Int,
        ) = ProjectSessionsResult(
            project = ProjectSummary(
                id = projectId,
                label = "Relay project",
                primaryPath = "/workspace/relay",
                sessionCount = 1,
                previewSessions = emptyList(),
            ),
            sessions = listOf(
                com.unsupportedpastels.hermesandroid.app.SessionSummary(
                    id = DurableSessionId("relay-project-session-1"),
                    title = "Project session",
                ),
            ),
        )

        override suspend fun resume(durableSessionId: DurableSessionId, profile: String?): ResumedChatSession {
            resumeCalls += 1
            cancellableResumeBarrier?.await()
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { resumeBarrier?.await() }
            return ResumedChatSession(RuntimeSessionId("runtime"), durableSessionId, true, resumeMessages, resumeRunning, null)
        }

        override suspend fun createSession(
            durableSessionId: DurableSessionId,
            profile: String?,
            workspacePath: String?,
        ) = ResumedChatSession(RuntimeSessionId("runtime-draft"), durableSessionId, true, emptyList(), false, null)

        override suspend fun setReasoning(runtimeSessionId: RuntimeSessionId, effort: String) = Unit

        override suspend fun setFast(runtimeSessionId: RuntimeSessionId, enabled: Boolean) = Unit

        var lastSubmitted: String? = null
        var submitCalls = 0
        var submitFailure: Exception? = null
        var submitBarrier: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun submitPrompt(runtimeSessionId: RuntimeSessionId, text: String): PromptSubmission {
            submitCalls += 1
            submitBarrier?.await()
            submitFailure?.let { throw it }
            lastSubmitted = text
            return PromptSubmission("accepted")
        }
        override suspend fun close() { closeCalls += 1 }
    }

    private fun target() = RelayPairedTarget(
        id = "00000000-0000-4000-8000-000000000001",
        label = "Study",
        relayOrigin = "https://relay.example.com",
        installationId = ByteArray(32) { (it + 0x80).toByte() },
        hostPublicKey = RelayPlatformCrypto.x25519PublicKey(ByteArray(32) { (it + 0x20).toByte() }),
        deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() }),
        deviceStaticPrivateKey = ByteArray(32) { (it + 0x40).toByte() },
        fingerprint = "0123456789abcdef",
        status = RelayTargetStatus.Approved,
        createdAtEpochSeconds = 1,
        lastUsedEpochSeconds = null,
    )
}
