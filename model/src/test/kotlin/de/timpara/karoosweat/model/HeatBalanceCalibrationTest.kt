package de.timpara.karoosweat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calibration tests for the heat balance model.
 *
 * These are the tests that matter. Each scenario asserts that the predicted sweat
 * rate falls inside a band drawn from the exercise physiology literature for a
 * typical trained cyclist. The bands are deliberately wide, because individual
 * variation is genuinely large; they are tight enough to catch a model that is
 * structurally wrong, and loose enough not to encode false precision.
 *
 * Reference expectations are drawn from the commonly reported ranges: roughly
 * 0.4-0.8 l/h for endurance riding in cool conditions, 1.0-1.5 l/h for hard riding
 * in warm conditions, and 1.5-2.5 l/h in hot or hot-humid conditions.
 */
class HeatBalanceCalibrationTest {

    private val rider = RiderProfile(massKg = 75.0, heightCm = 178.0)

    private fun sweat(
        power: Double,
        speedKmh: Double,
        tempC: Double,
        rhPct: Double,
        profile: RiderProfile = rider,
    ): Double = HeatBalance.evaluate(
        Conditions(
            powerWatts = power,
            airSpeedMs = speedKmh / 3.6,
            airTempC = tempC,
            relativeHumidityPct = rhPct,
        ),
        profile,
    ).sweatRateMlPerHour

    private fun assertInBand(actual: Double, low: Double, high: Double, label: String) {
        assertTrue(
            "$label: expected $low..$high ml/h but model produced ${"%.0f".format(actual)}",
            actual in low..high,
        )
    }

    // --- Reference scenario table -------------------------------------------------

    @Test
    fun `cool easy endurance ride`() {
        // 150 W at 10 C is close to thermally neutral; sweat should be minimal.
        assertInBand(sweat(150.0, 25.0, 10.0, 60.0), 50.0, 450.0, "cool endurance")
    }

    @Test
    fun `mild tempo ride`() {
        // The mid range is where the model is least constrained: dry heat loss and
        // heat production are of similar magnitude, so the evaporative requirement is
        // a difference of two large numbers and is correspondingly sensitive to the
        // skin temperature and airflow assumptions. The band is wide on purpose.
        assertInBand(sweat(200.0, 28.0, 18.0, 55.0), 250.0, 1000.0, "mild tempo")
    }

    @Test
    fun `warm threshold ride`() {
        // The canonical case: hard riding on a warm summer day.
        assertInBand(sweat(250.0, 30.0, 25.0, 50.0), 900.0, 1700.0, "warm threshold")
    }

    @Test
    fun `hot hard ride`() {
        assertInBand(sweat(250.0, 30.0, 32.0, 40.0), 1300.0, 2300.0, "hot hard")
    }

    @Test
    fun `hot humid ride is uncompensable`() {
        val result = HeatBalance.evaluate(
            Conditions(250.0, 30.0 / 3.6, 32.0, 85.0),
            rider,
        )
        assertTrue(
            "high heat and humidity must saturate skin wettedness",
            result.uncompensable,
        )
        assertEquals(1.0, result.skinWettedness, 1e-9)
        assertInBand(result.sweatRateMlPerHour, 1500.0, 2500.0, "hot humid")
    }

    @Test
    fun `slow hot climb is worse than fast hot flat at equal power`() {
        // Same power, same air: at 8 km/h there is almost no convective cooling,
        // so the evaporative requirement and therefore the sweat rate must be higher.
        val climbing = sweat(250.0, 8.0, 30.0, 50.0)
        val flat = sweat(250.0, 35.0, 30.0, 50.0)
        assertTrue(
            "climbing ${"%.0f".format(climbing)} should exceed flat ${"%.0f".format(flat)}",
            climbing > flat,
        )
    }

    // --- Monotonicity and structural properties -----------------------------------

    @Test
    fun `sweat rate increases monotonically with power`() {
        val rates = listOf(100.0, 150.0, 200.0, 250.0, 300.0, 350.0)
            .map { sweat(it, 30.0, 25.0, 50.0) }
        rates.zipWithNext { a, b ->
            assertTrue("sweat must increase with power: $a then $b", b > a)
        }
    }

    @Test
    fun `sweat rate increases monotonically with temperature`() {
        // Below roughly 15 C at this power the heat balance requires no evaporative
        // cooling at all, so the estimate rests on the basal floor and is flat by
        // construction. Monotonicity is only meaningful above that.
        val temps = listOf(5.0, 12.0, 20.0, 27.0, 35.0)
        val rates = temps.map { sweat(220.0, 28.0, it, 50.0) }

        rates.zipWithNext { a, b ->
            assertTrue("sweat must never decrease with temperature: $a then $b", b >= a)
        }
        temps.zip(rates).filter { it.first >= 20.0 }.map { it.second }
            .zipWithNext { a, b ->
                assertTrue("above the floor sweat must strictly increase: $a then $b", b > a)
            }
    }

    @Test
    fun `sweat rate increases with humidity at fixed temperature`() {
        val dry = sweat(250.0, 30.0, 30.0, 25.0)
        val humid = sweat(250.0, 30.0, 30.0, 85.0)
        assertTrue(
            "humid ${"%.0f".format(humid)} must exceed dry ${"%.0f".format(dry)}",
            humid > dry,
        )
    }

    @Test
    fun `heavier rider sweats more at the same absolute power`() {
        val light = sweat(250.0, 30.0, 25.0, 50.0, rider.copy(massKg = 60.0, heightCm = 168.0))
        val heavy = sweat(250.0, 30.0, 25.0, 50.0, rider.copy(massKg = 95.0, heightCm = 190.0))
        // Larger surface area dissipates more dry heat, but produces the same
        // metabolic heat, so the net effect on required evaporation is what matters.
        // We assert only that the model distinguishes them at all.
        assertTrue("body size must affect the estimate", kotlin.math.abs(heavy - light) > 20.0)
    }

    @Test
    fun `more clothing increases sweat rate`() {
        val summer = sweat(220.0, 28.0, 15.0, 60.0, rider.copy(clothingCloValue = 0.35))
        val winter = sweat(220.0, 28.0, 15.0, 60.0, rider.copy(clothingCloValue = 1.0))
        assertTrue(
            "winter kit ${"%.0f".format(winter)} must exceed summer ${"%.0f".format(summer)}",
            winter > summer,
        )
    }

    @Test
    fun `higher gross efficiency reduces sweat rate`() {
        val inefficient = sweat(250.0, 30.0, 25.0, 50.0, rider.copy(grossEfficiency = 0.19))
        val efficient = sweat(250.0, 30.0, 25.0, 50.0, rider.copy(grossEfficiency = 0.24))
        assertTrue("higher efficiency means less waste heat", efficient < inefficient)
    }

    // --- Bounds and degenerate inputs ---------------------------------------------

    @Test
    fun `zero power produces zero sweat`() {
        assertEquals(0.0, sweat(0.0, 0.0, 25.0, 50.0), 1e-9)
    }

    @Test
    fun `riding always produces at least the basal rate`() {
        // Freezing conditions, trivial power: the heat balance wants zero evaporation,
        // but a riding human still loses some fluid.
        val rate = sweat(80.0, 20.0, -5.0, 80.0)
        assertTrue("basal floor must apply, got $rate", rate in 80.0..200.0)
    }

    @Test
    fun `sweat rate is capped at the physiological maximum`() {
        val absurd = sweat(600.0, 5.0, 45.0, 95.0)
        assertTrue("must respect the cap", absurd <= rider.maxSweatRateMlPerHour + 1e-9)
    }

    @Test
    fun `calibration multiplier scales the output linearly`() {
        val base = sweat(250.0, 30.0, 25.0, 50.0)
        val scaled = sweat(250.0, 30.0, 25.0, 50.0, rider.copy(sweatMultiplier = 1.5))
        assertEquals(base * 1.5, scaled, base * 1.5 * 1e-6)
    }

    @Test
    fun `cool conditions are compensable`() {
        val result = HeatBalance.evaluate(Conditions(200.0, 8.0, 15.0, 50.0), rider)
        assertFalse("cool riding must not be uncompensable", result.uncompensable)
    }

    // --- Body surface area --------------------------------------------------------

    @Test
    fun `du bois surface area matches published values`() {
        // 75 kg, 178 cm is widely quoted as approximately 1.93 m^2.
        assertEquals(1.93, RiderProfile(massKg = 75.0, heightCm = 178.0).bodySurfaceAreaM2, 0.03)
        // 60 kg, 168 cm is approximately 1.68 m^2.
        assertEquals(1.68, RiderProfile(massKg = 60.0, heightCm = 168.0).bodySurfaceAreaM2, 0.03)
    }
}
