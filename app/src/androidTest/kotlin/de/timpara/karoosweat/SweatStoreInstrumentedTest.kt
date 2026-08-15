package de.timpara.karoosweat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.timpara.karoosweat.model.SweatSettings
import de.timpara.karoosweat.model.SweatState
import de.timpara.karoosweat.model.TemperatureSource
import de.timpara.karoosweat.model.WeatherSnapshot
import de.timpara.karoosweat.util.SweatStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the persistence layer.
 *
 * The JVM tests already cover serialisation of the data classes. What they cannot
 * cover is DataStore itself: real file I/O, the corruption handler, and the fact
 * that a mid-ride process death must not lose the rider's running total. Those need
 * an actual Android runtime, so they live here.
 */
@RunWith(AndroidJUnit4::class)
class SweatStoreInstrumentedTest {

    private lateinit var store: SweatStore

    @Before
    fun setUp() {
        store = SweatStore(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun settingsRoundTripThroughDataStore() = runTest {
        val settings = SweatSettings(
            heightCm = 181.0,
            clothingCloValue = 0.65,
            sweatMultiplier = 1.35,
            temperatureSource = TemperatureSource.DEVICE_SENSOR,
            overrideMassKg = 79.0,
            alertsEnabled = false,
        )
        store.saveSettings(settings)
        assertEquals(settings, store.settings())
    }

    @Test
    fun defaultsAreReturnedBeforeAnythingIsSaved() = runTest {
        // A fresh install must produce a usable profile rather than crashing.
        val settings = store.settings()
        assertTrue(settings.heightCm > 0)
        assertTrue(settings.grossEfficiency > 0)
        assertEquals(75.0, settings.toRiderProfile(null).massKg, 1e-9)
    }

    @Test
    fun rideStateSurvivesAndCanBeCleared() = runTest {
        val state = SweatState(
            cumulativeSweatMl = 1432.0,
            ridingTimeMs = 4_500_000L,
            currentRateMlPerHour = 1150.0,
            uncompensable = true,
            lastUpdatedEpochMs = 1_700_000_000_000L,
        )
        store.saveRideState(state)

        // Simulating what happens after the service is killed and restarted.
        val reloaded = SweatStore(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(state, reloaded.loadRideState())

        reloaded.clearRideState()
        assertNull(reloaded.loadRideState())
    }

    @Test
    fun weatherIsCachedAndReadableAcrossInstances() = runTest {
        val snapshot = WeatherSnapshot(
            temperatureC = 27.5,
            relativeHumidityPct = 41.0,
            latitude = 48.2082,
            longitude = 16.3738,
            fetchedAtEpochMs = 1_700_000_000_000L,
        )
        store.saveWeather(snapshot)

        val reloaded = SweatStore(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(snapshot, reloaded.weatherFlow().first())
    }

    @Test
    fun settingsFlowEmitsUpdates() = runTest {
        store.saveSettings(SweatSettings(sweatMultiplier = 1.0))
        assertEquals(1.0, store.settingsFlow().first().sweatMultiplier, 1e-9)

        store.saveSettings(SweatSettings(sweatMultiplier = 1.7))
        assertEquals(1.7, store.settingsFlow().first().sweatMultiplier, 1e-9)
    }

    @Test
    fun aCorruptPayloadFallsBackToDefaultsInsteadOfCrashing() = runTest {
        // Losing settings is annoying; taking the data fields down mid-ride because
        // a stored blob no longer parses is far worse.
        store.saveSettings(SweatSettings(heightCm = 190.0))
        assertEquals(190.0, store.settings().heightCm, 1e-9)

        store.writeRawSettingsForTest("{ this is not json")
        assertEquals(SweatSettings(), store.settings())
    }
}
