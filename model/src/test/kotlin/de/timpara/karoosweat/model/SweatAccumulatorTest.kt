package de.timpara.karoosweat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ride lifecycle tests for the accumulator. These cover the failure modes that
 * actually bite in the field: pause and resume, process death mid-ride, clock jumps,
 * and starting a second ride without restarting the app.
 */
class SweatAccumulatorTest {

    private val rider = RiderProfile(massKg = 75.0, heightCm = 178.0)
    private val policy = HydrationPolicy()

    /** A fixed 1000 ml/h evaluation, so accrual arithmetic is trivial to verify. */
    private val oneLitrePerHour = HeatBalanceResult(
        sweatRateMlPerHour = 1000.0,
        metabolicPowerW = 1000.0,
        heatProductionW = 780.0,
        dryHeatLossW = 200.0,
        requiredEvaporationW = 580.0,
        maxEvaporationW = 800.0,
        skinWettedness = 0.725,
        uncompensable = false,
        skinTempC = 33.75,
    )

    /** Drives the accumulator at 1 Hz for [seconds] seconds starting at [startMs]. */
    private fun run(
        acc: SweatAccumulator,
        startMs: Long,
        seconds: Int,
        result: HeatBalanceResult? = oneLitrePerHour,
        recording: Boolean = true,
    ): Long {
        var t = startMs
        repeat(seconds + 1) {
            acc.tick(t, result, recording)
            t += 1000L
        }
        return t - 1000L
    }

    @Test
    fun `accrues one litre over one hour at one litre per hour`() {
        val acc = SweatAccumulator()
        run(acc, 0L, 3600)
        assertEquals(1000.0, acc.state.cumulativeSweatMl, 1.0)
        assertEquals(3_600_000L, acc.state.ridingTimeMs)
    }

    @Test
    fun `first tick establishes a baseline without accruing`() {
        val acc = SweatAccumulator()
        acc.tick(0L, oneLitrePerHour, recording = true)
        assertEquals(0.0, acc.state.cumulativeSweatMl, 1e-9)
        assertEquals(0L, acc.state.ridingTimeMs)
    }

    @Test
    fun `paused time accrues neither sweat nor ride time`() {
        val acc = SweatAccumulator()
        val afterRiding = run(acc, 0L, 600)
        val sweatAfterRiding = acc.state.cumulativeSweatMl
        val timeAfterRiding = acc.state.ridingTimeMs

        // Ten minutes stopped at a cafe.
        run(acc, afterRiding + 1000L, 600, recording = false)

        assertEquals(sweatAfterRiding, acc.state.cumulativeSweatMl, 1e-9)
        assertEquals(timeAfterRiding, acc.state.ridingTimeMs)
    }

    @Test
    fun `resuming after a pause does not retroactively accrue the paused interval`() {
        val acc = SweatAccumulator()
        run(acc, 0L, 600)
        val sweatBeforePause = acc.state.cumulativeSweatMl

        // Paused for an hour, then a single resumed tick far in the future.
        acc.tick(4_000_000L, oneLitrePerHour, recording = false)
        acc.tick(4_001_000L, oneLitrePerHour, recording = true)

        // Only the one second between those two ticks may count, and because the
        // paused tick re-anchored the baseline, even that is bounded.
        assertTrue(
            "resume must not backfill the pause",
            acc.state.cumulativeSweatMl - sweatBeforePause < 1.0,
        )
    }

    @Test
    fun `long gaps from process death are clamped rather than integrated`() {
        val acc = SweatAccumulator()
        acc.tick(0L, oneLitrePerHour, recording = true)
        // Service was killed for two hours, then came back.
        acc.tick(7_200_000L, oneLitrePerHour, recording = true)
        assertTrue(
            "a two hour gap must not accrue two litres, got ${acc.state.cumulativeSweatMl}",
            acc.state.cumulativeSweatMl < 5.0,
        )
    }

    @Test
    fun `backwards clock jumps are ignored`() {
        val acc = SweatAccumulator()
        run(acc, 1_000_000L, 60)
        val before = acc.state.cumulativeSweatMl
        acc.tick(500_000L, oneLitrePerHour, recording = true)
        assertEquals(before, acc.state.cumulativeSweatMl, 1e-9)
    }

    @Test
    fun `null evaluation passes time without accruing sweat`() {
        val acc = SweatAccumulator()
        run(acc, 0L, 300, result = null)
        assertEquals(0.0, acc.state.cumulativeSweatMl, 1e-9)
        assertEquals(300_000L, acc.state.ridingTimeMs)
    }

    @Test
    fun `reset clears state for a new ride`() {
        val acc = SweatAccumulator()
        run(acc, 0L, 1800)
        assertTrue(acc.state.cumulativeSweatMl > 0)
        acc.reset()
        assertEquals(SweatState(), acc.state)
        // And the first tick of the new ride must not accrue against the old baseline.
        acc.tick(9_000_000L, oneLitrePerHour, recording = true)
        assertEquals(0.0, acc.state.cumulativeSweatMl, 1e-9)
    }

    @Test
    fun `restore resumes from persisted state without a phantom gap`() {
        val persisted = SweatState(
            cumulativeSweatMl = 850.0,
            ridingTimeMs = 3_000_000L,
            lastUpdatedEpochMs = 1_000_000L,
        )
        val acc = SweatAccumulator()
        acc.restore(persisted)
        assertEquals(850.0, acc.state.cumulativeSweatMl, 1e-9)

        // Process restarted an hour later; that hour must not be integrated.
        acc.tick(4_600_000L, oneLitrePerHour, recording = true)
        assertEquals(850.0, acc.state.cumulativeSweatMl, 1e-9)

        run(acc, 4_601_000L, 600)
        assertEquals(850.0 + 1000.0 * 600 / 3600, acc.state.cumulativeSweatMl, 1.0)
    }

    @Test
    fun `smoothed rate converges to the instantaneous rate`() {
        val acc = SweatAccumulator()
        run(acc, 0L, 600)
        assertEquals(1000.0, acc.state.currentRateMlPerHour, 10.0)
    }

    // --- Hydration policy ---------------------------------------------------------

    @Test
    fun `recommended intake applies the replacement fraction`() {
        val state = SweatState(cumulativeSweatMl = 1000.0, ridingTimeMs = 3_600_000L)
        assertEquals(800.0, state.recommendedIntakeMl(policy), 1e-9)
    }

    @Test
    fun `recommended intake is capped by gut absorption`() {
        // Sweating 2.5 l/h for an hour; the gut can only take 1 l/h, so recommending
        // 2 l would be actively harmful advice.
        val state = SweatState(cumulativeSweatMl = 2500.0, ridingTimeMs = 3_600_000L)
        assertEquals(1000.0, state.recommendedIntakeMl(policy), 1e-9)
    }

    @Test
    fun `gut cap scales with ride duration`() {
        val state = SweatState(cumulativeSweatMl = 5000.0, ridingTimeMs = 7_200_000L)
        assertEquals(2000.0, state.recommendedIntakeMl(policy), 1e-9)
    }

    @Test
    fun `body mass loss fraction is computed against rider mass`() {
        val state = SweatState(cumulativeSweatMl = 1500.0)
        assertEquals(0.02, state.bodyMassLossFraction(rider), 1e-9)
    }

    @Test
    fun `status bands track body mass loss thresholds`() {
        assertEquals(
            HydrationStatus.OK,
            SweatState(cumulativeSweatMl = 700.0).status(rider, policy),
        )
        assertEquals(
            HydrationStatus.WARN,
            SweatState(cumulativeSweatMl = 1600.0).status(rider, policy),
        )
        assertEquals(
            HydrationStatus.CRITICAL,
            SweatState(cumulativeSweatMl = 2400.0).status(rider, policy),
        )
    }

    // --- Heart rate fallback ------------------------------------------------------

    @Test
    fun `heart rate estimator maps threshold HR to FTP`() {
        // 88% of heart rate reserve should return approximately FTP.
        val hr = 50 + 0.88 * (190 - 50)
        val watts = HeartRateEstimator.estimatePowerWatts(hr, restingHr = 50, maxHr = 190, ftpWatts = 260)
        assertEquals(260.0, watts!!, 1.0)
    }

    @Test
    fun `heart rate estimator returns null without a usable profile`() {
        assertEquals(null, HeartRateEstimator.estimatePowerWatts(150.0, 50, 190, ftpWatts = 0))
        assertEquals(null, HeartRateEstimator.estimatePowerWatts(150.0, 190, 190, ftpWatts = 250))
        assertEquals(null, HeartRateEstimator.estimatePowerWatts(0.0, 50, 190, ftpWatts = 250))
    }
}
