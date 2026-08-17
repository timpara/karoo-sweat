package de.timpara.karoosweat.model

import kotlinx.serialization.Serializable

/**
 * Which source to trust for ambient temperature.
 *
 * Humidity is not negotiable: no Karoo data type reports it, so it always comes from
 * the weather service (or the manual fallback).
 */
enum class TemperatureSource {
    /** Prefer the weather service, which is unaffected by device self-heating. */
    WEATHER_API,

    /** Prefer the device stream when it reports, falling back to the service. */
    DEVICE_SENSOR,
}

/**
 * All user-configurable settings.
 *
 * Lives in the pure module rather than alongside the Android persistence layer so
 * that the derivation of a [RiderProfile] and a [HydrationPolicy] can be tested
 * directly, and so schema evolution can be covered by serialisation tests.
 *
 * Every field has a default. Combined with `ignoreUnknownKeys` at the decode site
 * this means an older build can read a newer payload and vice versa, so a settings
 * change never bricks an existing install.
 */
@Serializable
data class SweatSettings(
    val heightCm: Double = 178.0,
    val grossEfficiency: Double = 0.22,
    val clothingCloValue: Double = 0.35,
    val sweatMultiplier: Double = 1.0,
    /** Strategy for turning sweat loss into a drinking target. */
    val targetMode: HydrationTargetMode = HydrationTargetMode.DEFICIT,
    /** Used by [HydrationTargetMode.PROPORTIONAL] only. */
    val replacementFraction: Double = 0.8,
    /** Used by [HydrationTargetMode.DEFICIT] only. */
    val allowableDeficitFraction: Double = 0.015,
    val gutAbsorptionCapMlPerHour: Double = 1000.0,
    val temperatureSource: TemperatureSource = TemperatureSource.WEATHER_API,
    /** Used only when no weather data has ever been fetched and no sensor reports. */
    val fallbackTempC: Double = 20.0,
    /** Used whenever weather data is unavailable, since humidity has no sensor. */
    val fallbackHumidityPct: Double = 50.0,
    /** Manual override for rider mass; when null the Karoo user profile is used. */
    val overrideMassKg: Double? = null,
    val fitExportEnabled: Boolean = true,
    /** Alert the rider when projected body mass loss crosses a threshold. */
    val alertsEnabled: Boolean = true,
) {
    fun toPolicy() = HydrationPolicy(
        targetMode = targetMode,
        replacementFraction = replacementFraction,
        allowableDeficitFraction = allowableDeficitFraction,
        gutAbsorptionCapMlPerHour = gutAbsorptionCapMlPerHour,
    )

    /**
     * Combine settings with the mass reported by the Karoo user profile.
     *
     * Precedence is explicit override, then the Karoo profile, then a default. Rider
     * mass, FTP and heart rate zones are deliberately not duplicated as settings:
     * two sources of truth for the same number inevitably disagree.
     */
    fun toRiderProfile(profileMassKg: Double?) = RiderProfile(
        massKg = overrideMassKg ?: profileMassKg ?: DEFAULT_MASS_KG,
        heightCm = heightCm,
        grossEfficiency = grossEfficiency,
        clothingCloValue = clothingCloValue,
        sweatMultiplier = sweatMultiplier,
    )

    private companion object {
        const val DEFAULT_MASS_KG = 75.0
    }
}
