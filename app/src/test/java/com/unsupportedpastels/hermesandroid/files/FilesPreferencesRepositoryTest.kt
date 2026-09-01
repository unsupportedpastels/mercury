package com.unsupportedpastels.hermesandroid.files

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesPreferencesRepositoryTest {
    @Test
    fun missingOriginDefaultsToPreviewOff() = runTest {
        val repository = DataStoreFilesPreferencesRepository(InMemoryFilesPreferencesDataStore(emptyPreferences()))
        val origin = ServerOrigin.parse("https://hermes.example")
        assertEquals(FilesPreferences(inAppFilePreviewEnabled = false), repository.preferences(origin).first())
    }

    @Test
    fun persistsPreviewFlagPerNormalizedOriginWithoutLeakingToAnotherServer() = runTest {
        val repository = DataStoreFilesPreferencesRepository(InMemoryFilesPreferencesDataStore(emptyPreferences()))
        val first = ServerOrigin.parse("HTTPS://FIRST.example/")
        val second = ServerOrigin.parse("https://second.example")

        repository.setInAppFilePreviewEnabled(first, true)

        assertTrue(repository.preferences(ServerOrigin.parse("https://first.example")).first().inAppFilePreviewEnabled)
        assertFalse(repository.preferences(second).first().inAppFilePreviewEnabled)
    }
}

private class InMemoryFilesPreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
