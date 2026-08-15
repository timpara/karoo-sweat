package de.timpara.karoosweat.ui

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.timpara.karoosweat.datatype.renderHydrationView
import de.timpara.karoosweat.engine.SweatSnapshot
import de.timpara.karoosweat.model.Conditions
import de.timpara.karoosweat.model.Environment
import de.timpara.karoosweat.model.HeatBalance
import de.timpara.karoosweat.model.HydrationPolicy
import de.timpara.karoosweat.model.SweatAccumulator
import de.timpara.karoosweat.model.SweatSettings
import io.hammerhead.karooext.models.ViewConfig

/**
 * Developer harness. Debug builds only; not registered in the release manifest.
 *
 * The Karoo system service does not exist on an emulator or an ordinary phone, so
 * the extension can never receive real streams there. This screen substitutes
 * synthetic conditions, runs them through the genuine model and accumulator, and
 * renders the genuine Glance view into the genuine RemoteViews pipeline.
 *
 * That covers what an emulator can honestly verify: that the field composes, fits,
 * and stays legible at Karoo field dimensions across the full range of values,
 * including the awkward ones (four-digit millilitres, every caveat label showing at
 * once, a zero-sized ViewConfig). It cannot verify stream wiring or ride lifecycle;
 * only a real device can.
 */
class HarnessActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { HarnessScreen() } } }
    }
}

@Composable
private fun HarnessScreen() {
    var power by remember { mutableFloatStateOf(250f) }
    var speedKmh by remember { mutableFloatStateOf(30f) }
    var tempC by remember { mutableFloatStateOf(25f) }
    var humidity by remember { mutableFloatStateOf(50f) }
    var minutes by remember { mutableFloatStateOf(90f) }
    var textSize by remember { mutableFloatStateOf(20f) }

    val settings = SweatSettings()
    val rider = settings.toRiderProfile(75.0)
    val policy = HydrationPolicy()

    val result = HeatBalance.evaluate(
        Conditions(
            powerWatts = power.toDouble(),
            airSpeedMs = speedKmh.toDouble() / 3.6,
            airTempC = tempC.toDouble(),
            relativeHumidityPct = humidity.toDouble(),
        ),
        rider,
    )

    // Integrate the real accumulator over the requested duration at 10 s steps.
    val accumulator = remember(power, speedKmh, tempC, humidity, minutes) {
        SweatAccumulator().also { acc ->
            var t = 0L
            val end = (minutes * 60_000).toLong()
            while (t <= end) {
                acc.tick(t, result, recording = true)
                t += 10_000L
            }
        }
    }
    val state = accumulator.state

    val snapshot = SweatSnapshot(
        state = state,
        rider = rider,
        environment = Environment(
            temperatureC = tempC.toDouble(),
            relativeHumidityPct = humidity.toDouble(),
            temperatureFromSensor = false,
            humidityIsFallback = false,
            weatherAgeMs = 60_000L,
        ),
        status = state.status(rider, policy),
        recommendedIntakeMl = state.recommendedIntakeMl(policy),
        bodyMassLossFraction = state.bodyMassLossFraction(rider),
    )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Field preview", style = MaterialTheme.typography.titleMedium)

        // Full width, quarter height: the layout the graphical field is designed for.
        FieldPreview(snapshot, gridSize = 60 to 15, viewSize = 480 to 200, textSize = textSize.toInt())
        Text("60x15 (full width, quarter height)", style = MaterialTheme.typography.labelSmall)

        // Half width: the tightest realistic layout, where text overflow shows up.
        FieldPreview(snapshot, gridSize = 30 to 15, viewSize = 240 to 200, textSize = (textSize / 1.4f).toInt())
        Text("30x15 (half width)", style = MaterialTheme.typography.labelSmall)

        // The firmware bug case: a zero-sized config must not render blank.
        FieldPreview(snapshot, gridSize = 0 to 0, viewSize = 480 to 200, textSize = 0)
        Text("zero-size config (firmware <=1.527 bug)", style = MaterialTheme.typography.labelSmall)

        Text(
            "sweat %.0f ml | rate %.0f ml/h | loss %.2f%% | wet %.2f%s".format(
                state.cumulativeSweatMl,
                state.currentRateMlPerHour,
                snapshot.bodyMassLossFraction * 100,
                result.skinWettedness,
                if (result.uncompensable) " UNCOMPENSABLE" else "",
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        Knob("Power", power, 0f, 500f, "%.0f W") { power = it }
        Knob("Speed", speedKmh, 0f, 60f, "%.0f km/h") { speedKmh = it }
        Knob("Temperature", tempC, -10f, 45f, "%.0f C") { tempC = it }
        Knob("Humidity", humidity, 0f, 100f, "%.0f %%") { humidity = it }
        Knob("Duration", minutes, 1f, 480f, "%.0f min") { minutes = it }
        Knob("Base text size", textSize, 8f, 40f, "%.0f sp") { textSize = it }
    }
}

@Composable
private fun FieldPreview(
    snapshot: SweatSnapshot,
    gridSize: Pair<Int, Int>,
    viewSize: Pair<Int, Int>,
    textSize: Int,
) {
    val context = LocalContext.current
    val config = ViewConfig(
        gridSize = gridSize,
        viewSize = viewSize,
        textSize = textSize,
        preview = true,
    )
    // Glance composition is suspending, so the RemoteViews are produced
    // asynchronously and re-produced whenever the inputs change.
    var remoteViews by remember { mutableStateOf<RemoteViews?>(null) }
    LaunchedEffect(snapshot, gridSize, viewSize, textSize) {
        remoteViews = renderHydrationView(context, snapshot, config)
    }

    Card(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        remoteViews?.let { views ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FrameLayout(ctx) },
                update = { container ->
                    container.removeAllViews()
                    container.addView(views.apply(container.context, container))
                },
            )
        }
    }
}

@Composable
private fun Knob(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    format: String,
    onChange: (Float) -> Unit,
) {
    Text("$label: ${format.format(value)}", style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = onChange, valueRange = min..max)
}
