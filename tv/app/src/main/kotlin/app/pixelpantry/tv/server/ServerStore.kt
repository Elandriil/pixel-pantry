package app.pixelpantry.tv.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.pixelpantry.tv.api.ApiClient
import app.pixelpantry.tv.api.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists the server address (manual / last known, as `host:port`) and the last fetched settings. */
class ServerStore(private val dataStore: DataStore<Preferences>) {
    val manualAddress: Flow<String?> = dataStore.data.map { it[MANUAL] }
    val lastKnownAddress: Flow<String?> = dataStore.data.map { it[LAST_KNOWN] }
    val cachedSettings: Flow<Settings?> = dataStore.data.map { prefs ->
        prefs[CACHED_SETTINGS]?.let { runCatching { json.decodeFromString(Settings.serializer(), it) }.getOrNull() }
    }

    suspend fun setManualAddress(v: String?) {
        dataStore.edit { if (v == null) it.remove(MANUAL) else it[MANUAL] = v }
    }

    suspend fun setLastKnownAddress(v: String) {
        dataStore.edit { it[LAST_KNOWN] = v }
    }

    suspend fun setCachedSettings(s: Settings) {
        dataStore.edit { it[CACHED_SETTINGS] = json.encodeToString(Settings.serializer(), s) }
    }

    private companion object {
        val MANUAL = stringPreferencesKey("manual_address")
        val LAST_KNOWN = stringPreferencesKey("last_known_address")
        val CACHED_SETTINGS = stringPreferencesKey("cached_settings")
        val json = ApiClient.defaultJson
    }
}
