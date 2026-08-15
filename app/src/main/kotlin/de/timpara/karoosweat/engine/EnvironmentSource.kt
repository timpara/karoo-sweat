package de.timpara.karoosweat.engine

import de.timpara.karoosweat.util.SweatSettings
import de.timpara.karoosweat.util.SweatStore
import de.timpara.karoosweat.util.TemperatureSource
import de.timpara.karoosweat.util.field
import de.timpara.karoosweat.util.streamDataFlow
import de.timpara.karoosweat.weather.OpenMeteoClient
import de.timpara.karoosweat.weather.WeatherSnapshot
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Ambient conditions actually used by the model, plus where they came from. */
data class Environment(
    val temperatureC: Double,
    val relativeHumidityPct: Double,
    val temperatureFromSensor: Boolean,
    val humidityIsFallback: Boolean,
    val weatherAgeMs: Long?,
)

/**
 * Resolves ambient temperature and humidity from the best source available.
 *
 * Priority is deliberate:
 *  1. Open-Meteo, cached in DataStore so it survives going offline. This is the only
 *     possible source of humidity, because no Karoo data type reports it.
 *  2. The on-device [DataType.Type.TEMPERATURE] stream, for temperature only. It is
 *     optional (it may never report) and is biased upward by device self-heating and
 *     direct sun, which is why it is not the default.
 *  3. User-configured fallbacks, so the model still produces a defensible number on
 *     a rider who has never had connectivity.
 */
class EnvironmentSource(
    private val karooSystem: KarooSystemService,
    private val store: SweatStore,
    private val scope: CoroutineScope,
) {
    private val client = OpenMeteoClient(karooSystem)

    private val _environment = MutableStateFlow(
        Environment(
            temperatureC = 20.0,
            relativeHumidityPct = 50.0,
            temperatureFromSensor = false,
            humidityIsFallback = true,
            weatherAgeMs = null,
        ),
    )
    val environment: StateFlow<Environment> = _environment.asStateFlow()

    private var sensorTempC: Double? = null
    private var settings: SweatSettings = SweatSettings()
    private var lastFetchLat: Double? = null
    private var lastFetchLon: Double? = null

    fun start() {
        scope.launch { store.settingsFlow().collect { settings = it; recombine() } }
        scope.launch { observeSensorTemperature() }
        scope.launch { refreshLoop() }
        scope.launch { recombineOnChange() }
    }

    /**
     * The device temperature stream is best-effort. It frequently reports
     * [io.hammerhead.karooext.models.StreamState.NotAvailable], and when it does
     * report it can read several degrees high in the sun, so it is only consulted
     * when the rider has explicitly preferred it.
     */
    private suspend fun observeSensorTemperature() {
        karooSystem.streamDataFlow(DataType.Type.TEMPERATURE)
            .map { it.field(DataType.Field.TEMPERATURE) }
            .distinctUntilChanged()
            .collect { temp ->
                sensorTempC = temp
                recombine()
            }
    }

    private suspend fun recombineOnChange() {
        store.weatherFlow().collect { recombine(it) }
    }

    private suspend fun recombine(explicit: WeatherSnapshot? = null) {
        val weather = explicit ?: store.weatherFlow().first()
        val now = System.currentTimeMillis()

        val sensor = sensorTempC
        val preferSensor = settings.temperatureSource == TemperatureSource.DEVICE_SENSOR

        val temperature = when {
            preferSensor && sensor != null -> sensor
            weather != null -> weather.temperatureC
            sensor != null -> sensor
            else -> settings.fallbackTempC
        }
        val usedSensor = temperature == sensor && (preferSensor || weather == null)

        _environment.value = Environment(
            temperatureC = temperature,
            relativeHumidityPct = weather?.relativeHumidityPct ?: settings.fallbackHumidityPct,
            temperatureFromSensor = usedSensor,
            humidityIsFallback = weather == null,
            weatherAgeMs = weather?.ageMs(now),
        )
    }

    /**
     * Refetch hourly, or sooner if the rider has moved far enough that the local
     * conditions plausibly differ. Failures are silent and simply leave the cache in
     * place; a stale reading beats no reading.
     */
    private suspend fun refreshLoop() {
        while (scope.isActive) {
            val position = currentPosition()
            if (position != null && shouldRefetch(position)) {
                client.fetch(position.first, position.second)?.let { snapshot ->
                    lastFetchLat = position.first
                    lastFetchLon = position.second
                    store.saveWeather(snapshot)
                    recombine(snapshot)
                }
            }
            delay(REFRESH_INTERVAL_MS)
        }
    }

    private suspend fun shouldRefetch(position: Pair<Double, Double>): Boolean {
        val cached = store.weatherFlow().first() ?: return true
        if (cached.isStale(System.currentTimeMillis())) return true
        val lat = lastFetchLat ?: return true
        val lon = lastFetchLon ?: return true
        return approxDistanceKm(lat, lon, position.first, position.second) > REFETCH_DISTANCE_KM
    }

    private suspend fun currentPosition(): Pair<Double, Double>? {
        // A callbackFlow that never emits would block this loop forever, silently
        // ending all weather updates for the rest of the ride. Bound the wait.
        val state = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            karooSystem.streamDataFlow(DataType.Type.LOCATION)
                .firstOrNull { it.field(DataType.Field.LOC_LATITUDE) != null }
        } ?: return null

        val accuracy = state.field(DataType.Field.LOC_ACCURACY)
        // A fix this poor is not worth a weather lookup.
        if (accuracy != null && accuracy >= MIN_ACCURACY_M) return null
        val lat = state.field(DataType.Field.LOC_LATITUDE) ?: return null
        val lon = state.field(DataType.Field.LOC_LONGITUDE) ?: return null
        return lat to lon
    }

    /** Equirectangular approximation; ample for a "have I moved a few km" test. */
    private fun approxDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val meanLatRad = Math.toRadians((lat1 + lat2) / 2)
        val dx = (lon2 - lon1) * 111.32 * kotlin.math.cos(meanLatRad)
        val dy = (lat2 - lat1) * 110.57
        return kotlin.math.sqrt(dx * dx + dy * dy).let { abs(it) }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
        const val REFETCH_DISTANCE_KM = 5.0
        const val MIN_ACCURACY_M = 500.0
        const val LOCATION_TIMEOUT_MS = 15_000L
    }
}
