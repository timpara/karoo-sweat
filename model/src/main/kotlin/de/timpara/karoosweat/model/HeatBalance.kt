package de.timpara.karoosweat.model

import kotlinx.serialization.Serializable

/**
 * Rider-specific and preference-specific constants for the heat balance model.
 *
 * [massKg] and (optionally) FTP come from the Karoo `UserProfile`; everything else is
 * a user setting. Defaults are chosen to be reasonable for an adult cyclist in
 * typical summer kit.
 */
@Serializable
data class RiderProfile(
    /** Rider mass in kg. Sourced from Karoo `UserProfile.weight`. */
    val massKg: Double = 75.0,
    /** Rider height in cm. Not available from Karoo; user setting. */
    val heightCm: Double = 178.0,
    /**
     * Gross mechanical efficiency, i.e. mechanical watts / metabolic watts.
     * Trained cyclists sit around 0.20-0.24. Higher efficiency means less waste
     * heat and therefore less sweat for the same power.
     */
    val grossEfficiency: Double = 0.22,
    /**
     * Intrinsic clothing insulation in clo. 0.35 clo is a summer jersey and bibs;
     * ~0.6 clo adds arm warmers and a gilet; ~1.0 clo is a winter jacket.
     */
    val clothingCloValue: Double = 0.35,
    /**
     * Personal calibration factor, applied to the final sweat rate.
     *
     * Individual sweat rates vary by a factor of two to three at identical workload
     * and identical conditions, which no first-principles model can predict. A rider
     * who performs a nude weigh-in test should tune this. 1.0 leaves the model output
     * unscaled.
     */
    val sweatMultiplier: Double = 1.0,
    /**
     * Physiological ceiling on sweat rate in ml/h. Elite heat-acclimated athletes
     * can briefly exceed 2.5 l/h but sustained rates above this are not credible.
     */
    val maxSweatRateMlPerHour: Double = 2500.0,
) {
    init {
        require(massKg > 0) { "massKg must be positive" }
        require(heightCm > 0) { "heightCm must be positive" }
    }

    /**
     * Body surface area in m^2 via the Du Bois & Du Bois (1916) formula.
     *
     * BSA = 0.202 * mass^0.425 * height^0.725, with mass in kg and height in metres.
     */
    val bodySurfaceAreaM2: Double
        get() = 0.202 * Math.pow(massKg, 0.425) * Math.pow(heightCm / 100.0, 0.725)

    /** Intrinsic clothing insulation converted from clo to m^2*K/W (1 clo = 0.155). */
    val clothingInsulationM2KPerW: Double
        get() = clothingCloValue * 0.155

    /**
     * Clothing area factor: clothing increases the effective surface radiating and
     * convecting heat. Standard linear approximation from ISO 9920.
     */
    val clothingAreaFactor: Double
        get() = 1.0 + 0.31 * clothingCloValue

    /**
     * Fraction of body surface that is effectively bare, derived from [clothingCloValue].
     *
     * This matters enormously and is the term that simple sweat models most often
     * get wrong. A cyclist in summer kit has bare arms, legs, hands and head:
     * roughly half the body surface exchanges heat directly with the air rather than
     * through fabric. Treating the rider as uniformly clothed understates both dry
     * heat loss and evaporative capacity several-fold, which makes every warm ride
     * look like uncompensable heat stress.
     *
     * Rather than expose this as a second, redundant setting, it is tied to the
     * insulation the rider reports: putting on arm warmers and a jacket both raises
     * clo and covers skin.
     */
    val bareSkinFraction: Double
        get() = (0.65 - 0.5 * clothingCloValue).coerceIn(0.05, 0.65)
}

/**
 * Instantaneous ride and environmental conditions feeding the heat balance.
 */
data class Conditions(
    /** Mechanical power at the cranks, in watts. */
    val powerWatts: Double,
    /**
     * Air speed over the rider in m/s. Ground speed is used as a proxy; a headwind
     * increases and a tailwind decreases the true value, which is why very slow
     * climbing in still air is the worst case for heat dissipation.
     */
    val airSpeedMs: Double,
    /** Ambient air temperature in degrees Celsius. */
    val airTempC: Double,
    /** Relative humidity, 0..100. */
    val relativeHumidityPct: Double,
)

/**
 * Full output of one heat balance evaluation. Intermediate terms are exposed
 * deliberately: they are what makes the model testable and debuggable, and
 * [skinWettedness] in particular is worth surfacing to the rider because it is the
 * signal that heat stress is becoming uncompensable.
 */
data class HeatBalanceResult(
    /** Estimated whole-body sweat rate in ml/h, after calibration and capping. */
    val sweatRateMlPerHour: Double,
    /** Metabolic power in W. */
    val metabolicPowerW: Double,
    /** Net heat production (metabolic minus mechanical) in W. */
    val heatProductionW: Double,
    /** Dry heat exchange (convection + radiation) in W. Negative means heat gain. */
    val dryHeatLossW: Double,
    /** Required evaporative cooling in W. */
    val requiredEvaporationW: Double,
    /** Maximum evaporative capacity of the environment in W. */
    val maxEvaporationW: Double,
    /**
     * Skin wettedness, the ratio of required to maximum evaporation.
     * Values approaching or exceeding 1.0 indicate uncompensable heat stress:
     * sweat drips rather than evaporating, so fluid is lost without cooling benefit.
     */
    val skinWettedness: Double,
    /** True when [skinWettedness] saturated at 1.0, i.e. core temperature will drift up. */
    val uncompensable: Boolean,
    /** Assumed mean skin temperature in degrees Celsius. */
    val skinTempC: Double,
)

/**
 * A partitional-calorimetry sweat rate model.
 *
 * The chain is: metabolic rate (basal plus the cost of mechanical work) -> net heat
 * production -> subtract dry (convective + radiative) and respiratory losses -> the
 * remainder is the evaporative requirement -> divide by the latent heat of
 * vaporisation, adjusted for evaporative efficiency, to get a sweat rate.
 *
 * The approach follows the standard heat-stress framework (ISO 7933 / Gagge), with
 * the simplifications appropriate to a device that has power, speed, temperature and
 * humidity but no radiant temperature, no wind vector and no core temperature sensor.
 *
 * Honest statement of accuracy: the structure of this model is sound and it responds
 * correctly to intensity, temperature, humidity and airspeed. The absolute magnitude
 * for an individual rider is uncertain by roughly plus or minus 30% until
 * [RiderProfile.sweatMultiplier] has been calibrated against a weigh-in test.
 */
object HeatBalance {

    /** Latent heat of vaporisation of sweat at skin temperature, J per kg. */
    private const val LATENT_HEAT_J_PER_KG = 2_426_000.0

    /** Linear radiative heat transfer coefficient, W/m^2K, for a clothed person. */
    private const val RADIATIVE_COEFF = 4.7

    /** Moisture permeability index of typical cycling clothing (dimensionless). */
    private const val CLOTHING_PERMEABILITY_INDEX = 0.38

    /** Lewis relation constant, K/kPa, linking convective and evaporative transfer. */
    private const val LEWIS_RATIO = 16.5

    /**
     * Basal metabolic rate per unit body surface area, in W/m^2.
     *
     * Resting metabolism never switches off. For a 75 kg / 178 cm rider (BSA ~1.93
     * m^2) this is about 87 W, matching the textbook 80-90 W resting figure. Deriving
     * it from body surface area rather than a flat constant makes it scale correctly
     * with rider size, and it is the physiologically honest way to represent the heat
     * a coasting or descending rider still produces.
     *
     * Standard basal heat flux is ~44-46 W/m^2; 45 is used here.
     */
    private const val BASAL_METABOLIC_W_PER_M2 = 45.0

    /**
     * Insensible fluid loss while riding, in ml/h.
     *
     * A small non-evaporative floor covering respiratory water loss and baseline
     * transepidermal loss, which continue even when the heat balance calls for no
     * thermoregulatory sweating at all (a cold descent, say). Unlike the previous
     * effort-gated floor, this is not conditioned on power: a rider coasting downhill
     * still loses fluid, and that was exactly the case the model used to miss.
     */
    private const val INSENSIBLE_LOSS_ML_PER_HOUR = 60.0

    /**
     * Fraction of ground speed that counts as effective airflow over the body.
     *
     * The h_c = 8.3*v^0.6 relation is derived for a person standing in uniform flow.
     * A cyclist is not: the torso sits in its own wake, a jersey traps a boundary
     * layer, and the frontal area presented to the flow is a fraction of the total.
     * Using raw ground speed roughly doubles convective cooling and makes the model
     * claim riders stay cool at speeds where they demonstrably do not.
     *
     * This is the least principled constant in the model and the first thing to
     * revisit if calibration data disagrees.
     */
    private const val AIRFLOW_FACTOR = 0.55

    /**
     * Ratio of sweat actually produced to sweat strictly required for evaporative
     * cooling.
     *
     * Human thermoregulation is feed-forward, driven by core and skin temperature
     * rather than by a perfectly metered heat deficit. Sweat is over-produced,
     * regionally uneven, and visibly drips at intensities well below full skin
     * wettedness. Measured sweat rates in exercising cyclists consistently exceed
     * partitional-calorimetry requirements by roughly 40-60%, and a model that omits
     * this term systematically under-predicts fluid loss.
     */
    private const val SWEAT_OVERSHOOT = 1.6

    /**
     * Mean skin temperature as a function of ambient temperature.
     *
     * A fixed 35 C is the common textbook simplification, but it badly overestimates
     * dry heat loss in the cold, where peripheral vasoconstriction pulls skin
     * temperature down, and underestimates it in the heat, where vasodilation pushes
     * it up. This linear fit to exercising-subject data is bounded to a
     * physiologically sensible range.
     */
    fun skinTempC(airTempC: Double): Double =
        (27.5 + 0.22 * airTempC).coerceIn(28.0, 35.5)

    /**
     * Convective heat transfer coefficient for forced convection, W/m^2K.
     *
     * h_c = 8.3 * v^0.6 is the standard forced-convection relation, valid for roughly
     * 0.2 to 20 m/s, applied to the effective airflow rather than raw ground speed
     * (see [AIRFLOW_FACTOR]). The lower bound keeps natural convection represented
     * when stationary or crawling up a climb.
     */
    fun convectiveCoefficient(airSpeedMs: Double): Double =
        8.3 * Math.pow((airSpeedMs * AIRFLOW_FACTOR).coerceIn(0.2, 25.0), 0.6)

    /**
     * Evaluate the heat balance for one instant.
     *
     * All area-normalised terms are computed in W/m^2 (the convention in the heat
     * stress literature) and converted back to absolute watts at the end.
     */
    fun evaluate(conditions: Conditions, rider: RiderProfile): HeatBalanceResult {
        val area = rider.bodySurfaceAreaM2
        val power = conditions.powerWatts.coerceAtLeast(0.0)
        val tAir = conditions.airTempC
        val tSkin = skinTempC(tAir)

        // --- Metabolic rate and net heat production ---
        // Metabolism is basal plus the cost of the mechanical work. The basal term is
        // what a coasting or descending rider still produces; without it, zero power
        // would wrongly imply zero heat and zero fluid loss.
        val basalW = BASAL_METABOLIC_W_PER_M2 * area
        val metabolicW = basalW + power / rider.grossEfficiency
        // Only the mechanical work carries a useful-work credit; basal metabolism is
        // entirely dissipated as heat.
        val heatProductionW = metabolicW - power
        val metabolicPerArea = metabolicW / area
        val heatPerArea = heatProductionW / area

        // --- Dry heat exchange: convection + radiation ---
        // Bare and clothed surface are treated as parallel paths. Bare skin sees only
        // the surface boundary layer; clothed skin sees the fabric in series with it.
        val hc = convectiveCoefficient(conditions.airSpeedMs)
        val hTotal = hc + RADIATIVE_COEFF
        val bare = rider.bareSkinFraction
        val clothed = 1.0 - bare

        val bareDryResistance = 1.0 / hTotal
        val clothedDryResistance = rider.clothingInsulationM2KPerW +
            1.0 / (rider.clothingAreaFactor * hTotal)
        val deltaT = tSkin - tAir
        val dryPerArea = bare * (deltaT / bareDryResistance) +
            clothed * (deltaT / clothedDryResistance)

        // --- Respiratory losses (evaporative + convective) ---
        val pAmbient = Psychrometrics.ambientVapourPressure(tAir, conditions.relativeHumidityPct)
        val respiratoryEvapPerArea = 0.0173 * metabolicPerArea * (5.624 - pAmbient)
        val respiratoryConvPerArea = 0.0014 * metabolicPerArea * (35.0 - tAir)

        // --- Evaporative requirement ---
        val requiredPerArea =
            heatPerArea - dryPerArea - respiratoryEvapPerArea - respiratoryConvPerArea

        // --- Maximum evaporative capacity of the environment ---
        // Same parallel-path treatment, via the Lewis relation.
        val he = LEWIS_RATIO * hc
        val bareEvapResistance = 1.0 / he
        val clothedEvapResistance =
            rider.clothingInsulationM2KPerW / (LEWIS_RATIO * CLOTHING_PERMEABILITY_INDEX) +
                1.0 / (rider.clothingAreaFactor * he)
        val pSkinSat = Psychrometrics.saturationVapourPressure(tSkin)
        val deltaP = pSkinSat - pAmbient
        val maxPerArea = (
            bare * (deltaP / bareEvapResistance) + clothed * (deltaP / clothedEvapResistance)
            ).coerceAtLeast(1.0)

        // --- Skin wettedness and evaporative efficiency ---
        // Below full wettedness, sweat evaporates efficiently. As the skin approaches
        // saturation, an increasing fraction drips off and is lost without cooling.
        // Candas' relation: efficiency = 1 - w^2 / 2.
        val rawWettedness = if (requiredPerArea > 0) requiredPerArea / maxPerArea else 0.0
        val uncompensable = rawWettedness > 1.0
        val wettedness = rawWettedness.coerceIn(0.0, 1.0)
        val efficiency = (1.0 - wettedness * wettedness / 2.0).coerceAtLeast(0.5)

        // --- Convert to a sweat rate ---
        val sweatWattsPerArea = if (requiredPerArea > 0) requiredPerArea / efficiency else 0.0
        val sweatWatts = sweatWattsPerArea * area * SWEAT_OVERSHOOT
        val kgPerSecond = sweatWatts / LATENT_HEAT_J_PER_KG
        // 1 kg of sweat is ~1 litre, so kg/h and l/h are interchangeable here.
        val rawMlPerHour = kgPerSecond * 3600.0 * 1000.0

        // Insensible-loss floor. Now that basal metabolism is in the heat balance,
        // the evaporative requirement is already non-zero across normal riding, so
        // this floor no longer has to paper over a modelling gap. It only guarantees
        // the small respiratory and transepidermal loss that persists even on a cold
        // descent, and it is deliberately not gated on power.
        val floor = INSENSIBLE_LOSS_ML_PER_HOUR

        val sweatRate = (rawMlPerHour * rider.sweatMultiplier)
            .coerceAtLeast(floor)
            .coerceAtMost(rider.maxSweatRateMlPerHour)

        return HeatBalanceResult(
            sweatRateMlPerHour = sweatRate,
            metabolicPowerW = metabolicW,
            heatProductionW = heatProductionW,
            dryHeatLossW = dryPerArea * area,
            requiredEvaporationW = requiredPerArea * area,
            maxEvaporationW = maxPerArea * area,
            skinWettedness = wettedness,
            uncompensable = uncompensable,
            skinTempC = tSkin,
        )
    }
}
