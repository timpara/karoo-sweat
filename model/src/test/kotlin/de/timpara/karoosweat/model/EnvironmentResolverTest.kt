package de.timpara.karoosweat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ambient source selection.
 *
 * These rules are fiddly, and getting them wrong fails quietly: the rider still sees
 * a plausible number, just one computed from the wrong input. Every branch is
 * covered, including the provenance flags that drive the caveat labels on the
 * graphical field.
 */
class EnvironmentResolverTest {

    private val now = 1_000_000_000L

    private fun weather(
        tempC: Double = 22.0,
        rh: Double = 60.0,
        fetchedAt: Long = now - 60_000L,
    ) = WeatherSnapshot(
        temperatureC = tempC,
        relativeHumidityPct = rh,
        latitude = 48.2,
        longitude = 16.4,
        fetchedAtEpochMs = fetchedAt,
    )

    private val apiPreferred = SweatSettings(temperatureSource = TemperatureSource.WEATHER_API)
    private val sensorPreferred = SweatSettings(temperatureSource = TemperatureSource.DEVICE_SENSOR)

    @Test
    fun `weather is used when preferred and available`() {
        val env = EnvironmentResolver.resolve(weather(tempC = 22.0), 31.0, apiPreferred, now)
        assertEquals(22.0, env.temperatureC, 1e-9)
        assertFalse(env.temperatureFromSensor)
    }

    @Test
    fun `sensor is used when preferred and available`() {
        val env = EnvironmentResolver.resolve(weather(tempC = 22.0), 31.0, sensorPreferred, now)
        assertEquals(31.0, env.temperatureC, 1e-9)
        assertTrue(env.temperatureFromSensor)
    }

    @Test
    fun `sensor is used when preferred source has no data`() {
        // Weather preferred but never fetched: the sensor is better than a guess.
        val env = EnvironmentResolver.resolve(null, 17.0, apiPreferred, now)
        assertEquals(17.0, env.temperatureC, 1e-9)
        assertTrue(env.temperatureFromSensor)
    }

    @Test
    fun `weather is used when sensor preferred but silent`() {
        // The device temperature stream frequently never reports at all.
        val env = EnvironmentResolver.resolve(weather(tempC = 9.0), null, sensorPreferred, now)
        assertEquals(9.0, env.temperatureC, 1e-9)
        assertFalse(env.temperatureFromSensor)
    }

    @Test
    fun `configured fallback is the last resort`() {
        val settings = apiPreferred.copy(fallbackTempC = 14.0)
        val env = EnvironmentResolver.resolve(null, null, settings, now)
        assertEquals(14.0, env.temperatureC, 1e-9)
        assertFalse(env.temperatureFromSensor)
    }

    @Test
    fun `humidity always comes from weather when available`() {
        // There is no humidity sensor on the Karoo, so the preference is irrelevant.
        val env = EnvironmentResolver.resolve(weather(rh = 83.0), 30.0, sensorPreferred, now)
        assertEquals(83.0, env.relativeHumidityPct, 1e-9)
        assertFalse(env.humidityIsFallback)
    }

    @Test
    fun `humidity falls back and is flagged when no weather exists`() {
        val settings = apiPreferred.copy(fallbackHumidityPct = 45.0)
        val env = EnvironmentResolver.resolve(null, 25.0, settings, now)
        assertEquals(45.0, env.relativeHumidityPct, 1e-9)
        assertTrue(
            "the rider must be told the humidity is a guess",
            env.humidityIsFallback,
        )
    }

    @Test
    fun `weather age is reported and null when absent`() {
        val env = EnvironmentResolver.resolve(weather(fetchedAt = now - 90_000L), null, apiPreferred, now)
        assertEquals(90_000L, env.weatherAgeMs)
        assertNull(EnvironmentResolver.resolve(null, null, apiPreferred, now).weatherAgeMs)
    }

    @Test
    fun `staleness threshold is three hours`() {
        val fresh = weather(fetchedAt = now - 2 * 60 * 60 * 1000L)
        val stale = weather(fetchedAt = now - 4 * 60 * 60 * 1000L)
        assertFalse(fresh.isStale(now))
        assertTrue(stale.isStale(now))
    }
}

class GeoDistanceTest {

    @Test
    fun `identical points are zero distance apart`() {
        assertEquals(0.0, GeoDistance.approxKm(48.2, 16.4, 48.2, 16.4), 1e-9)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111.0, GeoDistance.approxKm(48.0, 16.0, 49.0, 16.0), 1.0)
    }

    @Test
    fun `longitude degrees shrink with latitude`() {
        val atEquator = GeoDistance.approxKm(0.0, 0.0, 0.0, 1.0)
        val atSixty = GeoDistance.approxKm(60.0, 0.0, 60.0, 1.0)
        assertEquals(111.3, atEquator, 1.0)
        // cos(60 degrees) is exactly 0.5.
        assertEquals(atEquator / 2, atSixty, 1.0)
    }

    @Test
    fun `known city pair matches published distance`() {
        // Vienna to Bratislava is approximately 55 km.
        assertEquals(55.0, GeoDistance.approxKm(48.2082, 16.3738, 48.1486, 17.1077), 3.0)
    }

    @Test
    fun `distance is symmetric`() {
        val there = GeoDistance.approxKm(48.2, 16.4, 47.1, 15.4)
        val back = GeoDistance.approxKm(47.1, 15.4, 48.2, 16.4)
        assertEquals(there, back, 1e-9)
    }
}
