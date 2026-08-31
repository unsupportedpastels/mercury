package com.unsupportedpastels.hermesandroid.connection

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogRepositoryTest {
    @Test
    fun migratesLegacyOriginIntoCatalogAndMakesItActive() = runTest {
        val dataStore = InMemoryCatalogDataStore(
            preferencesOf("server_origin" to "HTTPS://FIRST.example/"),
        )
        val repository = DataStoreServerSettingsRepository(dataStore)

        repository.states.test {
            assertEquals(ServerSettingsState.Loading, awaitItem())
            val state = awaitItem() as ServerSettingsState.Ready
            assertEquals(ServerOrigin.parse("https://first.example"), state.activeOrigin)
            assertEquals(
                listOf(ServerOrigin.parse("https://first.example")),
                state.catalog.entries.map(ServerCatalogEntry::origin),
            )
            assertEquals(ServerConnectionMode.Direct, state.catalog.activeEntry?.connectionMode)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(dataStore.current[stringPreferencesKey("server_catalog")].orEmpty().isNotBlank())
        assertEquals(
            "https://first.example",
            dataStore.current[stringPreferencesKey("active_origin")],
        )
        assertFalse(dataStore.current.contains(stringPreferencesKey("server_origin")))
    }

    @Test
    fun missingConnectionModeInExistingCatalogDefaultsToDirect() = runTest {
        val dataStore = InMemoryCatalogDataStore(
            preferencesOf(
                "server_catalog" to "{\"entries\":[{\"origin\":\"https://legacy.example\"}]}",
                "active_origin" to "https://legacy.example",
            ),
        )

        val state = DataStoreServerSettingsRepository(dataStore).states.firstReady()

        assertEquals(ServerConnectionMode.Direct, state.catalog.activeEntry?.connectionMode)
    }

    @Test
    fun unknownConnectionModeMakesStrictPersistedCatalogUnavailable() = runTest {
        val dataStore = InMemoryCatalogDataStore(
            preferencesOf(
                "server_catalog" to "{\"entries\":[{\"origin\":\"https://legacy.example\",\"connection_mode\":\"FutureMode\"}]}",
                "active_origin" to "https://legacy.example",
            ),
        )

        assertEquals(
            ServerSettingsState.Unavailable,
            DataStoreServerSettingsRepository(dataStore).states.first { it !is ServerSettingsState.Loading },
        )
    }

    @Test
    fun persistsExternalTunnelModeAndPreservesItAcrossLabelSelectAndRemoveOperations() = runTest {
        val dataStore = InMemoryCatalogDataStore(emptyPreferences())
        val repository = DataStoreServerSettingsRepository(dataStore, nowEpochSeconds = { 42L })
        val direct = ServerCatalogEntry(ServerOrigin.parse("https://direct.example"), label = "Direct")
        val tunnel = ServerCatalogEntry(
            ServerOrigin.parse("http://127.0.0.1:8080"),
            label = "Tunnel",
            connectionMode = ServerConnectionMode.ExternalSshTunnel,
        )

        repository.save(tunnel)
        repository.save(direct)
        repository.updateLabel(tunnel.copy(label = "Renamed tunnel"))
        repository.select(tunnel.origin)
        assertTrue(repository.remove(direct.origin))

        val state = DataStoreServerSettingsRepository(dataStore).states.firstReady()
        assertEquals(tunnel.origin, state.activeOrigin)
        assertEquals("Renamed tunnel", state.catalog.activeEntry?.label)
        assertEquals(ServerConnectionMode.ExternalSshTunnel, state.catalog.activeEntry?.connectionMode)
        assertTrue(
            dataStore.current[stringPreferencesKey("server_catalog")]
                .orEmpty()
                .contains("\"connection_mode\":\"ExternalSshTunnel\""),
        )
    }

    @Test
    fun normalizesDeduplicatesAndBoundsPersistedCatalog() = runTest {
        val origins = (0..10).map { "https://server-$it.example" }
        val entries = origins.joinToString(",") { origin ->
            "{\"origin\":\"$origin/\",\"label\":\"$origin\"}"
        }
        val dataStore = InMemoryCatalogDataStore(
            preferencesOf(
                "server_catalog" to "{\"entries\":[$entries,{\"origin\":\"HTTPS://SERVER-1.example/\",\"label\":\"updated\"}]}",
                "active_origin" to "HTTPS://SERVER-1.example/",
            ),
        )
        val repository = DataStoreServerSettingsRepository(dataStore)

        val state = repository.states.firstReady()

        assertEquals(MAX_SERVER_CATALOG_ENTRIES, state.catalog.entries.size)
        assertEquals(MAX_SERVER_CATALOG_ENTRIES, state.catalog.entries.map { it.origin }.distinct().size)
        assertEquals(ServerOrigin.parse("https://server-1.example"), state.activeOrigin)
        assertEquals("updated", state.catalog.activeEntry?.label)
    }

    @Test
    fun selectingAndRemovingAreOriginScopedAndActiveRemovalIsRejected() = runTest {
        val dataStore = InMemoryCatalogDataStore(emptyPreferences())
        val repository = DataStoreServerSettingsRepository(
            dataStore = dataStore,
            nowEpochSeconds = { 42L },
        )
        val first = ServerOrigin.parse("https://first.example")
        val second = ServerOrigin.parse("https://second.example")

        repository.save(ServerCatalogEntry(first, label = "First"))
        repository.save(ServerCatalogEntry(second, label = "Second"))

        assertTrue(repository.remove(first))
        assertFalse(repository.remove(second))
        val state = repository.states.firstReady()
        assertEquals(second, state.activeOrigin)
        assertEquals(listOf(second), state.catalog.entries.map(ServerCatalogEntry::origin))
    }

    @Test
    fun editingInactiveLabelDoesNotChangeActiveOrigin() = runTest {
        val repository = DataStoreServerSettingsRepository(InMemoryCatalogDataStore(emptyPreferences()))
        val first = ServerOrigin.parse("https://first.example")
        val second = ServerOrigin.parse("https://second.example")

        repository.save(ServerCatalogEntry(first, label = "First"))
        repository.save(ServerCatalogEntry(second, label = "Second"))
        repository.updateLabel(ServerCatalogEntry(first, label = "Renamed"))

        val state = repository.states.firstReady()
        assertEquals(second, state.activeOrigin)
        assertEquals("Renamed", state.catalog.entries.first { it.origin == first }.label)
    }

    @Test
    fun addingBeyondCapKeepsNewestActiveOriginAndDropsInactiveEntries() = runTest {
        val repository = DataStoreServerSettingsRepository(
            dataStore = InMemoryCatalogDataStore(emptyPreferences()),
            nowEpochSeconds = { 100L },
        )
        repeat(MAX_SERVER_CATALOG_ENTRIES) { index ->
            repository.save(ServerOrigin.parse("https://server-$index.example"))
        }
        val newest = ServerOrigin.parse("https://newest.example")
        repository.save(newest)

        val state = repository.states.firstReady()
        assertEquals(MAX_SERVER_CATALOG_ENTRIES, state.catalog.entries.size)
        assertEquals(newest, state.activeOrigin)
        assertFalse(state.catalog.entries.any { it.origin == ServerOrigin.parse("https://server-0.example") })
    }

    @Test
    fun rememberInstallIdPersistsAsNonSecretCatalogMetadata() = runTest {
        val dataStore = InMemoryCatalogDataStore(emptyPreferences())
        val repository = DataStoreServerSettingsRepository(dataStore, nowEpochSeconds = { 1L })
        val origin = ServerOrigin.parse("http://127.0.0.1:9119")
        repository.save(
            ServerCatalogEntry(origin, connectionMode = ServerConnectionMode.ExternalSshTunnel),
        )

        repository.rememberInstallId(origin, "install-a")

        val state = DataStoreServerSettingsRepository(dataStore).states.firstReady()
        assertEquals("install-a", state.catalog.activeEntry?.lastSeenInstallId)
        assertTrue(
            dataStore.current[stringPreferencesKey("server_catalog")]
                .orEmpty()
                .contains("\"last_seen_install_id\":\"install-a\""),
        )
    }

    @Test
    fun saveRejectsDirectLanCleartextAndTunnelLocalhost() = runTest {
        val repository = DataStoreServerSettingsRepository(InMemoryCatalogDataStore(emptyPreferences()))
        val lan = runCatching { repository.save(ServerOrigin.parse("http://10.0.1.2")) }.exceptionOrNull()
        assertTrue(lan is IllegalArgumentException)
        assertTrue(lan!!.message!!.contains("HTTPS"))

        val localhost = runCatching {
            repository.save(
                ServerCatalogEntry(
                    ServerOrigin.parse("http://localhost:9119"),
                    connectionMode = ServerConnectionMode.ExternalSshTunnel,
                ),
            )
        }.exceptionOrNull()
        assertTrue(localhost is IllegalArgumentException)
        assertTrue(localhost!!.message!!.contains("IPv4"))
    }
}

private suspend fun Flow<ServerSettingsState>.firstReady(): ServerSettingsState.Ready =
    first { it is ServerSettingsState.Ready } as ServerSettingsState.Ready

private fun preferencesOf(vararg values: Pair<String, String>): Preferences =
    androidx.datastore.preferences.core.preferencesOf(
        *values.map { (key, value) -> stringPreferencesKey(key) to value }.toTypedArray(),
    )

private class InMemoryCatalogDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    val current: Preferences get() = state.value
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
