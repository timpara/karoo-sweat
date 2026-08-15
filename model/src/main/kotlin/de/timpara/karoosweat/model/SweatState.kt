package de.timpara.karoosweat.model

import kotlinx.serialization.Serializable

/**
 * User preferences governing how an estimated sweat loss is turned into a drinking
 * recommendation.
 */
@Serializable
data class HydrationPolicy(
    /**
     * Fraction of sweat loss to replace during the ride, 0..1.
     *
     * Replacing 100% is rarely necessary or comfortable. Losing up to about 2% of
     * body mass has no meaningful performance cost for most riders, so a target
     * around 0.8 keeps the rider well inside that band without over-drinking.
     */
    val replacementFraction: Double = 0.8,
    /**
     * Ceiling on recommended drinking rate in ml/h, representing gastric emptying
     * and intestinal absorption limits. Recommending more than the gut can absorb
     * is not merely useless, it causes GI distress.
     */
    val gutAbsorptionCapMlPerHour: Double = 1000.0,
    /**
     * Body mass loss fraction at which the display should warn, e.g. 0.02 for 2%.
     */
    val warnBodyMassLossFraction: Double = 0.02,
    /**
     * Body mass loss fraction at which the display should show a critical state.
     */
    val criticalBodyMassLossFraction: Double = 0.03,
)

/** Severity band for the graphical field, derived from projected body mass loss. */
enum class HydrationStatus { OK, WARN, CRITICAL }

/**
 * Serialisable accumulated state for one ride. Persisted so that an app restart or
 * a crash mid-ride does not reset the rider's running total.
 */
@Serializable
data class SweatState(
    /** Cumulative estimated sweat loss in ml since the ride started. */
    val cumulativeSweatMl: Double = 0.0,
    /** Milliseconds spent actively recording (excludes paused time). */
    val ridingTimeMs: Long = 0L,
    /** Most recent instantaneous sweat rate in ml/h. */
    val currentRateMlPerHour: Double = 0.0,
    /** Most recent skin wettedness, 0..1. */
    val wettedness: Double = 0.0,
    /** Whether the most recent evaluation indicated uncompensable heat stress. */
    val uncompensable: Boolean = false,
    /** Whether the current estimate is running on the low-confidence HR fallback. */
    val estimatedFromHeartRate: Boolean = false,
    /** Epoch millis of the last update, used to detect stale state on restore. */
    val lastUpdatedEpochMs: Long = 0L,
) {
    val ridingHours: Double get() = ridingTimeMs / 3_600_000.0

    /** Recommended cumulative intake in ml, honouring both replacement and gut cap. */
    fun recommendedIntakeMl(policy: HydrationPolicy): Double {
        val byReplacement = cumulativeSweatMl * policy.replacementFraction
        val byGutCapacity = policy.gutAbsorptionCapMlPerHour * ridingHours
        return minOf(byReplacement, byGutCapacity).coerceAtLeast(0.0)
    }

    /**
     * Projected body mass loss as a fraction, assuming the rider has drunk nothing.
     * This is the physiologically meaningful number: 2% is the conventional
     * performance-decrement threshold.
     */
    fun bodyMassLossFraction(rider: RiderProfile): Double =
        (cumulativeSweatMl / 1000.0) / rider.massKg

    fun status(rider: RiderProfile, policy: HydrationPolicy): HydrationStatus {
        val loss = bodyMassLossFraction(rider)
        return when {
            loss >= policy.criticalBodyMassLossFraction -> HydrationStatus.CRITICAL
            loss >= policy.warnBodyMassLossFraction -> HydrationStatus.WARN
            else -> HydrationStatus.OK
        }
    }
}

/**
 * Integrates the instantaneous sweat rate over time.
 *
 * This is deliberately a pure state machine with no coroutines, no clock of its own
 * and no I/O, so that ride lifecycle behaviour (pause, resume, restart, restore from
 * disk) can be tested exhaustively and deterministically.
 */
class SweatAccumulator(initial: SweatState = SweatState()) {

    var state: SweatState = initial
        private set

    private var lastTickMs: Long? = null

    /**
     * Rolling window of (timestampMs, mlAccumulated) used to report a smoothed
     * current rate. Held in memory only; it is cheap to rebuild and not worth
     * persisting.
     */
    private val window = ArrayDeque<Pair<Long, Double>>()

    /**
     * Advance the accumulator.
     *
     * @param nowMs monotonic-ish timestamp in epoch millis
     * @param result the heat balance evaluation for this instant, or null if inputs
     *   were unavailable (in which case time passes but no sweat is accrued)
     * @param recording false while the ride is paused or stopped; time and sweat are
     *   both frozen, and the tick baseline is re-anchored so that resuming does not
     *   retroactively accrue the paused interval
     */
    fun tick(nowMs: Long, result: HeatBalanceResult?, recording: Boolean) {
        val previous = lastTickMs
        lastTickMs = nowMs

        if (!recording || previous == null) return

        // Guard against clock jumps and against long gaps caused by process death.
        val deltaMs = (nowMs - previous).coerceIn(0L, MAX_TICK_GAP_MS)
        if (deltaMs <= 0L) return

        val rate = result?.sweatRateMlPerHour ?: 0.0
        val added = rate * (deltaMs / 3_600_000.0)

        window.addLast(nowMs to added)
        val cutoff = nowMs - ROLLING_WINDOW_MS
        while (window.isNotEmpty() && window.first().first < cutoff) window.removeFirst()

        state = state.copy(
            cumulativeSweatMl = state.cumulativeSweatMl + added,
            ridingTimeMs = state.ridingTimeMs + deltaMs,
            currentRateMlPerHour = smoothedRate(),
            wettedness = result?.skinWettedness ?: state.wettedness,
            uncompensable = result?.uncompensable ?: false,
            lastUpdatedEpochMs = nowMs,
        )
    }

    /** Mark the estimate as coming from the heart-rate fallback rather than power. */
    fun setHeartRateFallback(active: Boolean) {
        if (state.estimatedFromHeartRate != active) {
            state = state.copy(estimatedFromHeartRate = active)
        }
    }

    /** Reset for a new ride. Called on the recording -> idle -> recording transition. */
    fun reset() {
        state = SweatState()
        window.clear()
        lastTickMs = null
    }

    /** Restore persisted state, e.g. after the service was killed mid-ride. */
    fun restore(restored: SweatState) {
        state = restored
        window.clear()
        lastTickMs = null
    }

    private fun smoothedRate(): Double {
        if (window.size < 2) return window.lastOrNull()?.second?.let { 0.0 } ?: 0.0
        val spanMs = window.last().first - window.first().first
        if (spanMs <= 0L) return 0.0
        val total = window.sumOf { it.second }
        return total / (spanMs / 3_600_000.0)
    }

    private companion object {
        /**
         * Any gap longer than this is treated as a lost interval rather than being
         * integrated, since we have no idea what the rider was doing.
         */
        const val MAX_TICK_GAP_MS = 10_000L
        const val ROLLING_WINDOW_MS = 5 * 60 * 1000L
    }
}
