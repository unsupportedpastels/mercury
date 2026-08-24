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
    fun selectingAndRemovingAreOriginScopedAndActiveRemovalSelectsAnotherEntry() = runTest {
        val dataStore = InMemoryCatalogDataStore(emptyPreferences())
        val repository = DataStoreServerSettingsRepository(
            dataStore = dataStore,
            nowEpochSeconds = { 42L },
        )
        val first = ServerOrigin.parse("https://first.example")
        val second = ServerOrigin.parse("https://second.example")

        repository.save(ServerCatalogEntry(first, label = "First"))
        repository.save(ServerCatalogEntry(second, label = "Second"))

        assertTrue(repository.remove(second))
        assertFalse(repository.remove(second))
        val state = repository.states.firstReady()
        assertEquals(first, state.activeOrigin)
        assertEquals(listOf(first), state.catalog.entries.map(ServerCatalogEntry::origin))
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
