package de.timpara.karoosweat.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialisation and settings-derivation tests.
 *
 * Schema compatibility is the thing that quietly ruins an install: a rider updates,
 * the stored payload no longer decodes, and their mid-ride total silently resets.
 * Both directions of schema drift are covered here.
 */
class SettingsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `settings survive a round trip`() {
        val original = SweatSettings(
            heightCm = 183.0,
            grossEfficiency = 0.235,
            clothingCloValue = 0.7,
            sweatMultiplier = 1.25,
            targetMode = HydrationTargetMode.PROPORTIONAL,
            replacementFraction = 0.9,
            allowableDeficitFraction = 0.02,
            temperatureSource = TemperatureSource.DEVICE_SENSOR,
            overrideMassKg = 81.5,
            alertsEnabled = false,
        )
        assertEquals(original, json.decodeFromString<SweatSettings>(json.encodeToString(original)))
    }

    @Test
    fun `ride state survives a round trip`() {
        val original = SweatState(
            cumulativeSweatMl = 1234.5,
            ridingTimeMs = 5_400_000L,
            currentRateMlPerHour = 980.0,
            wettedness = 0.61,
            uncompensable = true,
            estimatedFromHeartRate = true,
            lastUpdatedEpochMs = 1_700_000_000_000L,
        )
        assertEquals(original, json.decodeFromString<SweatState>(json.encodeToString(original)))
    }

    @Test
    fun `a payload from an older build decodes using defaults`() {
        // An older version that never knew about clothing or alerts.
        val old = """{"heightCm":170.0,"sweatMultiplier":1.1}"""
        val decoded = json.decodeFromString<SweatSettings>(old)
        assertEquals(170.0, decoded.heightCm, 1e-9)
        assertEquals(1.1, decoded.sweatMultiplier, 1e-9)
        assertEquals(SweatSettings().clothingCloValue, decoded.clothingCloValue, 1e-9)
        assertTrue(decoded.alertsEnabled)
    }

    @Test
    fun `a payload from a newer build ignores unknown fields`() {
        val future = """{"heightCm":175.0,"sodiumTrackingEnabled":true,"someFutureThing":42}"""
        assertEquals(175.0, json.decodeFromString<SweatSettings>(future).heightCm, 1e-9)
    }

    @Test
    fun `an empty payload yields defaults`() {
        assertEquals(SweatSettings(), json.decodeFromString<SweatSettings>("{}"))
    }

    // --- Derivation ---------------------------------------------------------------

    @Test
    fun `rider mass prefers an explicit override`() {
        val settings = SweatSettings(overrideMassKg = 68.0)
        assertEquals(68.0, settings.toRiderProfile(profileMassKg = 90.0).massKg, 1e-9)
    }

    @Test
    fun `rider mass falls back to the karoo profile`() {
        val settings = SweatSettings(overrideMassKg = null)
        assertEquals(90.0, settings.toRiderProfile(profileMassKg = 90.0).massKg, 1e-9)
    }

    @Test
    fun `rider mass falls back to a default when nothing is known`() {
        assertEquals(75.0, SweatSettings().toRiderProfile(profileMassKg = null).massKg, 1e-9)
    }

    @Test
    fun `settings map onto the rider profile`() {
        val settings = SweatSettings(
            heightCm = 165.0,
            grossEfficiency = 0.21,
            clothingCloValue = 0.9,
            sweatMultiplier = 0.8,
        )
        val rider = settings.toRiderProfile(72.0)
        assertEquals(165.0, rider.heightCm, 1e-9)
        assertEquals(0.21, rider.grossEfficiency, 1e-9)
        assertEquals(0.9, rider.clothingCloValue, 1e-9)
        assertEquals(0.8, rider.sweatMultiplier, 1e-9)
    }

    @Test
    fun `settings map onto the hydration policy`() {
        val settings = SweatSettings(
            targetMode = HydrationTargetMode.PROPORTIONAL,
            replacementFraction = 0.65,
            allowableDeficitFraction = 0.01,
            gutAbsorptionCapMlPerHour = 750.0,
        )
        val policy = settings.toPolicy()
        assertEquals(HydrationTargetMode.PROPORTIONAL, policy.targetMode)
        assertEquals(0.65, policy.replacementFraction, 1e-9)
        assertEquals(0.01, policy.allowableDeficitFraction, 1e-9)
        assertEquals(750.0, policy.gutAbsorptionCapMlPerHour, 1e-9)
    }

    @Test
    fun `a payload predating the target mode decodes to the deficit default`() {
        // Riders upgrading from a build that only had a replacement fraction get the
        // evidence-based default rather than an unset enum.
        val old = """{"heightCm":180.0,"replacementFraction":0.8}"""
        val decoded = json.decodeFromString<SweatSettings>(old)
        assertEquals(HydrationTargetMode.DEFICIT, decoded.targetMode)
        assertEquals(0.015, decoded.allowableDeficitFraction, 1e-9)
    }

    @Test
    fun `clothing drives the bare skin fraction`() {
        // Summer kit leaves roughly half the body exposed; a winter jacket does not.
        val summer = SweatSettings(clothingCloValue = 0.35).toRiderProfile(75.0)
        val winter = SweatSettings(clothingCloValue = 1.1).toRiderProfile(75.0)
        assertTrue(summer.bareSkinFraction > 0.4)
        assertTrue(winter.bareSkinFraction < 0.2)
    }

    @Test
    fun `an invalid rider profile is rejected rather than silently wrong`() {
        val thrown = try {
            RiderProfile(massKg = 0.0)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("zero mass must not be accepted", thrown)
    }
}
