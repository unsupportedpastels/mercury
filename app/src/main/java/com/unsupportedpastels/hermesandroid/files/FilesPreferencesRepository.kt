package com.unsupportedpastels.hermesandroid.files

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val FilesPreferencesDataStoreName = "files_preferences"
private val PreviewFlagsKey = stringPreferencesKey("in_app_file_preview_by_origin")
private val Context.filesPreferencesDataStore by preferencesDataStore(
    name = FilesPreferencesDataStoreName,
)

interface FilesPreferencesRepository {
    fun preferences(origin: ServerOrigin): Flow<FilesPreferences>
    suspend fun setInAppFilePreviewEnabled(origin: ServerOrigin, enabled: Boolean)
}

class DataStoreFilesPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : FilesPreferencesRepository {
    constructor(context: Context) : this(context.applicationContext.filesPreferencesDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    override fun preferences(origin: ServerOrigin): Flow<FilesPreferences> =
        dataStore.data.map { prefs ->
            FilesPreferences(inAppFilePreviewEnabled = readFlag(prefs, origin))
        }

    override suspend fun setInAppFilePreviewEnabled(origin: ServerOrigin, enabled: Boolean) {
        dataStore.edit { prefs -> writeFlag(prefs, origin, enabled) }
    }

    private fun readFlag(prefs: Preferences, origin: ServerOrigin): Boolean {
        val stored = prefs[PreviewFlagsKey] ?: return false
        val map = decodeMap(stored)
        return map[origin.value] ?: false
    }

    private fun writeFlag(prefs: MutablePreferences, origin: ServerOrigin, enabled: Boolean) {
        val current = decodeMap(prefs[PreviewFlagsKey])
        val updated = current.toMutableMap()
        updated[origin.value] = enabled
        prefs[PreviewFlagsKey] = encodeMap(updated)
    }

    private fun decodeMap(raw: String?): Map<String, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        return element.mapNotNull { (key, value) ->
            val flag = value.jsonPrimitive.booleanOrNull
                ?: value.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull()
            if (key.isBlank() || flag == null) null else key to flag
        }.toMap()
    }

    private fun encodeMap(map: Map<String, Boolean>): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                map.forEach { (origin, enabled) ->
                    put(origin, JsonPrimitive(enabled))
                }
            },
        )
}
