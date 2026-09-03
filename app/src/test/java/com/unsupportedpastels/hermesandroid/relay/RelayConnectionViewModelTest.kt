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
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.mercury.core.relay.AndroidRelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
            relaySessionFactory = { _, _ -> FakeRelaySession() },
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
    fun retryReconnectsTheActiveRelayTarget() = runTest(dispatcher) {
        var attempts = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(ServerCatalog.empty())),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
                    error("direct probe must not run")
            },
            relaySessionFactory = { _, _ ->
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
            relaySessionFactory = { _, _ -> relaySession },
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

    private class FakeRelaySession : HermesChatSession {
        override val events = emptyFlow<HermesChatEvent>()
        var closeCalls = 0
        var profileOptionsCalls = 0

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

        override suspend fun relayRequest(method: String, params: JsonObject): JsonObject {
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

        override suspend fun resume(durableSessionId: DurableSessionId, profile: String?) =
            ResumedChatSession(RuntimeSessionId("runtime"), durableSessionId, true, emptyList(), false, null)

        override suspend fun createSession(
            durableSessionId: DurableSessionId,
            profile: String?,
            workspacePath: String?,
        ) = ResumedChatSession(RuntimeSessionId("runtime-draft"), durableSessionId, true, emptyList(), false, null)

        override suspend fun setReasoning(runtimeSessionId: RuntimeSessionId, effort: String) = Unit

        override suspend fun setFast(runtimeSessionId: RuntimeSessionId, enabled: Boolean) = Unit

        var lastSubmitted: String? = null
        override suspend fun submitPrompt(runtimeSessionId: RuntimeSessionId, text: String): PromptSubmission {
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
        hostPublicKey = AndroidRelayCrypto.x25519PublicKey(ByteArray(32) { (it + 0x20).toByte() }),
        deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() }),
        deviceStaticPrivateKey = ByteArray(32) { (it + 0x40).toByte() },
        fingerprint = "0123456789abcdef",
        status = RelayTargetStatus.Approved,
        createdAtEpochSeconds = 1,
        lastUsedEpochSeconds = null,
    )
}
