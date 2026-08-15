package de.timpara.karoosweat.model

import kotlin.math.exp

/**
 * Psychrometric helpers. All pressures in kPa, all temperatures in degrees Celsius.
 *
 * Kept deliberately free of any Android or Karoo dependency so the whole physiological
 * core can be exercised by fast JVM unit tests.
 */
object Psychrometrics {

    /**
     * Saturation vapour pressure of water over a liquid surface, via the Magnus-Tetens
     * approximation (Alduchov & Eskridge 1996 coefficients).
     *
     * Accurate to better than 0.4% over -40..+50 C, which is far tighter than any
     * other uncertainty in this model.
     *
     * @param tempC temperature in degrees Celsius
     * @return saturation vapour pressure in kPa
     */
    fun saturationVapourPressure(tempC: Double): Double =
        0.61094 * exp(17.625 * tempC / (tempC + 243.04))

    /**
     * Ambient (partial) water vapour pressure.
     *
     * @param tempC air temperature in degrees Celsius
     * @param relativeHumidityPct relative humidity, 0..100
     * @return partial vapour pressure in kPa
     */
    fun ambientVapourPressure(tempC: Double, relativeHumidityPct: Double): Double =
        saturationVapourPressure(tempC) * (relativeHumidityPct.coerceIn(0.0, 100.0) / 100.0)

    /**
     * Dew point, provided for display purposes only; it is not used by the sweat model.
     */
    fun dewPointC(tempC: Double, relativeHumidityPct: Double): Double {
        val rh = relativeHumidityPct.coerceIn(1.0, 100.0) / 100.0
        val gamma = 17.625 * tempC / (tempC + 243.04) + kotlin.math.ln(rh)
        return 243.04 * gamma / (17.625 - gamma)
    }
}
