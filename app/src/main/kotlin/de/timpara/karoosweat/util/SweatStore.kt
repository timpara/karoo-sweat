package de.timpara.karoosweat.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.timpara.karoosweat.model.SweatSettings
import de.timpara.karoosweat.model.SweatState
import de.timpara.karoosweat.model.WeatherSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "karoo_sweat")

/**
 * Persistence layer.
 *
 * `ignoreUnknownKeys` and default values on every field mean an older build can read
 * a newer schema and vice versa, so a settings change never bricks a rider's install.
 */
class SweatStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun settingsFlow(): Flow<SweatSettings> = context.dataStore.data
        .map { prefs -> decode(prefs[KEY_SETTINGS], SweatSettings()) }
        .distinctUntilChanged()

    suspend fun settings(): SweatSettings = settingsFlow().first()

    suspend fun saveSettings(settings: SweatSettings) {
        context.dataStore.edit { it[KEY_SETTINGS] = json.encodeToString(settings) }
    }

    suspend fun loadRideState(): SweatState? =
        context.dataStore.data.first()[KEY_RIDE_STATE]?.let { decodeOrNull(it) }

    suspend fun saveRideState(state: SweatState) {
        context.dataStore.edit { it[KEY_RIDE_STATE] = json.encodeToString(state) }
    }

    suspend fun clearRideState() {
        context.dataStore.edit { it.remove(KEY_RIDE_STATE) }
    }

    fun weatherFlow(): Flow<WeatherSnapshot?> = context.dataStore.data
        .map { prefs -> prefs[KEY_WEATHER]?.let { decodeOrNull<WeatherSnapshot>(it) } }
        .distinctUntilChanged()

    suspend fun saveWeather(snapshot: WeatherSnapshot) {
        context.dataStore.edit { it[KEY_WEATHER] = json.encodeToString(snapshot) }
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T =
        raw?.let { decodeOrNull<T>(it) } ?: fallback

    private inline fun <reified T> decodeOrNull(raw: String): T? = try {
        json.decodeFromString<T>(raw)
    } catch (e: Exception) {
        // Corrupt or incompatible payload: fall back to defaults rather than crash
        // the extension service, which would take the data fields down mid-ride.
        null
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("settings")
        val KEY_RIDE_STATE = stringPreferencesKey("ride_state")
        val KEY_WEATHER = stringPreferencesKey("weather")
    }
}
