package de.timpara.karoosweat.model

import kotlinx.serialization.Serializable

/**
 * How a sweat loss estimate is converted into a drinking target.
 */
enum class HydrationTargetMode {
    /**
     * Replace a fixed fraction of everything lost, from the first millilitre.
     *
     * Simple and familiar, and what most hydration guidance describes, but it asks
     * the rider to drink during the opening hour when they are nowhere near a
     * meaningful deficit.
     */
    PROPORTIONAL,

    /**
     * Drink nothing until the projected deficit approaches the threshold at which
     * dehydration actually costs performance, then track sweat rate 1:1 to hold the
     * rider there.
     *
     * This is closer to what the evidence supports. Losing up to about 2% of body
     * mass has no reliable performance cost, and scale-measured loss overstates the
     * true body-water deficit anyway: substrate oxidation removes mass that was
     * never water, oxidation produces metabolic water, and glycogen is stored with
     * roughly three times its own mass in water that is liberated as it is burned.
     * Drinking more than you sweat is also the direct cause of exercise-associated
     * hyponatremia, which is far more dangerous than a 2% deficit.
     */
    DEFICIT,
}

/**
 * User preferences governing how an estimated sweat loss is turned into a drinking
 * recommendation.
 */
@Serializable
data class HydrationPolicy(
    /** Which targeting strategy [SweatState.recommendedIntakeMl] should apply. */
    val targetMode: HydrationTargetMode = HydrationTargetMode.DEFICIT,
    /**
     * Fraction of sweat loss to replace during the ride, 0..1.
     *
     * Only used by [HydrationTargetMode.PROPORTIONAL]. Replacing 100% is rarely
     * necessary or comfortable, so a target around 0.8 keeps the rider well inside
     * the 2% band without over-drinking.
     */
    val replacementFraction: Double = 0.8,
    /**
     * Body mass loss fraction the rider is content to carry, e.g. 0.015 for 1.5%.
     *
     * Only used by [HydrationTargetMode.DEFICIT]. Deliberately set below
     * [warnBodyMassLossFraction] so that following the recommendation keeps the
     * field out of its warning state rather than parking the rider on the boundary.
     */
    val allowableDeficitFraction: Double = 0.015,
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

    /**
     * Recommended cumulative intake in ml, honouring the target mode and the gut cap.
     *
     * The gut cap is applied last and to the cumulative figure, so a rider who has
     * been told to drink nothing for the first hour still accrues absorption
     * capacity during it. Without that, deficit mode could never catch up on a hot
     * ride: the cap would bite exactly when the target starts rising.
     */
    fun recommendedIntakeMl(rider: RiderProfile, policy: HydrationPolicy): Double {
        val byMode = when (policy.targetMode) {
            HydrationTargetMode.PROPORTIONAL ->
                cumulativeSweatMl * policy.replacementFraction

            HydrationTargetMode.DEFICIT -> {
                val allowanceMl =
                    policy.allowableDeficitFraction * rider.massKg * 1000.0
                cumulativeSweatMl - allowanceMl
            }
        }
        val byGutCapacity = policy.gutAbsorptionCapMlPerHour * ridingHours
        return minOf(byMode, byGutCapacity).coerceAtLeast(0.0)
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
        // A single sample carries no interval, so no rate can be derived from it.
        if (window.size < 2) return 0.0
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
