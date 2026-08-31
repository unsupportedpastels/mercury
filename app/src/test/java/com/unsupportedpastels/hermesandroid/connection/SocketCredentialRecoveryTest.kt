package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnector
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSocketClosedException
import com.unsupportedpastels.hermesandroid.gateway.InflightPrompt
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.SocketCloseClass
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.gateway.WsTicket
import com.unsupportedpastels.hermesandroid.gateway.WsTicketClient
import com.unsupportedpastels.hermesandroid.gateway.classifySocketClose
import com.unsupportedpastels.hermesandroid.voice.PcmSpeechSink
import com.unsupportedpastels.hermesandroid.voice.SpeechSocketFrame
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamConnector
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamOutcome
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamRun
import com.unsupportedpastels.hermesandroid.voice.SpeechStreamSocket
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Socket credential recovery (answers doc §21 and the design's *Credential
 * rotation*). Sockets reuse the REST recovery epoch rather than running a second
 * state machine, so a socket `4401` and a REST `401` carrying the same
 * credential must coalesce into one bootstrap, and the terminal-outcome marker
 * must suppress automatic socket recovery just as it does for reads.
 *
 * Chat recovery is driven by the event stream ending while a turn is in flight,
 * matching production — not by failing `resume` before a prompt is accepted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SocketCredentialRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = ServerOrigin.parse("http://127.0.0.1:19119")
    private val durableId = DurableSessionId("tunnel-1")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aRejectedChatSocketRecoversTheCredentialOnceAndReconnects() = runTest(dispatcher) {
        val rejected = PeerClosedChatSession(closeCode = 4401)
        val replacement = ResumingChatSession(runtimeId = "runtime-1")
        val connector = ScriptedChatConnector(listOf(rejected, replacement))
        val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token", "recovery-token"))
        val viewModel = viewModel(connector, bootstrap)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Hello there")
        advanceUntilIdle()

        assertEquals(2, bootstrap.calls)
        assertEquals(
            listOf("connect-token", "recovery-token"),
            connector.usedTokens,
        )
        assertEquals(1, replacement.resumeCalls)
        assertTrue(replacement.submittedPrompts.isEmpty())
        assertEquals(0, rejected.interruptCalls + replacement.interruptCalls)
        assertNull(viewModel.snapshots.value.tunnelConnectionFailure)
    }

    @Test
    fun aSecondChatSocketRejectionPublishesTheTerminalStateWithoutLooping() = runTest(dispatcher) {
        val connector = ScriptedChatConnector(
            listOf(
                PeerClosedChatSession(closeCode = 4401),
                RejectingChatSession(closeCode = 4401),
                RejectingChatSession(closeCode = 4401),
                RejectingChatSession(closeCode = 4401),
            ),
        )
        val bootstrap = TokenSequenceBootstrap(
            origin,
            listOf("connect-token", "recovery-token", "must-not-be-used"),
        )
        val viewModel = viewModel(connector, bootstrap)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Hello there")
        advanceUntilIdle()

        assertEquals(2, bootstrap.calls)
        assertEquals(
            TunnelConnectionFailure.CredentialRejected,
            viewModel.snapshots.value.tunnelConnectionFailure,
        )

        viewModel.sendMessage(durableId, "Another try")
        advanceUntilIdle()
        assertEquals(2, bootstrap.calls)
    }

    @Test
    fun noOtherCloseCodeRefreshesTheCredential() = runTest(dispatcher) {
        listOf(4403, 4404, 4408, 1011, 1006, null).forEach { code ->
            val replacement = ResumingChatSession(runtimeId = "runtime-heal")
            val connector = ScriptedChatConnector(
                listOf(PeerClosedChatSession(closeCode = code), replacement),
            )
            val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token"))
            val viewModel = viewModel(connector, bootstrap)
            advanceUntilIdle()

            viewModel.sendMessage(durableId, "Hello there")
            advanceUntilIdle()

            assertEquals("close code $code", 1, bootstrap.calls)
            assertEquals(
                "close code $code",
                null,
                viewModel.snapshots.value.tunnelConnectionFailure,
            )
            assertTrue(
                "close code $code",
                connector.usedTokens.all { it == "connect-token" },
            )
            assertEquals("close code $code", 1, replacement.resumeCalls)
        }
    }

    /**
     * A socket rejection and a REST rejection carrying the same credential are
     * one staleness, so they must share a single bootstrap. The recovery scrape is
     * held open so the socket can join the in-flight epoch; a count of 1 from
     * connect alone is not enough to pass.
     */
    @Test
    fun aSocketRejectionAndARestRejectionShareOneBootstrap() = runTest(dispatcher) {
        val client = FirstReadRejectingTunnelClient()
        val rejected = PeerClosedChatSession(closeCode = 4401)
        val replacement = ResumingChatSession(runtimeId = "runtime-1")
        val connector = ScriptedChatConnector(listOf(rejected, replacement))
        val bootstrap = GatedRecoveryBootstrap(origin, listOf("connect-token", "recovery-token"))
        val viewModel = viewModel(connector, bootstrap, client)
        advanceUntilIdle()
        assertEquals(1, bootstrap.calls)

        val read = async { runCatching { viewModel.loadHostFiles("/workspace") } }
        advanceUntilIdle()
        assertTrue("REST 401 must start the recovery scrape", bootstrap.recoveryStarted)
        assertEquals(2, bootstrap.calls)

        viewModel.sendMessage(durableId, "Hello there")
        advanceUntilIdle()

        bootstrap.release()
        advanceUntilIdle()
        val listing = read.await()
        assertTrue(listing.isSuccess)

        assertEquals(2, bootstrap.calls)
        assertEquals(
            listOf("connect-token", "recovery-token"),
            connector.usedTokens,
        )
        assertEquals(1, replacement.resumeCalls)
        assertTrue(replacement.submittedPrompts.isEmpty())
    }

    @Test
    fun aRejectedSpeechSocketRecoversWithoutReplayingBillableSpeech() = runTest(dispatcher) {
        val speech = RecordingSpeechConnector()
        val client = VoiceCapableTunnelClient()
        val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token", "recovery-token"))
        val viewModel = viewModel(
            connector = ScriptedChatConnector(emptyList()),
            bootstrap = bootstrap,
            client = client,
            speechConnector = speech,
        )
        advanceUntilIdle()

        viewModel.refreshVoiceCapabilities()
        advanceUntilIdle()

        val socket = viewModel.openSpeechStream()
        assertTrue("streaming speech must be available", socket != null)
        speech.sockets.single().closePeer(4401)
        val outcome = SpeechStreamRun(socket!!, NoOpSink(), Long.MAX_VALUE).pump()
        advanceUntilIdle()

        assertEquals(SpeechStreamOutcome.CredentialRejected, outcome)
        assertEquals(2, bootstrap.calls)
        assertEquals(0, client.speakCalls)

        val recovered = viewModel.openSpeechStream()
        assertTrue(recovered != null)
        assertEquals(
            listOf("connect-token", "recovery-token"),
            speech.usedTokens,
        )
        assertEquals(0, client.speakCalls)
    }

    @Test
    fun aSpeechPolicyCloseDoesNotRefreshTheCredential() = runTest(dispatcher) {
        val speech = RecordingSpeechConnector()
        val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token"))
        val viewModel = viewModel(
            connector = ScriptedChatConnector(emptyList()),
            bootstrap = bootstrap,
            client = VoiceCapableTunnelClient(),
            speechConnector = speech,
        )
        advanceUntilIdle()
        viewModel.refreshVoiceCapabilities()
        advanceUntilIdle()

        val socket = viewModel.openSpeechStream()
        assertTrue(socket != null)
        speech.sockets.single().closePeer(4403)
        val outcome = SpeechStreamRun(socket!!, NoOpSink(), Long.MAX_VALUE).pump()
        advanceUntilIdle()

        assertEquals(SpeechStreamOutcome.Fallback, outcome)
        assertEquals(1, bootstrap.calls)
        assertEquals(listOf("connect-token"), speech.usedTokens)
    }

    @Test
    fun aRejectedCronJobListRecoversOnceOnTheSharedEpoch() = runTest(dispatcher) {
        val jobs = listOf(CronJob("job-1", "Daily brief", "0 8 * * *", enabled = true))
        val projectConnector = ScriptedChatConnector(
            listOf(
                CronListSession(reject = true),
                CronListSession(reject = false, jobs = jobs),
            ),
        )
        val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token", "recovery-token"))
        val viewModel = viewModel(
            connector = ScriptedChatConnector(emptyList()),
            bootstrap = bootstrap,
            projectConnector = projectConnector,
        )
        advanceUntilIdle()

        viewModel.refreshCronJobs().join()
        advanceUntilIdle()

        assertEquals(2, bootstrap.calls)
        assertEquals(
            listOf("connect-token", "recovery-token"),
            projectConnector.usedTokens,
        )
        assertEquals(CronJobsState.Ready(jobs, profile = "default"), viewModel.snapshots.value.cronJobsState)
    }

    @Test
    fun aSecondMetadataSocketRejectionPublishesTheTerminalStateWithoutLooping() = runTest(dispatcher) {
        val projectConnector = ScriptedChatConnector(
            listOf(
                CronListSession(reject = true),
                CronListSession(reject = true),
                CronListSession(reject = false),
            ),
        )
        val bootstrap = TokenSequenceBootstrap(
            origin,
            listOf("connect-token", "recovery-token", "must-not-be-used"),
        )
        val viewModel = viewModel(
            connector = ScriptedChatConnector(emptyList()),
            bootstrap = bootstrap,
            projectConnector = projectConnector,
        )
        advanceUntilIdle()

        viewModel.refreshCronJobs().join()
        advanceUntilIdle()

        assertEquals(2, bootstrap.calls)
        assertEquals(
            TunnelConnectionFailure.CredentialRejected,
            viewModel.snapshots.value.tunnelConnectionFailure,
        )

        viewModel.refreshCronJobs().join()
        advanceUntilIdle()
        assertEquals(2, bootstrap.calls)
        assertEquals(
            TunnelConnectionFailure.CredentialRejected,
            viewModel.snapshots.value.tunnelConnectionFailure,
        )
    }

    @Test
    fun aChatSocketRejectionClosesSiblingLocalSocketsWithoutClosingTheRemoteRuntime() =
        runTest(dispatcher) {
            val metadata = CronListSession(reject = false)
            val rejected = PeerClosedChatSession(closeCode = 4401)
            val replacement = ResumingChatSession(runtimeId = "runtime-1")
            val speech = RecordingSpeechConnector()
            val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token", "recovery-token"))
            val viewModel = viewModel(
                connector = ScriptedChatConnector(listOf(rejected, replacement)),
                bootstrap = bootstrap,
                client = VoiceCapableTunnelClient(),
                speechConnector = speech,
                projectConnector = ScriptedChatConnector(listOf(metadata)),
            )
            advanceUntilIdle()

            viewModel.refreshCronJobs().join()
            advanceUntilIdle()
            viewModel.refreshVoiceCapabilities()
            advanceUntilIdle()
            val socket = viewModel.openSpeechStream()
            assertTrue(socket != null)

            viewModel.sendMessage(durableId, "Hello there")
            advanceUntilIdle()

            assertTrue("metadata socket must close", metadata.closeCalls >= 1)
            assertTrue("speech socket must close", speech.sockets.single().closeCalls >= 1)
            assertTrue("rejected chat socket must close", rejected.closeCalls >= 1)
            assertEquals(0, rejected.interruptCalls + replacement.interruptCalls)
            assertEquals(2, bootstrap.calls)
            assertNull(viewModel.snapshots.value.tunnelConnectionFailure)
        }

    @Test
    fun aStaleGenerationSocketRecoveryDoesNotPublish() = runTest(dispatcher) {
        val settings = MutableStateFlow<ServerSettingsState>(tunnelSettings())
        val rejected = PeerClosedChatSession(closeCode = 4401)
        val replacement = ResumingChatSession(runtimeId = "runtime-stale")
        val connector = ScriptedChatConnector(listOf(rejected, replacement))
        val bootstrap = GatedRecoveryBootstrap(
            origin,
            listOf("connect-token", "recovery-token", "next-generation-token"),
        )
        val viewModel = viewModel(connector, bootstrap, settingsStates = settings)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Hello there")
        advanceUntilIdle()
        assertTrue(bootstrap.recoveryStarted)

        settings.value = ServerSettingsState.Loading
        advanceUntilIdle()
        bootstrap.release()
        advanceUntilIdle()

        assertNull(viewModel.snapshots.value.tunnelConnectionFailure)

        settings.value = tunnelSettings()
        advanceUntilIdle()
        assertNull(viewModel.snapshots.value.tunnelConnectionFailure)
        assertTrue(replacement.resumeCalls == 0)
    }

    @Test
    fun aChatSocketRejectionDoesNotReplayControllerCommands() = runTest(dispatcher) {
        val rejected = PeerClosedChatSession(closeCode = 4401)
        val replacement = ResumingChatSession(runtimeId = "runtime-1")
        val connector = ScriptedChatConnector(listOf(rejected, replacement))
        val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token", "recovery-token"))
        val viewModel = viewModel(connector, bootstrap)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Hello there")
        advanceUntilIdle()

        assertEquals(1, replacement.resumeCalls)
        assertTrue(replacement.submittedPrompts.isEmpty())
        rejected.assertNoControllerReplay()
        replacement.assertNoControllerReplay()
        assertEquals(0, rejected.interruptCalls + replacement.interruptCalls)
    }

    @Test
    fun oauthSocketRejectionDoesNotCallTheLoopbackBootstrap() = runTest(dispatcher) {
        val oauthOrigin = ServerOrigin.parse("https://hermes.example")
        val bootstrap = TokenSequenceBootstrap(origin, listOf("must-not-be-used"))
        val tickets = RecordingTicketClient()
        val replacement = ResumingChatSession(runtimeId = "runtime-oauth")
        val connector = TicketMintingChatConnector(
            tickets,
            listOf(PeerClosedChatSession(closeCode = 4401), replacement),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(oauthOrigin)),
            client = OauthChatClient(),
            tokenStore = MemoryOauthTokenStore(),
            chatConnector = connector,
            loopbackSessionBootstrapClient = bootstrap,
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Hello there")
        advanceUntilIdle()

        assertEquals(0, bootstrap.calls)
        assertEquals(2, tickets.calls)
        assertEquals(1, replacement.resumeCalls)
        assertTrue(replacement.submittedPrompts.isEmpty())
        assertNull(viewModel.snapshots.value.tunnelConnectionFailure)
    }

    @Test
    fun fiveMinuteActiveTurnGiveUpDoesNotReplayThePrompt() = runTest(dispatcher) {
        val first = PeerClosedChatSession(closeCode = 1006)
        val connector = ScriptedChatConnector(listOf(first))
        val bootstrap = TokenSequenceBootstrap(origin, listOf("connect-token"))
        val viewModel = viewModel(
            connector,
            bootstrap,
            nowEpochMs = { dispatcher.scheduler.currentTime },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Hello there")
        advanceUntilIdle()

        advanceTimeBy(LIFECYCLE_RECOVERY_BUDGET_MS + 30_000L)
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.snapshots.value.connectionState)
        assertEquals(false, viewModel.snapshots.value.chatSessions[durableId]?.isSending)
        assertEquals(1, first.submitCalls)
    }

    private fun tunnelSettings() = ServerSettingsState.Ready(
        ServerCatalog.single(
            ServerCatalogEntry(origin, connectionMode = ServerConnectionMode.ExternalSshTunnel),
        ),
    )

    private fun viewModel(
        connector: ScriptedChatConnector,
        bootstrap: LoopbackSessionBootstrapClient,
        client: HermesConnectionClient = TunnelReadClient(),
        speechConnector: SpeechStreamConnector? = null,
        projectConnector: HermesChatConnector? = null,
        settingsStates: MutableStateFlow<ServerSettingsState> = MutableStateFlow<ServerSettingsState>(tunnelSettings()),
        nowEpochMs: () -> Long = { System.currentTimeMillis() },
    ) = HermesConnectionViewModel(
        settingsStates = settingsStates,
        client = client,
        chatConnector = connector,
        projectConnector = projectConnector,
        speechStreamConnector = speechConnector,
        loopbackSessionBootstrapClient = bootstrap,
        nowEpochMs = nowEpochMs,
    )
}

/** Reads the socket token through the same seam the transports use. */
private fun HermesCredential.tokenForAssertions(origin: ServerOrigin): String =
    (this as? HermesCredential.LoopbackSession)?.encodedWebSocketToken(origin) ?: "oauth"

private class ScriptedChatConnector(sessions: List<HermesChatSession>) : HermesChatConnector {
    private val remaining = ArrayDeque(sessions)
    val usedTokens = mutableListOf<String>()

    override suspend fun connect(
        origin: ServerOrigin,
        credential: HermesCredential,
    ): HermesChatSession {
        usedTokens += credential.tokenForAssertions(origin)
        return remaining.removeFirstOrNull()
            ?: throw HermesChatSocketClosedException(SocketCloseClass.TransportFailure, null)
    }
}

/** Mints a fresh OAuth ticket on every connect, matching production gateway routing. */
private class TicketMintingChatConnector(
    private val tickets: RecordingTicketClient,
    sessions: List<HermesChatSession>,
) : HermesChatConnector {
    private val remaining = ArrayDeque(sessions)

    override suspend fun connect(
        origin: ServerOrigin,
        credential: HermesCredential,
    ): HermesChatSession {
        if (credential is HermesCredential.NativeBearer) {
            tickets.mintTicket(origin, credential)
        }
        return remaining.removeFirstOrNull()
            ?: throw HermesChatSocketClosedException(SocketCloseClass.TransportFailure, null)
    }
}

private class RecordingTicketClient : WsTicketClient {
    var calls = 0
        private set

    override suspend fun mintTicket(
        origin: ServerOrigin,
        credential: HermesCredential.NativeBearer,
    ): WsTicket {
        calls += 1
        return WsTicket(ticket = "ticket-$calls", ttlSeconds = 30)
    }
}

/**
 * Accepts the prompt, then the peer closes the event stream. Recovery is the
 * event-loop path, not a failed resume before the turn is accepted.
 */
private class PeerClosedChatSession(private val closeCode: Int?) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    var interruptCalls = 0
        private set
    var closeCalls = 0
        private set
    var submitCalls = 0
        private set
    private val replay = ControllerReplayProbe()

    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override val closeClass: SocketCloseClass?
        get() = classifySocketClose(closeCode)

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-live"),
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = InflightPrompt(null, null, false),
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submitCalls += 1
        channel.close()
        return PromptSubmission("streaming")
    }

    override suspend fun interruptSession(
        runtimeSessionId: RuntimeSessionId,
    ): com.unsupportedpastels.hermesandroid.gateway.HermesChatResponse {
        interruptCalls += 1
        throw AssertionError("must not interrupt the shared runtime")
    }

    override suspend fun respondToApproval(
        runtimeSessionId: RuntimeSessionId,
        choice: String,
        all: Boolean,
        requestId: String?,
    ) = replay.approval()

    override suspend fun respondToBlockingPrompt(
        kind: UnsupportedBlockingKind,
        requestId: String,
        value: String,
    ) = replay.blockingPrompt()

    override suspend fun attachFile(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        mimeType: String,
        base64Content: String,
    ) = replay.attachFile()

    override suspend fun attachImage(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        base64Content: String,
    ) = replay.attachImage()

    fun assertNoControllerReplay() = replay.assertUnused()

    override suspend fun close() {
        closeCalls += 1
    }
}

/** Resume/create is refused with the given close code. */
private class RejectingChatSession(private val closeCode: Int?) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)

    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override val closeClass: SocketCloseClass?
        get() = classifySocketClose(closeCode)

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = throw closure()

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = throw closure()

    override suspend fun close() {
        channel.close()
    }

    private fun closure() = HermesChatSocketClosedException(
        classifySocketClose(closeCode),
        closeCode,
    )
}

/** A healthy replacement session: resume reconciles, nothing is replayed. */
private class ResumingChatSession(private val runtimeId: String) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    var resumeCalls = 0
        private set
    var interruptCalls = 0
        private set
    val submittedPrompts = mutableListOf<String>()
    private val replay = ControllerReplayProbe()

    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeCalls += 1
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId(runtimeId),
            durableSessionId = durableSessionId,
            resumed = true,
            messages = emptyList(),
            running = false,
            inflight = InflightPrompt(null, null, false),
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submittedPrompts += text
        return PromptSubmission("streaming")
    }

    override suspend fun interruptSession(
        runtimeSessionId: RuntimeSessionId,
    ): com.unsupportedpastels.hermesandroid.gateway.HermesChatResponse {
        interruptCalls += 1
        throw AssertionError("must not interrupt the shared runtime")
    }

    override suspend fun respondToApproval(
        runtimeSessionId: RuntimeSessionId,
        choice: String,
        all: Boolean,
        requestId: String?,
    ) = replay.approval()

    override suspend fun respondToBlockingPrompt(
        kind: UnsupportedBlockingKind,
        requestId: String,
        value: String,
    ) = replay.blockingPrompt()

    override suspend fun attachFile(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        mimeType: String,
        base64Content: String,
    ) = replay.attachFile()

    override suspend fun attachImage(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        base64Content: String,
    ) = replay.attachImage()

    fun assertNoControllerReplay() = replay.assertUnused()

    override suspend fun close() {
        channel.close()
    }
}

private class ControllerReplayProbe {
    var approvalCalls = 0
        private set
    var blockingPromptCalls = 0
        private set
    var attachFileCalls = 0
        private set
    var attachImageCalls = 0
        private set

    fun approval(): Nothing {
        approvalCalls += 1
        throw AssertionError("must not replay approvals")
    }

    fun blockingPrompt(): Nothing {
        blockingPromptCalls += 1
        throw AssertionError("must not replay terminal or sudo input")
    }

    fun attachFile(): Nothing {
        attachFileCalls += 1
        throw AssertionError("must not replay file attachments")
    }

    fun attachImage() {
        attachImageCalls += 1
        throw AssertionError("must not replay image attachments")
    }

    fun assertUnused() {
        assertEquals(0, approvalCalls)
        assertEquals(0, blockingPromptCalls)
        assertEquals(0, attachFileCalls)
        assertEquals(0, attachImageCalls)
    }
}

private class CronListSession(
    private val reject: Boolean,
    private val jobs: List<CronJob> = emptyList(),
) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    var closeCalls = 0
        private set
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override val closeClass: SocketCloseClass?
        get() = if (reject) SocketCloseClass.CredentialRejected else null

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = throw AssertionError("cron list must not resume a runtime")

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = throw AssertionError("cron list must not submit a prompt")

    override suspend fun loadCronJobs(): List<CronJob> {
        if (reject) {
            throw HermesChatSocketClosedException(SocketCloseClass.CredentialRejected, 4401)
        }
        return jobs
    }

    override suspend fun close() {
        closeCalls += 1
        channel.close()
    }
}

private open class TunnelReadClient : HermesConnectionClient {
    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
        throw AssertionError("Tunnel connections use the public-only probe")

    override suspend fun probeExternalTunnel(serverOrigin: ServerOrigin): HermesConnectionInfo =
        HermesConnectionInfo("0.20.4", false, false, emptyList())

    override suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean,
    ): List<SessionSummary> = listOf(SessionSummary(DurableSessionId("tunnel-1"), "Tunnel session"))

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ) = emptyList<com.unsupportedpastels.hermesandroid.gateway.ChatMessage>()
}

private class VoiceCapableTunnelClient : TunnelReadClient() {
    var speakCalls = 0
        private set

    override suspend fun probeVoiceCapabilities(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): VoiceCapabilities = VoiceCapabilities(
        audioRoutesPresent = true,
        elevenLabsVoicesAvailable = false,
    )

    override suspend fun speakText(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        text: String,
    ): com.unsupportedpastels.hermesandroid.voice.SpeechAudio {
        speakCalls += 1
        throw AssertionError("billable REST speech must not run")
    }
}

private class FirstReadRejectingTunnelClient : TunnelReadClient() {
    private var reads = 0

    override suspend fun loadHostFiles(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String?,
    ): HostFileListing {
        reads += 1
        if (reads == 1) throw HermesAuthenticationRejectedException("Hermes files returned HTTP 401")
        return HostFileListing(path.orEmpty(), emptyList())
    }
}

private class OauthChatClient : HermesConnectionClient {
    private val durableId = DurableSessionId("tunnel-1")

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.4",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ) = AuthenticatedHermesConnection(
        userId = "user-1",
        sessions = listOf(SessionSummary(durableId, "OAuth session")),
    )

    override suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean,
    ): List<SessionSummary> = listOf(SessionSummary(durableId, "OAuth session"))

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ) = emptyList<com.unsupportedpastels.hermesandroid.gateway.ChatMessage>()
}

private class MemoryOauthTokenStore : NativeTokenStore {
    private val tokens = NativeTokenSet(
        accessToken = "opaque-access",
        refreshToken = "opaque-refresh",
        expiresAt = 2_000_000_000,
        provider = "nous",
        userId = "user-1",
    )

    override suspend fun load(serverOrigin: ServerOrigin): NativeTokenSet = tokens
    override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) = Unit
    override suspend fun clear(serverOrigin: ServerOrigin) = Unit
}

private class TokenSequenceBootstrap(
    private val origin: ServerOrigin,
    tokens: List<String>,
) : LoopbackSessionBootstrapClient {
    private val remaining = ArrayDeque(tokens)
    var calls = 0
        private set

    override suspend fun bootstrap(origin: ServerOrigin): LoopbackSessionBootstrapResult {
        calls += 1
        val token = remaining.removeFirstOrNull()
            ?: return LoopbackSessionBootstrapResult.Failure(LoopbackSessionBootstrapFailure.TokenAbsent)
        return LoopbackSessionBootstrapResult.Success(
            HermesCredential.LoopbackSession.create(this.origin, token),
        )
    }
}

/**
 * Connect bootstrap completes immediately. The first recovery scrape is held
 * open so a second rejection can join the same epoch.
 */
private class GatedRecoveryBootstrap(
    private val origin: ServerOrigin,
    tokens: List<String>,
) : LoopbackSessionBootstrapClient {
    private val remaining = ArrayDeque(tokens)
    private val gate = CompletableDeferred<Unit>()
    var calls = 0
        private set
    var recoveryStarted = false
        private set

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun bootstrap(origin: ServerOrigin): LoopbackSessionBootstrapResult {
        calls += 1
        val token = remaining.removeFirstOrNull()
            ?: return LoopbackSessionBootstrapResult.Failure(LoopbackSessionBootstrapFailure.TokenAbsent)
        if (calls > 1) {
            recoveryStarted = true
            gate.await()
        }
        return LoopbackSessionBootstrapResult.Success(
            HermesCredential.LoopbackSession.create(this.origin, token),
        )
    }
}

private class RecordingSpeechConnector : SpeechStreamConnector {
    val sockets = mutableListOf<CodedSpeechSocket>()
    val usedTokens = mutableListOf<String>()

    override suspend fun connect(
        origin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): SpeechStreamSocket {
        usedTokens += credential.tokenForAssertions(origin)
        return CodedSpeechSocket().also(sockets::add)
    }
}

private class CodedSpeechSocket : SpeechStreamSocket {
    private val inbound = Channel<SpeechSocketFrame?>(Channel.UNLIMITED)

    @Volatile
    private var peerCloseCode: Int? = null
    var closeCalls = 0
        private set

    override suspend fun sendText(text: String) = Unit

    override suspend fun receiveFrame(): SpeechSocketFrame? = inbound.receive()

    override suspend fun closeCode(): Int? = peerCloseCode

    override suspend fun close() {
        closeCalls += 1
        inbound.trySend(null)
    }

    fun closePeer(code: Int?) {
        peerCloseCode = code
        inbound.trySend(null)
    }
}

private class NoOpSink : PcmSpeechSink {
    override fun start(sampleRateHz: Int, channels: Int): Boolean = true

    override suspend fun write(pcm: ByteArray) = Unit

    override suspend fun finish() = Unit

    override fun stop() = Unit
}
