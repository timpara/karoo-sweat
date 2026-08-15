package de.timpara.karoosweat.model

import kotlin.math.cos
import kotlin.math.sqrt

/** Distance helpers. Extracted so the approximation can be checked against reality. */
object GeoDistance {

    /**
     * Equirectangular approximation of great-circle distance, in km.
     *
     * Accurate to well under 1% over the tens of kilometres this is used for, and
     * enormously cheaper than haversine. It is only ever used to answer "have I moved
     * far enough to be worth refetching the weather", so precision beyond that would
     * be wasted.
     */
    fun approxKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val meanLatRad = Math.toRadians((lat1 + lat2) / 2)
        val dx = (lon2 - lon1) * KM_PER_DEGREE_LON_AT_EQUATOR * cos(meanLatRad)
        val dy = (lat2 - lat1) * KM_PER_DEGREE_LAT
        return sqrt(dx * dx + dy * dy)
    }

    private const val KM_PER_DEGREE_LAT = 110.574
    private const val KM_PER_DEGREE_LON_AT_EQUATOR = 111.320
}
