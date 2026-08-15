package de.timpara.karoosweat.engine

import de.timpara.karoosweat.model.Conditions
import de.timpara.karoosweat.model.HeartRateEstimator
import de.timpara.karoosweat.model.HeatBalance
import de.timpara.karoosweat.model.HeatBalanceResult
import de.timpara.karoosweat.model.HydrationStatus
import de.timpara.karoosweat.model.RiderProfile
import de.timpara.karoosweat.model.SweatAccumulator
import de.timpara.karoosweat.model.SweatState
import de.timpara.karoosweat.util.SweatSettings
import de.timpara.karoosweat.util.SweatStore
import de.timpara.karoosweat.util.consumerFlow
import de.timpara.karoosweat.util.streamDataFlow
import de.timpara.karoosweat.util.value
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything a data field needs in one immutable snapshot. */
data class SweatSnapshot(
    val state: SweatState,
    val rider: RiderProfile,
    val environment: Environment,
    val status: HydrationStatus,
    val recommendedIntakeMl: Double,
    val bodyMassLossFraction: Double,
)

/**
 * Drives the sweat model from live Karoo streams.
 *
 * Responsibilities, in order of how likely each is to go wrong in the field:
 *  - ride lifecycle (start, pause, resume, finish, and starting a second ride)
 *  - persistence, so a killed service does not zero the rider's running total
 *  - falling back to heart rate when there is no power meter
 *  - the actual arithmetic, which is delegated entirely to the `:model` module
 */
class SweatEngine(
    private val karooSystem: KarooSystemService,
    private val store: SweatStore,
    private val environmentSource: EnvironmentSource,
    private val scope: CoroutineScope,
) {
    private val accumulator = SweatAccumulator()

    private val _snapshot = MutableStateFlow<SweatSnapshot?>(null)
    val snapshot: StateFlow<SweatSnapshot?> = _snapshot.asStateFlow()

    private var power: Double? = null
    private var speed: Double? = null
    private var heartRate: Double? = null
    private var userProfile: UserProfile? = null
    private var rideState: RideState = RideState.Idle
    private var wasRecording = false
    private var lastPersistMs = 0L
    private var lastAlertedStatus = HydrationStatus.OK

    /**
     * Settings are cached rather than read on demand. The tick loop runs at 1 Hz and
     * touches settings twice per pass, so reading through to DataStore each time
     * would mean two disk reads per second for the entire ride.
     */
    private var settings: SweatSettings = SweatSettings()

    fun start() {
        scope.launch { store.settingsFlow().collect { settings = it } }
        scope.launch { restore() }
        scope.launch { collectStream(DataType.Type.POWER) { power = it } }
        scope.launch { collectStream(DataType.Type.SPEED) { speed = it } }
        scope.launch { collectStream(DataType.Type.HEART_RATE) { heartRate = it } }
        scope.launch { karooSystem.consumerFlow<UserProfile>().collect { userProfile = it } }
        scope.launch { observeRideState() }
        scope.launch { tickLoop() }
    }

    private suspend fun restore() {
        store.loadRideState()?.let { accumulator.restore(it) }
        publish()
    }

    private suspend fun collectStream(dataTypeId: String, assign: (Double?) -> Unit) {
        karooSystem.streamDataFlow(dataTypeId).collect { assign(it.value()) }
    }

    /**
     * Ride lifecycle.
     *
     * The transition that matters is recording -> idle -> recording, which means a
     * new ride and must reset the totals. Paused is emphatically *not* a reset: a
     * rider stopping at a cafe for an hour still owes their body the fluid they lost
     * before they stopped.
     */
    private suspend fun observeRideState() {
        karooSystem.consumerFlow<RideState>().collect { state ->
            rideState = state
            when (state) {
                is RideState.Recording -> {
                    if (!wasRecording && accumulator.state.ridingTimeMs == 0L) {
                        accumulator.reset()
                    }
                    wasRecording = true
                }

                is RideState.Idle -> {
                    if (wasRecording) {
                        persist(force = true)
                        accumulator.reset()
                        store.clearRideState()
                        lastAlertedStatus = HydrationStatus.OK
                    }
                    wasRecording = false
                }

                is RideState.Paused -> Unit
            }
            publish()
        }
    }

    private suspend fun tickLoop() {
        while (scope.isActive) {
            tick()
            delay(TICK_INTERVAL_MS)
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val recording = rideState is RideState.Recording
        val result = evaluate()
        accumulator.tick(now, result, recording)

        if (recording && now - lastPersistMs > PERSIST_INTERVAL_MS) {
            persist(force = false)
            lastPersistMs = now
        }
        publish()
    }

    /**
     * Evaluate the heat balance for this instant, or return null if we lack the
     * inputs to do so honestly. Returning null lets time pass without inventing
     * sweat, which is preferable to silently substituting a guess.
     */
    private fun evaluate(): HeatBalanceResult? {
        val profile = userProfile
        val rider = settings.toRiderProfile(profile?.weight?.toDouble())

        val measuredPower = power
        val effectivePower: Double? = if (measuredPower != null) {
            accumulator.setHeartRateFallback(false)
            measuredPower
        } else {
            val hr = heartRate
            val estimated = if (hr != null && profile != null) {
                HeartRateEstimator.estimatePowerWatts(
                    heartRateBpm = hr,
                    restingHr = profile.restingHr,
                    maxHr = profile.maxHr,
                    ftpWatts = profile.ftp,
                )
            } else {
                null
            }
            accumulator.setHeartRateFallback(estimated != null)
            estimated
        }

        if (effectivePower == null) return null

        val env = environmentSource.environment.value
        return HeatBalance.evaluate(
            Conditions(
                powerWatts = effectivePower,
                // A missing speed stream must not be read as "stationary". Zero
                // airspeed means almost no convective cooling, which would inflate
                // every estimate substantially. Assume a nominal riding speed instead.
                airSpeedMs = speed ?: NOMINAL_AIR_SPEED_MS,
                airTempC = env.temperatureC,
                relativeHumidityPct = env.relativeHumidityPct,
            ),
            rider,
        )
    }

    private fun publish() {
        val rider = settings.toRiderProfile(userProfile?.weight?.toDouble())
        val policy = settings.toPolicy()
        val state = accumulator.state
        val status = state.status(rider, policy)

        _snapshot.value = SweatSnapshot(
            state = state,
            rider = rider,
            environment = environmentSource.environment.value,
            status = status,
            recommendedIntakeMl = state.recommendedIntakeMl(policy),
            bodyMassLossFraction = state.bodyMassLossFraction(rider),
        )

        if (settings.alertsEnabled) maybeAlert(status)
    }

    /**
     * Alert once per threshold crossing, never repeatedly. An in-ride alert that
     * fires every second is worse than no alert at all.
     */
    private fun maybeAlert(status: HydrationStatus) {
        if (status == lastAlertedStatus) return
        if (status.ordinal <= lastAlertedStatus.ordinal) {
            lastAlertedStatus = status
            return
        }
        lastAlertedStatus = status
        HydrationAlerts.dispatch(karooSystem, status)
    }

    suspend fun persist(force: Boolean) {
        if (!force && accumulator.state.ridingTimeMs == 0L) return
        store.saveRideState(accumulator.state)
    }

    private companion object {
        const val TICK_INTERVAL_MS = 1000L
        const val PERSIST_INTERVAL_MS = 30_000L

        /** Roughly 22 km/h; used only when the speed stream is unavailable. */
        const val NOMINAL_AIR_SPEED_MS = 6.0
    }
}
