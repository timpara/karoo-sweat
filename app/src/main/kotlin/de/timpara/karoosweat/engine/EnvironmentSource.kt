package de.timpara.karoosweat.engine

import de.timpara.karoosweat.model.Environment
import de.timpara.karoosweat.model.EnvironmentResolver
import de.timpara.karoosweat.model.GeoDistance
import de.timpara.karoosweat.model.SweatSettings
import de.timpara.karoosweat.model.WeatherSnapshot
import de.timpara.karoosweat.util.SweatStore
import de.timpara.karoosweat.util.field
import de.timpara.karoosweat.util.streamDataFlow
import de.timpara.karoosweat.weather.OpenMeteoClient
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

/**
 * Feeds ambient conditions to the model from the best source available.
 *
 * This class is only plumbing: it owns the coroutines, the network calls and the
 * cache. The actual decision of which reading to trust lives in
 * [EnvironmentResolver], where it can be tested.
 */
class EnvironmentSource(
    private val karooSystem: KarooSystemService,
    private val store: SweatStore,
    private val scope: CoroutineScope,
) {
    private val client = OpenMeteoClient(karooSystem)

    private val _environment = MutableStateFlow(
        EnvironmentResolver.resolve(null, null, SweatSettings(), 0L),
    )
    val environment: StateFlow<Environment> = _environment.asStateFlow()

    private var sensorTempC: Double? = null
    private var settings: SweatSettings = SweatSettings()
    private var lastFetchLat: Double? = null
    private var lastFetchLon: Double? = null

    fun start() {
        scope.launch {
            store.settingsFlow().collect {
                settings = it
                recombine()
            }
        }
        scope.launch { observeSensorTemperature() }
        scope.launch { store.weatherFlow().collect { recombine(it) } }
        scope.launch { refreshLoop() }
    }

    /**
     * The device temperature stream is best-effort. It frequently reports
     * `NotAvailable`, and when it does report it can read several degrees high in
     * direct sun or from the unit's own waste heat, which is why it is not the
     * default source.
     */
    private suspend fun observeSensorTemperature() {
        karooSystem.streamDataFlow(DataType.Type.TEMPERATURE)
            .map { it.field(DataType.Field.TEMPERATURE) }
            .distinctUntilChanged()
            .collect {
                sensorTempC = it
                recombine()
            }
    }

    private suspend fun recombine(explicit: WeatherSnapshot? = null) {
        val weather = explicit ?: store.weatherFlow().first()
        _environment.value = EnvironmentResolver.resolve(
            weather = weather,
            sensorTempC = sensorTempC,
            settings = settings,
            nowMs = System.currentTimeMillis(),
        )
    }

    /**
     * Refetch hourly, or sooner if the rider has moved far enough that local
     * conditions plausibly differ. Failures are silent and leave the cache in place:
     * a stale reading beats no reading.
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
        return GeoDistance.approxKm(lat, lon, position.first, position.second) > REFETCH_DISTANCE_KM
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

    private companion object {
        const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
        const val REFETCH_DISTANCE_KM = 5.0
        const val MIN_ACCURACY_M = 500.0
        const val LOCATION_TIMEOUT_MS = 15_000L
    }
}
