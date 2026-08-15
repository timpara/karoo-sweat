package de.timpara.karoosweat.engine

import de.timpara.karoosweat.R
import de.timpara.karoosweat.model.HydrationStatus
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.InRideAlert

/**
 * In-ride alerts on crossing a dehydration threshold.
 *
 * These fire at most once per threshold per ride. The thresholds are expressed in
 * body mass loss rather than millilitres because that is the number with actual
 * physiological meaning: roughly 2% is where endurance performance starts to
 * measurably degrade, and 3% is where thermoregulation and cognition follow.
 */
object HydrationAlerts {

    fun dispatch(karooSystem: KarooSystemService, status: HydrationStatus) {
        val alert = when (status) {
            HydrationStatus.OK -> return

            HydrationStatus.WARN -> InRideAlert(
                id = "karoo-sweat-warn",
                icon = R.drawable.ic_sweat,
                title = "Drink up",
                detail = "Estimated 2% body mass lost",
                autoDismissMs = 10_000L,
                backgroundColor = R.color.hydration_warn,
                textColor = R.color.alert_text,
            )

            HydrationStatus.CRITICAL -> InRideAlert(
                id = "karoo-sweat-critical",
                icon = R.drawable.ic_sweat,
                title = "Dehydrated",
                detail = "Estimated 3% body mass lost",
                autoDismissMs = 15_000L,
                backgroundColor = R.color.hydration_critical,
                textColor = R.color.alert_text,
            )
        }
        karooSystem.dispatch(alert)
    }
}
