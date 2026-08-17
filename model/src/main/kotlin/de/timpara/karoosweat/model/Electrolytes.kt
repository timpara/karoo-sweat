package de.timpara.karoosweat.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Sodium loss and replacement.
 *
 * Water is only half the problem. Sweat is not distilled water: it carries sodium at
 * a concentration that varies more between riders than sweat rate itself, and the
 * consequences of getting the balance wrong run in both directions.
 *
 * - Replace fluid without sodium over many hours and plasma sodium is diluted. This,
 *   not sodium loss on its own, is the mechanism of exercise-associated hyponatremia,
 *   which is the one hydration failure that actually kills people.
 * - Replace neither and you carry a deficit that shows up as cramping in the riders
 *   prone to it, though the evidence linking cramp to sodium is a good deal weaker
 *   than the supplement industry implies.
 *
 * The model here is deliberately shallow, because the physiology does not support a
 * deep one on the inputs a bike computer has. Sweat sodium concentration is largely
 * a fixed personal trait, so this is a user setting with a sweat-rate correction,
 * not something derived from the heat balance.
 */

/** Molar mass of sodium in mg/mmol, for converting concentrations to a mass. */
const val SODIUM_MG_PER_MMOL = 22.99

/**
 * Coarse personal sweat sodium bands.
 *
 * Whole-body sweat sodium spans roughly 10-90 mmol/l across individuals and is
 * strongly heritable, weakly related to fitness, and only modestly reduced by heat
 * acclimation. There is no way to infer it from power, temperature or humidity, so
 * the rider picks a band. The salty band is not rare: it describes the riders who
 * finish rides with visible white residue on their kit.
 */
enum class SweatSodiumClass(
    /** Whole-body sweat sodium at a reference 1 l/h sweat rate, in mmol/l. */
    val baselineMmolPerLitre: Double,
    val label: String,
) {
    /** Low-sodium sweat; no visible salt residue after hard, hot rides. */
    LIGHT(25.0, "light"),

    /** The population middle, and the right default in the absence of a sweat test. */
    TYPICAL(40.0, "typical"),

    /** Visible white residue on kit and straps, stinging eyes, salty taste. */
    SALTY(60.0, "salty"),
}

/**
 * How sodium loss is turned into a replacement recommendation.
 */
@Serializable
data class ElectrolytePolicy(
    /** The rider's sweat sodium band, or a measured value via [overrideMmolPerLitre]. */
    val sodiumClass: SweatSodiumClass = SweatSodiumClass.TYPICAL,
    /**
     * Measured whole-body sweat sodium in mmol/l from a lab or patch test, which
     * supersedes [sodiumClass] when set. A patch test is the only way to know this;
     * everything else is a guess with a wide interval.
     */
    val overrideMmolPerLitre: Double? = null,
    /**
     * Fraction of sodium loss to replace during the ride.
     *
     * Full replacement is no more necessary than it is for fluid: total body sodium
     * is large relative to what a ride removes, and normal food restores it. What
     * matters is replacing enough that the fluid going in does not dilute what is
     * left, which is why this is applied to the same losses the drink target is.
     */
    val replacementFraction: Double = 0.5,
    /**
     * Ride duration below which no sodium is recommended, in minutes.
     *
     * Short rides do not need electrolytes, and telling riders otherwise is how the
     * supplement industry sells sachets to people doing an hour in the cold.
     */
    val minimumDurationMinutes: Double = 90.0,
    /**
     * Drink sodium concentration above which the advice switches from "mix this into
     * your bottle" to "take it separately", in mg/l.
     *
     * Around 1500 mg/l most riders find a drink unpalatable and stop drinking it,
     * which trades a sodium problem for a worse fluid one.
     */
    val palatableCeilingMgPerLitre: Double = 1500.0,
)

/**
 * Whole-body sweat sodium concentration at a given sweat rate, in mmol/l.
 *
 * Concentration rises with sweat rate: the duct reabsorbs sodium at a roughly fixed
 * maximum rate, so the faster sweat passes through it, the less of the sodium is
 * recovered. The slope used here is deliberately gentle, and the result is clamped
 * to the physiological range, because the between-rider spread dwarfs this
 * correction and a steep slope would imply a precision the model does not have.
 */
fun sweatSodiumMmolPerLitre(policy: ElectrolytePolicy, sweatRateMlPerHour: Double): Double {
    val baseline = policy.overrideMmolPerLitre ?: policy.sodiumClass.baselineMmolPerLitre
    val litresPerHour = (sweatRateMlPerHour / 1000.0).coerceAtLeast(0.0)
    val scaled = baseline * (1.0 + RATE_SLOPE * (litresPerHour - REFERENCE_LITRES_PER_HOUR))
    return scaled.coerceIn(MIN_MMOL_PER_LITRE, MAX_MMOL_PER_LITRE)
}

/** Sodium lost in mg for [sweatMl] of sweat produced at [sweatRateMlPerHour]. */
fun sodiumLossMg(
    policy: ElectrolytePolicy,
    sweatMl: Double,
    sweatRateMlPerHour: Double,
): Double = (sweatMl / 1000.0) *
    sweatSodiumMmolPerLitre(policy, sweatRateMlPerHour) *
    SODIUM_MG_PER_MMOL

/**
 * The sodium half of the recommendation, derived from a ride's accumulated losses.
 *
 * @param targetMg how much sodium to have taken by now
 * @param concentrationMgPerLitre what that works out to in the fluid the rider has
 *   been told to drink, which is the actionable number: it is what a drink mix
 *   label reports
 * @param takeSeparately true when the concentration exceeds palatability, meaning
 *   capsules or food rather than a stronger bottle
 */
data class SodiumAdvice(
    val lossMg: Double,
    val targetMg: Double,
    val concentrationMgPerLitre: Double,
    val takeSeparately: Boolean,
) {
    /** Rounded to 50 mg, because no rider doses sodium more finely than that. */
    val targetMgRounded: Int get() = (targetMg / 50.0).roundToInt() * 50
}

/**
 * Turn accumulated sodium loss and a fluid target into advice.
 *
 * The concentration is expressed against the *recommended* intake rather than the
 * loss, so that the two halves of the recommendation are consistent: following both
 * numbers puts the right amount of sodium into the right amount of fluid. When the
 * fluid target is still zero, which it is early in a ride under deficit targeting,
 * there is no concentration to report and the advice is to take sodium separately
 * if it is needed at all.
 */
fun sodiumAdvice(
    policy: ElectrolytePolicy,
    cumulativeSodiumMg: Double,
    recommendedIntakeMl: Double,
    ridingHours: Double,
): SodiumAdvice {
    val longEnough = ridingHours * 60.0 >= policy.minimumDurationMinutes
    val target = if (longEnough) cumulativeSodiumMg * policy.replacementFraction else 0.0

    val litres = recommendedIntakeMl / 1000.0
    val concentration = if (litres > 0.0) target / litres else 0.0

    return SodiumAdvice(
        lossMg = cumulativeSodiumMg,
        targetMg = target,
        concentrationMgPerLitre = concentration,
        takeSeparately = target > 0.0 &&
            (litres <= 0.0 || concentration > policy.palatableCeilingMgPerLitre),
    )
}

/**
 * Reference sweat rate for [SweatSodiumClass.baselineMmolPerLitre], in l/h. Chosen
 * because it is close to the whole-body rate at which most published sweat sodium
 * data was collected.
 */
private const val REFERENCE_LITRES_PER_HOUR = 1.0

/**
 * Fractional change in sodium concentration per l/h of sweat rate away from the
 * reference. Gentle on purpose; see [sweatSodiumMmolPerLitre].
 */
private const val RATE_SLOPE = 0.15

/** Physiological bounds on whole-body sweat sodium, in mmol/l. */
private const val MIN_MMOL_PER_LITRE = 10.0
private const val MAX_MMOL_PER_LITRE = 90.0
