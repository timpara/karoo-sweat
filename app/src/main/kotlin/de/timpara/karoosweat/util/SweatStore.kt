package de.timpara.karoosweat.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.timpara.karoosweat.model.HydrationPolicy
import de.timpara.karoosweat.model.RiderProfile
import de.timpara.karoosweat.model.SweatState
import de.timpara.karoosweat.weather.WeatherSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Which source to trust for ambient temperature.
 *
 * Humidity is not negotiable: no Karoo data type reports it, so it always comes from
 * the weather API (or the manual fallback).
 */
enum class TemperatureSource {
    /** Prefer the weather API, which is unaffected by device self-heating. */
    WEATHER_API,

    /** Prefer the device stream when it reports, falling back to the API. */
    DEVICE_SENSOR,
}

/** All user-configurable settings, persisted as a single JSON blob. */
@Serializable
data class SweatSettings(
    val heightCm: Double = 178.0,
    val grossEfficiency: Double = 0.22,
    val clothingCloValue: Double = 0.35,
    val sweatMultiplier: Double = 1.0,
    val replacementFraction: Double = 0.8,
    val gutAbsorptionCapMlPerHour: Double = 1000.0,
    val temperatureSource: TemperatureSource = TemperatureSource.WEATHER_API,
    /** Used only when no weather data has ever been fetched and no sensor reports. */
    val fallbackTempC: Double = 20.0,
    /** Used whenever weather data is unavailable, since humidity has no sensor. */
    val fallbackHumidityPct: Double = 50.0,
    /** Manual override for rider mass; when null the Karoo user profile is used. */
    val overrideMassKg: Double? = null,
    val fitExportEnabled: Boolean = true,
    /** Alert the rider when projected body mass loss crosses the warning threshold. */
    val alertsEnabled: Boolean = true,
) {
    fun toPolicy() = HydrationPolicy(
        replacementFraction = replacementFraction,
        gutAbsorptionCapMlPerHour = gutAbsorptionCapMlPerHour,
    )

    /** Combine settings with the mass reported by the Karoo user profile. */
    fun toRiderProfile(profileMassKg: Double?) = RiderProfile(
        massKg = overrideMassKg ?: profileMassKg ?: 75.0,
        heightCm = heightCm,
        grossEfficiency = grossEfficiency,
        clothingCloValue = clothingCloValue,
        sweatMultiplier = sweatMultiplier,
    )
}

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
