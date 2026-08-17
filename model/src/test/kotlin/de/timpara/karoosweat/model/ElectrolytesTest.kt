package de.timpara.karoosweat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sodium model tests.
 *
 * The arithmetic here is simple; what needs pinning down is the judgement encoded
 * around it. Sodium advice is the part of a hydration tool most likely to be wrong
 * in the direction that sells sachets, so the tests assert restraint as much as
 * they assert numbers.
 */
class ElectrolytesTest {

    private val typical = ElectrolytePolicy()
    private val salty = ElectrolytePolicy(sodiumClass = SweatSodiumClass.SALTY)

    // --- Concentration ----------------------------------------------------------

    @Test
    fun `at the reference sweat rate concentration is the band baseline`() {
        assertEquals(40.0, sweatSodiumMmolPerLitre(typical, 1000.0), 1e-9)
        assertEquals(60.0, sweatSodiumMmolPerLitre(salty, 1000.0), 1e-9)
    }

    @Test
    fun `concentration rises with sweat rate`() {
        // Duct sodium reabsorption is rate-limited, so faster sweat is saltier.
        val slow = sweatSodiumMmolPerLitre(typical, 500.0)
        val fast = sweatSodiumMmolPerLitre(typical, 2000.0)
        assertTrue(slow < 40.0)
        assertTrue(fast > 40.0)
    }

    @Test
    fun `the rate correction stays small next to between-rider spread`() {
        // Doubling the sweat rate must not move concentration more than switching
        // bands does, or the model would be claiming a precision it does not have.
        val rateEffect = sweatSodiumMmolPerLitre(typical, 2000.0) -
            sweatSodiumMmolPerLitre(typical, 1000.0)
        val bandEffect = sweatSodiumMmolPerLitre(salty, 1000.0) -
            sweatSodiumMmolPerLitre(typical, 1000.0)
        assertTrue(rateEffect < bandEffect)
    }

    @Test
    fun `concentration is clamped to the physiological range`() {
        assertTrue(sweatSodiumMmolPerLitre(typical, 0.0) >= 10.0)
        val absurd = ElectrolytePolicy(overrideMmolPerLitre = 500.0)
        assertTrue(sweatSodiumMmolPerLitre(absurd, 5000.0) <= 90.0)
    }

    @Test
    fun `a measured value supersedes the band`() {
        val measured = ElectrolytePolicy(
            sodiumClass = SweatSodiumClass.LIGHT,
            overrideMmolPerLitre = 55.0,
        )
        assertEquals(55.0, sweatSodiumMmolPerLitre(measured, 1000.0), 1e-9)
    }

    // --- Loss -------------------------------------------------------------------

    @Test
    fun `sodium loss is concentration times volume`() {
        // 1 litre at 40 mmol/l is 40 * 22.99 mg.
        assertEquals(919.6, sodiumLossMg(typical, 1000.0, 1000.0), 1e-6)
    }

    @Test
    fun `a salty sweater loses more from the same volume`() {
        assertTrue(sodiumLossMg(salty, 1000.0, 1000.0) > sodiumLossMg(typical, 1000.0, 1000.0))
    }

    @Test
    fun `no sweat means no sodium`() {
        assertEquals(0.0, sodiumLossMg(typical, 0.0, 0.0), 1e-9)
    }

    // --- Advice -----------------------------------------------------------------

    @Test
    fun `short rides get no sodium advice at all`() {
        val advice = sodiumAdvice(typical, cumulativeSodiumMg = 800.0, 1000.0, ridingHours = 1.0)
        assertEquals(0.0, advice.targetMg, 1e-9)
        assertFalse(advice.takeSeparately)
        // The loss is still reported; it is the recommendation that is withheld.
        assertEquals(800.0, advice.lossMg, 1e-9)
    }

    @Test
    fun `past the duration threshold a fraction of the loss is recommended`() {
        val advice = sodiumAdvice(typical, cumulativeSodiumMg = 2000.0, 1500.0, ridingHours = 3.0)
        assertEquals(1000.0, advice.targetMg, 1e-9)
    }

    @Test
    fun `concentration is expressed against the fluid target not the loss`() {
        // 1000 mg of sodium into the 2 litres the rider was told to drink.
        val advice = sodiumAdvice(typical, cumulativeSodiumMg = 2000.0, 2000.0, ridingHours = 3.0)
        assertEquals(500.0, advice.concentrationMgPerLitre, 1e-9)
        assertFalse(advice.takeSeparately)
    }

    @Test
    fun `an unpalatable concentration becomes advice to take it separately`() {
        // A salty sweater under deficit targeting: lots of sodium, little fluid.
        val advice = sodiumAdvice(salty, cumulativeSodiumMg = 4000.0, 500.0, ridingHours = 4.0)
        assertTrue(advice.concentrationMgPerLitre > 1500.0)
        assertTrue(advice.takeSeparately)
    }

    @Test
    fun `a zero fluid target does not divide by zero`() {
        val advice = sodiumAdvice(typical, cumulativeSodiumMg = 1500.0, 0.0, ridingHours = 2.0)
        assertEquals(0.0, advice.concentrationMgPerLitre, 1e-9)
        assertTrue(advice.takeSeparately)
    }

    @Test
    fun `a rider replacing no sodium is told to take none`() {
        val none = ElectrolytePolicy(replacementFraction = 0.0)
        val advice = sodiumAdvice(none, cumulativeSodiumMg = 3000.0, 2000.0, ridingHours = 4.0)
        assertEquals(0.0, advice.targetMg, 1e-9)
        assertFalse(advice.takeSeparately)
    }

    @Test
    fun `the target rounds to a dose a rider can actually measure`() {
        val advice = sodiumAdvice(typical, cumulativeSodiumMg = 1234.0, 1000.0, ridingHours = 2.0)
        assertEquals(600, advice.targetMgRounded)
    }

    // --- Integration with the accumulator ---------------------------------------

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

    private fun runHours(acc: SweatAccumulator, result: HeatBalanceResult, hours: Double) {
        var t = acc.state.ridingTimeMs
        val end = t + (hours * 3_600_000).toLong()
        while (t <= end) {
            acc.tick(t, result, recording = true)
            t += 1000L
        }
    }

    @Test
    fun `the accumulator integrates sodium alongside fluid`() {
        val acc = SweatAccumulator()
        runHours(acc, oneLitrePerHour, 1.0)
        assertEquals(1000.0, acc.state.cumulativeSweatMl, 5.0)
        assertEquals(919.6, acc.state.cumulativeSodiumMg, 10.0)
    }

    @Test
    fun `sodium is integrated at the rate in force at the time`() {
        // An hour easy then an hour hard loses more sodium than two easy hours, and
        // less than two hard ones. Deriving it from the total at read time would
        // lose that, which is the reason it is accumulated rather than computed.
        val hard = oneLitrePerHour.copy(sweatRateMlPerHour = 2000.0)

        val mixed = SweatAccumulator()
        runHours(mixed, oneLitrePerHour, 1.0)
        runHours(mixed, hard, 1.0)

        val easyOnly = SweatAccumulator()
        runHours(easyOnly, oneLitrePerHour, 2.0)

        val hardOnly = SweatAccumulator()
        runHours(hardOnly, hard, 2.0)

        assertTrue(mixed.state.cumulativeSodiumMg > easyOnly.state.cumulativeSodiumMg)
        assertTrue(mixed.state.cumulativeSodiumMg < hardOnly.state.cumulativeSodiumMg)
    }

    @Test
    fun `changing the band mid-ride does not rewrite what is already accrued`() {
        val acc = SweatAccumulator()
        runHours(acc, oneLitrePerHour, 1.0)
        val afterFirstHour = acc.state.cumulativeSodiumMg

        acc.electrolytePolicy = salty
        runHours(acc, oneLitrePerHour, 1.0)

        val secondHour = acc.state.cumulativeSodiumMg - afterFirstHour
        assertTrue(secondHour > afterFirstHour)
        // ...and the first hour is untouched by the change.
        assertEquals(919.6, afterFirstHour, 10.0)
    }

    @Test
    fun `a reset clears sodium with everything else`() {
        val acc = SweatAccumulator()
        runHours(acc, oneLitrePerHour, 1.0)
        acc.reset()
        assertEquals(0.0, acc.state.cumulativeSodiumMg, 1e-9)
    }

    // --- Plausibility -----------------------------------------------------------

    @Test
    fun `a long hot ride produces a defensible sodium figure`() {
        // Four hours at 1.5 l/h is 6 litres of sweat. A typical sweater should land
        // in the region of 6 g of sodium, which is what published whole-body
        // measurements give for that volume. Anything an order of magnitude out
        // would be a units error, which is the failure mode worth catching.
        val acc = SweatAccumulator()
        runHours(acc, oneLitrePerHour.copy(sweatRateMlPerHour = 1500.0), 4.0)
        val grams = acc.state.cumulativeSodiumMg / 1000.0
        assertTrue("got $grams g", grams in 4.0..9.0)
    }
}
