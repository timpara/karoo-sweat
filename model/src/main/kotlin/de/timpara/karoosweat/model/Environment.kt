package de.timpara.karoosweat.model

import kotlinx.serialization.Serializable

/** Cached ambient conditions, with enough metadata to judge staleness. */
@Serializable
data class WeatherSnapshot(
    val temperatureC: Double,
    val relativeHumidityPct: Double,
    val latitude: Double,
    val longitude: Double,
    val fetchedAtEpochMs: Long,
) {
    fun ageMs(nowMs: Long): Long = nowMs - fetchedAtEpochMs

    /**
     * Weather this old is still far better than a guess, but a rider who has
     * descended 1500 m or ridden from morning into evening should not trust it.
     */
    fun isStale(nowMs: Long): Boolean = ageMs(nowMs) > STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 3 * 60 * 60 * 1000L
    }
}

/**
 * Ambient conditions actually used by the model, plus provenance.
 *
 * Provenance is carried through deliberately so the graphical field can tell the
 * rider when it is working from a guessed humidity rather than a real observation.
 * An estimate whose caveats are invisible is worse than one that admits them.
 */
data class Environment(
    val temperatureC: Double,
    val relativeHumidityPct: Double,
    val temperatureFromSensor: Boolean,
    val humidityIsFallback: Boolean,
    val weatherAgeMs: Long?,
)

/**
 * Decides which ambient readings to use, given everything currently known.
 *
 * Pure and total: no I/O, no clock of its own, no Android types. The priority rules
 * are fiddly and easy to get subtly wrong, which is exactly why they belong here
 * rather than tangled into a coroutine that can only be exercised on a device.
 */
object EnvironmentResolver {

    /**
     * @param weather most recent cached observation, or null if none was ever fetched
     * @param sensorTempC latest reading from the device temperature stream, if any
     * @param settings user preferences, including the manual fallbacks
     * @param nowMs current time, used only to report the age of the observation
     */
    fun resolve(
        weather: WeatherSnapshot?,
        sensorTempC: Double?,
        settings: SweatSettings,
        nowMs: Long,
    ): Environment {
        val preferSensor = settings.temperatureSource == TemperatureSource.DEVICE_SENSOR

        // Temperature: honour the preference when that source is actually reporting,
        // otherwise take whatever is available, and only then fall back to the
        // configured constant.
        val usedSensor = when {
            sensorTempC == null -> false
            preferSensor -> true
            else -> weather == null
        }
        val temperature = when {
            usedSensor -> sensorTempC!!
            weather != null -> weather.temperatureC
            else -> settings.fallbackTempC
        }

        return Environment(
            temperatureC = temperature,
            // No Karoo data type reports humidity, so there is no sensor branch here.
            relativeHumidityPct = weather?.relativeHumidityPct ?: settings.fallbackHumidityPct,
            temperatureFromSensor = usedSensor,
            humidityIsFallback = weather == null,
            weatherAgeMs = weather?.ageMs(nowMs),
        )
    }
}
