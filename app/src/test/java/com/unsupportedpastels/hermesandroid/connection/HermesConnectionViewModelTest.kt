package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.cache.CacheScope
import com.unsupportedpastels.hermesandroid.cache.CachedSession
import com.unsupportedpastels.hermesandroid.cache.OfflineCacheRepository
import com.unsupportedpastels.hermesandroid.cache.OfflineCacheSnapshot
import com.unsupportedpastels.hermesandroid.gateway.CacheSource
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.TunnelConnectionFailure
import com.unsupportedpastels.hermesandroid.gateway.HermesChatConnector
import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.HermesChatMethodNotFoundException
import com.unsupportedpastels.hermesandroid.gateway.HermesChatSession
import com.unsupportedpastels.hermesandroid.gateway.HermesChatTransportException
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.gateway.OperationalStatus
import com.unsupportedpastels.hermesandroid.gateway.OperationalStatusState
import com.unsupportedpastels.hermesandroid.gateway.OperationalHealth
import com.unsupportedpastels.hermesandroid.gateway.OperationalPressure
import com.unsupportedpastels.hermesandroid.gateway.lastGoodOrNull
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.CurrentModelInfo
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.PromptSubmission
import com.unsupportedpastels.hermesandroid.app.ProjectSessionsResult
import com.unsupportedpastels.hermesandroid.app.ProjectTreeResult
import com.unsupportedpastels.hermesandroid.gateway.ResumedChatSession
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobAction
import com.unsupportedpastels.hermesandroid.gateway.CronJobRun
import com.unsupportedpastels.hermesandroid.gateway.CronJobRunsState
import com.unsupportedpastels.hermesandroid.gateway.CronJobScope
import com.unsupportedpastels.hermesandroid.gateway.CronRestCapability
import com.unsupportedpastels.hermesandroid.gateway.CronJobsState
import com.unsupportedpastels.hermesandroid.gateway.HermesChatProtocolException
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
import com.unsupportedpastels.hermesandroid.app.NO_PROJECT_BUCKET_ID
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.ProcessRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import com.unsupportedpastels.hermesandroid.voice.VoiceServerConfig
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HermesConnectionViewModelTest {
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
    fun externalTunnelBootstrapsMemoryCredentialBeforeProtectedSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val client = TunnelConnectionClient()
        val bootstrap = RecordingTunnelBootstrap(origin, listOf("session-token"))
        val store = RecordingNativeTokenStore()

        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = client,
            tokenStore = store,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        assertEquals(viewModel.snapshots.value.connectionError, ConnectionState.Connected, viewModel.snapshots.value.connectionState)
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)
        assertEquals(listOf("Tunnel session"), viewModel.snapshots.value.durableSessions.map { it.title })
        assertEquals(1, bootstrap.calls)
        assertTrue(client.credentials.single() is HermesCredential.LoopbackSession)
        assertEquals(0, store.loadCalls)
        assertEquals(0, store.saveCalls)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun externalTunnelPublishesSpecificUnavailableAndBootstrapErrors() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val unavailable = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = TunnelConnectionClient(probeFailure = HermesConnectionException("refused")),
            loopbackSessionBootstrapClient = RecordingTunnelBootstrap(origin, emptyList()),
        )
        advanceUntilIdle()
        assertEquals(TunnelConnectionFailure.TunnelUnavailable, unavailable.snapshots.value.tunnelConnectionFailure)
        assertTrue(unavailable.snapshots.value.connectionError!!.startsWith("SSH tunnel unavailable"))

        val rejected = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = TunnelConnectionClient(),
            loopbackSessionBootstrapClient = object : LoopbackSessionBootstrapClient {
                override suspend fun bootstrap(origin: ServerOrigin) = LoopbackSessionBootstrapResult.Failure(
                    LoopbackSessionBootstrapFailure.TokenAbsent,
                )
            },
        )
        advanceUntilIdle()
        assertEquals(TunnelConnectionFailure.BootstrapRejected, rejected.snapshots.value.tunnelConnectionFailure)
        assertEquals("Hermes tunnel authorization bootstrap was rejected", rejected.snapshots.value.connectionError)
    }

    @Test
    fun externalTunnelRetriesAnIdempotentReadAfterOneSharedRebootstrap() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val client = TunnelConnectionClient(rejectSessionCalls = setOf(2))
        val bootstrap = RecordingTunnelBootstrap(origin, listOf("old-token", "new-token"))
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = client,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        viewModel.refreshDurableSessions(archivedOnly = true).join()
        advanceUntilIdle()

        assertEquals(2, bootstrap.calls)
        assertEquals(3, client.credentials.size)
        assertTrue(client.credentials[1] !== client.credentials[2])
        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
    }

    @Test
    fun externalTunnelNeverReplaysMutationAfterAuthorizationRejection() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val client = TunnelConnectionClient(rejectMutation = true)
        val bootstrap = RecordingTunnelBootstrap(origin, listOf("session-token", "unused-token"))
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = client,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        assertFalse(viewModel.setVoiceAutoTts(true))

        assertEquals(1, client.mutationCalls)
        assertEquals(1, bootstrap.calls)
    }

    @Test
    fun concurrentlyRejectedReadsShareOneSuccessfulBootstrap() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val client = ConcurrentHostReadTunnelClient(expectedConcurrentReads = 3)
        val bootstrap = RecordingTunnelBootstrap(origin, listOf("old-token", "new-token"))
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = client,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        val reads = listOf(
            async { runCatching { viewModel.loadHostFiles("/workspace") } },
            async { runCatching { viewModel.loadManagedFile("/workspace/notes.txt") } },
            async { runCatching { viewModel.downloadManagedImage("/workspace/shot.png") } },
        )
        advanceUntilIdle()

        assertTrue(reads.all { it.await().isSuccess })
        // One bootstrap for the initial connection plus exactly one shared recovery.
        assertEquals(2, bootstrap.calls)
        assertEquals(3, client.retryCredentials.size)
        assertEquals(1, client.retryCredentials.distinct().size)
        assertEquals(6, client.readAttempts)
    }

    @Test
    fun concurrentlyRejectedReadsShareOneFailedBootstrap() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val client = ConcurrentHostReadTunnelClient(expectedConcurrentReads = 3)
        val bootstrap = RecordingTunnelBootstrap(origin, listOf("old-token"))
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = client,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        val reads = listOf(
            async { runCatching { viewModel.loadHostFiles("/workspace") } },
            async { runCatching { viewModel.loadManagedFile("/workspace/notes.txt") } },
            async { runCatching { viewModel.downloadManagedImage("/workspace/shot.png") } },
        )
        advanceUntilIdle()

        assertTrue(reads.all { it.await().isFailure })
        // A failed bootstrap must be shared too, or every waiter starts its own.
        assertEquals(2, bootstrap.calls)
        assertEquals(0, client.retryCredentials.size)
    }

    @Test
    fun secondRejectionStopsAfterOneRetryAndPublishesCredentialRejected() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val client = ConcurrentHostReadTunnelClient(
            expectedConcurrentReads = 1,
            rejectEveryRead = true,
        )
        val bootstrap = RecordingTunnelBootstrap(origin, listOf("old-token", "new-token", "unused"))
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = client,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        val failure = runCatching { viewModel.loadHostFiles("/workspace") }
        advanceUntilIdle()

        assertTrue(failure.isFailure)
        assertEquals(2, client.readAttempts)
        assertEquals(2, bootstrap.calls)
        assertEquals(
            TunnelConnectionFailure.CredentialRejected,
            viewModel.snapshots.value.tunnelConnectionFailure,
        )
    }

    @Test
    fun rebootstrapIsDiscardedAfterTheTunnelPortChanges() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val replacement = ServerOrigin.parse("http://127.0.0.1:29119")
        val settings = MutableStateFlow(tunnelSettings(origin))
        val rejectionGate = CompletableDeferred<Unit>()
        val client = ConcurrentHostReadTunnelClient(
            expectedConcurrentReads = 1,
            rejectEveryRead = true,
            rejectionGate = rejectionGate,
        )
        val bootstrap = SwitchableTunnelBootstrap()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        val read = async { runCatching { viewModel.loadHostFiles("/workspace") } }
        advanceUntilIdle()
        settings.value = tunnelSettings(replacement)
        advanceUntilIdle()
        rejectionGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(read.await().isFailure)
        assertEquals(listOf(origin, replacement), bootstrap.origins)
        assertEquals(0, client.retryCredentials.size)
        assertEquals(ConnectionState.Connected, viewModel.snapshots.value.connectionState)
    }

    @Test
    fun managementReadsRecoverThroughTheSharedRetryHelper() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("http://127.0.0.1:19119")
        val client = ManagementReadTunnelClient()
        val bootstrap = RecordingTunnelBootstrap(origin, listOf("old-token", "new-token"))
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(tunnelSettings(origin)),
            client = client,
            loopbackSessionBootstrapClient = bootstrap,
        )
        advanceUntilIdle()

        viewModel.loadManagementSettings(refreshStatus = false).join()
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(null, snapshot.managementError)
        assertEquals("hermes-4", snapshot.currentModelInfo?.model)
        assertEquals("high", snapshot.profileReasoningDefault)
        assertEquals("high", snapshot.profileReasoningEffort)
        assertEquals(mapOf(ModelSelection("nous", "hermes-4") to "low"), snapshot.profileModelReasoningOverrides)
        assertEquals(listOf("Tunnel session"), snapshot.durableSessions.map { it.title })
        assertEquals(2, bootstrap.calls)
    }

    @Test
    fun configuredOriginIsProbedAndBecomesReachableSignInRequired() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = FakeHermesConnectionClient()
        val info =
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = true,
                nativeOAuthSupported = true,
                providers = listOf(
                    HermesAuthProvider("nous", "Nous Research", supportsPassword = false),
                ),
            )
        val viewModel = HermesConnectionViewModel(settings, client)

        runCurrent()
        assertEquals(ConnectionState.Connecting, viewModel.snapshots.value.connectionState)
        client.response.complete(info)
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(ConnectionState.Connected, snapshot.connectionState)
        assertEquals(AuthenticationState.SignInRequired, snapshot.authenticationState)
        assertEquals("0.20.0", snapshot.serverVersion)
        assertTrue(snapshot.nativeOAuthSupported)
        assertEquals(listOf("nous"), snapshot.authProviders.map { it.name })
        assertEquals(listOf(origin), client.probedOrigins)
    }

    @Test
    fun serverWithoutAuthenticationPublishesProbedDurableSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = FakeHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(settings, client)

        runCurrent()
        client.response.complete(
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = false,
                nativeOAuthSupported = false,
                providers = emptyList(),
                sessions = listOf(
                    SessionSummary(DurableSessionId("stored-1"), "First session"),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(AuthenticationState.NotRequired, viewModel.snapshots.value.authenticationState)
        assertEquals(
            listOf("First session"),
            viewModel.snapshots.value.durableSessions.map { it.title },
        )
    }

    @Test
    fun serverWithoutAuthenticationUsesNoCredentialForVoiceRestProbe() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val client = UnauthenticatedVoiceClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
        )

        advanceUntilIdle()
        viewModel.refreshVoiceCapabilities()

        assertEquals(AuthenticationState.NotRequired, viewModel.snapshots.value.authenticationState)
        assertEquals(
            listOf(HermesCredential.None, HermesCredential.None),
            client.credentials,
        )
    }

    @Test
    fun serverWithoutAuthenticationLoadsRecentSessionsWithoutAToken() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = UnauthenticatedRecentSessionsClient()
        val viewModel = HermesConnectionViewModel(settings, client)

        advanceUntilIdle()
        viewModel.loadRecentSessions().join()

        assertEquals(1, client.recentSessionPageCalls)
        assertTrue(client.sawNullAccessToken)
        assertEquals(
            listOf("Public session"),
            viewModel.snapshots.value.recentSessions.sessions.map { it.title },
        )
        assertEquals(null, viewModel.snapshots.value.recentSessions.error)
    }

    @Test
    fun failedConnectionPreservesSessionsLoadedFromOfflineCache() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val cached = SessionSummary(DurableSessionId("cached-1"), "Cached session")
        val cache = RecordingOfflineCacheRepository(
            snapshots = mapOf(
                CacheScope(origin, "default") to OfflineCacheSnapshot(
                    listOf(CachedSession(cached, updatedAtEpochSeconds = 10)),
                ),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = object : HermesConnectionClient {
                override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
                    throw HermesConnectionException("offline")
            },
            cacheRepository = cache,
            nowEpochSeconds = { 11 },
        )

        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.snapshots.value.connectionState)
        assertEquals(CacheSource.Cached, viewModel.snapshots.value.sessionMetadataSource)
        assertEquals(listOf(cached), viewModel.snapshots.value.durableSessions)
    }

    @Test
    fun delayedOfflineCacheCannotReplaceLiveSessionsAfterSuccessfulFirstLaunch() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val cached = SessionSummary(DurableSessionId("cached-1"), "Cached session")
        val live = SessionSummary(DurableSessionId("live-1"), "Live session")
        val cacheReadBarrier = CompletableDeferred<Unit>()
        val cache = RecordingOfflineCacheRepository(
            snapshots = mapOf(
                CacheScope(origin, "default") to OfflineCacheSnapshot(
                    listOf(CachedSession(cached, updatedAtEpochSeconds = 10)),
                ),
            ),
            readBarrier = cacheReadBarrier,
        )
        val client = FakeHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            cacheRepository = cache,
            nowEpochSeconds = { 11 },
        )
        runCurrent()
        client.response.complete(
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = false,
                nativeOAuthSupported = false,
                providers = emptyList(),
                sessions = listOf(live),
            ),
        )
        runCurrent()
        assertEquals(CacheSource.Live, viewModel.snapshots.value.sessionMetadataSource)
        assertEquals(listOf(live), viewModel.snapshots.value.durableSessions)

        cacheReadBarrier.complete(Unit)
        advanceUntilIdle()

        assertEquals(CacheSource.Live, viewModel.snapshots.value.sessionMetadataSource)
        assertEquals(listOf(live), viewModel.snapshots.value.durableSessions)
    }

    @Test
    fun clearingOfflineCachePreservesLiveServerSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val live = SessionSummary(DurableSessionId("live-1"), "Live session")
        val client = FakeHermesConnectionClient()
        val cache = RecordingOfflineCacheRepository()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            cacheRepository = cache,
        )
        runCurrent()
        client.response.complete(
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = false,
                nativeOAuthSupported = false,
                providers = emptyList(),
                sessions = listOf(live),
            ),
        )
        advanceUntilIdle()

        viewModel.clearOfflineCache().join()

        assertEquals(CacheSource.Live, viewModel.snapshots.value.sessionMetadataSource)
        assertEquals(listOf(live), viewModel.snapshots.value.durableSessions)
        assertEquals(listOf<CacheScope?>(null), cache.clearedScopes)
    }

    @Test
    fun changingProfileClearsOnlyThePreviousProfileTranscriptTail() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val client = AuthenticatingHermesConnectionClient().apply {
            profiles = listOf("default", "work")
        }
        val cache = RecordingOfflineCacheRepository()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            cacheRepository = cache,
            nowEpochSeconds = { 1_900_000_000 },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        viewModel.loadManagementSettings("work").join()
        advanceUntilIdle()

        assertEquals(listOf(CacheScope(origin, "default")), cache.clearedTranscriptScopes)
    }

    @Test
    fun nativeSignInVerifiesBearerAndPublishesDurableSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient()
        val login = FakeNativeLogin()
        val metadata = MetadataOnlyProjectSession(
            ProjectTreeResult(
                projects = listOf(
                    ProjectSummary(ProjectId("project-1"), "App", "/workspace/app", 0, emptyList()),
                ),
            ),
        )
        var metadataConnections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            nativeLogin = login,
            projectConnector = HermesChatConnector { _, _ ->
                metadataConnections += 1
                metadata
            },
        )
        runCurrent()
        client.probeResponse.complete(
            HermesConnectionInfo(
                version = "0.20.0",
                authRequired = true,
                nativeOAuthSupported = true,
                providers = listOf(HermesAuthProvider("nous", "Nous Research")),
            ),
        )
        advanceUntilIdle()

        viewModel.signIn { }
        runCurrent()
        assertEquals(AuthenticationState.SigningIn, viewModel.snapshots.value.authenticationState)
        login.response.complete(
            NativeTokenSet(
                accessToken = "opaque-access",
                refreshToken = "opaque-refresh",
                provider = "nous",
            ),
        )
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                userId = "user",
                sessions = listOf(
                    SessionSummary(DurableSessionId("stored-1"), "First session"),
                ),
            ),
        )
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(listOf("First session"), snapshot.durableSessions.map { it.title })
        assertTrue(client.authenticatedWith is HermesCredential.NativeBearer)
        assertEquals(ProjectId("project-1"), snapshot.projects.single().id)
        assertEquals(false, metadata.closed)

        client.hostDirectoryResponse = HostDirectoryListing(
            path = "/srv",
            directories = listOf(HostDirectoryEntry("app", "/srv/app")),
            parentPath = "/",
        )
        val hostFolders = viewModel.loadHostDirectories("/srv")

        assertEquals(listOf("app"), hostFolders.directories.map { it.name })
        assertEquals(1, client.hostDirectoryRequests.size)
        val hostDirectoryRequest = client.hostDirectoryRequests.single()
        assertEquals(origin, hostDirectoryRequest.first)
        assertTrue(hostDirectoryRequest.second is HermesCredential.NativeBearer)
        assertEquals("/srv", hostDirectoryRequest.third)
        assertEquals(1, metadataConnections)
    }

    @Test
    fun serverProbeAndSavedTokenLoadRunConcurrently() = runTest(dispatcher) {
        val probeStarted = CompletableDeferred<Unit>()
        val tokenLoadStarted = CompletableDeferred<Unit>()

        val result = async {
            probeAndLoadSavedTokenConcurrently(
                probe = {
                    probeStarted.complete(Unit)
                    tokenLoadStarted.await()
                    "probe"
                },
                loadSavedToken = {
                    tokenLoadStarted.complete(Unit)
                    probeStarted.await()
                    "tokens"
                },
                needsSavedToken = { true },
            )
        }

        advanceUntilIdle()

        assertTrue(probeStarted.isCompleted)
        assertTrue(tokenLoadStarted.isCompleted)
        assertEquals("probe" to "tokens", result.await())
    }

    @Test
    fun authenticationAndProjectObserverPrefetchRunConcurrently() = runTest(dispatcher) {
        val authenticationStarted = CompletableDeferred<Unit>()
        val metadataStarted = CompletableDeferred<Unit>()
        val metadata = MetadataOnlyProjectSession(ProjectTreeResult(emptyList()))

        val result = async {
            authenticateAndPrefetchConcurrently(
                authenticate = {
                    authenticationStarted.complete(Unit)
                    metadataStarted.await()
                    AuthenticatedHermesConnection("user", emptyList())
                },
                prefetchMetadata = {
                    metadataStarted.complete(Unit)
                    authenticationStarted.await()
                    metadata
                },
                discardMetadata = { it.close() },
            )
        }
        advanceUntilIdle()

        assertTrue(authenticationStarted.isCompleted)
        assertTrue(metadataStarted.isCompleted)
        assertEquals("user", result.await().first.userId)
        assertEquals(metadata, result.await().second.getOrNull())
        assertEquals(false, metadata.closed)
    }

    @Test
    fun failedAuthenticationDiscardsCompletedProjectObserverPrefetch() = runTest(dispatcher) {
        val metadataReady = CompletableDeferred<Unit>()
        val metadata = MetadataOnlyProjectSession(ProjectTreeResult(emptyList()))

        val failure = runCatching {
            authenticateAndPrefetchConcurrently(
                authenticate = {
                    metadataReady.await()
                    throw HermesAuthenticationRejectedException("rejected")
                },
                prefetchMetadata = {
                    metadataReady.complete(Unit)
                    metadata
                },
                discardMetadata = { it.close() },
            )
        }.exceptionOrNull()

        assertTrue(failure is HermesAuthenticationRejectedException)
        assertTrue(metadata.closed)
    }

    @Test
    fun authenticatedConnectionLoadsProjectsThroughMetadataOnlySession() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        var now = 1_900_000_000L
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient()
        val tree = CompletableDeferred<ProjectTreeResult>()
        val metadata = MetadataOnlyProjectSession.fromTree(tree)
        val projectTree = ProjectTreeResult(
                projects = listOf(
                    ProjectSummary(
                        id = ProjectId("project-1"),
                        label = "App",
                        primaryPath = "/workspace/app",
                        sessionCount = 1,
                        previewSessions = listOf(
                            SessionSummary(
                                DurableSessionId("stored-1"),
                                "Preview title",
                                workspacePath = "/preview",
                            ),
                        ),
                    ),
                ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ -> metadata },
            nowEpochSeconds = { now },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        runCurrent()
        assertEquals(ProjectLoadState.Loading, viewModel.snapshots.value.projectState)
        tree.complete(projectTree)
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(ProjectId("project-1"), snapshot.projects.single().id)
        assertEquals(
            ProjectId("project-1"),
            (snapshot.projectState as ProjectLoadState.Loaded).projects.single().id,
        )
        assertEquals(listOf("stored-1"), snapshot.projects.single().previewSessions.map { it.id.value })
        assertEquals("Preview title", snapshot.projects.single().previewSessions.single().title)
        assertEquals(0, metadata.resumeCalls)
        assertEquals(0, metadata.createCalls)
        assertEquals(false, metadata.closed)

        now = 2_100_000_000L
        val draftId = viewModel.createNewSession()
        viewModel.sendMessage(draftId, "Trigger token refresh")
        advanceUntilIdle()

        val expired = viewModel.snapshots.value
        assertEquals(AuthenticationState.SignInRequired, expired.authenticationState)
        assertTrue(expired.projects.isEmpty())
        assertEquals(ProjectLoadState.Loaded(emptyList()), expired.projectState)
        assertTrue(expired.projectSessions.isEmpty())
        assertTrue(expired.projectSessionStates.isEmpty())
        assertEquals(null, expired.activeProjectId)
        assertTrue(metadata.closed)
    }

    @Test
    fun projectCreationReusesMetadataSessionAndPublishesServerProject() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient()
        val createdProject = ProjectSummary(
            ProjectId("p-created"),
            "Demo",
            "/srv/demo",
            0,
            emptyList(),
        )
        val metadata = MetadataOnlyProjectSession.forTreeAndProjectCreation(
            tree = ProjectTreeResult(emptyList()),
            created = createdProject,
        )
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ ->
                connections += 1
                metadata
            },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val result = viewModel.createProject("Demo", "/srv/demo")

        assertEquals(createdProject, result)
        assertEquals(listOf(createdProject), viewModel.snapshots.value.projects)
        assertEquals(ProjectId("p-created"), viewModel.snapshots.value.activeProjectId)
        assertEquals(0, metadata.resumeCalls)
        assertEquals(0, metadata.createCalls)
        assertEquals(1, connections)
        assertEquals(false, metadata.closed)
    }

    @Test
    fun unsupportedProjectMetadataPreservesAuthenticatedFlatSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient()
        val tokenStore = FixedTokenStore()
        val durable = SessionSummary(DurableSessionId("stored-1"), "REST session")
        val metadata = MetadataOnlyProjectSession(
            HermesChatMethodNotFoundException("projects.tree"),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = tokenStore,
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(durable)))
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(listOf(durable), snapshot.durableSessions)
        assertEquals(ProjectLoadState.Unsupported, snapshot.projectState)
        assertEquals(0, tokenStore.clearCalls)
        assertTrue(metadata.closed)
    }

    @Test
    fun transientProjectMetadataErrorPreservesAuthenticationAndFlatSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient()
        val tokenStore = FixedTokenStore()
        val durable = SessionSummary(DurableSessionId("stored-1"), "REST session")
        val metadata = MetadataOnlyProjectSession(
            HermesChatTransportException("temporary metadata outage"),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = tokenStore,
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(durable)))
        advanceUntilIdle()

        val snapshot = viewModel.snapshots.value
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(listOf(durable), snapshot.durableSessions)
        val projectState = snapshot.projectState
        assertTrue(projectState is ProjectLoadState.TransientError)
        assertEquals("temporary metadata outage", (projectState as ProjectLoadState.TransientError).message)
        assertEquals(0, tokenStore.clearCalls)
        assertTrue(metadata.closed)
    }

    @Test
    fun newDraftHydratesProfileModelAndReasoningDefaultsWithoutCreatingRuntime() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            defaultModelOptions = ModelOptions(
                current = ModelSelection("openai-codex", "gpt-5.6-sol"),
                providers = emptyList(),
            )
            profileReasoningEffort = "high"
        }
        var chatConnections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ ->
                chatConnections += 1
                error("draft defaults must not create a runtime")
            },
            projectConnector = null,
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(draftId)
        assertEquals("openai-codex", chat.provider)
        assertEquals("gpt-5.6-sol", chat.model)
        assertEquals("high", chat.reasoningEffort)
        assertTrue(chat.draftDefaultsLoaded)
        assertEquals(0, chatConnections)
    }

    @Test
    fun newDraftUsesTheSameSelectedProfileForPreviewAndFirstRuntime() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            profiles = listOf("default", "work")
            defaultModelOptions = ModelOptions(
                current = ModelSelection("openai-codex", "gpt-5.6-sol"),
                providers = emptyList(),
            )
        }
        val chatSession = RecordingProjectDraftChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
            projectConnector = null,
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        viewModel.loadManagementSettings("work").join()

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()
        viewModel.sendMessage(draftId, "Use work defaults")
        advanceUntilIdle()

        assertEquals("work", client.defaultModelProfiles.last())
        assertEquals("work", chatSession.createdProfile)
    }

    @Test
    fun newDraftPreservesResolvedModelWhenReasoningLookupFails() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            defaultModelOptions = ModelOptions(
                current = ModelSelection("openai-codex", "gpt-5.6-sol"),
                providers = emptyList(),
            )
            profileReasoningFailure = HermesConnectionException("malformed config")
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = null,
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(draftId)
        assertEquals("openai-codex", chat.provider)
        assertEquals("gpt-5.6-sol", chat.model)
        assertEquals(null, chat.reasoningEffort)
        assertTrue(chat.draftDefaultsLoaded)
    }

    @Test
    fun createProjectSessionPublishesExactProjectDraftWithValidatedWorkspaceWithoutRuntime() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val firstProjectId = ProjectId("project:/srv/one/app")
        val secondProjectId = ProjectId("project:/srv/two/app")
        val metadata = MetadataOnlyProjectSession(
            ProjectTreeResult(
                projects = listOf(
                    ProjectSummary(firstProjectId, "App", "/srv/one/app", 0, emptyList()),
                    ProjectSummary(secondProjectId, "App", " /srv/two/app ", 0, emptyList()),
                ),
            ),
        )
        val client = AuthenticatingHermesConnectionClient()
        var chatConnections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ ->
                chatConnections += 1
                error("project draft must not connect until send")
            },
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createProjectSession(secondProjectId, "Task in App")
        val snapshot = viewModel.snapshots.value
        val draft = snapshot.projectSessions.getValue(secondProjectId).single { it.id == draftId }

        assertEquals(secondProjectId, draft.projectId)
        assertEquals("/srv/two/app", draft.workspacePath)
        assertTrue(draft.isLocalDraft)
        assertEquals("Task in App", draft.title)
        assertTrue(snapshot.projectSessions[firstProjectId].orEmpty().none { it.id == draftId })
        assertEquals(0, chatConnections)
        assertEquals(false, metadata.closed)
    }

    @Test
    fun projectDraftWithoutValidWorkspaceRejectsSendBeforeConnecting() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project-without-workspace")
        val metadata = MetadataOnlyProjectSession(
            ProjectTreeResult(
                projects = listOf(ProjectSummary(projectId, "No folder", "relative/path", 0, emptyList())),
            ),
        )
        val client = AuthenticatingHermesConnectionClient()
        var chatConnections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ ->
                chatConnections += 1
                error("No workspace draft must not connect")
            },
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createProjectSession(projectId, "Needs a folder")
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Start this task")
        advanceUntilIdle()

        assertEquals(0, chatConnections)
        assertEquals("No workspace", viewModel.snapshots.value.chatSessions.getValue(draftId).error)
    }

    @Test
    fun homeBucketDraftSendsWithoutWorkspaceUsingServerDefaultCwd() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        // The gateway's synthetic no-project bucket ("Home") has no path by design;
        // drafts in it are created without a cwd and the server applies its default.
        val projectId = ProjectId(NO_PROJECT_BUCKET_ID)
        val metadata = MetadataOnlyProjectSession(
            ProjectTreeResult(
                projects = listOf(ProjectSummary(projectId, "Home", null, 0, emptyList())),
            ),
        )
        val chatSession = RecordingProjectDraftChatSession()
        val client = AuthenticatingHermesConnectionClient()
        var chatConnections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ ->
                chatConnections += 1
                chatSession
            },
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createProjectSession(projectId, "Home task")
        assertEquals(null, viewModel.snapshots.value.chatSessions[draftId]?.error)

        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Start at home")
        advanceUntilIdle()

        assertEquals(1, chatConnections)
        assertEquals(1, chatSession.createCalls)
        assertEquals(null, chatSession.createdWorkspacePath)
        assertEquals(draftId, chatSession.createdForDurableId)
        assertEquals("Start at home", chatSession.submittedText)
        assertEquals(null, viewModel.snapshots.value.chatSessions.getValue(draftId).error)
    }

    @Test
    fun firstSendOnProjectDraftCreatesOnceWithExactWorkspaceBeforeSubmitting() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project:/srv/app")
        val metadata = MetadataOnlyProjectSession(
            ProjectTreeResult(
                projects = listOf(ProjectSummary(projectId, "App", " /srv/app ", 0, emptyList())),
            ),
        )
        val chatSession = RecordingProjectDraftChatSession()
        val client = AuthenticatingHermesConnectionClient()
        var chatConnections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ ->
                chatConnections += 1
                chatSession
            },
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createProjectSession(projectId, "Task")
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "First instruction")
        advanceUntilIdle()

        assertEquals(1, chatConnections)
        assertEquals(1, chatSession.createCalls)
        assertEquals("/srv/app", chatSession.createdWorkspacePath)
        assertEquals(draftId, chatSession.createdForDurableId)
        assertEquals("First instruction", chatSession.submittedText)
    }

    @Test
    fun completedFirstTurnPromotesProjectDraftToPersistedSession() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project:/srv/app")
        val metadata = MetadataOnlyProjectSession(
            ProjectTreeResult(
                projects = listOf(ProjectSummary(projectId, "App", "/srv/app", 0, emptyList())),
            ),
        )
        val runtimeId = RuntimeSessionId("runtime-scripted")
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.MessageStart(runtimeId, null),
                HermesChatEvent.MessageDelta(runtimeId, "Finished"),
                HermesChatEvent.MessageComplete(runtimeId, "Finished", "complete"),
            ),
        )
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createProjectSession(projectId, "Finished task")
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Do it")
        advanceUntilIdle()

        val completed = viewModel.snapshots.value.projectSessions
            .getValue(projectId)
            .single { it.id == draftId }
        assertFalse(completed.isLocalDraft)
    }

    @Test
    fun completingFirstTurnRefreshesCanonicalInboxMetadata() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project:/srv/app")
        val metadata = MetadataOnlyProjectSession(
            ProjectTreeResult(
                projects = listOf(ProjectSummary(projectId, "App", "/srv/app", 0, emptyList())),
            ),
        )
        val runtimeId = RuntimeSessionId("runtime-scripted")
        val canonicalId = DurableSessionId("canonical-rich")
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.SessionInfo(
                    sessionId = runtimeId,
                    storedSessionId = canonicalId,
                    model = "gpt-5.6-luna",
                    provider = "openai-codex",
                    running = true,
                ),
                HermesChatEvent.MessageComplete(runtimeId, "Finished", "complete"),
            ),
        )
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createProjectSession(projectId, "Finished task")
        client.authenticatedSessionsOverride = listOf(
            SessionSummary(
                id = canonicalId,
                title = "Canonical title",
                workspacePath = "/srv/app",
                preview = "Do it",
                lastActiveEpochSeconds = 1_700_000_000.0,
                messageCount = 2,
                model = "gpt-5.6-luna",
                provider = "openai-codex",
                profile = "default",
            ),
        )
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Do it")
        advanceUntilIdle()

        val completed = viewModel.snapshots.value.projectSessions
            .getValue(projectId)
            .single { it.id == draftId }
        assertEquals("Canonical title", completed.title)
        assertEquals("gpt-5.6-luna", completed.model)
        assertEquals("openai-codex", completed.provider)
        assertEquals(2, completed.messageCount)
        assertEquals(1_700_000_000.0, completed.lastActiveEpochSeconds ?: error("missing recency"), 0.0)
    }


    @Test
    fun interruptedMessageCompleteMarksResponseCancelled() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.MessageComplete(
                    RuntimeSessionId("runtime-scripted"),
                    "partial",
                    "interrupted",
                ),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Stop")
        advanceUntilIdle()

        assertEquals(
            "Hermes response was cancelled",
            viewModel.snapshots.value.chatSessions.getValue(draftId).error,
        )
    }

    @Test
    fun billingCompleteSurfacesStructuredBillingNotice() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.MessageComplete(
                    sessionId = RuntimeSessionId("runtime-scripted"),
                    text = "",
                    status = "error",
                    error = "billing required",
                    billing = HermesChatEvent.BillingInfo(
                        provider = "nous",
                        billingUrl = "https://billing.example",
                        isNous = true,
                        message = "Add credits",
                    ),
                ),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Continue")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(draftId)
        assertEquals(null, chat.error)
        assertEquals("nous", chat.billingNotice?.provider)
        assertEquals("https://billing.example", chat.billingNotice?.billingUrl)
        assertEquals("Add credits", chat.billingNotice?.message)
        assertTrue(chat.billingNotice?.isNous == true)
    }

    @Test
    fun reasoningDeltasAccumulateIntoTheStreamingAssistantMessage() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val runtimeId = RuntimeSessionId("runtime-scripted")
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.MessageStart(runtimeId, null),
                HermesChatEvent.ReasoningDelta(runtimeId, "hmm "),
                HermesChatEvent.ReasoningDelta(runtimeId, "let me think"),
                HermesChatEvent.MessageDelta(runtimeId, "Answer"),
                HermesChatEvent.MessageComplete(runtimeId, "Answer", "complete"),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Think")
        advanceUntilIdle()

        val assistant = viewModel.snapshots.value.chatSessions.getValue(draftId)
            .messages.last()
        assertEquals("hmm let me think", assistant.reasoningText)
        assertEquals("Answer", assistant.text)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun authoritativeReasoningSnapshotReplacesPriorDeltas() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val runtimeId = RuntimeSessionId("runtime-scripted")
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.MessageStart(runtimeId, null),
                HermesChatEvent.ReasoningDelta(runtimeId, "stale partial"),
                HermesChatEvent.ReasoningDelta(runtimeId, "authoritative reasoning", replace = true),
                HermesChatEvent.MessageDelta(runtimeId, "Answer"),
                HermesChatEvent.MessageComplete(runtimeId, "Answer", "complete"),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Think")
        advanceUntilIdle()

        val assistant = viewModel.snapshots.value.chatSessions.getValue(draftId).messages.last()
        assertEquals("authoritative reasoning", assistant.reasoningText)
    }

    @Test
    fun interimAssistantTextSealsTheStreamingSegmentWithoutDuplication() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val runtimeId = RuntimeSessionId("runtime-scripted")
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.MessageStart(runtimeId, null),
                HermesChatEvent.MessageDelta(runtimeId, "Checking config…"),
                HermesChatEvent.MessageInterim(runtimeId, "Checking config…", false),
                HermesChatEvent.MessageDelta(runtimeId, "Done"),
                HermesChatEvent.MessageComplete(runtimeId, "Done", "complete"),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Go")
        advanceUntilIdle()

        val messages = viewModel.snapshots.value.chatSessions.getValue(draftId).messages
        val assistant = messages.filter { it.role == com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole.Assistant }
        assertEquals(listOf("Checking config…", "Done"), assistant.map { it.text })
        assertFalse(assistant.any { it.isStreaming })
    }

    @Test
    fun sessionTitlePatchRenamesTheDurableSession() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val runtimeId = RuntimeSessionId("runtime-scripted")
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.SessionTitle(runtimeId, "Renamed by agent"),
                HermesChatEvent.MessageComplete(runtimeId, "Done", "complete"),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession("Original title")
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Go")
        advanceUntilIdle()

        assertEquals(
            "Renamed by agent",
            viewModel.snapshots.value.durableSessions.first { it.id == draftId }.title,
        )
    }

    @Test
    fun sessionInfoPatchesPersistModelProviderAndReasoningEffort() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val runtimeId = RuntimeSessionId("runtime-scripted")
        val chatSession = ScriptedEventChatSession(
            listOf(
                HermesChatEvent.SessionInfo(
                    sessionId = runtimeId,
                    model = "deepseek/deepseek-v4-flash-0731",
                    provider = "nous",
                    reasoningEffort = "medium",
                    running = true,
                ),
                HermesChatEvent.SessionInfo(
                    sessionId = runtimeId,
                    running = false,
                ),
                HermesChatEvent.MessageComplete(runtimeId, "Done", "complete"),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openSession(draftId)
        viewModel.sendMessage(draftId, "Go")
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(draftId)
        assertEquals("deepseek/deepseek-v4-flash-0731", chat.model)
        assertEquals("nous", chat.provider)
        assertEquals("medium", chat.reasoningEffort)
    }

    @Test
    fun reasoningSelectionTargetsActiveRuntimeAndUpdatesSessionState() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val durableId = DurableSessionId("stored-reasoning-control")
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ReasoningControlChatSession(durableId)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(durableId, "Reasoning control")),
            ),
        )
        advanceUntilIdle()
        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.setReasoningEffort(durableId, "medium")
        advanceUntilIdle()

        assertEquals(RuntimeSessionId("runtime-reasoning-control"), chatSession.appliedRuntimeId)
        assertEquals("medium", chatSession.appliedEffort)
        assertEquals("medium", viewModel.snapshots.value.chatSessions.getValue(durableId).reasoningEffort)
    }

    @Test
    fun resumeRehydratesRuntimeMetadataReasoningOnlyAssistantAndToolRows() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val durableId = DurableSessionId("stored-reasoning")
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ReasoningResumeChatSession(durableId)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(durableId, "Reasoning session")),
            ),
        )
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        val messages = chat.messages
        assertEquals(2, messages.size)
        assertEquals("Recovered reasoning", messages[0].reasoningText)
        assertEquals("", messages[0].text)
        assertEquals(com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole.Tool, messages[1].role)
        assertEquals("terminal · pwd", messages[1].text)
        assertEquals("gpt-5.6-sol", chat.model)
        assertEquals("openai-codex", chat.provider)
        assertEquals("medium", chat.reasoningEffort)
    }

    @Test
    fun resumeOnFirstOpenPopulatesModelCapabilitiesSoReasoningIsEditable() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val durableId = DurableSessionId("stored-reasoning-capabilities")
        val client = AuthenticatingHermesConnectionClient().apply {
            defaultModelOptions = ModelOptions(
                current = ModelSelection("openai-codex", "gpt-5.6-sol"),
                providers = listOf(
                    ModelProviderOption(
                        slug = "openai-codex",
                        name = "Codex",
                        models = listOf("gpt-5.6-sol"),
                        capabilities = mapOf(
                            "gpt-5.6-sol" to ModelCapabilities(reasoning = true, fast = true),
                        ),
                    ),
                ),
            )
        }
        val chatSession = ReasoningResumeChatSession(durableId)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(durableId, "Reasoning capabilities session")),
            ),
        )
        advanceUntilIdle()
        viewModel.loadManagementSettings().join()
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(ModelCapabilities(reasoning = true, fast = true), chat.modelCapabilities)
    }

    @Test
    fun resumePopulatesCapabilitiesFromCurrentModelInfoForTheResumedModel() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val durableId = DurableSessionId("stored-current-info-capabilities")
        val client = AuthenticatingHermesConnectionClient().apply {
            currentModelInfoLoader = { _, _, _ ->
                CurrentModelInfo(
                    profile = "default",
                    model = "gpt-5.6-sol",
                    provider = "openai-codex",
                    effectiveContextLength = 8192,
                    capabilities = ModelCapabilities(reasoning = true, fast = false),
                )
            }
        }
        val chatSession = ReasoningResumeChatSession(durableId)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(durableId, "Current model info capabilities")),
            ),
        )
        advanceUntilIdle()
        viewModel.loadManagementSettings().join()
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(ModelCapabilities(reasoning = true, fast = false), chat.modelCapabilities)
    }

    @Test
    fun resumeMatchesQualifiedRuntimeModelToCurrentModelCapabilities() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val durableId = DurableSessionId("stored-qualified-model-capabilities")
        val client = AuthenticatingHermesConnectionClient().apply {
            currentModelInfoLoader = { _, _, _ ->
                CurrentModelInfo(
                    profile = "default",
                    model = "gpt-5.6-sol",
                    provider = "openai-codex",
                    effectiveContextLength = 8192,
                    capabilities = ModelCapabilities(reasoning = true, fast = true),
                )
            }
        }
        val chatSession = ReasoningResumeChatSession(
            durableSessionId = durableId,
            model = "openai/gpt-5.6-sol",
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(durableId, "Qualified model capabilities")),
            ),
        )
        advanceUntilIdle()
        viewModel.loadManagementSettings().join()
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(ModelCapabilities(reasoning = true, fast = true), chat.modelCapabilities)
    }

    @Test
    fun resumeFallsBackToModelOptionsWhenCurrentInfoHasNoExplicitCapabilities() = runTest(dispatcher) {
        val settings = MutableStateFlow(
            ServerSettingsState.Ready(ServerOrigin.parse("https://hermes.example")),
        )
        val durableId = DurableSessionId("stored-options-capabilities")
        val client = AuthenticatingHermesConnectionClient().apply {
            currentModelInfoLoader = { _, _, _ ->
                CurrentModelInfo(
                    profile = "default",
                    model = "gpt-5.6-sol",
                    provider = "openai-codex",
                    effectiveContextLength = 8192,
                    capabilities = ModelCapabilities(),
                )
            }
            defaultModelOptions = ModelOptions(
                current = ModelSelection("openai-codex", "gpt-5.6-sol"),
                providers = listOf(
                    ModelProviderOption(
                        slug = "openai-codex",
                        name = "Codex",
                        models = listOf("gpt-5.6-sol"),
                        capabilities = mapOf(
                            "gpt-5.6-sol" to ModelCapabilities(reasoning = true, fast = true),
                        ),
                    ),
                ),
            )
        }
        val chatSession = ReasoningResumeChatSession(durableId)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(durableId, "Options capabilities")),
            ),
        )
        advanceUntilIdle()
        viewModel.loadManagementSettings().join()
        advanceUntilIdle()

        viewModel.openSession(durableId)
        advanceUntilIdle()

        val chat = viewModel.snapshots.value.chatSessions.getValue(durableId)
        assertEquals(ModelCapabilities(reasoning = true, fast = true), chat.modelCapabilities)
    }

    @Test
    fun modelPickerCreatesDraftRuntimeAndAppliesOnlyAdvertisedSessionSelection() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ModelPickerChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()

        viewModel.openModelPicker(draftId)
        advanceUntilIdle()

        val ready = viewModel.modelPickerState.value as ModelPickerState.Ready
        assertEquals(draftId, ready.durableSessionId)
        assertEquals(ModelSelection("nous", "current"), ready.options.current)
        assertEquals(1, chatSession.createCalls)
        assertEquals(RuntimeSessionId("runtime-model"), chatSession.optionsRuntimeId)

        viewModel.selectModel(ModelSelection("nous", "gpt-5.6-luna"))
        advanceUntilIdle()

        assertEquals(ModelPickerState.Closed, viewModel.modelPickerState.value)
        assertEquals(ModelSelection("nous", "gpt-5.6-luna"), chatSession.appliedSelection)
        assertEquals(RuntimeSessionId("runtime-model"), chatSession.appliedRuntimeId)
        assertEquals(false, chatSession.confirmExpensive)
    }

    @Test
    fun fastSelectionRequiresAdvertisedCapabilityAndExactRuntimeIdentity() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ModelPickerChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openModelPicker(draftId)
        advanceUntilIdle()

        viewModel.setFast(draftId, fast = true)
        advanceUntilIdle()

        assertEquals(RuntimeSessionId("runtime-model"), chatSession.fastRuntimeId)
        assertEquals(true, chatSession.appliedFast)
        assertEquals("fast", viewModel.snapshots.value.chatSessions.getValue(draftId).fastMode)
    }

    @Test
    fun fastSelectionLazilyAttachesAnAdvertisedDurableSession() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val durableId = DurableSessionId("stored-lazy-fast")
        val client = AuthenticatingHermesConnectionClient().apply {
            currentModelInfoLoader = { _, _, _ ->
                CurrentModelInfo(
                    profile = "default",
                    model = "gpt-5.6-sol",
                    provider = "openai-codex",
                    effectiveContextLength = 8192,
                    capabilities = ModelCapabilities(reasoning = true, fast = true),
                )
            }
        }
        val chatSession = ReasoningResumeChatSession(durableId)
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(durableId, "Lazy Fast session")),
            ),
        )
        advanceUntilIdle()
        viewModel.loadManagementSettings().join()
        viewModel.openSession(durableId)
        advanceUntilIdle()

        viewModel.setFast(durableId, fast = true)
        advanceUntilIdle()

        assertEquals(2, chatSession.resumeCalls)
        assertEquals(RuntimeSessionId("runtime-reasoning"), chatSession.fastRuntimeId)
        assertEquals(true, chatSession.appliedFast)
        assertEquals("fast", viewModel.snapshots.value.chatSessions.getValue(durableId).fastMode)
    }

    @Test
    fun fastSelectionIsIgnoredWhenRuntimeIdentityIsNoLongerCurrent() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ModelPickerChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openModelPicker(draftId)
        advanceUntilIdle()
        viewModel.logout()
        advanceUntilIdle()

        viewModel.setFast(draftId, fast = true)
        advanceUntilIdle()

        assertEquals(null, chatSession.fastRuntimeId)
    }

    @Test
    fun draftFastSetupFailurePreservesCreatedRuntimeAndRetryReusesIt() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
            defaultModelOptions = ModelOptions(
                current = ModelSelection("nous", "fast-model"),
                providers = listOf(
                    ModelProviderOption(
                        "nous",
                        "Nous Research",
                        listOf("fast-model"),
                        capabilities = mapOf("fast-model" to ModelCapabilities(fast = true, reasoning = true)),
                    ),
                ),
            )
        }
        val chatSession = FastFailureDraftChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
            projectConnector = null,
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()
        viewModel.setFast(draftId, fast = true)
        advanceUntilIdle()

        viewModel.sendMessage(draftId, "Run the task")
        advanceUntilIdle()

        // The transient Fast RPC failure must not orphan the created runtime:
        // the controller is still published and the prompt still submits.
        assertEquals(1, chatSession.createCalls)
        assertEquals(1, chatSession.setFastCalls)
        assertEquals(1, chatSession.submitCalls)
        assertFalse(chatSession.closed)
        assertTrue(
            viewModel.snapshots.value.activeRuntimes.any {
                it.durableSessionId == draftId && it.access == RuntimeAccess.Controller
            },
        )
        assertEquals(null, viewModel.snapshots.value.chatSessions.getValue(draftId).error)

        // A retry resumes the same runtime instead of creating a second orphan.
        viewModel.sendMessage(draftId, "Keep going")
        advanceUntilIdle()
        assertEquals(1, chatSession.createCalls)
        assertEquals(2, chatSession.submitCalls)
    }

    @Test
    fun promptStartRefreshesProcessRowsFromTheLaunchedTurn() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        }
        val chatSession = ProcessAwareDraftChatSession()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
            projectConnector = null,
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        advanceUntilIdle()

        viewModel.sendMessage(draftId, "Launch the build")
        advanceUntilIdle()

        // The process row only exists after the prompt was accepted, so the
        // post-submit refresh must be the one that populates the activity stack.
        assertEquals(1, chatSession.submitCalls)
        assertTrue(chatSession.processListCalls >= 2)
        val rows = viewModel.snapshots.value.chatSessions.getValue(draftId).processRows
        assertEquals(listOf("proc-1"), rows.map { it.processId })
        assertEquals("running", rows.single().status)
    }

    @Test
    fun modelPickerKeepsConfirmationOpenUntilHermesAcceptsExpensiveSelection() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val client = AuthenticatingHermesConnectionClient()
        val chatSession = ModelPickerChatSession(requireConfirmation = true)
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        val draftId = viewModel.createNewSession()
        viewModel.openModelPicker(draftId)
        advanceUntilIdle()

        viewModel.selectModel(ModelSelection("nous", "gpt-5.6-luna"))
        advanceUntilIdle()

        val confirming = viewModel.modelPickerState.value as ModelPickerState.Ready
        assertEquals("This model is expensive", confirming.confirmationMessage)
        assertEquals(ModelSelection("nous", "gpt-5.6-luna"), confirming.pendingSelection)
        viewModel.confirmModelSelection()
        advanceUntilIdle()

        assertEquals(ModelPickerState.Closed, viewModel.modelPickerState.value)
        assertEquals(true, chatSession.confirmExpensive)
        assertEquals(2, chatSession.applyCalls)
    }

    @Test
    fun terminalMessageFinalizesUnmatchedToolStartsInPublishedSnapshot() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val chatSession = TerminalToolChatSession()
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ -> chatSession },
            nowEpochSeconds = { 1_900_000_000 },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        viewModel.sendMessage(draftId, "research")
        advanceUntilIdle()

        val published = viewModel.snapshots.value.chatSessions.getValue(draftId)
        assertEquals(false, published.isSending)
        assertEquals(RunToolState.Completed, published.runState.tools.single().state)
    }

    @Test
    fun staleCreateCannotOverwriteAliasFromNewerDraftOperation() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val first = BlockingCreateProjectDraftChatSession("stale-canonical")
        val second = CanonicalProjectDraftChatSession("current-canonical")
        val third = CanonicalProjectDraftChatSession("unused-canonical")
        val sessions = ArrayDeque<HermesChatSession>().apply {
            add(first)
            add(second)
            add(third)
        }
        val client = AuthenticatingHermesConnectionClient()
        var connections = 0
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            chatConnector = HermesChatConnector { _, _ ->
                connections += 1
                sessions.removeFirst()
            },
            nowEpochSeconds = { 1_900_000_000 },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        val draftId = viewModel.createNewSession()
        viewModel.sendMessage(draftId, "stale operation")
        runCurrent()
        assertTrue(first.createStarted.isCompleted)

        viewModel.sendMessage(draftId, "current operation")
        advanceUntilIdle()
        assertEquals(2, connections)
        assertEquals("current operation", second.submittedText)

        first.releaseCreate.complete(Unit)
        advanceUntilIdle()
        second.closeEvents()
        advanceUntilIdle()

        viewModel.sendMessage(draftId, "resume canonical")
        advanceUntilIdle()

        assertEquals(3, connections)
        assertEquals(DurableSessionId("current-canonical"), third.resumedDurableId)
    }

    @Test
    fun originChangeCannotBeOverwrittenByLateProjectMetadata() = runTest(dispatcher) {
        val originA = ServerOrigin.parse("https://a.example")
        val originB = ServerOrigin.parse("https://b.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(originA))
        val client = SwitchingHermesConnectionClient()
        val oldTree = CompletableDeferred<ProjectTreeResult>()
        val metadata = MetadataOnlyProjectSession.fromTree(oldTree)
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { origin, _ ->
                if (origin == originA) metadata else MetadataOnlyProjectSession(
                    ProjectTreeResult(emptyList()),
                )
            },
        )

        runCurrent()
        client.probes.getValue(originA).complete(authRequiredInfo())
        advanceUntilIdle()
        client.authenticatedTokens.clear()
        settings.value = ServerSettingsState.Ready(originB)
        runCurrent()
        client.probes.getValue(originB).complete(authRequiredInfo())
        advanceUntilIdle()
        oldTree.complete(
            ProjectTreeResult(
                projects = listOf(
                    ProjectSummary(ProjectId("stale"), "Stale", null, 0, emptyList()),
                ),
            ),
        )
        advanceUntilIdle()

        assertTrue(viewModel.snapshots.value.projects.none { it.id == ProjectId("stale") })
    }

    @Test
    fun projectMetadataConnectionIsReusedForProjectDrillIn() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())
        val metadata = MetadataOnlyProjectSession.forTreeAndSessions(
            tree = ProjectTreeResult(projects = listOf(project)),
            sessions = ProjectSessionsResult(
                project = project,
                sessions = listOf(SessionSummary(DurableSessionId("stored-1"), "Session")),
            ),
        )
        var connections = 0
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ ->
                connections += 1
                metadata
            },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        assertEquals(1, connections)
        assertEquals(false, metadata.closed)

        viewModel.openProject(projectId).join()

        assertEquals(1, connections)
        assertEquals(false, metadata.closed)
        val loaded = viewModel.snapshots.value.projectSessionStates.getValue(projectId)
            as ProjectSessionLoadState.Loaded
        assertEquals("Session", loaded.sessions.single().title)
    }

    @Test
    fun staleProjectMetadataConnectionIsReplacedAndRetriedOnTransportFailure() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())
        val tree = ProjectTreeResult(projects = listOf(project))
        // Simulates the WebSocket the OS killed while the app was locked/backgrounded:
        // the cached metadata session fails its next RPC with the closed-transport error.
        val staleSession = MetadataOnlyProjectSession.forTreeAndSessionsFailure(
            tree = tree,
            failure = HermesChatTransportException("Hermes chat connection is closed"),
        )
        val healthySession = MetadataOnlyProjectSession.forTreeAndSessions(
            tree = tree,
            sessions = ProjectSessionsResult(
                project = project,
                sessions = listOf(SessionSummary(DurableSessionId("stored-1"), "Session")),
            ),
        )
        var connections = 0
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ ->
                connections += 1
                if (connections == 1) staleSession else healthySession
            },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        assertEquals(1, connections)

        viewModel.openProject(projectId).join()

        // Self-healing: the stale cached connection is closed, a fresh one is
        // established, and the RPC is retried instead of surfacing a raw error.
        assertEquals(2, connections)
        assertEquals(true, staleSession.closed)
        val loaded = viewModel.snapshots.value.projectSessionStates.getValue(projectId)
            as ProjectSessionLoadState.Loaded
        assertEquals("Session", loaded.sessions.single().title)
    }

    @Test
    fun freshProjectMetadataConnectionFailureSurfacesWithoutRetryLoop() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())
        val tree = ProjectTreeResult(projects = listOf(project))
        var connections = 0
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ ->
                connections += 1
                MetadataOnlyProjectSession.forTreeAndSessionsFailure(
                    tree = tree,
                    failure = HermesChatTransportException("Hermes chat connection is closed"),
                )
            },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()
        assertEquals(1, connections)

        viewModel.openProject(projectId).join()

        // One healing retry on a fresh connection, then the error surfaces —
        // a genuinely unreachable host must not spin reconnect attempts.
        assertEquals(2, connections)
        val state = viewModel.snapshots.value.projectSessionStates.getValue(projectId)
        assertEquals(true, state is ProjectSessionLoadState.TransientError)
    }

    @Test
    fun openProjectReconcilesProjectSummarySessionCountFromDrillIn() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project-1")
        val preview = SessionSummary(
            DurableSessionId("stored-preview"),
            "Preview title",
            workspacePath = "/workspace/app",
        )
        val tree = ProjectTreeResult(
            projects = listOf(
                ProjectSummary(projectId, "App", "/workspace/app", 2, listOf(preview)),
            ),
        )
        val sessionsResult = CompletableDeferred<ProjectSessionsResult>()
        val metadata = MetadataOnlyProjectSession.fromTreeAndSessions(tree, sessionsResult)
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf()))
        advanceUntilIdle()

        val job = viewModel.openProject(projectId)
        runCurrent()
        sessionsResult.complete(
            ProjectSessionsResult(
                project = ProjectSummary(
                    projectId,
                    "App",
                    "/workspace/app",
                    sessionCount = 3,
                    previewSessions = emptyList(),
                ),
                sessions = (1..3).map { index ->
                    SessionSummary(
                        DurableSessionId("stored-$index"),
                        "Session $index",
                        workspacePath = "/workspace/app",
                    )
                },
            ),
        )
        job.join()

        val snapshot = viewModel.snapshots.value
        val reconciled = snapshot.projects.single { it.id == projectId }
        assertEquals(3, reconciled.sessionCount)
        assertEquals("App", reconciled.label)
        assertEquals("/workspace/app", reconciled.primaryPath)
        assertEquals(listOf(preview.copy(projectId = projectId)), reconciled.previewSessions)
        assertEquals(3, snapshot.projectSessions.getValue(projectId).size)
    }

    @Test
    fun refreshHomeDataReloadsSelectedProfileSessionsAndTreeAndTogglesRefreshing() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project-1")
        val tree = ProjectTreeResult(
            projects = listOf(ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())),
        )
        val sessionsResult = CompletableDeferred<ProjectSessionsResult>()
        val delegate = MetadataOnlyProjectSession.fromTreeAndSessions(tree, sessionsResult)
        var treeLoads = 0
        val metadata = object : HermesChatSession by delegate {
            override suspend fun loadProjectTree(
                profile: String?,
                previewLimit: Int,
                sessionLimit: Int,
            ): ProjectTreeResult {
                treeLoads += 1
                return delegate.loadProjectTree(profile, previewLimit, sessionLimit)
            }
        }
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(
            AuthenticatedHermesConnection(
                "user",
                listOf(SessionSummary(DurableSessionId("stored-1"), "First")),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, client.authenticateCalls)
        assertEquals(1, treeLoads)
        assertFalse(viewModel.homeRefreshing.value)

        val refreshBarrier = CompletableDeferred<Unit>()
        client.authenticateBarriers.addLast(refreshBarrier)
        val refreshJob = viewModel.refreshHomeData()
        runCurrent()
        assertTrue(viewModel.homeRefreshing.value)
        refreshBarrier.complete(Unit)
        advanceUntilIdle()
        refreshJob.join()

        // The Home refresh reloads the selected profile's rows through the
        // profile-scoped contract instead of re-authenticating the default profile.
        assertEquals(1, client.authenticateCalls)
        assertEquals(2, client.sessionsForProfileRequests.size)
        assertEquals("default", client.sessionsForProfileRequests.last().profile)
        assertEquals(2, treeLoads)
        assertFalse(viewModel.homeRefreshing.value)
        assertEquals(ProjectLoadState.Loaded(tree.projects), viewModel.snapshots.value.projectState)
        assertTrue(client.authenticatedWith is HermesCredential.NativeBearer)
    }

    @Test
    fun refreshHomeDataPublishesProcessLocalDelegatedChildren() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val delegate = MetadataOnlyProjectSession.fromTreeAndSessions(
            ProjectTreeResult(emptyList()),
            CompletableDeferred(),
        )
        val metadata = object : HermesChatSession by delegate {
            override suspend fun loadDelegationStatus() = DelegationStatus(
                active = listOf(
                    DelegatedSubagent("child-1", "Inspect lifecycle races", "running"),
                ),
            )
        }
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        viewModel.refreshHomeData().join()

        assertEquals("child-1", viewModel.snapshots.value.delegationStatus.active.single().subagentId)
    }

    @Test
    fun refreshCronJobsPublishesEnabledAndPausedJobsReadOnly() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val expected = listOf(
            CronJob("job-enabled", "Daily brief", "0 8 * * *", enabled = true),
            CronJob("job-paused", "Price watch", "every 2h", enabled = false),
        )
        val delegate = MetadataOnlyProjectSession.fromTreeAndSessions(
            ProjectTreeResult(emptyList()),
            CompletableDeferred(),
        )
        val metadata = object : HermesChatSession by delegate {
            override suspend fun loadCronJobs(): List<CronJob> = expected
        }
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        viewModel.refreshCronJobs().join()

        assertEquals(CronJobsState.Ready(expected), viewModel.snapshots.value.cronJobsState)
    }

    @Test
    fun manageCronJobRunsActionThenReloadsJobsAndSurfacesFailures() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val managed = mutableListOf<Pair<String, CronJobAction>>()
        val jobs = listOf(CronJob("job-1", "Daily brief", "0 8 * * *", enabled = true))
        val delegate = MetadataOnlyProjectSession.fromTreeAndSessions(
            ProjectTreeResult(emptyList()),
            CompletableDeferred(),
        )
        val metadata = object : HermesChatSession by delegate {
            override suspend fun loadCronJobs(): List<CronJob> = jobs
            override suspend fun manageCronJob(jobId: String, action: CronJobAction) {
                if (action == CronJobAction.Enable) throw HermesChatProtocolException("job not found")
                managed += jobId to action
            }
        }
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        viewModel.manageCronJob("job-1", CronJobAction.Disable).join()
        advanceUntilIdle()

        assertEquals(listOf("job-1" to CronJobAction.Disable), managed)
        assertEquals(null, viewModel.snapshots.value.cronJobActionJobId)
        assertEquals(null, viewModel.snapshots.value.cronJobActionError)
        assertEquals(CronJobsState.Ready(jobs), viewModel.snapshots.value.cronJobsState)

        viewModel.manageCronJob("job-1", CronJobAction.Enable).join()
        advanceUntilIdle()

        assertEquals(null, viewModel.snapshots.value.cronJobActionJobId)
        assertEquals("Could not enable the job", viewModel.snapshots.value.cronJobActionError)
    }

    @Test
    fun cronRestActionsAreCapabilityGatedScopedAndPerJobLoadingIsIdempotent() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val scope = CronJobScope(origin.value, "work", "job-1")
        val jobs = listOf(CronJob("job-1", "Daily brief", "0 8 * * *", enabled = true))
        val client = AuthenticatingHermesConnectionClient().apply {
            profiles = listOf("work")
            cronTriggerResponse = CompletableDeferred()
            cronRunsResponse = listOf(
                CronJobRun("run-1", title = "Returned run", preview = "A returned preview"),
            )
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = null,
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        viewModel.loadManagementSettings("work").join()
        viewModel.triggerCronJob("job-1")
        viewModel.triggerCronJob("job-1")
        runCurrent()
        assertEquals(setOf(scope), viewModel.snapshots.value.cronRunLoadingScopes)
        assertEquals(1, client.cronTriggerCalls)

        client.cronTriggerResponse!!.complete(jobs.single())
        advanceUntilIdle()
        assertEquals(1, client.cronTriggerCalls)
        assertEquals(CronRestCapability.Supported, viewModel.snapshots.value.cronTriggerCapability)

        client.cronTriggerFailure = HermesCronJobClaimedException("job-2")
        viewModel.triggerCronJob("job-2").join()
        assertEquals(
            "Cron job is already running or was claimed by another scheduler",
            viewModel.snapshots.value.cronRunErrors[scope.copy(jobId = "job-2")],
        )

        viewModel.toggleCronJobRuns("job-1").join()
        assertEquals(
            CronJobRunsState.Ready(client.cronRunsResponse),
            viewModel.snapshots.value.cronRunsByScope[scope],
        )
        assertEquals(listOf(scope), client.cronRunsScopes)
    }

    @Test
    fun cronRestUnsupportedAndClaimedResponsesDoNotBecomeGenericFailures() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val client = AuthenticatingHermesConnectionClient().apply {
            cronTriggerFailure = HermesCronRestUnsupportedException(CronRestEndpoint.Trigger, 404)
            cronRunsFailure = HermesCronRestUnsupportedException(CronRestEndpoint.Runs, 405)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = MutableStateFlow(ServerSettingsState.Ready(origin)),
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = null,
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        viewModel.triggerCronJob("job-1").join()
        viewModel.toggleCronJobRuns("job-1").join()

        assertEquals(CronRestCapability.Unsupported, viewModel.snapshots.value.cronTriggerCapability)
        assertEquals(CronRestCapability.Unsupported, viewModel.snapshots.value.cronHistoryCapability)
        assertTrue(viewModel.snapshots.value.cronRunErrors.isEmpty())
        assertTrue(viewModel.snapshots.value.cronRunsByScope.values.all { it is CronJobRunsState.Unsupported })

    }

    @Test
    fun openProjectLoadsSessionsIntoPerProjectStateAndReconcilesDurableWorkspace() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val durable = SessionSummary(
            id = DurableSessionId("stored-1"),
            title = "REST title",
            workspacePath = "/authoritative/cwd",
        )
        val projectId = ProjectId("project-1")
        val tree = ProjectTreeResult(
            projects = listOf(ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())),
        )
        val sessionsResult = CompletableDeferred<ProjectSessionsResult>()
        val metadata = MetadataOnlyProjectSession.fromTreeAndSessions(tree, sessionsResult)
        var connections = 0
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ ->
                connections += 1
                metadata
            },
        )
        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(durable)))
        advanceUntilIdle()

        assertEquals(ProjectLoadState.Loaded(tree.projects), viewModel.snapshots.value.projectState)
        val job = viewModel.openProject(projectId)
        runCurrent()
        assertEquals(ProjectSessionLoadState.Loading, viewModel.snapshots.value.projectSessionStates[projectId])
        sessionsResult.complete(
            ProjectSessionsResult(
                project = ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList()),
                sessions = listOf(
                    SessionSummary(durable.id, "Metadata title", workspacePath = "/metadata/cwd"),
                ),
            ),
        )
        job.join()

        val snapshot = viewModel.snapshots.value
        val session = snapshot.projectSessions.getValue(projectId).single()
        assertEquals(ProjectSessionLoadState.Loaded(listOf(session)), snapshot.projectSessionStates.getValue(projectId))
        assertEquals(projectId, session.projectId)
        assertEquals("REST title", session.title)
        assertEquals("/authoritative/cwd", session.workspacePath)
        assertEquals(0, metadata.resumeCalls + metadata.createCalls + metadata.submitCalls)
        assertEquals(false, metadata.closed)
        assertEquals(1, connections)
    }

    @Test
    fun projectSessionsMethodNotFoundPreservesLoadedTreeDurableSessionsAndAuthentication() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project-1")
        val durable = SessionSummary(
            id = DurableSessionId("stored-1"),
            title = "REST title",
            workspacePath = "/authoritative/cwd",
        )
        val tree = ProjectTreeResult(
            projects = listOf(ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())),
        )
        val metadata = MetadataOnlyProjectSession.forTreeAndSessionsFailure(
            tree = tree,
            failure = HermesChatMethodNotFoundException("projects.project_sessions"),
        )
        val client = AuthenticatingHermesConnectionClient()
        val tokenStore = FixedTokenStore()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = tokenStore,
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(durable)))
        advanceUntilIdle()

        viewModel.openProject(projectId).join()

        val snapshot = viewModel.snapshots.value
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(listOf(durable), snapshot.durableSessions)
        assertEquals(tree.projects, snapshot.projects)
        assertEquals(ProjectSessionLoadState.Unsupported, snapshot.projectSessionStates.getValue(projectId))
        assertTrue(snapshot.projectSessions[projectId].isNullOrEmpty())
        assertEquals(0, tokenStore.clearCalls)
        assertEquals(false, metadata.closed)
    }

    @Test
    fun transientProjectSessionsErrorRetainsExistingSnapshotState() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project-1")
        val durable = SessionSummary(
            id = DurableSessionId("stored-1"),
            title = "REST title",
            workspacePath = "/authoritative/cwd",
        )
        val tree = ProjectTreeResult(
            projects = listOf(ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())),
        )
        val metadata = MetadataOnlyProjectSession.forTreeAndSessionsFailure(
            tree = tree,
            failure = HermesChatTransportException("temporary project session outage"),
        )
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { _, _ -> metadata },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(durable)))
        advanceUntilIdle()

        viewModel.openProject(projectId).join()

        val snapshot = viewModel.snapshots.value
        assertEquals(AuthenticationState.Authenticated, snapshot.authenticationState)
        assertEquals(listOf(durable), snapshot.durableSessions)
        assertEquals(tree.projects, snapshot.projects)
        assertEquals(
            ProjectSessionLoadState.TransientError("temporary project session outage"),
            snapshot.projectSessionStates.getValue(projectId),
        )
        assertTrue(snapshot.projectSessions[projectId].isNullOrEmpty())
        assertTrue(metadata.closed)
    }

    @Test
    fun projectSessionDrillInRefreshesExpiringAccessToken() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project-1")
        var now = 1_900_000_000L
        var storedTokens = NativeTokenSet(
            accessToken = "initial-access",
            refreshToken = "opaque-refresh",
            expiresAt = 2_000_000_000L,
            provider = "nous",
            userId = "user",
        )
        val tokenStore = object : NativeTokenStore {
            override suspend fun load(serverOrigin: ServerOrigin) = storedTokens
            override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) {
                storedTokens = tokens
            }
            override suspend fun clear(serverOrigin: ServerOrigin) = Unit
        }
        var refreshCalls = 0
        val refreshClient = object : NativeRefreshClient {
            override suspend fun refresh(
                serverOrigin: ServerOrigin,
                refreshToken: String,
                provider: String,
            ): NativeTokenSet {
                refreshCalls += 1
                return storedTokens.copy(
                    accessToken = "refreshed-access",
                    expiresAt = 2_100_000_000L,
                )
            }
        }
        val tree = ProjectTreeResult(
            projects = listOf(ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())),
        )
        val projectSessions = ProjectSessionsResult(
            project = tree.projects.single(),
            sessions = listOf(SessionSummary(DurableSessionId("stored-1"), "Session")),
        )
        val connectorCredentials = mutableListOf<HermesCredential>()
        var connections = 0
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = tokenStore,
            refreshClient = refreshClient,
            projectConnector = HermesChatConnector { _, credential ->
                connectorCredentials += credential
                connections += 1
                if (connections == 1) {
                    MetadataOnlyProjectSession(tree)
                } else {
                    MetadataOnlyProjectSession(projectSessions)
                }
            },
            nowEpochSeconds = { now },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        now = 2_000_000_000L
        viewModel.openProject(projectId).join()

        assertEquals(1, refreshCalls)
        assertEquals(
            listOf("Bearer initial-access", "Bearer refreshed-access"),
            connectorCredentials.map { credential ->
                val request = io.ktor.client.request.HttpRequestBuilder()
                request.applyHermesCredential(credential, origin)
                request.headers[io.ktor.http.HttpHeaders.Authorization]
            },
        )
        val loaded = viewModel.snapshots.value.projectSessionStates.getValue(projectId)
            as ProjectSessionLoadState.Loaded
        assertEquals("Session", loaded.sessions.single().title)
        assertEquals(projectId, loaded.sessions.single().projectId)
    }

    @Test
    fun concurrentOperationsShareOneTokenRefreshAndNeverBurnRotatedRefreshToken() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val projectId = ProjectId("project-1")
        var now = 1_900_000_000L
        var storedTokens = NativeTokenSet(
            accessToken = "initial-access",
            refreshToken = "opaque-refresh",
            expiresAt = 2_000_000_000L,
            provider = "nous",
            userId = "user",
        )
        val tokenStore = object : NativeTokenStore {
            override suspend fun load(serverOrigin: ServerOrigin) = storedTokens
            override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) {
                storedTokens = tokens
            }
            override suspend fun clear(serverOrigin: ServerOrigin) = Unit
        }
        // Rotation semantics: each refresh token is single-use; presenting a burned one fails.
        var currentRefreshToken = "opaque-refresh"
        var refreshCalls = 0
        val refreshGate = CompletableDeferred<Unit>()
        val refreshClient = object : NativeRefreshClient {
            override suspend fun refresh(
                serverOrigin: ServerOrigin,
                refreshToken: String,
                provider: String,
            ): NativeTokenSet {
                refreshCalls += 1
                if (refreshToken != currentRefreshToken) throw NativeRefreshExpiredException()
                refreshGate.await()
                currentRefreshToken = "rotated-refresh"
                return storedTokens.copy(
                    accessToken = "refreshed-access",
                    refreshToken = currentRefreshToken,
                    expiresAt = 2_100_000_000L,
                )
            }
        }
        val tree = ProjectTreeResult(
            projects = listOf(ProjectSummary(projectId, "App", "/workspace/app", 1, emptyList())),
        )
        val projectSessions = ProjectSessionsResult(
            project = tree.projects.single(),
            sessions = listOf(SessionSummary(DurableSessionId("stored-1"), "Session")),
        )
        val client = AuthenticatingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = tokenStore,
            refreshClient = refreshClient,
            projectConnector = HermesChatConnector { _, _ ->
                object : HermesChatSession by MetadataOnlyProjectSession.fromTreeAndSessions(
                    tree,
                    CompletableDeferred(projectSessions),
                ) {
                    override suspend fun loadCronJobs(): List<CronJob> = emptyList()
                }
            },
            nowEpochSeconds = { now },
        )

        runCurrent()
        client.probeResponse.complete(authRequiredInfo())
        runCurrent()
        client.authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
        advanceUntilIdle()

        now = 2_000_000_000L
        val openJob = viewModel.openProject(projectId)
        runCurrent()
        val jobsJob = viewModel.refreshCronJobs()
        runCurrent()

        assertEquals(1, refreshCalls)
        refreshGate.complete(Unit)
        advanceUntilIdle()
        openJob.join()
        jobsJob.join()

        assertEquals(1, refreshCalls)
        assertEquals(AuthenticationState.Authenticated, viewModel.snapshots.value.authenticationState)
        assertEquals("refreshed-access", storedTokens.accessToken)
        assertEquals("rotated-refresh", storedTokens.refreshToken)
    }

    @Test
    fun lateProjectSessionsFromOldOriginAreRejectedAndMetadataSessionCloses() = runTest(dispatcher) {
        val originA = ServerOrigin.parse("https://a.example")
        val originB = ServerOrigin.parse("https://b.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(originA))
        val projectId = ProjectId("project-1")
        val oldSessions = CompletableDeferred<ProjectSessionsResult>()
        val oldMetadata = MetadataOnlyProjectSession.fromTreeAndSessions(
            tree = ProjectTreeResult(
                projects = listOf(ProjectSummary(projectId, "A", null, 0, emptyList())),
            ),
            sessions = oldSessions,
        )
        val newTreeSession = MetadataOnlyProjectSession(ProjectTreeResult(emptyList()))
        val client = SwitchingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            projectConnector = HermesChatConnector { origin, _ ->
                if (origin == originA) oldMetadata else newTreeSession
            },
        )

        runCurrent()
        client.probes.getValue(originA).complete(authRequiredInfo())
        advanceUntilIdle()
        viewModel.openProject(projectId)
        runCurrent()
        assertEquals(ProjectSessionLoadState.Loading, viewModel.snapshots.value.projectSessionStates[projectId])

        settings.value = ServerSettingsState.Ready(originB)
        runCurrent()
        client.probes.getValue(originB).complete(authRequiredInfo())
        advanceUntilIdle()
        oldSessions.complete(
            ProjectSessionsResult(
                project = ProjectSummary(projectId, "stale", null, 1, emptyList()),
                sessions = listOf(SessionSummary(DurableSessionId("stale"), "stale")),
            ),
        )
        advanceUntilIdle()

        assertTrue(oldMetadata.closed)
        assertTrue(newTreeSession.closed.not())
        assertTrue(viewModel.snapshots.value.projectSessions.values.flatten().none { it.id.value == "stale" })
        assertTrue(viewModel.snapshots.value.projectSessionStates[projectId] !is ProjectSessionLoadState.Loaded)
    }

    @Test
    fun originSwitchClearsOldOriginSnapshotBeforeNewProbeCompletes() = runTest(dispatcher) {
        val originA = ServerOrigin.parse("https://a.example")
        val originB = ServerOrigin.parse("https://b.example")
        val oldSession = SessionSummary(DurableSessionId("old"), "Old origin")
        val newSession = SessionSummary(DurableSessionId("new"), "New origin")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(originA))
        val client = SwitchingHermesConnectionClient()
        val viewModel = HermesConnectionViewModel(settings, client)

        runCurrent()
        client.probes.getValue(originA).complete(
            HermesConnectionInfo(
                version = "a",
                authRequired = false,
                nativeOAuthSupported = false,
                providers = emptyList(),
                sessions = listOf(oldSession),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf(oldSession), viewModel.snapshots.value.durableSessions)

        settings.value = ServerSettingsState.Ready(originB)
        runCurrent()
        assertTrue(viewModel.snapshots.value.durableSessions.isEmpty())
        assertTrue(viewModel.snapshots.value.projects.isEmpty())
        assertTrue(viewModel.snapshots.value.chatSessions.isEmpty())
        assertTrue(viewModel.snapshots.value.activeRuntimes.isEmpty())
        assertEquals(ModelPickerState.Closed, viewModel.modelPickerState.value)

        client.probes.getValue(originB).complete(
            HermesConnectionInfo(
                version = "b",
                authRequired = false,
                nativeOAuthSupported = false,
                providers = emptyList(),
                sessions = listOf(newSession),
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf(newSession), viewModel.snapshots.value.durableSessions)
        assertTrue(viewModel.snapshots.value.durableSessions.none { it.id == oldSession.id })
    }

    @Test
    fun originChangeCancelsSignInAndRejectsLateOldOriginTokens() = runTest(dispatcher) {
        val originA = ServerOrigin.parse("https://a.example")
        val originB = ServerOrigin.parse("https://b.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(originA))
        val client = SwitchingHermesConnectionClient()
        val login = LateNativeLogin()
        val viewModel = HermesConnectionViewModel(settings, client, login)
        runCurrent()
        client.probes.getValue(originA).complete(authRequiredInfo())
        advanceUntilIdle()

        viewModel.signIn { }
        runCurrent()
        settings.value = ServerSettingsState.Ready(originB)
        runCurrent()
        login.response.complete(
            NativeTokenSet(
                accessToken = "old-origin-token",
                refreshToken = "old-refresh",
                expiresAt = 1,
                provider = "nous",
                userId = "user",
            ),
        )
        runCurrent()
        client.probes.getValue(originB).complete(authRequiredInfo())
        advanceUntilIdle()

        assertTrue(login.wasCancelled)
        assertEquals(emptyList<HermesCredential>(), client.authenticatedTokens)
        assertEquals(AuthenticationState.SignInRequired, viewModel.snapshots.value.authenticationState)
    }

    @Test
    fun savedFiltersReloadOnlyForCurrentOriginAndProfile() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
            profiles = listOf("default", "work")
        }
        val repository = RecordingSessionFilterRepository()
        repository.save(
            com.unsupportedpastels.hermesandroid.session.SessionFilterScope(origin, "work"),
            com.unsupportedpastels.hermesandroid.session.SavedSessionFilter(
                "Work",
                com.unsupportedpastels.hermesandroid.session.SessionListFilter(query = "todo"),
            ),
        )
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            sessionFilterRepository = repository,
        )
        advanceUntilIdle()

        assertTrue(viewModel.savedSessionFilters.value.isEmpty())
        viewModel.loadManagementSettings("work").join()
        assertEquals("Work", viewModel.savedSessionFilters.value.single().name)
        viewModel.loadManagementSettings("default").join()
        assertTrue(viewModel.savedSessionFilters.value.isEmpty())

        val otherOrigin = ServerOrigin.parse("https://other.example")
        settings.value = ServerSettingsState.Ready(otherOrigin)
        runCurrent()
        assertTrue(viewModel.savedSessionFilters.value.isEmpty())
    }

    @Test
    fun loadManagementSettingsAtomicallyPublishesSelectedProfileSessions() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val defaultSessions = listOf(SessionSummary(DurableSessionId("stored-default"), "Default row"))
        val workSessions = listOf(SessionSummary(DurableSessionId("stored-work"), "Work row"))
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", defaultSessions))
            profiles = listOf("default", "work")
            sessionsForProfileLoader = { _, _, profile, _ ->
                if (profile == "work") workSessions else defaultSessions
            }
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
        )
        advanceUntilIdle()
        assertEquals(defaultSessions, viewModel.snapshots.value.durableSessions)

        viewModel.loadManagementSettings("work").join()

        assertEquals("work", viewModel.snapshots.value.selectedProfile)
        // Bulk-delete validation must never see the previous profile's rows.
        assertEquals(workSessions, viewModel.snapshots.value.durableSessions)
        assertEquals("work", client.sessionsForProfileRequests.last().profile)
    }

    @Test
    fun archivedOnlyFilterFetchesArchivedRowsAndClearingRestoresExcludeListing() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val archivedRow = SessionSummary(DurableSessionId("stored-archived"), "Archived", archived = true)
        val activeRow = SessionSummary(DurableSessionId("stored-active"), "Active")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(activeRow)))
            sessionsForProfileLoader = { _, _, _, archivedOnly ->
                if (archivedOnly) listOf(archivedRow) else listOf(activeRow)
            }
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
        )
        advanceUntilIdle()

        viewModel.refreshDurableSessions(archivedOnly = true).join()
        assertEquals(listOf(archivedRow), viewModel.snapshots.value.durableSessions)
        assertEquals(true, client.sessionsForProfileRequests.last().archivedOnly)

        viewModel.refreshDurableSessions(archivedOnly = false).join()
        assertEquals(listOf(activeRow), viewModel.snapshots.value.durableSessions)
        assertEquals(false, client.sessionsForProfileRequests.last().archivedOnly)
    }

    @Test
    fun homeRefreshKeepsArchivedRowsWhileArchivedFilterIsActive() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val archivedRow = SessionSummary(DurableSessionId("stored-archived"), "Archived", archived = true)
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
            sessionsForProfileLoader = { _, _, _, archivedOnly ->
                if (archivedOnly) listOf(archivedRow) else emptyList()
            }
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
        )
        advanceUntilIdle()

        viewModel.refreshDurableSessions(archivedOnly = true).join()
        assertEquals(listOf(archivedRow), viewModel.snapshots.value.durableSessions)

        // A pull-to-refresh while the archived filter is active must not clobber
        // the archived rows with an exclude listing.
        viewModel.refreshHomeData().join()
        assertEquals(listOf(archivedRow), viewModel.snapshots.value.durableSessions)
        assertEquals(true, client.sessionsForProfileRequests.last().archivedOnly)
    }

    @Test
    fun bulkDeleteRefreshesDurableRowsOnlyAfterAuthoritativeSuccess() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val first = SessionSummary(DurableSessionId("stored-1"), "One")
        val second = SessionSummary(DurableSessionId("stored-2"), "Two")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(first, second)))
            bulkDeleteResponse = CompletableDeferred(BulkDeleteResult(ok = true, deleted = 1))
            bulkSessionsAfterDelete = listOf(second)
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
        )
        advanceUntilIdle()

        val result = viewModel.bulkDeleteSessions(listOf(first.id))

        assertEquals(1, result.deleted)
        assertEquals(listOf(first.id), client.bulkDeletedIds)
        assertEquals(listOf(second), viewModel.snapshots.value.durableSessions)
    }

    @Test
    fun bulkDeleteDoesNotPublishStaleCompletionAfterOriginSwitch() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val otherOrigin = ServerOrigin.parse("https://other.example")
        val first = SessionSummary(DurableSessionId("stored-1"), "One")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", listOf(first)))
            bulkDeleteResponse = CompletableDeferred()
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
        )
        advanceUntilIdle()

        val deletion = async(kotlinx.coroutines.SupervisorJob()) {
            runCatching { viewModel.bulkDeleteSessions(listOf(first.id)) }
        }
        runCurrent()
        settings.value = ServerSettingsState.Ready(otherOrigin)
        runCurrent()
        client.bulkDeleteResponse.complete(BulkDeleteResult(ok = true, deleted = 1))
        val deletionResult = deletion.await()
        advanceUntilIdle()

        assertTrue(deletionResult.isFailure)
        assertEquals(otherOrigin, client.probedOrigins.last())
    }

    @Test
    fun operationalStatusUsesSixtySecondCadenceAndPreservesLastGoodSnapshotOnTransientFailure() = runTest(dispatcher) {
        var now = 1_900_000_000L
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
            statusResponse = operationalStatus("default", "0.20.1")
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()
        assertEquals(listOf("default"), client.operationalStatusProfiles)

        viewModel.refreshOperationalStatus().join()
        assertEquals(1, client.operationalStatusProfiles.size)
        now += 60
        viewModel.refreshOperationalStatus().join()
        assertEquals(2, client.operationalStatusProfiles.size)

        client.operationalStatusFailure = HermesConnectionException("temporary")
        now += 60
        viewModel.refreshOperationalStatus(force = true).join()
        val state = viewModel.snapshots.value.operationalStatusState
        assertTrue(state is OperationalStatusState.TransientError)
        assertEquals("0.20.1", checkNotNull(state.lastGoodOrNull()).status.version)
    }

    @Test
    fun operationalStatusSchedulesNextFetchAfterSixtySecondsWithoutExplicitCall() = runTest(dispatcher) {
        var now = 1_900_000_000L
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val client = AuthenticatingHermesConnectionClient().apply {
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
            statusResponse = operationalStatus("default", "0.20.1")
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
            nowEpochSeconds = { now },
        )
        advanceUntilIdle()
        assertEquals(1, client.operationalStatusProfiles.size)

        // Establish a fresh fetch whose completed tick owns the next cadence;
        // the initial connect's tick was already consumed during advanceUntilIdle.
        viewModel.refreshOperationalStatus(force = true).join()
        assertEquals(2, client.operationalStatusProfiles.size)

        // Once the 60-second window passes, the tick scheduled by the completed
        // fetch refreshes the status without any explicit caller.
        now += 60
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, client.operationalStatusProfiles.size)
        assertTrue(viewModel.snapshots.value.operationalStatusState is OperationalStatusState.Ready)
    }

    @Test
    fun lateDefaultProfileStatusCannotReplaceSelectedProfileStatus() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val defaultStatus = CompletableDeferred<Unit>()
        val workStatus = CompletableDeferred<Unit>()
        val client = AuthenticatingHermesConnectionClient().apply {
            profiles = listOf("default", "work")
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
            operationalStatusLoader = { _, profile ->
                operationalStatusProfiles += profile
                when (profile) {
                    "default" -> defaultStatus.await()
                    "work" -> workStatus.await()
                }
                operationalStatus(profile, profile)
            }
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
        )
        runCurrent()
        viewModel.loadManagementSettings("work")
        runCurrent()
        defaultStatus.complete(Unit)
        advanceUntilIdle()
        assertEquals("work", viewModel.snapshots.value.selectedProfile)
        assertTrue(viewModel.snapshots.value.operationalStatusState !is OperationalStatusState.Ready)

        workStatus.complete(Unit)
        advanceUntilIdle()
        val ready = viewModel.snapshots.value.operationalStatusState
        assertTrue(ready is OperationalStatusState.Ready)
        assertEquals("work", checkNotNull(ready.lastGoodOrNull()).profile)
    }
    @Test
    fun lateCurrentModelInfoCannotReplaceSelectedProfileMetadata() = runTest(dispatcher) {
        val origin = ServerOrigin.parse("https://hermes.example")
        val settings = MutableStateFlow<ServerSettingsState>(ServerSettingsState.Ready(origin))
        val defaultInfo = CompletableDeferred<CurrentModelInfo>()
        val client = AuthenticatingHermesConnectionClient().apply {
            profiles = listOf("default", "work")
            probeResponse.complete(authRequiredInfo())
            authenticationResponse.complete(AuthenticatedHermesConnection("user", emptyList()))
            currentModelInfoLoader = { _, _, profile ->
                if (profile == "default") defaultInfo.await()
                else CurrentModelInfo(
                    profile = "work",
                    model = "work-model",
                    provider = "work-provider",
                    effectiveContextLength = 65536,
                    capabilities = ModelCapabilities(reasoning = true),
                )
            }
        }
        val viewModel = HermesConnectionViewModel(
            settingsStates = settings,
            client = client,
            tokenStore = FixedTokenStore(),
        )
        advanceUntilIdle()

        viewModel.loadManagementSettings("default")
        runCurrent()
        viewModel.loadManagementSettings("work").join()
        defaultInfo.complete(
            CurrentModelInfo(
                profile = "default",
                model = "default-model",
                provider = "default-provider",
                effectiveContextLength = 4096,
                capabilities = ModelCapabilities(fast = true),
            ),
        )
        advanceUntilIdle()

        assertEquals("work", viewModel.snapshots.value.selectedProfile)
        assertEquals("work-model", viewModel.snapshots.value.currentModelInfo?.model)
        assertEquals("work", viewModel.snapshots.value.currentModelInfo?.profile)
    }

    @Test
    fun clearingViewModelClosesOwnedNetworkResources() {
        var closed = false
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        ViewModelProvider(
            owner,
            HermesConnectionViewModel.Factory(
                settingsStates = MutableStateFlow(ServerSettingsState.Loading),
                client = FakeHermesConnectionClient(),
                closeResources = { closed = true },
            ),
        )[HermesConnectionViewModel::class.java]

        store.clear()

        assertTrue(closed)
    }
}

private fun operationalStatus(profile: String, version: String) = OperationalStatus(
    profile = profile,
    version = version,
    overall = OperationalHealth.Ok,
    memoryPressure = OperationalPressure.Ok,
    diskPressure = OperationalPressure.Ok,
)

private fun authRequiredInfo() = HermesConnectionInfo(
    version = "0.20.0",
    authRequired = true,
    nativeOAuthSupported = true,
    providers = listOf(HermesAuthProvider("nous", "Nous Research")),
)

private fun tunnelSettings(origin: ServerOrigin) = ServerSettingsState.Ready(
    ServerCatalog.single(
        ServerCatalogEntry(origin, connectionMode = ServerConnectionMode.ExternalSshTunnel),
    ),
)

private class RecordingTunnelBootstrap(
    private val origin: ServerOrigin,
    tokens: List<String>,
) : LoopbackSessionBootstrapClient {
    private val remaining = ArrayDeque(tokens)
    var calls = 0

    override suspend fun bootstrap(origin: ServerOrigin): LoopbackSessionBootstrapResult {
        calls += 1
        assertEquals(this.origin, origin)
        val token = remaining.removeFirstOrNull()
            ?: return LoopbackSessionBootstrapResult.Failure(LoopbackSessionBootstrapFailure.TransportFailure)
        return LoopbackSessionBootstrapResult.Success(HermesCredential.LoopbackSession.create(origin, token))
    }
}

/** Bootstraps any loopback origin, recording the order it was asked for. */
private class SwitchableTunnelBootstrap : LoopbackSessionBootstrapClient {
    val origins = mutableListOf<ServerOrigin>()
    private var issued = 0

    override suspend fun bootstrap(origin: ServerOrigin): LoopbackSessionBootstrapResult {
        origins += origin
        issued += 1
        return LoopbackSessionBootstrapResult.Success(
            HermesCredential.LoopbackSession.create(origin, "session-token-$issued"),
        )
    }
}

/**
 * Rejects protected host-file reads that still carry the credential adopted at
 * connect time, releasing every waiting read at the same instant so recovery is
 * genuinely concurrent rather than serialized by the test scheduler.
 */
private class ConcurrentHostReadTunnelClient(
    private val expectedConcurrentReads: Int,
    private val rejectEveryRead: Boolean = false,
    private val rejectionGate: CompletableDeferred<Unit>? = null,
) : HermesConnectionClient {
    private val allArrived = CompletableDeferred<Unit>()
    private var arrivals = 0
    private var connectCredential: HermesCredential? = null
    val retryCredentials = mutableListOf<HermesCredential>()
    var readAttempts = 0
        private set

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
        throw AssertionError("Tunnel connection must use the public-only probe")

    override suspend fun probeExternalTunnel(serverOrigin: ServerOrigin) =
        HermesConnectionInfo("0.20.4", false, false, emptyList())

    override suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean,
    ): List<SessionSummary> {
        if (connectCredential == null) connectCredential = credential
        return listOf(SessionSummary(DurableSessionId("tunnel-1"), "Tunnel session"))
    }

    private suspend fun protectedRead(credential: HermesCredential) {
        readAttempts += 1
        if (rejectEveryRead || credential === connectCredential) {
            arrivals += 1
            if (arrivals >= expectedConcurrentReads) allArrived.complete(Unit)
            allArrived.await()
            rejectionGate?.await()
            throw HermesAuthenticationRejectedException("Hermes host read returned HTTP 401")
        }
        retryCredentials += credential
    }

    override suspend fun loadHostFiles(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String?,
    ): HostFileListing {
        protectedRead(credential)
        return HostFileListing(path = path ?: "/workspace", entries = emptyList())
    }

    override suspend fun downloadManagedFile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): HostFileContent {
        protectedRead(credential)
        return HostFileContent("notes.txt", path, "text/plain", ByteArray(0))
    }

    override suspend fun downloadManagedImage(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): ByteArray {
        protectedRead(credential)
        return ByteArray(1)
    }
}

/**
 * Rejects the connect-time credential once for every management read, so a read
 * that bypasses the shared retry helper leaves its field unpopulated.
 */
private class ManagementReadTunnelClient : HermesConnectionClient {
    private var connectCredential: HermesCredential? = null
    private val rejectedOnce = mutableSetOf<String>()

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
        throw AssertionError("Tunnel connection must use the public-only probe")

    override suspend fun probeExternalTunnel(serverOrigin: ServerOrigin) =
        HermesConnectionInfo("0.20.4", false, false, emptyList())

    private fun rejectFirstUseOf(route: String, credential: HermesCredential) {
        if (credential !== connectCredential) return
        if (rejectedOnce.add(route)) {
            throw HermesAuthenticationRejectedException("Hermes $route returned HTTP 401")
        }
    }

    override suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean,
    ): List<SessionSummary> {
        if (connectCredential == null) {
            connectCredential = credential
        } else {
            rejectFirstUseOf("sessions", credential)
        }
        return listOf(SessionSummary(DurableSessionId("tunnel-1"), "Tunnel session"))
    }

    override suspend fun loadProfiles(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): List<String> = listOf("default")

    override suspend fun loadDefaultModelOptions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): ModelOptions = ModelOptions(
        current = ModelSelection("nous", "hermes-4"),
        providers = listOf(ModelProviderOption("nous", "Nous", listOf("hermes-4"))),
        profile = profile,
    )

    override suspend fun loadCurrentModelInfo(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): CurrentModelInfo {
        rejectFirstUseOf("model info", credential)
        return CurrentModelInfo(
            profile = profile,
            model = "hermes-4",
            provider = "nous",
            effectiveContextLength = null,
            capabilities = ModelCapabilities(),
        )
    }

    override suspend fun loadProfileReasoningEffort(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        provider: String,
        model: String,
    ): String? {
        rejectFirstUseOf("reasoning effort", credential)
        return "high"
    }

    override suspend fun loadProfileReasoningDefault(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): String? {
        rejectFirstUseOf("reasoning default", credential)
        return "high"
    }

    override suspend fun loadProfileReasoningOverrides(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        options: ModelOptions,
    ): Map<ModelSelection, String> {
        rejectFirstUseOf("reasoning overrides", credential)
        return mapOf(ModelSelection("nous", "hermes-4") to "low")
    }
}

private class TunnelConnectionClient(
    private val probeFailure: Exception? = null,
    private val rejectSessionCalls: Set<Int> = emptySet(),
    private val rejectMutation: Boolean = false,
) : HermesConnectionClient {
    val credentials = mutableListOf<HermesCredential>()
    var mutationCalls = 0

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
        throw AssertionError("Tunnel connection must use the public-only probe")

    override suspend fun probeExternalTunnel(serverOrigin: ServerOrigin): HermesConnectionInfo {
        probeFailure?.let { throw it }
        return HermesConnectionInfo("0.20.4", false, false, emptyList())
    }

    override suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean,
    ): List<SessionSummary> {
        credentials += credential
        if (credentials.size in rejectSessionCalls) {
            throw HermesAuthenticationRejectedException("Hermes sessions returned HTTP 401")
        }
        return listOf(SessionSummary(DurableSessionId("tunnel-1"), "Tunnel session"))
    }

    override suspend fun updateServerConfig(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        config: JsonObject,
    ): Boolean {
        mutationCalls += 1
        if (rejectMutation) throw HermesUnauthorizedException()
        return true
    }
}

private class RecordingNativeTokenStore : NativeTokenStore {
    var loadCalls = 0
    var saveCalls = 0
    var clearCalls = 0
    override suspend fun load(serverOrigin: ServerOrigin): NativeTokenSet? {
        loadCalls += 1
        return null
    }
    override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) { saveCalls += 1 }
    override suspend fun clear(serverOrigin: ServerOrigin) { clearCalls += 1 }
}

private class FakeHermesConnectionClient : HermesConnectionClient {
    val response = CompletableDeferred<HermesConnectionInfo>()
    val probedOrigins = mutableListOf<ServerOrigin>()

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo {
        probedOrigins += serverOrigin
        return response.await()
    }
}

private class UnauthenticatedVoiceClient : HermesConnectionClient {
    val credentials = mutableListOf<HermesCredential>()

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = false,
        nativeOAuthSupported = false,
        providers = emptyList(),
        sessions = emptyList(),
    )

    override suspend fun probeVoiceCapabilities(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): VoiceCapabilities {
        credentials += credential
        return VoiceCapabilities.NONE
    }

    override suspend fun loadVoiceServerConfig(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): VoiceServerConfig {
        credentials += credential
        return VoiceServerConfig.DEFAULT
    }
}

private class UnauthenticatedRecentSessionsClient : HermesConnectionClient {
    var recentSessionPageCalls = 0
    var sawNullAccessToken = false

    override suspend fun probe(serverOrigin: ServerOrigin) = HermesConnectionInfo(
        version = "0.20.0",
        authRequired = false,
        nativeOAuthSupported = false,
        providers = emptyList(),
        sessions = emptyList(),
    )

    override suspend fun loadSessionsPageForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        limit: Int,
        offset: Int,
        archivedOnly: Boolean,
    ): SessionPage {
        recentSessionPageCalls += 1
        sawNullAccessToken = credential == HermesCredential.None
        return SessionPage(
            sessions = listOf(
                SessionSummary(
                    com.unsupportedpastels.hermesandroid.app.DurableSessionId("public-1"),
                    "Public session",
                ),
            ),
            total = 1,
            limit = limit,
            offset = offset,
        )
    }
}

private class RecordingOfflineCacheRepository(
    private val snapshots: Map<CacheScope, OfflineCacheSnapshot> = emptyMap(),
    private val readBarrier: CompletableDeferred<Unit>? = null,
) : OfflineCacheRepository {
    override val transcriptCachingEnabled = MutableStateFlow(false)
    val clearedScopes = mutableListOf<CacheScope?>()
    val clearedTranscriptScopes = mutableListOf<CacheScope?>()

    override suspend fun read(scope: CacheScope, nowEpochSeconds: Long): OfflineCacheSnapshot {
        readBarrier?.await()
        return snapshots[scope] ?: OfflineCacheSnapshot()
    }

    override suspend fun writeMetadata(
        scope: CacheScope,
        sessions: List<SessionSummary>,
        nowEpochSeconds: Long,
    ) = Unit

    override suspend fun writeTranscript(
        scope: CacheScope,
        summary: SessionSummary,
        messages: List<ChatMessage>,
        nowEpochSeconds: Long,
    ) = Unit

    override suspend fun deleteSession(scope: CacheScope, durableSessionId: DurableSessionId) = Unit

    override suspend fun clearTranscriptTails(scope: CacheScope?) {
        clearedTranscriptScopes += scope
    }

    override suspend fun clear(scope: CacheScope?) {
        clearedScopes += scope
    }

    override suspend fun setTranscriptCachingEnabled(enabled: Boolean) {
        transcriptCachingEnabled.value = enabled
    }
}

private data class SessionProfileRequest(
    val origin: String,
    val profile: String,
    val archivedOnly: Boolean,
)

private class AuthenticatingHermesConnectionClient : HermesConnectionClient {
    val probeResponse = CompletableDeferred<HermesConnectionInfo>()
    val authenticationResponse = CompletableDeferred<AuthenticatedHermesConnection>()
    var authenticatedWith: HermesCredential? = null
    var authenticateCalls = 0
    var authenticatedSessionsOverride: List<SessionSummary>? = null
    val authenticateBarriers = ArrayDeque<CompletableDeferred<Unit>>()
    var hostDirectoryResponse = HostDirectoryListing("/srv", emptyList())
    val hostDirectoryRequests = mutableListOf<Triple<ServerOrigin, HermesCredential, String?>>()
    var defaultModelOptions = ModelOptions(current = null, providers = emptyList())
    var profiles = listOf("default")
    val defaultModelProfiles = mutableListOf<String>()
    var profileReasoningEffort: String? = null
    var profileReasoningFailure: Throwable? = null
    var currentModelInfoLoader: suspend (ServerOrigin, HermesCredential, String) -> CurrentModelInfo = { _, _, profile ->
        throw UnsupportedOperationException("current model info not configured for $profile")
    }
    var cronTriggerResponse: CompletableDeferred<CronJob>? = null
    var cronTriggerFailure: Throwable? = null
    var cronTriggerCalls = 0
    var cronRunsResponse: List<CronJobRun> = emptyList()
    var cronRunsFailure: Throwable? = null
    val cronRunsScopes = mutableListOf<CronJobScope>()
    var bulkDeleteResponse: CompletableDeferred<BulkDeleteResult> =
        CompletableDeferred(BulkDeleteResult(ok = true, deleted = 0))
    var bulkSessionsAfterDelete: List<SessionSummary>? = null
    val bulkDeletedIds = mutableListOf<DurableSessionId>()
    val probedOrigins = mutableListOf<ServerOrigin>()
    val operationalStatusProfiles = mutableListOf<String>()
    var statusResponse = operationalStatus("default", "0.20.0")
    var operationalStatusFailure: Throwable? = null
    var operationalStatusLoader: suspend (ServerOrigin, String) -> OperationalStatus = { _, profile ->
        operationalStatusFailure?.let { throw it }
        operationalStatusProfiles += profile
        operationalStatus(profile, statusResponse.version ?: "0.20.0")
    }
    val sessionsForProfileRequests = mutableListOf<SessionProfileRequest>()
    var sessionsForProfileFailure: Throwable? = null
    var sessionsForProfileLoader: suspend (ServerOrigin, HermesCredential, String, Boolean) -> List<SessionSummary> =
        { _, _, _, _ ->
            authenticatedSessionsOverride?.let { return@let it }
                ?: authenticationResponse.await().sessions
        }

    override suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean,
    ): List<SessionSummary> {
        sessionsForProfileRequests += SessionProfileRequest(serverOrigin.value, profile, archivedOnly)
        sessionsForProfileFailure?.let { throw it }
        authenticateBarriers.firstOrNull()?.let { it.await() }
        return sessionsForProfileLoader(serverOrigin, credential, profile, archivedOnly)
    }

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo {
        probedOrigins += serverOrigin
        return probeResponse.await()
    }

    override suspend fun loadOperationalStatus(
        serverOrigin: ServerOrigin,
        profile: String,
    ): OperationalStatus = operationalStatusLoader(serverOrigin, profile)

    override suspend fun bulkDeleteSessions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionIds: Collection<DurableSessionId>,
        profile: String?,
    ): BulkDeleteResult {
        bulkDeletedIds += durableSessionIds
        bulkSessionsAfterDelete?.let { authenticatedSessionsOverride = it }
        return bulkDeleteResponse.await()
    }

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): AuthenticatedHermesConnection {
        authenticateCalls += 1
        authenticatedWith = credential
        authenticateBarriers.firstOrNull()?.let { it.await() }
        val authenticated = authenticationResponse.await()
        return authenticatedSessionsOverride?.let { authenticated.copy(sessions = it) } ?: authenticated
    }

    override suspend fun loadHostDirectories(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String?,
    ): HostDirectoryListing {
        hostDirectoryRequests += Triple(serverOrigin, credential, path)
        return hostDirectoryResponse
    }

    override suspend fun loadDefaultModelOptions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): ModelOptions {
        defaultModelProfiles += profile
        return defaultModelOptions
    }

    override suspend fun loadProfiles(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): List<String> = profiles

    override suspend fun loadCurrentModelInfo(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): CurrentModelInfo = currentModelInfoLoader(serverOrigin, credential, profile)

    override suspend fun loadProfileReasoningEffort(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        provider: String,
        model: String,
    ): String? {
        profileReasoningFailure?.let { throw it }
        return profileReasoningEffort
    }

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ): List<com.unsupportedpastels.hermesandroid.gateway.ChatMessage> = emptyList()

    override suspend fun triggerCronJob(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        jobId: String,
    ): CronJob {
        cronTriggerCalls += 1
        cronTriggerFailure?.let { throw it }
        return cronTriggerResponse?.await() ?: CronJob(jobId, "Job", "every 1h")
    }

    override suspend fun loadCronJobRuns(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        jobId: String,
        limit: Int,
    ): List<CronJobRun> {
        cronRunsScopes += CronJobScope(serverOrigin.value, profile, jobId)
        cronRunsFailure?.let { throw it }
        return cronRunsResponse
    }
}

private class RecordingSessionFilterRepository :
    com.unsupportedpastels.hermesandroid.session.SessionFilterRepository {
    private val values = mutableMapOf<
        com.unsupportedpastels.hermesandroid.session.SessionFilterScope,
        MutableList<com.unsupportedpastels.hermesandroid.session.SavedSessionFilter>,
    >()

    override suspend fun list(
        scope: com.unsupportedpastels.hermesandroid.session.SessionFilterScope,
    ): List<com.unsupportedpastels.hermesandroid.session.SavedSessionFilter> =
        values[scope].orEmpty().toList()

    override suspend fun save(
        scope: com.unsupportedpastels.hermesandroid.session.SessionFilterScope,
        filter: com.unsupportedpastels.hermesandroid.session.SavedSessionFilter,
    ) {
        val filters = values.getOrPut(scope) { mutableListOf() }
        filters.removeAll { it.normalizedName == filter.normalizedName }
        filters += filter
    }

    override suspend fun remove(
        scope: com.unsupportedpastels.hermesandroid.session.SessionFilterScope,
        name: String,
    ) {
        values[scope]?.removeAll { it.normalizedName == name.trim() }
    }
}

private class FakeNativeLogin : NativeLogin {
    val response = CompletableDeferred<NativeTokenSet>()

    override suspend fun signIn(
        serverOrigin: ServerOrigin,
        provider: String,
        openBrowser: suspend (String) -> Unit,
    ): NativeTokenSet = response.await()
}

private class SwitchingHermesConnectionClient : HermesConnectionClient {
    val probes = mutableMapOf<ServerOrigin, CompletableDeferred<HermesConnectionInfo>>()
    val authenticatedTokens = mutableListOf<HermesCredential>()

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo =
        probes.getOrPut(serverOrigin) { CompletableDeferred() }.await()

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): AuthenticatedHermesConnection {
        authenticatedTokens += credential
        return AuthenticatedHermesConnection("user", emptyList())
    }
}

private class LateNativeLogin : NativeLogin {
    val response = CompletableDeferred<NativeTokenSet>()
    var wasCancelled = false

    override suspend fun signIn(
        serverOrigin: ServerOrigin,
        provider: String,
        openBrowser: suspend (String) -> Unit,
    ): NativeTokenSet = try {
        withContext(NonCancellable) { response.await() }
    } finally {
        wasCancelled = !currentCoroutineContext().isActive
    }
}

private class FixedTokenStore : NativeTokenStore {
    private val tokens = NativeTokenSet(
        accessToken = "opaque-access",
        refreshToken = "opaque-refresh",
        expiresAt = 2_000_000_000,
        provider = "nous",
        userId = "user",
    )

    var clearCalls = 0

    override suspend fun load(serverOrigin: ServerOrigin): NativeTokenSet = tokens
    override suspend fun save(serverOrigin: ServerOrigin, tokens: NativeTokenSet) = Unit
    override suspend fun clear(serverOrigin: ServerOrigin) {
        clearCalls += 1
    }
}

private class MetadataOnlyProjectSession private constructor(
    private val treeProvider: suspend () -> ProjectTreeResult,
    private val sessionsProvider: suspend (ProjectId) -> ProjectSessionsResult,
    private val createProjectProvider: suspend (String, String) -> ProjectSummary = { _, _ ->
        error("project creation not configured")
    },
) : HermesChatSession {
    constructor(tree: ProjectTreeResult) : this({ tree }, { error("project sessions not configured") })
    constructor(sessions: ProjectSessionsResult) : this({ ProjectTreeResult(emptyList()) }, { sessions })
    constructor(failure: Throwable) : this({ throw failure }, { throw failure })

    companion object {
        fun fromTree(tree: Deferred<ProjectTreeResult>) =
            MetadataOnlyProjectSession({ tree.await() }, { error("project sessions not configured") })

        fun fromTreeAndSessions(
            tree: ProjectTreeResult,
            sessions: Deferred<ProjectSessionsResult>,
        ) = MetadataOnlyProjectSession({ tree }, { sessions.await() })

        fun fromSessions(sessions: Deferred<ProjectSessionsResult>) =
            MetadataOnlyProjectSession({ ProjectTreeResult(emptyList()) }, { sessions.await() })

        fun forTreeAndSessionsFailure(
            tree: ProjectTreeResult,
            failure: Throwable,
        ) = MetadataOnlyProjectSession({ tree }, { throw failure })

        fun forTreeAndSessions(
            tree: ProjectTreeResult,
            sessions: ProjectSessionsResult,
        ) = MetadataOnlyProjectSession({ tree }, { sessions })

        fun forTreeAndProjectCreation(
            tree: ProjectTreeResult,
            created: ProjectSummary,
        ) = MetadataOnlyProjectSession(
            treeProvider = { tree },
            sessionsProvider = { error("project sessions not configured") },
            createProjectProvider = { _, _ -> created },
        )

        fun forProjectCreation(created: ProjectSummary) = MetadataOnlyProjectSession(
            treeProvider = { ProjectTreeResult(emptyList()) },
            sessionsProvider = { error("project sessions not configured") },
            createProjectProvider = { _, _ -> created },
        )
    }

    override val events = emptyFlow<HermesChatEvent>()
    var resumeCalls = 0
    var createCalls = 0
    var submitCalls = 0
    var closed = false

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeCalls += 1
        error("metadata connection must not resume")
    }

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createCalls += 1
        error("metadata connection must not create")
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submitCalls += 1
        error("metadata connection must not submit")
    }

    override suspend fun loadProjectTree(
        profile: String?,
        previewLimit: Int,
        sessionLimit: Int,
    ): ProjectTreeResult = treeProvider()

    override suspend fun loadProjectSessions(
        projectId: ProjectId,
        profile: String?,
        sessionLimit: Int,
    ): ProjectSessionsResult = sessionsProvider(projectId)

    override suspend fun createProject(
        name: String,
        path: String,
        profile: String?,
    ): ProjectSummary = createProjectProvider(name, path)

    override suspend fun close() {
        closed = true
    }
}

private class ModelPickerChatSession(
    private val requireConfirmation: Boolean = false,
) : HermesChatSession {
    override val events = MutableSharedFlow<HermesChatEvent>()
    var createCalls = 0
    var optionsRuntimeId: RuntimeSessionId? = null
    var appliedRuntimeId: RuntimeSessionId? = null
    var appliedSelection: ModelSelection? = null
    var confirmExpensive = false
    var applyCalls = 0
    var fastRuntimeId: RuntimeSessionId? = null
    var appliedFast: Boolean? = null

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = error("draft should be created")

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createCalls += 1
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-model"),
            durableSessionId = null,
            resumed = false,
            messages = emptyList(),
            running = false,
            inflight = null,
        )
    }

    override suspend fun loadModelOptions(runtimeSessionId: RuntimeSessionId): ModelOptions {
        optionsRuntimeId = runtimeSessionId
        return ModelOptions(
            current = ModelSelection("nous", "current"),
            providers = listOf(
                ModelProviderOption(
                    "nous",
                    "Nous Research",
                    listOf("current", "gpt-5.6-luna"),
                    capabilities = mapOf(
                        "current" to ModelCapabilities(fast = true, reasoning = true),
                        "gpt-5.6-luna" to ModelCapabilities(fast = true, reasoning = true),
                    ),
                ),
            ),
        )
    }

    override suspend fun setModel(
        runtimeSessionId: RuntimeSessionId,
        provider: String,
        model: String,
        confirmExpensiveModel: Boolean,
    ): ModelSwitchResult {
        applyCalls += 1
        appliedRuntimeId = runtimeSessionId
        appliedSelection = ModelSelection(provider, model)
        confirmExpensive = confirmExpensiveModel
        return if (requireConfirmation && !confirmExpensiveModel) {
            ModelSwitchResult(
                accepted = false,
                confirmationRequired = true,
                confirmationMessage = "This model is expensive",
            )
        } else {
            ModelSwitchResult(accepted = true)
        }
    }

    override suspend fun setFast(runtimeSessionId: RuntimeSessionId, fast: Boolean) {
        fastRuntimeId = runtimeSessionId
        appliedFast = fast
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = error("picker must not submit a prompt")

    override suspend fun close() = Unit
}

private class FastFailureDraftChatSession : HermesChatSession {
    override val events = MutableSharedFlow<HermesChatEvent>()
    var createCalls = 0
    var setFastCalls = 0
    var submitCalls = 0
    var closed = false

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = error("draft should be created")

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createCalls += 1
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-fast-fail"),
            durableSessionId = durableSessionId,
            resumed = false,
            messages = emptyList(),
            running = false,
            inflight = null,
        )
    }

    override suspend fun setFast(runtimeSessionId: RuntimeSessionId, fast: Boolean) {
        setFastCalls += 1
        throw HermesChatTransportException("fast mode RPC failed transiently")
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submitCalls += 1
        return PromptSubmission("streaming")
    }

    override suspend fun close() {
        closed = true
    }
}

private class ProcessAwareDraftChatSession : HermesChatSession {
    override val events = MutableSharedFlow<HermesChatEvent>()
    var createCalls = 0
    var submitCalls = 0
    var processListCalls = 0
    var closed = false

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = error("draft should be created")

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createCalls += 1
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-processes"),
            durableSessionId = durableSessionId,
            resumed = false,
            messages = emptyList(),
            running = false,
            inflight = null,
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submitCalls += 1
        return PromptSubmission("streaming")
    }

    override suspend fun loadProcessList(runtimeSessionId: RuntimeSessionId): List<ProcessRow> {
        processListCalls += 1
        // The prompt's background process only exists after the turn was accepted.
        return if (submitCalls == 0) emptyList() else {
            listOf(ProcessRow(processId = "proc-1", command = "npm run build", status = "running"))
        }
    }

    override suspend fun close() {
        closed = true
    }
}

private class RecordingProjectDraftChatSession : HermesChatSession {
    override val events = MutableSharedFlow<HermesChatEvent>()
    var createCalls = 0
    var createdForDurableId: DurableSessionId? = null
    var createdProfile: String? = null
    var createdWorkspacePath: String? = null
    var submittedText: String? = null

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = error("project draft must be created, not resumed")

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createCalls += 1
        createdForDurableId = durableSessionId
        createdProfile = profile
        createdWorkspacePath = workspacePath
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-project-draft"),
            durableSessionId = durableSessionId,
            resumed = false,
            messages = emptyList(),
            running = false,
            inflight = null,
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submittedText = text
        return PromptSubmission("streaming")
    }

    override suspend fun close() = Unit
}

private class ReasoningResumeChatSession(
    private val durableSessionId: DurableSessionId,
    private val model: String = "gpt-5.6-sol",
) : HermesChatSession {
    override val events = emptyFlow<HermesChatEvent>()
    var resumeCalls = 0
    var fastRuntimeId: RuntimeSessionId? = null
    var appliedFast: Boolean? = null

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumeCalls += 1
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-reasoning"),
            durableSessionId = this.durableSessionId,
            resumed = true,
            messages = listOf(
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("assistant"),
                        "text" to JsonPrimitive(""),
                        "reasoning_content" to JsonPrimitive("Recovered reasoning"),
                    ),
                ),
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("tool"),
                        "name" to JsonPrimitive("terminal"),
                        "context" to JsonPrimitive("pwd"),
                    ),
                ),
            ),
            running = false,
            inflight = null,
            model = model,
            provider = "openai-codex",
            reasoningEffort = "medium",
        )
    }

    override suspend fun setFast(runtimeSessionId: RuntimeSessionId, fast: Boolean) {
        fastRuntimeId = runtimeSessionId
        appliedFast = fast
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = error("resume fixture must not submit")

    override suspend fun close() = Unit
}

private class ReasoningControlChatSession(
    private val durableSessionId: DurableSessionId,
) : HermesChatSession {
    override val events = emptyFlow<HermesChatEvent>()
    var appliedRuntimeId: RuntimeSessionId? = null
    var appliedEffort: String? = null

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = ResumedChatSession(
        runtimeSessionId = RuntimeSessionId("runtime-reasoning-control"),
        durableSessionId = this.durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
        model = "gpt-5.6-sol",
        provider = "openai-codex",
        reasoningEffort = "xhigh",
    )

    override suspend fun setReasoning(
        runtimeSessionId: RuntimeSessionId,
        effort: String,
    ) {
        appliedRuntimeId = runtimeSessionId
        appliedEffort = effort
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = error("reasoning control must not submit")

    override suspend fun close() = Unit
}


private class ScriptedEventChatSession(
    private val scriptedEvents: List<HermesChatEvent>,
) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    private val runtimeSessionId = RuntimeSessionId("runtime-scripted")
    override val events = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        resumed = true,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        resumed = false,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        scriptedEvents.forEach { channel.send(it) }
        channel.close()
        return PromptSubmission("streaming")
    }

    override suspend fun close() {
        channel.close()
    }
}

private class BlockingCreateProjectDraftChatSession(
    private val canonicalId: String,
) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    override val events = channel.receiveAsFlow()
    val createStarted = CompletableDeferred<Unit>()
    val releaseCreate = CompletableDeferred<Unit>()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = error("stale draft operation must create")

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession {
        createStarted.complete(Unit)
        withContext(NonCancellable) { releaseCreate.await() }
        return ResumedChatSession(
            runtimeSessionId = RuntimeSessionId("runtime-$canonicalId"),
            durableSessionId = DurableSessionId(canonicalId),
            resumed = false,
            messages = emptyList(),
            running = false,
            inflight = null,
        )
    }

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission = PromptSubmission("streaming")

    override suspend fun close() {
        channel.close()
    }
}

private class CanonicalProjectDraftChatSession(
    private val canonicalId: String,
) : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    private val runtimeSessionId = RuntimeSessionId("runtime-$canonicalId")
    override val events = channel.receiveAsFlow()
    var submittedText: String? = null
    var resumedDurableId: DurableSessionId? = null

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession {
        resumedDurableId = durableSessionId
        return ResumedChatSession(
            runtimeSessionId = runtimeSessionId,
            durableSessionId = DurableSessionId(canonicalId),
            resumed = true,
            messages = emptyList(),
            running = false,
            inflight = null,
        )
    }

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = DurableSessionId(canonicalId),
        resumed = false,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        submittedText = text
        channel.send(HermesChatEvent.MessageComplete(runtimeSessionId, "done", "done"))
        return PromptSubmission("streaming")
    }

    fun closeEvents() = channel.close()

    override suspend fun close() {
        channel.close()
    }
}

private class TerminalToolChatSession : HermesChatSession {
    private val channel = Channel<HermesChatEvent>(Channel.UNLIMITED)
    private val runtimeSessionId = RuntimeSessionId("runtime-terminal-tools")
    override val events = channel.receiveAsFlow()

    override suspend fun resume(
        durableSessionId: DurableSessionId,
        profile: String?,
    ): ResumedChatSession = error("new draft must be created")

    override suspend fun createSession(
        durableSessionId: DurableSessionId,
        profile: String?,
        workspacePath: String?,
    ): ResumedChatSession = ResumedChatSession(
        runtimeSessionId = runtimeSessionId,
        durableSessionId = durableSessionId,
        resumed = false,
        messages = emptyList(),
        running = false,
        inflight = null,
    )

    override suspend fun submitPrompt(
        runtimeSessionId: RuntimeSessionId,
        text: String,
    ): PromptSubmission {
        channel.send(HermesChatEvent.ToolStart(runtimeSessionId, "tool-stale", "web_search", "query"))
        channel.send(HermesChatEvent.MessageComplete(runtimeSessionId, "done", "done"))
        return PromptSubmission("streaming")
    }

    override suspend fun close() {
        channel.close()
    }
}
