package de.timpara.karoosweat.model

import org.junit.Test

/**
 * Not an assertion test: prints the model's prediction surface so that a human can
 * sanity-check it. Run with:
 *
 *   ./gradlew :model:test --tests '*ReferenceTable*' -i
 */
class ReferenceTableTest {

    private val rider = RiderProfile(massKg = 75.0, heightCm = 178.0)

    @Test
    fun `print reference table`() {
        println()
        println("Sweat rate (ml/h) - 75 kg / 178 cm rider, 0.35 clo, GE 0.22")
        println("=".repeat(78))
        println(
            "%-6s %-7s %-6s %-8s %8s %8s %7s %s".format(
                "Power", "Speed", "Temp", "RH", "Sweat", "Dry loss", "Wet", "Note",
            ),
        )
        println("-".repeat(78))

        data class Row(val p: Double, val kmh: Double, val t: Double, val rh: Double)

        val rows = listOf(
            Row(120.0, 22.0, 5.0, 70.0),
            Row(150.0, 25.0, 10.0, 60.0),
            Row(200.0, 28.0, 18.0, 55.0),
            Row(250.0, 30.0, 25.0, 50.0),
            Row(250.0, 8.0, 25.0, 50.0),
            Row(250.0, 30.0, 32.0, 40.0),
            Row(250.0, 30.0, 32.0, 85.0),
            Row(200.0, 10.0, 35.0, 60.0),
            Row(300.0, 34.0, 28.0, 45.0),
            Row(180.0, 26.0, 22.0, 70.0),
            // Descents: zero power. Issue #12 was that these accrued exactly zero;
            // the insensible-loss floor now guarantees a plausible minimum. Note the
            // model treats a descent as thermally compensated once airflow sheds the
            // basal heat, which is correct for the steady state but does not capture
            // residual heat carried into the descent from a hard climb.
            Row(0.0, 55.0, 30.0, 45.0),
            Row(0.0, 45.0, 15.0, 60.0),
        )
        for (r in rows) {
            val res = HeatBalance.evaluate(
                Conditions(r.p, r.kmh / 3.6, r.t, r.rh), rider,
            )
            println(
                "%-6.0f %-7.0f %-6.0f %-8.0f %8.0f %8.0f %7.2f %s".format(
                    r.p, r.kmh, r.t, r.rh,
                    res.sweatRateMlPerHour,
                    res.dryHeatLossW,
                    res.skinWettedness,
                    if (res.uncompensable) "UNCOMPENSABLE" else "",
                ),
            )
        }
        println("=".repeat(78))

        // What the rider actually sees after two hours of the canonical warm ride.
        val res = HeatBalance.evaluate(Conditions(250.0, 30.0 / 3.6, 25.0, 50.0), rider)
        val acc = SweatAccumulator()
        var t = 0L
        repeat(7201) { acc.tick(t, res, true); t += 1000L }
        val policy = HydrationPolicy()
        println(
            (
                "After 2 h at 250 W / 25 C / 50%% RH: sweat %.0f ml, " +
                    "drink target %.0f ml, body mass loss %.1f%%, status %s"
                ).format(
                acc.state.cumulativeSweatMl,
                acc.state.recommendedIntakeMl(rider, policy),
                acc.state.bodyMassLossFraction(rider) * 100,
                acc.state.status(rider, policy),
            ),
        )
        println()
    }
}
