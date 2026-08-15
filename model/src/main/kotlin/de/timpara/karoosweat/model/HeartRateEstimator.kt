package de.timpara.karoosweat.model

/**
 * Fallback estimator for riders without a power meter.
 *
 * Heart rate is a poor proxy for mechanical power in absolute terms: it drifts
 * upward over long efforts (cardiac drift), rises with heat independently of
 * workload, and lags step changes by tens of seconds. It is nonetheless far better
 * than nothing, and two of its biases happen to point the right way for this
 * application, since heat-driven cardiac drift genuinely does coincide with elevated
 * sweat rates.
 *
 * Output from this estimator should be presented to the rider as lower confidence.
 */
object HeartRateEstimator {

    /**
     * Fraction of heart rate reserve typically observed at functional threshold
     * power. Used to anchor the HR-to-power mapping against the rider's known FTP.
     */
    private const val HRR_AT_THRESHOLD = 0.88

    /**
     * Estimate mechanical power in watts from heart rate.
     *
     * The mapping is anchored on the rider's FTP: the fraction of heart rate reserve
     * is scaled so that [HRR_AT_THRESHOLD] maps to exactly FTP, and the relationship
     * is treated as linear either side of that.
     *
     * @param heartRateBpm current heart rate
     * @param restingHr resting heart rate from the Karoo user profile
     * @param maxHr maximum heart rate from the Karoo user profile
     * @param ftpWatts functional threshold power from the Karoo user profile
     * @return estimated mechanical power in watts, or null if the profile is
     *   insufficiently configured to make the estimate meaningful
     */
    fun estimatePowerWatts(
        heartRateBpm: Double,
        restingHr: Int,
        maxHr: Int,
        ftpWatts: Int,
    ): Double? {
        if (ftpWatts <= 0 || maxHr <= 0 || maxHr <= restingHr) return null
        if (heartRateBpm <= 0) return null

        val reserve = (maxHr - restingHr).toDouble()
        val hrr = ((heartRateBpm - restingHr) / reserve).coerceIn(0.0, 1.2)
        return (hrr / HRR_AT_THRESHOLD) * ftpWatts
    }
}
