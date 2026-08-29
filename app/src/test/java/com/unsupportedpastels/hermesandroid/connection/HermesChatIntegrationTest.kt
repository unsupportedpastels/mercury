package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.attachment.AttachmentByteReader
import com.unsupportedpastels.hermesandroid.attachment.AttachmentReadException
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.DelegationPauseResult
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnector
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatResponse
import com.unsupportedpastels.hermesandroid.gateway.HermesChatResponseStatus
import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.hermesandroid.gateway.InflightPrompt
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.ContextBreakdownCategory
import com.unsupportedpastels.hermesandroid.gateway.SessionBranchResult
import com.unsupportedpastels.hermesandroid.gateway.SessionCompressResult
import com.unsupportedpastels.hermesandroid.gateway.SessionContextBreakdown
import com.unsupportedpastels.hermesandroid.gateway.SessionSteerResult
import com.unsupportedpastels.hermesandroid.gateway.SessionUndoResult
import com.unsupportedpastels.hermesandroid.gateway.SessionUsage
import com.unsupportedpastels.hermesandroid.gateway.SubagentInterruptResult
import com.unsupportedpastels.hermesandroid.gateway.SubagentSteerResult
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionItem
import com.unsupportedpastels.hermesandroid.gateway.SlashCompletionResult
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.notifications.TurnNotificationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class HermesChatIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    private val origin = ServerOrigin.parse("https://hermes.example")
    private val durableId = DurableSessionId("durable-1")
    private val tokens = NativeTokenSet(
        accessToken = "opaque-access",
        refreshToken = "opaque-refresh",
        expiresAt = 2_000_000_000,
        provider = "nous",
        userId = "user-1",
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun storedAuthenticationIsRestoredAndSelectedTranscriptLoads() = runTest(dispatcher) {
        val client = ChatConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )

        advanceUntilIdle()
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(listOf("Earlier question", "Earlier answer"), chat.messages.map { it.text })
        assertFalse(chat.isLoading)
        assertTrue(client.transcriptAccessToken is HermesCredential.NativeBearer)
    }

    @Test
    fun rejectedPersistedAuthenticationClearsTokensAndRequiresSignIn() = runTest(dispatcher) {
        val tokenStore = MemoryTokenStore(tokens)
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = RejectedAuthenticationClient(),
            tokenStore = tokenStore,
            nowEpochSeconds = { 1_900_000_000 },
        )

        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
        assertNull(tokenStore.load(origin))
    }

    @Test
    fun sendResumesDurableSessionAndReducesStreamedAssistantText() = runTest(dispatcher) {
        val session = StreamingChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { requestedOrigin, credential ->
                assertEquals(origin, requestedOrigin)
                val request = io.ktor.client.request.HttpRequestBuilder()
                request.applyHermesCredential(credential, requestedOrigin)
                assertEquals(
                    "Bearer opaque-access",
                    request.headers[io.ktor.http.HttpHeaders.Authorization],
                )
                session
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "New question")
        runCurrent()
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(
            listOf(ChatMessageRole.User, ChatMessageRole.Assistant),
            chat.messages.takeLast(2).map { it.role },
        )
        assertEquals(listOf("New question", "Hello world"), chat.messages.takeLast(2).map { it.text })
        assertFalse(chat.isSending)
        assertEquals(durableId, session.resumedDurableId)
        assertEquals("New question", session.submittedText)
    }

    @Test
    fun sendMessageStagesFileAttachmentBeforeSubmitAndPrependsRefText() = runTest(dispatcher) {
        val session = StreamingChatSession()
        val viewModel = chatViewModel(
            session,
            attachmentReader = AttachmentByteReader { "hello".toByteArray() },
        )
        advanceUntilIdle()

        viewModel.addAttachments(
            durableId,
            listOf(ComposerAttachment("a1", "content://provider/report", "report.txt", "text/plain", 5)),
        )
        assertEquals(1, viewModel.attachments.value[durableId].orEmpty().size)

        viewModel.sendMessage(durableId, "summarize")
        advanceUntilIdle()

        assertEquals(
            listOf(Triple("report.txt", "text/plain", Base64.getEncoder().encodeToString("hello".toByteArray()))),
            session.fileAttachCalls,
        )
        assertEquals("@file:.hermes/desktop-attachments/report.txt\n\nsummarize", session.submittedText)
        assertTrue(session.imageAttachCalls.isEmpty())
        // Chips clear once staging + submit are underway.
        assertTrue(viewModel.attachments.value[durableId].orEmpty().isEmpty())
        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
    }

    @Test
    fun sendMessageStagesImageAttachmentToRideNextPrompt() = runTest(dispatcher) {
        val session = StreamingChatSession()
        val viewModel = chatViewModel(
            session,
            attachmentReader = AttachmentByteReader { "pngbytes".toByteArray() },
        )
        advanceUntilIdle()

        viewModel.addAttachments(
            durableId,
            listOf(ComposerAttachment("a1", "content://provider/photo", "photo.png", "image/png", 8)),
        )
        viewModel.sendMessage(durableId, "what is this")
        advanceUntilIdle()

        assertEquals(
            listOf("photo.png" to Base64.getEncoder().encodeToString("pngbytes".toByteArray())),
            session.imageAttachCalls,
        )
        assertTrue(session.fileAttachCalls.isEmpty())
        assertEquals("what is this", session.submittedText)
        assertTrue(viewModel.attachments.value[durableId].orEmpty().isEmpty())
    }

    @Test
    fun stagingFailureKeepsDraftEditableAndChipsIntact() = runTest(dispatcher) {
        val session = StreamingChatSession()
        val viewModel = chatViewModel(
            session,
            attachmentReader = AttachmentByteReader { throw AttachmentReadException("provider unreachable") },
        )
        advanceUntilIdle()

        viewModel.addAttachments(
            durableId,
            listOf(ComposerAttachment("a1", "content://provider/report", "report.txt", "text/plain", 5)),
        )
        viewModel.sendMessage(durableId, "summarize")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertTrue(chat.error.orEmpty().contains("provider unreachable"))
        assertNull(session.submittedText)
        assertTrue(session.fileAttachCalls.isEmpty())
        // No user bubble was appended and the chips survive for a retry.
        assertFalse(chat.messages.any { it.role == ChatMessageRole.User })
        assertTrue(viewModel.attachments.value[durableId].orEmpty().isNotEmpty())
    }

    @Test
    fun promptRejectionAfterFileStagingKeepsAttachmentForRetry() = runTest(dispatcher) {
        val session = StreamingChatSession(
            submitFailure = HermesChatProtocolException("prompt rejected"),
        )
        val viewModel = chatViewModel(
            session,
            attachmentReader = AttachmentByteReader { "hello".toByteArray() },
        )
        advanceUntilIdle()

        viewModel.addAttachments(
            durableId,
            listOf(ComposerAttachment("a1", "content://provider/report", "report.txt", "text/plain", 5)),
        )
        viewModel.sendMessage(durableId, "summarize")
        advanceUntilIdle()

        assertEquals(1, session.fileAttachCalls.size)
        assertTrue(viewModel.attachments.value[durableId].orEmpty().isNotEmpty())
        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertTrue(chat.error != null)
        assertFalse(chat.isSending)
    }

    @Test
    fun rejectedPromptDoesNotStartAPhantomActiveTurn() = runTest(dispatcher) {
        val session = StreamingChatSession(
            submitFailure = HermesChatProtocolException("prompt rejected"),
        )
        val notifications = RecordingTurnNotificationController()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> session },
            nowEpochSeconds = { 1_900_000_000 },
            notifications = notifications,
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Summon a response")
        advanceUntilIdle()

        // The prompt was rejected by the server, so no turn was accepted and the
        // foreground "working" notification must not be started for this session.
        assertFalse(notifications.turnStarts.any { it == durableId })
        assertEquals(0, notifications.lastActiveCount)
    }

    @Test
    fun openingASessionWithARunningTurnCountsItActiveAndReleasesOnCompletion() = runTest(dispatcher) {
        var liveChannel: Channel<HermesChatEvent>? = null
        var liveRuntime: RuntimeSessionId? = null
        val session = ReconnectingChatSession(
            runtimeId = "runtime-adopted",
            running = true,
            inflightText = "partial from another client",
            inflightUser = "Earlier prompt",
            onResume = { channel, runtime ->
                liveChannel = channel
                liveRuntime = runtime
            },
        )
        val notifications = RecordingTurnNotificationController()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> session },
            nowEpochSeconds = { 1_900_000_000 },
            notifications = notifications,
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        // A turn already running on the server (started elsewhere or before an app
        // restart) is an active turn for this client too: it must be counted so the
        // foreground service anchors event delivery while the app is backgrounded.
        assertTrue(notifications.turnStarts.any { it == durableId })
        assertEquals(1, notifications.lastActiveCount)

        liveChannel?.trySend(
            HermesChatEvent.MessageComplete(checkNotNull(liveRuntime), "done elsewhere", "complete"),
        )
        liveChannel?.close()
        advanceUntilIdle()

        assertEquals(0, notifications.lastActiveCount)
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)
    }

    @Test
    fun recoveryGiveUpReleasesTheActiveTurnCount() = runTest(dispatcher) {
        val first = ReconnectingChatSession(
            runtimeId = "runtime-lost",
            running = false,
            inflightText = null,
            onSubmit = { channel, runtime ->
                channel.trySend(HermesChatEvent.MessageDelta(runtime, "partial before loss"))
                channel.close()
            },
        )
        var connections = 0
        val notifications = RecordingTurnNotificationController()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                if (connections == 1) first else throw HermesChatTransportException("still offline")
            },
            nowEpochSeconds = { 1_900_000_000 },
            notifications = notifications,
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Keep working")
        runCurrent()
        assertEquals(1, notifications.lastActiveCount)
        advanceUntilIdle()

        // Every reconnect attempt failed; the turn is over from this client's view,
        // so the ongoing "working" notification must be released, not stuck on.
        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertEquals(0, notifications.lastActiveCount)
    }

    @Test
    fun stagingFailureOnFreshDraftTearsDownTheRuntime() = runTest(dispatcher) {
        val session = StreamingChatSession()
        val viewModel = chatViewModel(
            session,
            attachmentReader = AttachmentByteReader { throw AttachmentReadException("provider unreachable") },
        )
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        viewModel.addAttachments(
            draftId,
            listOf(ComposerAttachment("a1", "content://provider/report", "report.txt", "text/plain", 5)),
        )
        viewModel.openSession(draftId)
        advanceUntilIdle()

        viewModel.sendMessage(draftId, "summarize")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(draftId)
        assertFalse(chat.isSending)
        assertTrue(chat.error != null)
        // The fresh draft runtime was torn down so the next send starts clean.
        assertTrue(session.closed)
        assertTrue(viewModel.attachments.value[draftId].orEmpty().isNotEmpty())
    }

    @Test
    fun protocolFailureAfterPromptStagingFinalizesPlaceholder() = runTest(dispatcher) {
        val session = ReconnectingChatSession(
            runtimeId = "runtime-protocol-error",
            running = false,
            inflightText = null,
            submitFailure = HermesChatProtocolException("invalid response"),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.any { it.isStreaming })
        assertTrue(chat.error != null)
    }

    @Test
    fun terminalErrorCompletionStopsStreamingAndShowsFailure() = runTest(dispatcher) {
        val session = TerminalEventChatSession { runtime ->
            HermesChatEvent.MessageComplete(
                sessionId = runtime,
                text = "partial response",
                status = "error",
                error = "provider detail",
            )
        }
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.last().isStreaming)
        assertEquals("Hermes response failed", chat.error)
    }

    @Test
    fun standaloneErrorEventStopsStreamingAssistant() = runTest(dispatcher) {
        val session = TerminalEventChatSession { runtime ->
            HermesChatEvent.Error(runtime, "temporary failure")
        }
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.last().isStreaming)
        assertEquals("temporary failure", chat.error)
    }

    @Test
    fun slashCompletionPublishesItemsForSlashComposerText() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(
                items = listOf(SlashCompletionItem("goal", "/goal", "Set a standing goal")),
                replaceFrom = 1,
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.updateSlashCompletion(durableId, "/go")
        advanceUntilIdle()

        assertEquals(listOf("/go"), session.completionRequests)
        val state = viewModel.slashCompletions.value[durableId]
        assertEquals("/go", state?.composerText)
        assertEquals(1, state?.replaceFrom)
        assertEquals(listOf("/goal"), state?.items?.map { it.display })
    }

    @Test
    fun slashCompletionIgnoresNonSlashComposerText() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(emptyList(), 0),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.updateSlashCompletion(durableId, "/home/user/file")
        viewModel.updateSlashCompletion(durableId, "hello")
        advanceUntilIdle()

        assertTrue(session.completionRequests.isEmpty())
        assertNull(viewModel.slashCompletions.value[durableId])
    }

    @Test
    fun staleSlashCompletionResultIsDiscardedAfterTextChanges() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(
                items = listOf(SlashCompletionItem("goal", "/goal", null)),
                replaceFrom = 1,
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.updateSlashCompletion(durableId, "/go")
        runCurrent()
        // Composer moves on before the response lands; the stale result must not publish.
        viewModel.updateSlashCompletion(durableId, "/goa")
        advanceUntilIdle()

        val state = viewModel.slashCompletions.value[durableId]
        assertTrue(state == null || state.composerText == "/goa")
    }

    @Test
    fun switchingSessionsClearsSlashCompletion() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(
                items = listOf(SlashCompletionItem("goal", "/goal", null)),
                replaceFrom = 1,
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.updateSlashCompletion(durableId, "/go")
        advanceUntilIdle()
        assertTrue(viewModel.slashCompletions.value.containsKey(durableId))

        viewModel.clearSlashCompletion(durableId)
        advanceUntilIdle()
        assertNull(viewModel.slashCompletions.value[durableId])
    }

    @Test
    fun backgroundControllerCompletesSlashWithoutDependingOnSelectedSession() = runTest(dispatcher) {
        val firstId = DurableSessionId("slash-first")
        val secondId = DurableSessionId("slash-second")
        val first = CompletableSlashChatSession(
            result = SlashCompletionResult(
                items = listOf(SlashCompletionItem("goal", "/goal", null)),
                replaceFrom = 1,
            ),
        )
        val second = CompletableSlashChatSession(
            result = SlashCompletionResult(emptyList(), 0),
        )
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(firstId)
        advanceUntilIdle()
        viewModel.openSession(secondId)
        advanceUntilIdle()

        viewModel.updateSlashCompletion(firstId, "/go")
        advanceUntilIdle()

        assertEquals(listOf("/go"), first.completionRequests)
        assertEquals("/goal", viewModel.slashCompletions.value[firstId]?.items?.single()?.display)
    }

    @Test
    fun createNewSessionAddsExplicitUnscopedDraftWithoutOpeningRuntime() = runTest(dispatcher) {
        val session = CompletableSlashChatSession(
            result = SlashCompletionResult(emptyList(), 0),
        )
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                session
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        val draft = snapshot.durableSessions.first { it.id == draftId }
        assertEquals("New chat", draft.title)
        assertNull(draft.projectId)
        assertNull(draft.workspacePath)
        assertTrue(draft.isLocalDraft)

        viewModel.openSession(draftId)
        advanceUntilIdle()

        assertEquals(0, connections)
        assertNull(session.createdForDurableId)
    }

    @Test
    fun createNewSessionRefreshesListAfterFirstSend() = runTest(dispatcher) {
        val client = ChatConnectionClient()
        val session = StreamingChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> session },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()
        viewModel.openSession(draftId)
        advanceUntilIdle()

        val loadsBefore = client.transcriptLoads
        viewModel.sendMessage(draftId, "First prompt")
        advanceUntilIdle()

        assertEquals("First prompt", session.submittedText)
        assertFalse(viewModel.snapshots.value.chatSessions[draftId]?.isSending ?: true)
        assertTrue(client.transcriptLoads > loadsBefore)
    }

    @Test
    fun closedDraftTransportReconnectsUsingServerDurableIdBeforeStagingAttachment() = runTest(dispatcher) {
        val client = ChatConnectionClient()
        val canonicalId = DurableSessionId("server-canonical-draft")
        val first = CanonicalClosingDraftSession(canonicalId)
        val second = StreamingChatSession()
        val candidates = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> candidates.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
            attachmentReader = AttachmentByteReader { byteArrayOf(7, 8, 9) },
        )
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        viewModel.openSession(draftId)
        advanceUntilIdle()
        viewModel.sendMessage(draftId, "First prompt")
        advanceUntilIdle()

        assertEquals(canonicalId, client.lastTranscriptDurableId)
        first.closeEvents()
        advanceUntilIdle()
        viewModel.addAttachments(
            draftId,
            listOf(
                ComposerAttachment(
                    id = "doc",
                    uri = "content://picker/doc",
                    displayName = "note.txt",
                    mimeType = "text/plain",
                    sizeBytes = 3,
                ),
            ),
        )
        viewModel.sendMessage(draftId, "Second prompt")
        advanceUntilIdle()

        assertEquals(canonicalId, second.resumedDurableId)
        assertEquals("Second prompt", second.submittedText?.lineSequence()?.last())
        assertEquals(listOf("note.txt"), second.fileAttachCalls.map { it.first })
        assertTrue(viewModel.attachments.value[draftId].orEmpty().isEmpty())
    }

    @Test
    fun acceptedDraftPromptResumesCanonicalSessionAfterTransportClosesBeforeCompletion() =
        runTest(dispatcher) {
            val canonicalId = DurableSessionId("server-canonical-accepted")
            val first = CanonicalClosingDraftSession(
                canonicalId = canonicalId,
                completeFirstTurn = false,
            )
            val recovery = StreamingChatSession()
            val secondSend = StreamingChatSession()
            val candidates = ArrayDeque<HermesChatSession>().apply {
                add(first)
                add(recovery)
                add(secondSend)
            }
            val viewModel = HermesConnectionViewModel(
                settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
                client = ChatConnectionClient(),
                tokenStore = MemoryTokenStore(tokens),
                chatConnector = HermesChatConnector { _, _ -> candidates.removeFirst() },
                nowEpochSeconds = { 1_900_000_000 },
            )
            advanceUntilIdle()

            val draftId = viewModel.createNewSession()
            viewModel.sendMessage(draftId, "First prompt")
            advanceUntilIdle()
            first.closeEvents()
            advanceUntilIdle()

            viewModel.sendMessage(draftId, "Second prompt")
            advanceUntilIdle()

            assertEquals(canonicalId, recovery.resumedDurableId)
            assertEquals(canonicalId, secondSend.resumedDurableId)
            assertEquals("Second prompt", secondSend.submittedText)
        }

    @Test
    fun openingAnotherSessionDoesNotCancelConcurrentDraftCreation() = runTest(dispatcher) {
        val candidate = CancellableCreateChatSession()
        val replacement = StreamingChatSession()
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(candidate)
            add(replacement)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        viewModel.sendMessage(draftId, "Start creation")
        runCurrent()
        candidate.createStarted.await()

        viewModel.openSession(DurableSessionId("replacement"))
        advanceUntilIdle()

        assertFalse(candidate.closeStarted)
        assertFalse(candidate.closeCompleted)
    }

    private fun chatViewModel(
        session: HermesChatSession,
        client: HermesConnectionClient = ChatConnectionClient(),
        attachmentReader: AttachmentByteReader =
            AttachmentByteReader { error("no attachment reads expected") },
    ) = HermesConnectionViewModel(
        settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
        client = client,
        tokenStore = MemoryTokenStore(tokens),
        chatConnector = HermesChatConnector { _, _ -> session },
        nowEpochSeconds = { 1_900_000_000 },
        attachmentReader = attachmentReader,
    )

    @Test
    fun cancellingResumeClosesUnpublishedSocketSession() = runTest(dispatcher) {
        val session = BlockingResumeChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        runCurrent()
        assertTrue(session.resumeStarted.isCompleted)

        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertTrue(session.closed)
    }

    @Test
    fun openingAnotherSessionPreservesStagedConcurrentSend() = runTest(dispatcher) {
        val session = BlockingSubmitChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Question")
        runCurrent()
        assertTrue(session.submitStarted.isCompleted)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)

        viewModel.openSession(DurableSessionId("durable-2"))
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertTrue(chat.isSending)
        assertTrue(chat.messages.any { it.isStreaming })
    }

    @Test
    fun twoHamStartedSessionsStreamAndCompleteIndependently() = runTest(dispatcher) {
        val firstId = DurableSessionId("durable-first")
        val secondId = DurableSessionId("durable-second")
        val first = RunEventChatSession("runtime-first")
        val second = RunEventChatSession("runtime-second")
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(firstId, "Run the first task")
        runCurrent()
        viewModel.sendMessage(secondId, "Run the second task")
        runCurrent()

        first.emit(HermesChatEvent.MessageDelta(first.runtimeSessionId, "first partial"))
        second.emit(HermesChatEvent.MessageDelta(second.runtimeSessionId, "second partial"))
        runCurrent()

        assertEquals(
            "first partial",
            viewModel.snapshots.value.chatSessions.getValue(firstId).messages.last().text,
        )
        assertEquals(
            "second partial",
            viewModel.snapshots.value.chatSessions.getValue(secondId).messages.last().text,
        )
        assertEquals(2, viewModel.snapshots.value.activeRuntimes.size)

        first.emit(HermesChatEvent.MessageComplete(first.runtimeSessionId, "first done", "complete"))
        runCurrent()
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(firstId).isSending)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(secondId).isSending)

        second.emit(HermesChatEvent.MessageComplete(second.runtimeSessionId, "second done", "complete"))
        runCurrent()
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(secondId).isSending)
        // Both controllers are retained after completion, so both runtime markers persist.
        assertEquals(
            setOf(first.runtimeSessionId, second.runtimeSessionId),
            viewModel.snapshots.value.activeRuntimes.map { it.runtimeSessionId }.toSet(),
        )
    }

    @Test
    fun backgroundedStreamingDisconnectWaitsForForegroundBeforeReconnect() = runTest(dispatcher) {
        val foreground = MutableStateFlow(true)
        val first = ReconnectingChatSession(
            runtimeId = "runtime-backgrounded",
            running = false,
            inflightText = null,
            onSubmit = { channel, runtime ->
                channel.trySend(HermesChatEvent.MessageDelta(runtime, "partial before background"))
                channel.close()
            },
        )
        val recovered = ReconnectingChatSession(
            runtimeId = "runtime-foreground-recovered",
            running = true,
            inflightText = "authoritative recovered partial",
            inflightUser = "Keep working",
            onResume = { channel, runtime ->
                channel.trySend(
                    HermesChatEvent.MessageComplete(
                        runtime,
                        "completed after foreground",
                        "complete",
                    ),
                )
                channel.close()
            },
        )
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(recovered)
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
            appForegroundStates = foreground,
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Keep working")
        runCurrent()
        foreground.value = false
        advanceTimeBy(10_000)
        runCurrent()

        val backgrounded = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(1, connections)
        assertTrue(backgrounded.isSending)
        assertEquals(null, backgrounded.error)
        assertTrue(backgrounded.messages.last().isStreaming)

        foreground.value = true
        advanceUntilIdle()

        val foregrounded = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(2, connections)
        assertEquals("completed after foreground", foregrounded.messages.last().text)
        assertFalse(foregrounded.isSending)
        assertEquals(null, foregrounded.error)
    }

    @Test
    fun foregroundReconnectsAfterTransientConnectFailure() = runTest(dispatcher) {
        val foreground = MutableStateFlow(true)
        val client = FailThenSucceedConnectionClient(failuresBeforeSuccess = 1)
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
            appForegroundStates = foreground,
        )

        // The initial connect hits a transient failure and lands Disconnected —
        // exactly the "Offline" state seen after resuming from background.
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, viewModel.snapshots.value.connectionState)
        assertEquals(1, client.probeAttempts)

        // Toggling foreground off→on (returning from background) must self-heal.
        foreground.value = false
        runCurrent()
        foreground.value = true
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)
        assertTrue(client.probeAttempts >= 2)
    }

    @Test
    fun retryConnectionRecoversFromTransientFailure() = runTest(dispatcher) {
        val client = FailThenSucceedConnectionClient(failuresBeforeSuccess = 1)
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, viewModel.snapshots.value.connectionState)

        viewModel.retryConnection()
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)
    }

    @Test
    fun foregroundReconnectDoesNotUseReadySettingsAfterTheOriginIsRemoved() = runTest(dispatcher) {
        val foreground = MutableStateFlow(false)
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = FailThenSucceedConnectionClient(failuresBeforeSuccess = 1)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
            appForegroundStates = foreground,
        )

        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, viewModel.snapshots.value.connectionState)
        val probesBeforeSettingsRemoval = client.probeAttempts

        settings.value = ServerSettingsState.Unavailable
        runCurrent()
        foreground.value = true
        advanceUntilIdle()

        assertEquals(probesBeforeSettingsRemoval, client.probeAttempts)
        assertEquals(ConnectionState.Disconnected, viewModel.snapshots.value.connectionState)
        assertEquals("Server settings unavailable", viewModel.snapshots.value.connectionError)
    }

    @Test
    fun unauthorizedTranscriptLoadReconnectsAndRetriesTheSameSession() = runTest(dispatcher) {
        val client = UnauthorizedOnceTranscriptClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )

        advanceUntilIdle()
        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(2, client.transcriptLoads)
        assertEquals(listOf("Recovered question"), chat.messages.map { it.text })
        assertFalse(chat.isLoading)
        assertEquals(null, chat.error)
        assertTrue(client.authenticateCalls >= 2)
    }

    /**
     * The transcript 401 type is now a refinement of the shared credential
     * rejection, so this pins that a native OAuth connection still heals through
     * reconnect/refresh and never scrapes a loopback bootstrap token.
     */
    @Test
    fun unauthorizedTranscriptLoadUnderOAuthNeverBootstrapsALoopbackSession() = runTest(dispatcher) {
        val client = UnauthorizedOnceTranscriptClient()
        val bootstrap = CountingLoopbackBootstrap()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
            loopbackSessionBootstrapClient = bootstrap,
        )

        advanceUntilIdle()
        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(0, bootstrap.calls)
        assertEquals(2, client.transcriptLoads)
        assertEquals(listOf("Recovered question"), chat.messages.map { it.text })
        assertEquals(null, chat.error)
    }

    @Test
    fun reconnectReplacesLocalPartialWithInflightSnapshot() = runTest(dispatcher) {
        val first = ReconnectingChatSession(
            runtimeId = "runtime-1",
            running = false,
            inflightText = null,
            submitFailure = HermesChatTransportException("socket closed before acknowledgement"),
            onSubmit = { channel, runtime ->
                channel.trySend(HermesChatEvent.MessageDelta(runtime, "stale partial"))
                channel.close()
            },
        )
        val second = ReconnectingChatSession(
            runtimeId = "runtime-2",
            running = true,
            inflightText = "authoritative snapshot",
            inflightUser = "Reconnect this turn",
            resumeMessages = listOf(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", "Earlier answer")
                },
            ),
            onResume = { channel, runtime ->
                channel.trySend(HermesChatEvent.MessageDelta(runtime, " plus delta"))
                channel.trySend(
                    HermesChatEvent.MessageComplete(
                        runtime,
                        "authoritative snapshot plus delta",
                        "done",
                    ),
                )
                channel.close()
            },
        )
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                if (connections == 2) {
                    throw HermesChatTransportException("network not restored yet")
                }
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Reconnect this turn")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(3, connections)
        assertEquals(
            listOf("Reconnect this turn", "authoritative snapshot plus delta"),
            chat.messages.takeLast(2).map { it.text },
        )
        assertEquals(
            1,
            chat.messages.count {
                it.role == ChatMessageRole.Assistant &&
                    it.text == "authoritative snapshot plus delta"
            },
        )
        assertFalse(chat.isSending)
    }

    @Test
    fun reconnectReplayReducesTheSameToolIdIntoOneCompletedRow() = runTest(dispatcher) {
        val first = ReconnectingChatSession(
            runtimeId = "runtime-tool-first",
            running = false,
            inflightText = null,
            submitFailure = HermesChatTransportException("socket closed"),
            onSubmit = { channel, runtime ->
                channel.trySend(HermesChatEvent.ToolStart(runtime, "tool-1", "shell", "/workspace"))
            },
        )
        val recovered = ReconnectingChatSession(
            runtimeId = "runtime-tool-recovered",
            running = true,
            inflightText = "partial answer",
            onResume = { channel, runtime ->
                channel.trySend(HermesChatEvent.ToolStart(runtime, "tool-1", "shell", "/workspace"))
                channel.trySend(HermesChatEvent.ToolComplete(runtime, "tool-1", "shell", "finished"))
            },
        )
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(recovered)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Run with a tool")
        advanceUntilIdle()

        val tools = viewModel.snapshots.value.chatSessions.getValue(durableId).runState.tools
        assertEquals(1, tools.size)
        assertEquals("tool-1", tools.single().toolId)
        assertEquals(RunToolState.Completed, tools.single().state)
        assertEquals("finished", tools.single().summary)
    }

    @Test
    fun genuinelyNewPromptResetsRunStateWithoutAffectingAttachedSessionPreservation() = runTest(dispatcher) {
        val session = RunEventChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        session.emit(HermesChatEvent.ToolStart(session.runtimeSessionId, "old-tool", "shell", null))
        advanceUntilIdle()
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).runState.tools.isNotEmpty())

        viewModel.sendMessage(durableId, "New prompt")
        advanceUntilIdle()

        assertEquals(RunEventState(), viewModel.snapshots.value.chatSessions.getValue(durableId).runState)
    }

    @Test
    fun newPromptCanRecoverAfterPriorSuccessfulReconnect() = runTest(dispatcher) {
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-first",
                    running = false,
                    inflightText = null,
                    submitFailure = HermesChatTransportException("first disconnect"),
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-second",
                    running = false,
                    inflightText = null,
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-third",
                    running = false,
                    inflightText = null,
                    submitFailure = HermesChatTransportException("second disconnect"),
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-fourth",
                    running = false,
                    inflightText = null,
                ),
            )
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "First turn")
        advanceUntilIdle()
        assertEquals(2, connections)

        viewModel.sendMessage(durableId, "Second turn")
        advanceUntilIdle()
        assertEquals(4, connections)
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)
    }

    @Test
    fun openingSessionResumesAndReconcilesInflightPrompt() = runTest(dispatcher) {
        val session = ReconnectingChatSession(
            runtimeId = "runtime-open-resume",
            running = true,
            inflightText = "partial answer",
            inflightUser = "accepted question",
            resumeMessages = listOf(
                buildJsonObject {
                    put("role", "assistant")
                    put("content", "prior answer")
                },
            ),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(
            listOf("accepted question", "partial answer"),
            chat.messages.takeLast(2).map { it.text },
        )
        assertTrue(chat.messages.last().isStreaming)
    }

    @Test
    fun streamedRunEventsReduceIntoTheMatchingDurableSessionInFifoOrder() = runTest(dispatcher) {
        val session = RunEventChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val runtime = session.runtimeSessionId
        session.emit(HermesChatEvent.ToolStart(runtime, "tool-1", "shell", "/workspace"))
        session.emit(HermesChatEvent.ToolComplete(runtime, "tool-1", "shell", "finished"))
        session.emit(HermesChatEvent.StatusUpdate(runtime, "working", "Running checks"))
        session.emit(
            HermesChatEvent.ClarifyRequest(
                runtime,
                requestId = "clarify-1",
                question = "Which target?",
                choices = listOf("debug", "release"),
                multiSelect = false,
            ),
        )
        session.emit(HermesChatEvent.ClarifyExpire(runtime, "clarify-1"))
        session.emit(
            HermesChatEvent.ApprovalRequest(
                runtime,
                requestId = "approval-1",
                command = "./gradlew test",
                description = "Run tests",
                choices = listOf("allow", "deny"),
            ),
        )
        session.emit(HermesChatEvent.ApprovalExpire(runtime, "approval-1"))
        session.emit(
            HermesChatEvent.UnsupportedBlockingRequest(
                runtime,
                kind = UnsupportedBlockingKind.Secret,
                requestId = "secret-1",
                prompt = "Password",
            ),
        )
        session.emit(
            HermesChatEvent.UnsupportedBlockingExpire(
                runtime,
                kind = UnsupportedBlockingKind.Secret,
                requestId = "secret-1",
            ),
        )
        session.emit(HermesChatEvent.ToolStart(RuntimeSessionId("stale-runtime"), "stale", "ignored", null))
        advanceUntilIdle()

        val runState = viewModel.snapshots.value.chatSessions.getValue(durableId).runState
        assertEquals(
            listOf(RunToolRow("tool-1", "shell", "/workspace", "finished", RunToolState.Completed)),
            runState.tools,
        )
        assertEquals("working", runState.status?.kind)
        assertEquals("Running checks", runState.status?.text)
        assertEquals(RunInteractionLifecycle.Expired, runState.clarification?.lifecycle)
        assertEquals(RunInteractionLifecycle.Expired, runState.approval?.lifecycle)
        assertEquals(RunInteractionLifecycle.Expired, runState.unsupportedBlocking?.lifecycle)
        assertTrue(runState.tools.none { it.toolId == "stale" })
    }

    @Test
    fun clarificationControllerTargetsPendingRuntimeOnceAndMapsResolvedResponse() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        session.emit(
            HermesChatEvent.ClarifyRequest(
                session.runtimeSessionId,
                requestId = "clarify-1",
                question = "Which target?",
                choices = listOf("debug", "release"),
                multiSelect = false,
            ),
        )
        advanceUntilIdle()

        viewModel.respondToClarification(DurableSessionId("other"), "clarify-1", "debug").join()
        viewModel.respondToClarification(durableId, "wrong-request", "debug").join()
        assertTrue(session.clarificationCalls.isEmpty())

        val first = viewModel.respondToClarification(durableId, "clarify-1", "release")
        val duplicate = viewModel.respondToClarification(durableId, "clarify-1", "release")
        runCurrent()

        assertEquals(listOf("clarify-1" to "release"), session.clarificationCalls)
        assertEquals(
            RunInteractionLifecycle.Responding,
            viewModel.snapshots.value.chatSessions.getValue(durableId).runState.clarification?.lifecycle,
        )

        session.clarificationResponse.complete(HermesChatResponse(HermesChatResponseStatus.Resolved))
        first.join()
        duplicate.join()

        // A resolved response clears the card immediately rather than leaving a
        // settled placeholder that lingers until the next turn.
        assertNull(
            viewModel.snapshots.value.chatSessions.getValue(durableId).runState.clarification,
        )
    }

    @Test
    fun blockingPromptControllerCorrelatesSecretsAndAutoAnswersUnavailableReadSurfaces() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()
        viewModel.openSession(durableId)
        advanceUntilIdle()

        session.emit(
            HermesChatEvent.UnsupportedBlockingRequest(
                session.runtimeSessionId,
                UnsupportedBlockingKind.Secret,
                "secret-1",
                "Enter credential",
            ),
        )
        advanceUntilIdle()
        viewModel.respondToBlockingPrompt(durableId, UnsupportedBlockingKind.Secret, "wrong", "ignored").join()
        assertTrue(session.blockingCalls.isEmpty())

        val response = viewModel.respondToBlockingPrompt(
            durableId,
            UnsupportedBlockingKind.Secret,
            "secret-1",
            "opaque-value",
        )
        runCurrent()
        assertEquals(
            listOf(Triple(UnsupportedBlockingKind.Secret, "secret-1", "opaque-value")),
            session.blockingCalls,
        )
        assertEquals(
            RunInteractionLifecycle.Responding,
            viewModel.snapshots.value.chatSessions.getValue(durableId).runState.unsupportedBlocking?.lifecycle,
        )
        session.blockingResponse.complete(HermesChatResponse(HermesChatResponseStatus.Resolved))
        response.join()
        assertEquals(
            RunInteractionLifecycle.Resolved,
            viewModel.snapshots.value.chatSessions.getValue(durableId).runState.unsupportedBlocking?.lifecycle,
        )

        session.blockingResponse = CompletableDeferred(HermesChatResponse(HermesChatResponseStatus.Ok))
        session.emit(
            HermesChatEvent.UnsupportedBlockingRequest(
                session.runtimeSessionId,
                UnsupportedBlockingKind.TerminalRead,
                "read-1",
                null,
            ),
        )
        advanceUntilIdle()
        assertEquals(Triple(UnsupportedBlockingKind.TerminalRead, "read-1", ""), session.blockingCalls.last())
        assertEquals(
            RunInteractionLifecycle.Resolved,
            viewModel.snapshots.value.chatSessions.getValue(durableId).runState.unsupportedBlocking?.lifecycle,
        )
    }

    @Test
    fun approvalControllerRequiresAdvertisedChoiceTargetsRuntimeOnceAndMapsExpiredResponse() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        session.emit(
            HermesChatEvent.ApprovalRequest(
                session.runtimeSessionId,
                requestId = "approval-1",
                command = "secret command must stay out of controller calls",
                description = "Run the build",
                choices = listOf("allow", "deny"),
            ),
        )
        advanceUntilIdle()

        viewModel.respondToApproval(DurableSessionId("other"), "allow").join()
        viewModel.respondToApproval(durableId, "not-advertised").join()
        assertTrue(session.approvalCalls.isEmpty())

        val first = viewModel.respondToApproval(durableId, "allow", all = true)
        val duplicate = viewModel.respondToApproval(durableId, "allow", all = true)
        runCurrent()

        assertEquals(
            listOf(Triple(session.runtimeSessionId, "allow", true)),
            session.approvalCalls,
        )
        assertEquals(listOf("approval-1"), session.approvalRequestIds)
        assertEquals(
            RunInteractionLifecycle.Responding,
            viewModel.snapshots.value.chatSessions.getValue(durableId).runState.approval?.lifecycle,
        )

        session.approvalResponse.complete(HermesChatResponse(HermesChatResponseStatus.Expired))
        first.join()
        duplicate.join()

        assertEquals(
            RunInteractionLifecycle.Expired,
            viewModel.snapshots.value.chatSessions.getValue(durableId).runState.approval?.lifecycle,
        )
    }

    @Test
    fun approvalControllerPromotesTheNextQueuedApprovalAfterResolvingTheLatest() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        val firstApproval = HermesChatEvent.ApprovalRequest(
            sessionId = session.runtimeSessionId,
            requestId = "approval-1",
            command = "first command",
            description = "First approval",
            choices = listOf("once", "deny"),
        )
        session.emit(firstApproval)
        session.emit(
            HermesChatEvent.ApprovalRequest(
                sessionId = session.runtimeSessionId,
                requestId = "approval-2",
                command = "second command",
                description = "Second approval",
                choices = listOf("session", "deny"),
            ),
        )
        advanceUntilIdle()

        val response = viewModel.respondToApproval(durableId, "session")
        runCurrent()
        session.approvalResponse.complete(
            HermesChatResponse(
                status = HermesChatResponseStatus.Ok,
                nextApproval = firstApproval,
            ),
        )
        response.join()

        val promoted = viewModel.snapshots.value.chatSessions.getValue(durableId).runState.approval
        assertEquals("approval-1", promoted?.requestId)
        assertEquals("First approval", promoted?.descriptionPreview)
        assertEquals(listOf("once", "deny"), promoted?.choices)
        assertEquals(RunInteractionLifecycle.Pending, promoted?.lifecycle)
    }

    @Test
    fun stopControllerTargetsSendingRuntimeOnceAndTerminalizesLiveInteractionsWithoutClosingSocket() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.sendMessage(durableId, "Long-running prompt")
        advanceUntilIdle()
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)

        session.emit(
            HermesChatEvent.ClarifyRequest(
                session.runtimeSessionId,
                requestId = "clarify-stop",
                question = "Continue?",
                choices = listOf("yes", "no"),
                multiSelect = false,
            ),
        )
        session.emit(
            HermesChatEvent.ApprovalRequest(
                session.runtimeSessionId,
                requestId = "approval-stop",
                command = "hidden command",
                description = "A gated action",
                choices = listOf("allow", "deny"),
            ),
        )
        advanceUntilIdle()

        viewModel.stopSession(DurableSessionId("other")).join()
        assertTrue(session.interruptCalls.isEmpty())

        val first = viewModel.stopSession(durableId)
        val duplicate = viewModel.stopSession(durableId)
        runCurrent()

        assertEquals(listOf(session.runtimeSessionId), session.interruptCalls)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isStopping)

        session.interruptResponse.complete(HermesChatResponse(HermesChatResponseStatus.Ok))
        first.join()
        duplicate.join()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.isSending)
        assertFalse(chat.isStopping)
        assertTrue(chat.messages.none { it.isStreaming })
        assertEquals(RunInteractionLifecycle.Expired, chat.runState.clarification?.lifecycle)
        assertEquals(RunInteractionLifecycle.Expired, chat.runState.approval?.lifecycle)
        assertTrue(viewModel.snapshots.value.activeRuntimes.none { it.runtimeSessionId == session.runtimeSessionId })
        assertFalse(session.closed)
    }

    @Test
    fun steerControllerTargetsExactSendingRuntimeWithoutAppendingTranscriptMessage() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.sendMessage(durableId, "Long-running prompt")
        advanceUntilIdle()
        val messagesBeforeSteer = viewModel.snapshots.value.chatSessions.getValue(durableId).messages

        viewModel.steerSession(DurableSessionId("other"), "ignore this").join()
        assertTrue(session.steerCalls.isEmpty())

        viewModel.steerSession(durableId, "Focus on the failing test").join()

        assertEquals(
            listOf(session.runtimeSessionId to "Focus on the failing test"),
            session.steerCalls,
        )
        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertTrue(chat.isSending)
        assertEquals(messagesBeforeSteer, chat.messages)
        assertEquals("Guidance queued for the active turn", chat.notice)
        assertNull(chat.error)
    }

    @Test
    fun sessionInsightsLoadForExactControlledRuntimeAndPublishUsageAndContext() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.loadSessionInsights(durableId).join()

        assertEquals(listOf(session.runtimeSessionId), session.usageCalls)
        assertEquals(listOf(session.runtimeSessionId), session.contextCalls)
        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(chat.insightsLoading)
        assertEquals(42L, chat.sessionUsage?.totalTokens)
        assertEquals(100L, chat.contextBreakdown?.maxTokens)
        assertEquals("Conversation", chat.contextBreakdown?.categories?.single()?.name)
        assertNull(chat.insightsError)
    }

    @Test
    fun maintenanceActionsReplaceTranscriptRefreshUndoAndPublishBranchWithoutSharingParentTransport() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val client = ChatConnectionClient()
        val viewModel = chatViewModel(session, client)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.sendMessage(durableId, "Long-running prompt")
        advanceUntilIdle()
        session.emit(HermesChatEvent.MessageComplete(session.runtimeSessionId, "Done", "completed"))
        advanceUntilIdle()

        viewModel.compressSession(durableId, "tests").join()
        var chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(listOf("Compressed summary"), chat.messages.map { it.text })
        assertEquals("Context compressed", chat.notice)

        val transcriptLoadsBeforeUndo = client.transcriptLoads
        viewModel.undoSession(durableId).join()
        chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(listOf("Earlier question", "Earlier answer"), chat.messages.map { it.text })
        assertEquals(transcriptLoadsBeforeUndo + 1, client.transcriptLoads)

        viewModel.branchSession(durableId, count = 1, name = "Test branch").join()
        val branchId = DurableSessionId("stored-branch")
        val snapshot = viewModel.snapshots.value
        assertEquals(branchId, snapshot.lastBranchedSessionId)
        assertTrue(snapshot.durableSessions.any { it.id == branchId && it.title == "Test branch" })
        assertEquals(listOf("Branch question"), snapshot.chatSessions.getValue(branchId).messages.map { it.text })
        assertFalse(snapshot.activeRuntimes.any { it.durableSessionId == branchId })
        assertFalse(session.closed)
    }

    @Test
    fun openingASelectedControllerLoadsOnlyItsProcessLocalRows() = runTest(dispatcher) {
        val session = ControllerChatSession()
        session.processListResponse = listOf(
            ProcessRow("process-1", "python server.py", "running", outputTail = "ready"),
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(session.processListResponse, chat.processRows)
        assertEquals(listOf(session.runtimeSessionId), session.processListCalls)
    }

    @Test
    fun staleProcessRowsCannotBleedAcrossOriginAndRuntimeReplacement() = runTest(dispatcher) {
        val replacementOrigin = ServerOrigin.parse("https://replacement.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val old = ControllerChatSession("runtime-old").apply {
            processListResponse = listOf(ProcessRow("old", "old server", "running"))
            processListGate = CompletableDeferred()
            processListNonCooperative = true
        }
        val replacement = ControllerChatSession("runtime-new").apply {
            processListResponse = listOf(ProcessRow("new", "new server", "running"))
        }
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(old)
            add(replacement)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        runCurrent()
        assertTrue(old.processListStarted.isCompleted)

        settings.value = ServerSettingsState.Ready(replacementOrigin)
        advanceUntilIdle()
        viewModel.openSession(durableId)
        advanceUntilIdle()
        assertEquals(
            listOf(replacement.processListResponse),
            listOf(viewModel.snapshots.value.chatSessions.getValue(durableId).processRows),
        )

        old.processListGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            replacement.processListResponse,
            viewModel.snapshots.value.chatSessions.getValue(durableId).processRows,
        )
    }

    @Test
    fun subagentControlsUseParentControllerAndUpdateProcessLocalStatus() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.sendMessage(durableId, "Delegate work")
        advanceUntilIdle()

        viewModel.setDelegationPaused(durableId, true).join()
        viewModel.steerSubagent(durableId, "child-1", "Focus on Android tests").join()
        viewModel.interruptSubagent(durableId, "child-1").join()

        assertEquals(listOf(true), session.pauseDelegationCalls)
        assertEquals(
            listOf(Triple(session.runtimeSessionId, "child-1", "Focus on Android tests")),
            session.subagentSteerCalls,
        )
        assertEquals(listOf("child-1"), session.subagentInterruptCalls)
        val status = viewModel.snapshots.value.delegationStatus
        assertTrue(status.paused)
        assertEquals("Subagent interrupted", status.notice)
        assertNull(status.error)
    }

    @Test
    fun failedStopClearsStoppingWithBoundedErrorAndAllowsRetry() = runTest(dispatcher) {
        val session = ControllerChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.sendMessage(durableId, "Long-running prompt")
        advanceUntilIdle()

        val failed = viewModel.stopSession(durableId)
        runCurrent()
        session.interruptResponse.completeExceptionally(
            HermesChatTransportException("transport detail must not become UI state"),
        )
        failed.join()

        val failedChat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertFalse(failedChat.isStopping)
        assertTrue(failedChat.isSending)
        assertEquals("Could not stop session", failedChat.error)
        assertTrue(failedChat.error.orEmpty().length <= 160)

        session.interruptResponse = CompletableDeferred()
        val retry = viewModel.stopSession(durableId)
        runCurrent()
        assertEquals(listOf(session.runtimeSessionId, session.runtimeSessionId), session.interruptCalls)
        session.interruptResponse.complete(HermesChatResponse(HermesChatResponseStatus.Interrupted))
        retry.join()
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)
    }

    @Test
    fun staleNonCooperativeClarificationResponseCannotMutateReplacementRuntime() = runTest(dispatcher) {
        val first = ControllerChatSession("runtime-clarify-old").apply {
            clarificationNonCooperative = true
        }
        val second = ControllerChatSession("runtime-clarify-current")
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        val secondDurableId = DurableSessionId("durable-2")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        first.emit(
            HermesChatEvent.ClarifyRequest(
                first.runtimeSessionId,
                "clarify-shared",
                "Old question",
                listOf("old"),
                false,
            ),
        )
        advanceUntilIdle()
        val staleResponse = viewModel.respondToClarification(durableId, "clarify-shared", "old")
        runCurrent()

        viewModel.openSession(secondDurableId)
        advanceUntilIdle()
        second.emit(
            HermesChatEvent.ClarifyRequest(
                second.runtimeSessionId,
                "clarify-shared",
                "Current question",
                listOf("current"),
                false,
            ),
        )
        advanceUntilIdle()

        first.clarificationResponse.complete(HermesChatResponse(HermesChatResponseStatus.Resolved))
        staleResponse.join()

        val current = viewModel.snapshots.value.chatSessions
            .getValue(secondDurableId)
            .runState
            .clarification
        assertEquals(second.runtimeSessionId, current?.runtimeSessionId)
        assertEquals("Current question", current?.question)
        assertEquals(RunInteractionLifecycle.Pending, current?.lifecycle)
    }

    @Test
    fun staleNonCooperativeApprovalResponseCannotMutateReplacementRuntime() = runTest(dispatcher) {
        val first = ControllerChatSession("runtime-approval-old").apply {
            approvalNonCooperative = true
        }
        val second = ControllerChatSession("runtime-approval-current")
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        val secondDurableId = DurableSessionId("durable-2")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        first.emit(
            HermesChatEvent.ApprovalRequest(
                first.runtimeSessionId,
                requestId = "approval-shared",
                command = null,
                description = "Old approval",
                choices = listOf("allow", "deny"),
            ),
        )
        advanceUntilIdle()
        val staleResponse = viewModel.respondToApproval(durableId, "allow")
        runCurrent()

        viewModel.openSession(secondDurableId)
        advanceUntilIdle()
        second.emit(
            HermesChatEvent.ApprovalRequest(
                second.runtimeSessionId,
                requestId = "approval-shared",
                command = null,
                description = "Current approval",
                choices = listOf("allow", "deny"),
            ),
        )
        advanceUntilIdle()

        first.approvalResponse.complete(HermesChatResponse(HermesChatResponseStatus.Resolved))
        staleResponse.join()

        val current = viewModel.snapshots.value.chatSessions
            .getValue(secondDurableId)
            .runState
            .approval
        assertEquals(second.runtimeSessionId, current?.runtimeSessionId)
        assertEquals("Current approval", current?.descriptionPreview)
        assertEquals(RunInteractionLifecycle.Pending, current?.lifecycle)
    }

    @Test
    fun staleNonCooperativeStopCannotTerminalizeReplacementController() = runTest(dispatcher) {
        val first = ControllerChatSession("runtime-stop-old").apply {
            interruptNonCooperative = true
        }
        val second = ControllerChatSession("runtime-stop-current")
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        val secondDurableId = DurableSessionId("durable-2")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.sendMessage(durableId, "Old run")
        advanceUntilIdle()
        val staleStop = viewModel.stopSession(durableId)
        runCurrent()

        viewModel.openSession(secondDurableId)
        advanceUntilIdle()
        viewModel.sendMessage(secondDurableId, "Current run")
        advanceUntilIdle()

        first.interruptResponse.complete(HermesChatResponse(HermesChatResponseStatus.Interrupted))
        staleStop.join()

        val current = viewModel.snapshots.value.chatSessions.getValue(secondDurableId)
        assertTrue(current.isSending)
        assertFalse(current.isStopping)
        assertEquals(
            listOf(second.runtimeSessionId),
            viewModel.snapshots.value.activeRuntimes.map { it.runtimeSessionId },
        )
    }

    @Test
    fun secondPromptReusesRetainedControllerRuntimeAfterTheFirstTurnCompletes() = runTest(dispatcher) {
        val session = ControllerChatSession("runtime-reused")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> session },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        session.emit(HermesChatEvent.MessageComplete(session.runtimeSessionId, "First turn", "done"))
        advanceUntilIdle()
        // The controller is retained across turns, so the runtime marker persists
        // while the live controller is still connected (maintenance stays available).
        assertEquals(
            listOf(session.runtimeSessionId),
            viewModel.snapshots.value.activeRuntimes.map { it.runtimeSessionId },
        )

        viewModel.sendMessage(durableId, "Second turn")
        advanceUntilIdle()

        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)
        assertEquals(
            listOf(session.runtimeSessionId),
            viewModel.snapshots.value.activeRuntimes.map { it.runtimeSessionId },
        )
    }

    @Test
    fun runningControllerRuntimeIsPublishedWithDurableIdentityAndRetainedUntilControllerDetaches() = runTest(dispatcher) {
        val session = RunEventChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ActiveRuntimeSession(
                    runtimeSessionId = session.runtimeSessionId,
                    durableSessionId = durableId,
                    title = "Test session",
                    access = RuntimeAccess.Controller,
                ),
            ),
            viewModel.snapshots.value.activeRuntimes,
        )

        session.emit(HermesChatEvent.MessageComplete(session.runtimeSessionId, "done", "done"))
        advanceUntilIdle()

        // A normal completion does not detach the controller; the runtime marker
        // is retained so maintenance remains available while the controller is idle.
        assertEquals(
            listOf(
                ActiveRuntimeSession(
                    runtimeSessionId = session.runtimeSessionId,
                    durableSessionId = durableId,
                    title = "Test session",
                    access = RuntimeAccess.Controller,
                ),
            ),
            viewModel.snapshots.value.activeRuntimes,
        )
    }

    @Test
    fun terminalErrorRemovesOnlyTheMatchingControllerRuntime() = runTest(dispatcher) {
        val session = RunEventChatSession()
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        session.emit(HermesChatEvent.Error(session.runtimeSessionId, "failed"))
        advanceUntilIdle()

        assertTrue(viewModel.snapshots.value.activeRuntimes.isEmpty())
    }

    @Test
    fun openingAnotherControllerPublishesBothConcurrentRuntimes() = runTest(dispatcher) {
        val first = RunEventChatSession("runtime-first")
        val second = RunEventChatSession("runtime-second")
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
        }
        val secondDurableId = DurableSessionId("durable-2")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> sessions.removeFirst() },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        viewModel.openSession(secondDurableId)
        advanceUntilIdle()

        assertEquals(
            setOf(first.runtimeSessionId, second.runtimeSessionId),
            viewModel.snapshots.value.activeRuntimes.map { it.runtimeSessionId }.toSet(),
        )
    }

    @Test
    fun reopeningTheSameAttachedSessionIsANoopAndPreservesTheLivePartialRun() = runTest(dispatcher) {
        val session = ReopenPreservingChatSession()
        val client = ChatConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ -> session },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()
        session.emit(HermesChatEvent.ToolStart(session.runtimeSessionId, "tool-1", "shell", null))
        advanceUntilIdle()

        val beforeReopen = viewModel.snapshots.value.chatSessions.getValue(durableId)
        val transcriptLoadsBeforeReopen = client.transcriptLoads
        val activeRuntimeBeforeReopen = viewModel.snapshots.value.activeRuntimes.single()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val afterReopen = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(1, session.resumeCalls)
        assertEquals(transcriptLoadsBeforeReopen, client.transcriptLoads)
        assertEquals(0, session.closeCalls)
        assertEquals(beforeReopen.messages, afterReopen.messages)
        assertEquals(beforeReopen.runState, afterReopen.runState)
        assertEquals(activeRuntimeBeforeReopen, viewModel.snapshots.value.activeRuntimes.single())
    }

    @Test
    fun staleRecoveryCleanupCannotUnlockReplacementRecovery() = runTest(dispatcher) {
        val initial = ControllableFailingChatSession()
        val staleRecovery = NonCooperativeResumeChatSession("runtime-stale-recovery")
        val replacementInitial = ControllableFailingChatSession("runtime-replacement")
        val currentRecovery = NonCooperativeResumeChatSession("runtime-current-recovery")
        val duplicate = ReconnectingChatSession(
            runtimeId = "runtime-duplicate",
            running = false,
            inflightText = null,
        )
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(initial)
            add(staleRecovery)
            add(replacementInitial)
            add(currentRecovery)
            add(duplicate)
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "First operation")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(staleRecovery.resumeStarted.isCompleted)

        viewModel.sendMessage(durableId, "Replacement operation")
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertTrue(currentRecovery.resumeStarted.isCompleted)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)

        staleRecovery.releaseResume.complete(Unit)
        runCurrent()
        assertTrue(staleRecovery.closed)
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isSending)
        replacementInitial.closeEvents()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()

        assertEquals(4, connections)
        currentRecovery.releaseResume.complete(Unit)
        runCurrent()
    }

    @Test
    fun recoveredSessionFailureUsesSecondBoundedRecovery() = runTest(dispatcher) {
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-initial-failure",
                    running = false,
                    inflightText = null,
                    submitFailure = HermesChatTransportException("initial disconnect"),
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-recovered-failure",
                    running = true,
                    inflightText = "authoritative partial",
                    onResume = { channel, _ -> channel.close() },
                ),
            )
            add(
                ReconnectingChatSession(
                    runtimeId = "runtime-final-recovery",
                    running = false,
                    inflightText = null,
                ),
            )
        }
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Recover twice")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(3, connections)
        assertFalse(chat.isSending)
        assertFalse(chat.messages.any { it.isStreaming })
    }

    @Test
    fun openingCompletedSessionClosesIdleSocketAfterReconciliation() = runTest(dispatcher) {
        val session = ReconnectingChatSession(
            runtimeId = "runtime-open-idle",
            running = false,
            inflightText = null,
        )
        val viewModel = chatViewModel(session)
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertTrue(session.closed)
        assertFalse(viewModel.snapshots.value.chatSessions.getValue(durableId).isLoading)
    }

    @Test
    fun accessOnlyExpiryWhileOpeningSessionReturnsToSignIn() = runTest(dispatcher) {
        var now = 1_900_000_000L
        val accessOnly = tokens.copy(refreshToken = "")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(accessOnly),
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
        assertTrue(viewModel.snapshots.value.chatSessions.isEmpty())
    }

    @Test
    fun refreshExpiryWhileOpeningSessionReturnsToSignIn() = runTest(dispatcher) {
        var now = 1_900_000_000L
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = ChatConnectionClient(),
            tokenStore = MemoryTokenStore(tokens),
            refreshClient = object : NativeRefreshClient {
                override suspend fun refresh(
                    serverOrigin: ServerOrigin,
                    refreshToken: String,
                    provider: String,
                ): NativeTokenSet = throw NativeRefreshExpiredException()
            },
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        advanceUntilIdle()

        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
        assertTrue(viewModel.snapshots.value.durableSessions.isEmpty())
        assertTrue(viewModel.snapshots.value.chatSessions.isEmpty())
    }

    @Test
    fun staleSameOriginRefreshCannotOverwriteNewGenerationCredentials() = runTest(dispatcher) {
        val firstTokens = tokens.copy(expiresAt = 1_900_000_100L)
        val newerTokens = tokens.copy(
            accessToken = "new-generation-access",
            refreshToken = "new-generation-refresh",
            expiresAt = 2_100_000_000L,
        )
        val staleRefreshedTokens = firstTokens.copy(
            accessToken = "stale-refreshed-access",
            expiresAt = 2_100_000_000L,
        )
        val stored = mutableMapOf(origin to firstTokens)
        val tokenStore = object : NativeTokenStore {
            override suspend fun load(serverOrigin: ServerOrigin) = stored[serverOrigin]
            override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) {
                stored[serverOrigin] = tokens
            }
            override suspend fun clear(serverOrigin: ServerOrigin) {
                stored.remove(serverOrigin)
            }
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val refreshClient = object : NativeRefreshClient {
            override suspend fun refresh(
                serverOrigin: ServerOrigin,
                refreshToken: String,
                provider: String,
            ): NativeTokenSet {
                refreshStarted.complete(Unit)
                withContext(NonCancellable) { releaseRefresh.await() }
                return staleRefreshedTokens
            }
        }
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        var now = 1_900_000_000L
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = ChatConnectionClient(),
            tokenStore = tokenStore,
            refreshClient = refreshClient,
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        runCurrent()
        assertTrue(refreshStarted.isCompleted)

        settings.value = ServerSettingsState.Loading
        runCurrent()
        stored[origin] = newerTokens
        settings.value = ServerSettingsState.Ready(origin)
        runCurrent()
        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        assertEquals(newerTokens, stored[origin])
    }

    @Test
    fun staleOriginRefreshCannotReplaceCurrentOriginCredentials() = runTest(dispatcher) {
        val firstOrigin = origin
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val firstTokens = tokens.copy(expiresAt = 1_900_000_100L)
        val secondTokens = tokens.copy(
            accessToken = "second-origin-access",
            refreshToken = "second-origin-refresh",
            expiresAt = 2_100_000_000L,
        )
        val refreshedFirstTokens = firstTokens.copy(
            accessToken = "refreshed-first-access",
            expiresAt = 2_100_000_000L,
        )
        val stored = mutableMapOf(firstOrigin to firstTokens, secondOrigin to secondTokens)
        val tokenStore = object : NativeTokenStore {
            override suspend fun load(serverOrigin: ServerOrigin) = stored[serverOrigin]
            override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) {
                stored[serverOrigin] = tokens
            }
            override suspend fun clear(serverOrigin: ServerOrigin) {
                stored.remove(serverOrigin)
            }
        }
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val refreshClient = object : NativeRefreshClient {
            override suspend fun refresh(
                serverOrigin: ServerOrigin,
                refreshToken: String,
                provider: String,
            ): NativeTokenSet {
                assertEquals(firstOrigin, serverOrigin)
                refreshStarted.complete(Unit)
                withContext(NonCancellable) { releaseRefresh.await() }
                return refreshedFirstTokens
            }
        }
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(firstOrigin))
        var now = 1_900_000_000L
        var connectorCredential: HermesCredential? = null
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = ChatConnectionClient(),
            tokenStore = tokenStore,
            refreshClient = refreshClient,
            chatConnector = HermesChatConnector { _, credential ->
                connectorCredential = credential
                StreamingChatSession()
            },
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()

        now = 2_000_000_000L
        viewModel.openSession(durableId)
        runCurrent()
        assertTrue(refreshStarted.isCompleted)

        settings.value = ServerSettingsState.Ready(secondOrigin)
        runCurrent()
        releaseRefresh.complete(Unit)
        advanceUntilIdle()

        viewModel.sendMessage(durableId, "Current origin prompt")
        advanceUntilIdle()

        val request = io.ktor.client.request.HttpRequestBuilder()
        request.applyHermesCredential(checkNotNull(connectorCredential), secondOrigin)
        assertEquals(
            "Bearer ${secondTokens.accessToken}",
            request.headers[io.ktor.http.HttpHeaders.Authorization],
        )
    }

    @Test
    fun nonCooperativeOldOriginAuthenticationCannotPublishStaleSessions() = runTest(dispatcher) {
        val firstOrigin = origin
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(firstOrigin))
        val client = NonCooperativeAuthenticationClient(firstOrigin)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )
        runCurrent()
        assertTrue(client.firstAuthenticationStarted.isCompleted)

        settings.value = ServerSettingsState.Ready(secondOrigin)
        runCurrent()
        client.releaseFirstAuthentication.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("second-session"),
            viewModel.snapshots.value.durableSessions.map { it.id.value },
        )
    }

    @Test
    fun originChangeCancelsTranscriptAndClearsOriginScopedChatState() = runTest(dispatcher) {
        val firstOrigin = origin
        val secondOrigin = ServerOrigin.parse("https://second.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(firstOrigin))
        val client = BlockingTranscriptClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        runCurrent()
        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isLoading)

        settings.value = ServerSettingsState.Ready(secondOrigin)
        advanceUntilIdle()

        assertTrue(client.transcriptCancelled)
        assertTrue(viewModel.snapshots.value.chatSessions.isEmpty())
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)
    }

    @Test
    fun openingAnotherTranscriptDoesNotCancelTheFirstLoad() = runTest(dispatcher) {
        val client = BlockingTranscriptClient()
        val secondDurableId = DurableSessionId("durable-2")
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = MemoryTokenStore(tokens),
            nowEpochSeconds = { 1_900_000_000 },
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        runCurrent()
        viewModel.openSession(secondDurableId)
        runCurrent()

        assertTrue(viewModel.snapshots.value.chatSessions.getValue(durableId).isLoading)
    }
}

private class RejectedAuthenticationClient : HermesConnectionClient {
    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider(name = "nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): AuthenticatedHermesConnection = throw HermesAuthenticationRejectedException(
        "Hermes authentication returned HTTP 401",
    )
}

private class MemoryTokenStore(initial: NativeTokenSet?) : NativeTokenStore {
    private var value = initial
    override suspend fun load(serverOrigin: ServerOrigin): NativeTokenSet? = value
    override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) { value = tokens }
    override suspend fun clear(serverOrigin: ServerOrigin) { value = null }
}

private class ChatConnectionClient : HermesConnectionClient {
    private val durableId = DurableSessionId("durable-1")
    var transcriptAccessToken: HermesCredential? = null
    var transcriptLoads = 0
    var lastTranscriptDurableId: DurableSessionId? = null

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ) = AuthenticatedHermesConnection(
        userId = "user-1",
        sessions = listOf(SessionSummary(durableId, "Test session")),
    )

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ): List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage> {
        transcriptAccessToken = credential
        transcriptLoads += 1
        lastTranscriptDurableId = durableSessionId
        return listOf(
            com.unsupportedpastels.hermesandroid.gateway.ChatMessage(ChatMessageRole.User, "Earlier question"),
            com.unsupportedpastels.hermesandroid.gateway.ChatMessage(ChatMessageRole.Assistant, "Earlier answer"),
        )
    }
}

/** Fails the test if a non-loopback connection ever tries to adopt a session token. */
private class CountingLoopbackBootstrap : LoopbackSessionBootstrapClient {
    var calls = 0
        private set

    override suspend fun bootstrap(origin: ServerOrigin): LoopbackSessionBootstrapResult {
        calls += 1
        return LoopbackSessionBootstrapResult.Failure(LoopbackSessionBootstrapFailure.TokenAbsent)
    }
}

private class UnauthorizedOnceTranscriptClient : HermesConnectionClient {
    private val durableId = DurableSessionId("durable-1")
    var authenticateCalls = 0
    var transcriptLoads = 0

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ) = AuthenticatedHermesConnection(
        userId = "user-1",
        sessions = listOf(SessionSummary(durableId, "Test session")),
    ).also { authenticateCalls += 1 }

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ): List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage> {
        transcriptLoads += 1
        if (transcriptLoads == 1) throw HermesUnauthorizedException()
        return listOf(
            com.unsupportedpastels.hermesandroid.gateway.ChatMessage(
                ChatMessageRole.User,
                "Recovered question",
            ),
        )
    }
}

/**
 * Probes fail with a transient transport error for the first [failuresBeforeSuccess]
 * attempts, then succeed. Models a resume-time network blip that leaves the app
 * Disconnected until it retries.
 */
private class FailThenSucceedConnectionClient(
    private val failuresBeforeSuccess: Int,
) : HermesConnectionClient {
    private val durableId = DurableSessionId("durable-1")
    var probeAttempts = 0
        private set

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo {
        probeAttempts += 1
        if (probeAttempts <= failuresBeforeSuccess) {
            throw HermesConnectionException("Could not reach Hermes Serve")
        }
        return HermesConnectionInfo(
            version = "0.20.0",
            authRequired = true,
            nativeOAuthSupported = true,
            providers = listOf(HermesAuthProvider("nous")),
        )
    }

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ) = AuthenticatedHermesConnection(
        userId = "user-1",
        sessions = listOf(SessionSummary(durableId, "Test session")),
    )
}

private class RecordingTurnNotificationController : TurnNotificationController {
    val turnStarts = mutableListOf<DurableSessionId>()
    var lastActiveCount = 0
    override fun turnStarted(
        sessionId: DurableSessionId,
        title: String,
        activeCount: Int,
    ) {
        turnStarts += sessionId
        lastActiveCount = activeCount
    }

    override fun activeCountChanged(activeCount: Int) {
        lastActiveCount = activeCount
    }

    override fun approvalRequired(
        sessionId: DurableSessionId,
        title: String,
        preview: String,
    ) = Unit

    override fun clarificationRequired(
        sessionId: DurableSessionId,
        title: String,
        preview: String,
    ) = Unit

    override fun unsupportedInputRequired(
        sessionId: DurableSessionId,
        title: String,
        preview: String,
    ) = Unit

    override fun turnCompleted(
        sessionId: DurableSessionId,
        title: String,
        text: String,
        status: String?,
    ) {
        lastActiveCount = 0
    }
}

private class NonCooperativeAuthenticationClient(
    private val firstOrigin: ServerOrigin,
) : HermesConnectionClient {
    val firstAuthenticationStarted = CompletableDeferred<Unit>()
    val releaseFirstAuthentication = CompletableDeferred<Unit>()

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): AuthenticatedHermesConnection {
        if (serverOrigin == firstOrigin) {
            withContext(NonCancellable) {
                firstAuthenticationStarted.complete(Unit)
                releaseFirstAuthentication.await()
            }
            return AuthenticatedHermesConnection(
                userId = "first-user",
                sessions = listOf(SessionSummary(DurableSessionId("first-session"), "First")),
            )
        }
        return AuthenticatedHermesConnection(
            userId = "second-user",
            sessions = listOf(SessionSummary(DurableSessionId("second-session"), "Second")),
        )
    }

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ) = emptyList<com.unsupportedpastels.hermesandroid.gateway.ChatMessage>()
}

private class BlockingTranscriptClient : HermesConnectionClient {
    var transcriptCancelled = false
    private val transcript = CompletableDeferred<List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage>>()

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = true,
        nativeOAuthSupported = true,
        providers = listOf(HermesAuthProvider("nous")),
    )

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ) = AuthenticatedHermesConnection(
        userId = "user-1",
        sessions = listOf(SessionSummary(DurableSessionId("durable-1"), "Test session")),
    )

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ): List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage> = try {
        transcript.await()
    } catch (cancelled: CancellationException) {
        transcriptCancelled = true
        throw cancelled
    }
}

private class ControllableFailingChatSession(
    runtimeId: String = "runtime-controllable",
) : HermesChatSession {
    private val runtimeSessionId = RuntimeSessionId(runtimeId)
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = throw HermesChatTransportException("transport failed")

    fun closeEvents() = channel.close()
    override suspend fun close() = Unit
}

private class NonCooperativeResumeChatSession(
    private val runtimeId: String,
) : HermesChatSession {
    val resumeStarted = CompletableDeferred<Unit>()
    val releaseResume = CompletableDeferred<Unit>()
    var closed = false
    override val events: Flow<HermesChatEvent> = MutableSharedFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeStarted.complete(Unit)
        withContext(NonCancellable) { releaseResume.await() }
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId(runtimeId),
            durableSessionId = durableSessionId,
            resumed = true,
            messages = emptyList(),
            running = true,
            inflight = InflightPrompt("accepted prompt", "partial response", true),
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    override suspend fun close() {
        closed = true
    }
}

private class BlockingResumeChatSession : HermesChatSession {
    val resumeStarted = CompletableDeferred<Unit>()
    var closed = false
    override val events: Flow<HermesChatEvent> = MutableSharedFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeStarted.complete(Unit)
        awaitCancellation()
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    override suspend fun close() {
        closed = true
    }
}

private class BlockingSubmitChatSession : HermesChatSession {
    val submitStarted = CompletableDeferred<Unit>()
    override val events: Flow<HermesChatEvent> = MutableSharedFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-blocking-submit"),
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submitStarted.complete(Unit)
        awaitCancellation()
    }

    override suspend fun close() = Unit
}

private class TerminalEventChatSession(
    private val terminalEvent: (RuntimeSessionId) -> HermesChatEvent,
) : HermesChatSession {
    private val mutableEvents = MutableSharedFlow<HermesChatEvent>(extraBufferCapacity = 8)
    override val events: Flow<HermesChatEvent> = mutableEvents

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-error"),
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        mutableEvents.emit(HermesChatEvent.MessageStart(runtimeSessionId, null))
        mutableEvents.emit(HermesChatEvent.MessageDelta(runtimeSessionId, "partial"))
        mutableEvents.emit(terminalEvent(runtimeSessionId))
        return PromptSubmission("streaming")
    }

    override suspend fun close() = Unit
}

private class CompletableSlashChatSession(
    private val result: SlashCompletionResult,
) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()
    val completionRequests = mutableListOf<String>()
    var createdForDurableId: DurableSessionId? = null

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createdForDurableId = durableSessionId
        return resume(durableSessionId, profile)
    }

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-slash"),
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = true,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    override suspend fun completeSlash(text: String): SlashCompletionResult {
        completionRequests += text
        return result
    }

    override suspend fun close() = Unit
}

private class CanonicalClosingDraftSession(
    private val canonicalId: DurableSessionId,
    private val completeFirstTurn: Boolean = true,
) : HermesChatSession {
    private val runtimeId = RuntimeSessionId("runtime-canonical-draft")
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ) = ResumedChatSession(
        runtimeSessionId = runtimeId,
        durableSessionId = canonicalId,
        resumed = false,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = error("draft should be created, not resumed")

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        channel.send(HermesChatEvent.MessageStart(runtimeId, null))
        if (completeFirstTurn) {
            channel.send(HermesChatEvent.MessageComplete(runtimeId, "done", "complete", null))
        }
        return PromptSubmission("streaming")
    }

    fun closeEvents() = channel.close()

    override suspend fun close() = Unit
}

private class CancellableCreateChatSession : HermesChatSession {
    override val events: Flow<HermesChatEvent> = MutableSharedFlow()
    val createStarted = CompletableDeferred<Unit>()
    var closeStarted = false
    var closeCompleted = false

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createStarted.complete(Unit)
        awaitCancellation()
    }

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = error("candidate should not resume")

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = error("candidate should not submit")

    override suspend fun close() {
        closeStarted = true
        yield()
        closeCompleted = true
    }
}

private class StreamingChatSession(
    private val submitFailure: Exception? = null,
) : HermesChatSession {
    private val mutableEvents = MutableSharedFlow<HermesChatEvent>(extraBufferCapacity = 8)
    override val events: Flow<HermesChatEvent> = mutableEvents
    var resumedDurableId: DurableSessionId? = null
    var submittedText: String? = null
    var closed = false
    val fileAttachCalls = mutableListOf<Triple<String, String, String>>()
    val imageAttachCalls = mutableListOf<Pair<String, String>>()

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession = resume(durableSessionId, profile)

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumedDurableId = durableSessionId
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-1"),
            durableSessionId = durableSessionId,
            resumed = true,
            messages = emptyList(),
            running = false,
            inflight = null as InflightPrompt?,
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submittedText = text
        submitFailure?.let { throw it }
        mutableEvents.emit(HermesChatEvent.MessageStart(runtimeSessionId, null))
        mutableEvents.emit(HermesChatEvent.MessageDelta(runtimeSessionId, "Hello "))
        mutableEvents.emit(HermesChatEvent.MessageDelta(runtimeSessionId, "world"))
        mutableEvents.emit(HermesChatEvent.MessageComplete(runtimeSessionId, "Hello world", "done"))
        return PromptSubmission("streaming")
    }

    override suspend fun attachFile(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        mimeType: String,
        base64Content: String,
    ): String {
        fileAttachCalls += Triple(filename, mimeType, base64Content)
        return "@file:.hermes/desktop-attachments/$filename"
    }

    override suspend fun attachImage(
        runtimeSessionId: RuntimeSessionId,
        filename: String,
        base64Content: String,
    ) {
        imageAttachCalls += filename to base64Content
    }

    override suspend fun close() {
        closed = true
    }
}

private class RunEventChatSession(
    runtimeId: String = "runtime-run-events",
) : HermesChatSession {
    val runtimeSessionId = RuntimeSessionId(runtimeId)
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = true,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    suspend fun emit(event: HermesChatEvent) {
        channel.send(event)
    }

    override suspend fun close() {
        channel.close()
    }
}

private class ControllerChatSession(
    runtimeId: String = "runtime-controller",
) : HermesChatSession {
    val runtimeSessionId = RuntimeSessionId(runtimeId)
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()
    val clarificationCalls = mutableListOf<Pair<String, String>>()
    val approvalCalls = mutableListOf<Triple<RuntimeSessionId, String, Boolean>>()
    val approvalRequestIds = mutableListOf<String?>()
    val blockingCalls = mutableListOf<Triple<UnsupportedBlockingKind, String, String>>()
    val interruptCalls = mutableListOf<RuntimeSessionId>()
    val steerCalls = mutableListOf<Pair<RuntimeSessionId, String>>()
    val usageCalls = mutableListOf<RuntimeSessionId>()
    val contextCalls = mutableListOf<RuntimeSessionId>()
    val compressCalls = mutableListOf<Pair<RuntimeSessionId, String?>>()
    val undoCalls = mutableListOf<RuntimeSessionId>()
    val branchCalls = mutableListOf<Triple<RuntimeSessionId, Int?, String?>>()
    val pauseDelegationCalls = mutableListOf<Boolean>()
    val subagentInterruptCalls = mutableListOf<String>()
    val subagentSteerCalls = mutableListOf<Triple<RuntimeSessionId, String, String>>()
    val processListCalls = mutableListOf<RuntimeSessionId>()
    val processListStarted = CompletableDeferred<Unit>()
    var processListGate: CompletableDeferred<Unit>? = null
    var processListNonCooperative = false
    var processListResponse: List<ProcessRow> = emptyList()
    var clarificationResponse = CompletableDeferred<HermesChatResponse>()
    var approvalResponse = CompletableDeferred<HermesChatResponse>()
    var blockingResponse = CompletableDeferred<HermesChatResponse>()
    var interruptResponse = CompletableDeferred<HermesChatResponse>()
    var clarificationNonCooperative = false
    var approvalNonCooperative = false
    var interruptNonCooperative = false
    var closed = false

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ) = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = true,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    override suspend fun loadProcessList(runtimeSessionId: RuntimeSessionId): List<ProcessRow> {
        processListCalls += runtimeSessionId
        processListGate?.let { gate ->
            if (!processListStarted.isCompleted) processListStarted.complete(Unit)
            if (processListNonCooperative) {
                withContext(NonCancellable) { gate.await() }
            } else {
                gate.await()
            }
        }
        return processListResponse
    }

    override suspend fun respondToClarification(
        requestId: String,
        answer: String,
    ): HermesChatResponse {
        clarificationCalls += requestId to answer
        return if (clarificationNonCooperative) {
            withContext(NonCancellable) { clarificationResponse.await() }
        } else {
            clarificationResponse.await()
        }
    }

    override suspend fun respondToApproval(
        runtimeSessionId: RuntimeSessionId,
        choice: String,
        all: Boolean,
        requestId: String?,
    ): HermesChatResponse {
        approvalCalls += Triple(runtimeSessionId, choice, all)
        approvalRequestIds += requestId
        return if (approvalNonCooperative) {
            withContext(NonCancellable) { approvalResponse.await() }
        } else {
            approvalResponse.await()
        }
    }

    override suspend fun respondToBlockingPrompt(
        kind: UnsupportedBlockingKind,
        requestId: String,
        value: String,
    ): HermesChatResponse {
        blockingCalls += Triple(kind, requestId, value)
        return blockingResponse.await()
    }

    override suspend fun interruptSession(
        runtimeSessionId: RuntimeSessionId,
    ): HermesChatResponse {
        interruptCalls += runtimeSessionId
        return if (interruptNonCooperative) {
            withContext(NonCancellable) { interruptResponse.await() }
        } else {
            interruptResponse.await()
        }
    }

    override suspend fun steer(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): SessionSteerResult {
        steerCalls += runtimeSessionId to text
        return SessionSteerResult(status = "queued", text = text)
    }

    override suspend fun loadSessionUsage(runtimeSessionId: RuntimeSessionId): SessionUsage {
        usageCalls += runtimeSessionId
        return SessionUsage(totalTokens = 42, contextUsedTokens = 42, contextMaxTokens = 100)
    }

    override suspend fun loadContextBreakdown(runtimeSessionId: RuntimeSessionId): SessionContextBreakdown {
        contextCalls += runtimeSessionId
        return SessionContextBreakdown(
            categories = listOf(ContextBreakdownCategory("Conversation", tokens = 42)),
            usedTokens = 42,
            maxTokens = 100,
            percent = 42.0,
        )
    }

    override suspend fun compressSession(
        runtimeSessionId: RuntimeSessionId,
        focusTopic: String?,
    ): SessionCompressResult {
        compressCalls += runtimeSessionId to focusTopic
        return SessionCompressResult(
            status = "compressed",
            messages = listOf(buildJsonObject {
                put("role", "assistant")
                put("text", "Compressed summary")
            }),
        )
    }

    override suspend fun undoSession(runtimeSessionId: RuntimeSessionId): SessionUndoResult {
        undoCalls += runtimeSessionId
        return SessionUndoResult(removed = 2)
    }

    override suspend fun branchSession(
        runtimeSessionId: RuntimeSessionId,
        count: Int?,
        name: String?,
    ): SessionBranchResult {
        branchCalls += Triple(runtimeSessionId, count, name)
        return SessionBranchResult(
            runtimeSessionId = RuntimeSessionId("runtime-branch"),
            durableSessionId = DurableSessionId("stored-branch"),
            title = "Test branch",
            messages = listOf(buildJsonObject {
                put("role", "user")
                put("text", "Branch question")
            }),
        )
    }

    override suspend fun pauseDelegation(paused: Boolean): DelegationPauseResult {
        pauseDelegationCalls += paused
        return DelegationPauseResult(paused)
    }

    override suspend fun interruptSubagent(subagentId: String): SubagentInterruptResult {
        subagentInterruptCalls += subagentId
        return SubagentInterruptResult(found = true, subagentId = subagentId)
    }

    override suspend fun steerSubagent(
        runtimeSessionId: RuntimeSessionId,
        subagentId: String,
        text: String,
    ): SubagentSteerResult {
        subagentSteerCalls += Triple(runtimeSessionId, subagentId, text)
        return SubagentSteerResult(status = "queued", text = text)
    }

    suspend fun emit(event: HermesChatEvent) {
        channel.send(event)
    }

    override suspend fun close() {
        closed = true
        channel.close()
    }
}

private class ReopenPreservingChatSession : HermesChatSession {
    val runtimeSessionId = RuntimeSessionId("runtime-reopen-preserve")
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()
    var resumeCalls = 0
    var closeCalls = 0

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeCalls += 1
        return ResumedChatSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            resumed = true,
            messages = emptyList(),
            running = true,
            inflight = InflightPrompt("Question", "partial answer", true),
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ) = PromptSubmission("streaming")

    suspend fun emit(event: HermesChatEvent) {
        channel.send(event)
    }

    override suspend fun close() {
        closeCalls += 1
    }
}

private class ReconnectingChatSession(
    runtimeId: String,
    private val running: Boolean,
    private val inflightText: String?,
    private val inflightUser: String? = null,
    private val resumeMessages: List<JsonObject> = emptyList(),
    private val submitFailure: Exception? = null,
    private val onResume: (Channel<HermesChatEvent>, RuntimeSessionId) -> Unit = { _, _ -> },
    private val onSubmit: (Channel<HermesChatEvent>, RuntimeSessionId) -> Unit = { _, _ -> },
) : HermesChatSession {
    private val runtimeSessionId = RuntimeSessionId(runtimeId)
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    var closed = false
    override val events: Flow<HermesChatEvent> = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        onResume(channel, runtimeSessionId)
        return ResumedChatSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = durableSessionId,
            resumed = true,
            messages = resumeMessages,
            running = running,
            inflight = if (inflightUser != null || inflightText != null) {
                InflightPrompt(inflightUser, inflightText, true)
            } else {
                null
            },
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        onSubmit(channel, runtimeSessionId)
        submitFailure?.let { throw it }
        return PromptSubmission("streaming")
    }

    override suspend fun close() {
        closed = true
    }
}
