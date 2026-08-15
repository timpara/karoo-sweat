package de.timpara.karoosweat.datatype

import android.content.Context
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.timpara.karoosweat.engine.SweatEngine
import de.timpara.karoosweat.engine.SweatSnapshot
import de.timpara.karoosweat.model.HydrationStatus
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The headline graphical field: how much to drink, how fast you are losing it, and
 * whether you are in trouble.
 *
 * Colour tracks projected body mass loss rather than absolute volume, because 1.5
 * litres means something very different to a 55 kg rider than to a 95 kg one.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class HydrationDataType(
    extension: String,
    private val engine: SweatEngine,
) : DataTypeImpl(extension, TYPE_ID) {

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // We draw our own label, so suppress the system header.
        emitter.onNext(UpdateGraphicConfig(showHeader = false))

        val job = CoroutineScope(Dispatchers.IO).launch {
            engine.snapshot.filterNotNull().collect { snapshot ->
                // Karoo rate-limits view updates to about 1 Hz and silently drops
                // anything faster, so there is no point throttling further here.
                emitter.updateView(renderHydrationView(context, snapshot, config))
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object { const val TYPE_ID = "hydration" }
}

/**
 * Compose the hydration field into RemoteViews.
 *
 * Extracted from the data type so the debug harness can render the exact same view
 * off-device. Anything that only the harness exercises is not worth testing, so the
 * production path and the preview path deliberately share this function.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
suspend fun renderHydrationView(
    context: Context,
    snapshot: SweatSnapshot,
    config: ViewConfig,
): RemoteViews = GlanceRemoteViews()
    .compose(context, DpSize.Unspecified) { HydrationView(snapshot, config) }
    .remoteViews

@Composable
private fun HydrationView(snapshot: SweatSnapshot, config: ViewConfig) {
    val accent = when (snapshot.status) {
        HydrationStatus.OK -> Color(0xFF2E7D32)
        HydrationStatus.WARN -> Color(0xFFEF6C00)
        HydrationStatus.CRITICAL -> Color(0xFFC62828)
    }
    // Karoo firmware up to ~1.527 reports a zero-sized ViewConfig (karoo-ext#26).
    // Scaling fonts directly off that would render every label at 0sp, so the
    // rider would see an entirely blank field with no clue why. Fall back to a
    // sane base size rather than trusting the value.
    val base = if (config.textSize > 0) config.textSize else DEFAULT_TEXT_SIZE
    val big = (base * 1.6f).sp
    val small = (base * 0.62f).sp
    val tiny = (base * 0.5f).sp

    Column(
        modifier = GlanceModifier.fillMaxSize().padding(4.dp()).background(Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "DRINK BY NOW",
            style = TextStyle(fontSize = small, color = androidx.glance.unit.ColorProvider(accent)),
        )
        Text(
            text = "${snapshot.recommendedIntakeMl.roundToInt()} ml",
            style = TextStyle(
                fontSize = big,
                fontWeight = FontWeight.Bold,
                color = androidx.glance.unit.ColorProvider(accent),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${snapshot.state.currentRateMlPerHour.roundToInt()} ml/h",
                style = TextStyle(fontSize = small),
            )
            Text(text = "   ", style = TextStyle(fontSize = small))
            Text(
                text = "-%.1f%%".format(snapshot.bodyMassLossFraction * 100),
                style = TextStyle(fontSize = small, fontWeight = FontWeight.Medium),
            )
        }
        // Surface the caveats rather than hiding them. A rider who knows the estimate
        // is running on a guessed humidity can weigh it accordingly.
        val caveats = buildList {
            if (snapshot.state.estimatedFromHeartRate) add("HR est")
            if (snapshot.environment.humidityIsFallback) add("no RH")
            if (snapshot.state.uncompensable) add("HEAT")
        }
        if (caveats.isNotEmpty()) {
            Text(
                text = caveats.joinToString(" - "),
                style = TextStyle(fontSize = tiny),
            )
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

/** Used when the Karoo reports a zero-sized view config. */
private const val DEFAULT_TEXT_SIZE = 20
