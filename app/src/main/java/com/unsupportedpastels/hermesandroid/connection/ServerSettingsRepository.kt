package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ServerSettingsDataStoreName = "server_settings"
private const val MAX_SERVER_CATALOG_JSON_CHARS = 64 * 1024
private val ServerOriginKey = stringPreferencesKey("server_origin")
private val ServerCatalogKey = stringPreferencesKey("server_catalog")
private val ActiveOriginKey = stringPreferencesKey("active_origin")
private val Context.serverSettingsDataStore by preferencesDataStore(
    name = ServerSettingsDataStoreName,
)

sealed interface ServerSettingsState {
    data object Loading : ServerSettingsState

    data class Ready(
        val catalog: ServerCatalog,
    ) : ServerSettingsState {
        /** Compatibility alias for callers that only need the active origin. */
        val serverOrigin: ServerOrigin?
            get() = catalog.activeOrigin

        val activeOrigin: ServerOrigin?
            get() = catalog.activeOrigin

        constructor(serverOrigin: ServerOrigin?) : this(
            serverOrigin?.let { ServerCatalog.single(ServerCatalogEntry(it)) }
                ?: ServerCatalog.empty(),
        )
    }

    data object Unavailable : ServerSettingsState
}

interface ServerSettingsRepository {
    val states: Flow<ServerSettingsState>

    /** Compatibility entry point: add/update the origin and make it active. */
    suspend fun save(serverOrigin: ServerOrigin)

    /** Add or update local metadata and make the entry active. */
    suspend fun save(entry: ServerCatalogEntry) {
        save(entry.origin)
    }

    /** Select an existing catalog entry without importing credentials or remote state. */
    suspend fun select(serverOrigin: ServerOrigin) {
        save(serverOrigin)
    }

    /** Update only local display metadata without changing the active origin. */
    suspend fun updateLabel(entry: ServerCatalogEntry) {
        save(entry)
    }

    /** Returns true only when an inactive entry was removed. */
    suspend fun remove(serverOrigin: ServerOrigin): Boolean = false
}

@Serializable
private data class PersistedServerCatalog(
    val entries: List<PersistedServerCatalogEntry> = emptyList(),
)

@Serializable
private data class PersistedServerCatalogEntry(
    val origin: String,
    val label: String = "",
    @SerialName("last_used_epoch_seconds") val lastUsedEpochSeconds: Long? = null,
    @SerialName("connection_mode") val connectionMode: String = ServerConnectionMode.Direct.name,
)

class DataStoreServerSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val retryDelayMillis: Long = 1_000,
    internal val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : ServerSettingsRepository {
    constructor(context: Context) : this(context.applicationContext.serverSettingsDataStore)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    override val states: Flow<ServerSettingsState> = dataStore.data
        .map<Preferences, ServerSettingsState>(::readState)
        .retryWhen { error, _ ->
            if (error is IOException) {
                emit(ServerSettingsState.Unavailable)
                delay(retryDelayMillis)
                true
            } else {
                false
            }
        }
        .onStart {
            emit(ServerSettingsState.Loading)
            migrateLegacyOriginIfNeeded()
        }
        .distinctUntilChanged()

    override suspend fun save(serverOrigin: ServerOrigin) {
        save(ServerCatalogEntry(serverOrigin))
    }

    override suspend fun save(entry: ServerCatalogEntry) {
        val normalizedEntry = entry.normalized()
        dataStore.edit { preferences ->
            val current = readCatalog(preferences)
            val now = nowEpochSeconds().coerceAtLeast(0L)
            val updated = current.entries
                .filterNot { it.origin == normalizedEntry.origin }
                .plus(normalizedEntry.copy(lastUsedEpochSeconds = now))
            writeCatalog(
                preferences = preferences,
                catalog = ServerCatalog.normalized(updated, normalizedEntry.origin),
            )
        }
    }

    override suspend fun select(serverOrigin: ServerOrigin) {
        dataStore.edit { preferences ->
            val current = readCatalog(preferences)
            check(current.entries.any { it.origin == serverOrigin }) {
                "Server origin is not in the local catalog"
            }
            val now = nowEpochSeconds().coerceAtLeast(0L)
            val updated = current.entries.map { entry ->
                if (entry.origin == serverOrigin) {
                    entry.copy(lastUsedEpochSeconds = now)
                } else {
                    entry
                }
            }
            writeCatalog(
                preferences = preferences,
                catalog = ServerCatalog.normalized(updated, serverOrigin),
            )
        }
    }

    override suspend fun updateLabel(entry: ServerCatalogEntry) {
        val normalizedEntry = entry.normalized()
        dataStore.edit { preferences ->
            val current = readCatalog(preferences)
            check(current.entries.any { it.origin == normalizedEntry.origin }) {
                "Server origin is not in the local catalog"
            }
            val updated = current.entries.map { existing ->
                if (existing.origin == normalizedEntry.origin) {
                    existing.copy(label = normalizedEntry.label)
                } else {
                    existing
                }
            }
            writeCatalog(
                preferences = preferences,
                catalog = ServerCatalog.normalized(updated, current.activeOrigin),
            )
        }
    }

    override suspend fun remove(serverOrigin: ServerOrigin): Boolean {
        var removed = false
        dataStore.edit { preferences ->
            val current = readCatalog(preferences)
            if (current.activeOrigin == serverOrigin) return@edit
            if (current.entries.none { it.origin == serverOrigin }) return@edit
            removed = true
            writeCatalog(
                preferences = preferences,
                catalog = ServerCatalog.normalized(
                    current.entries.filterNot { it.origin == serverOrigin },
                    current.activeOrigin,
                ),
            )
        }
        return removed
    }

    private fun readState(preferences: Preferences): ServerSettingsState = try {
        ServerSettingsState.Ready(readCatalog(preferences))
    } catch (_: IllegalArgumentException) {
        ServerSettingsState.Unavailable
    }

    private fun readCatalog(preferences: Preferences): ServerCatalog {
        val stored = preferences[ServerCatalogKey]
        if (stored != null) {
            require(stored.length <= MAX_SERVER_CATALOG_JSON_CHARS) {
                "Server catalog is too large"
            }
            val persisted = json.decodeFromString<PersistedServerCatalog>(stored)
            val entries = persisted.entries
                .take(MAX_SERVER_CATALOG_ENTRIES * 2)
                .map { entry ->
                    ServerCatalogEntry(
                        origin = ServerOrigin.parse(entry.origin),
                        label = entry.label.take(MAX_SERVER_LABEL_CHARS),
                        lastUsedEpochSeconds = entry.lastUsedEpochSeconds,
                        connectionMode = ServerConnectionMode.valueOf(entry.connectionMode),
                    )
                }
            val active = preferences[ActiveOriginKey]
                ?.let(ServerOrigin::parse)
            return ServerCatalog.normalized(entries, active)
        }

        val legacy = preferences[ServerOriginKey] ?: return ServerCatalog.empty()
        return ServerCatalog.single(ServerCatalogEntry(ServerOrigin.parse(legacy)))
    }

    private fun writeCatalog(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        catalog: ServerCatalog,
    ) {
        val persisted = PersistedServerCatalog(
            entries = catalog.entries.map { entry ->
                PersistedServerCatalogEntry(
                    origin = entry.origin.value,
                    label = entry.label,
                    lastUsedEpochSeconds = entry.lastUsedEpochSeconds,
                    connectionMode = entry.connectionMode.name,
                )
            },
        )
        preferences[ServerCatalogKey] = json.encodeToString(persisted)
        catalog.activeOrigin?.let { active ->
            preferences[ActiveOriginKey] = active.value
        } ?: preferences.remove(ActiveOriginKey)
        preferences.remove(ServerOriginKey)
    }

    private suspend fun migrateLegacyOriginIfNeeded() {
        dataStore.edit { preferences ->
            if (preferences[ServerCatalogKey] != null || preferences[ActiveOriginKey] != null) return@edit
            val legacy = preferences[ServerOriginKey] ?: return@edit
            val origin = runCatching { ServerOrigin.parse(legacy) }.getOrNull() ?: return@edit
            writeCatalog(preferences, ServerCatalog.single(ServerCatalogEntry(origin)))
        }
    }
}
