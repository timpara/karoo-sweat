package de.timpara.karoosweat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.timpara.karoosweat.model.HydrationTargetMode
import de.timpara.karoosweat.model.SweatSodiumClass
import de.timpara.karoosweat.model.SweatSettings
import de.timpara.karoosweat.util.SweatStore
import kotlinx.coroutines.launch

/**
 * Settings screen.
 *
 * Rider mass, FTP and heart rate zones deliberately do not appear here: they come
 * from the Karoo user profile, and duplicating them would create two sources of
 * truth that inevitably disagree.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SweatStore(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(store)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(store: SweatStore) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(SweatSettings()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        settings = store.settings()
        loaded = true
    }

    fun update(transform: (SweatSettings) -> SweatSettings) {
        settings = transform(settings)
        scope.launch { store.saveSettings(settings) }
    }

    if (!loaded) {
        Text("Loading...", modifier = Modifier.padding(16.dp))
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Rider", style = MaterialTheme.typography.titleMedium)

        SliderSetting(
            label = "Height",
            value = settings.heightCm,
            range = 140f..210f,
            format = { "%.0f cm".format(it) },
        ) { update { s -> s.copy(heightCm = it) } }

        SliderSetting(
            label = "Gross efficiency",
            value = settings.grossEfficiency,
            range = 0.18f..0.25f,
            format = { "%.0f %%".format(it * 100) },
        ) { update { s -> s.copy(grossEfficiency = it) } }

        SliderSetting(
            label = "Clothing insulation",
            value = settings.clothingCloValue,
            range = 0.2f..1.2f,
            format = {
                val kit = when {
                    it < 0.45 -> "summer kit"
                    it < 0.75 -> "arm warmers"
                    else -> "winter jacket"
                }
                "%.2f clo (%s)".format(it, kit)
            },
        ) { update { s -> s.copy(clothingCloValue = it) } }

        HorizontalDivider()
        Text("Calibration", style = MaterialTheme.typography.titleMedium)
        Text(
            "Weigh yourself nude before and after a ride, add back what you drank, " +
                "and compare with the recorded sweat loss. Adjust until they agree.",
            style = MaterialTheme.typography.bodySmall,
        )

        SliderSetting(
            label = "Sweat multiplier",
            value = settings.sweatMultiplier,
            range = 0.5f..2.0f,
            format = { "%.2fx".format(it) },
        ) { update { s -> s.copy(sweatMultiplier = it) } }

        HorizontalDivider()
        Text("Drinking target", style = MaterialTheme.typography.titleMedium)

        val deficitMode = settings.targetMode == HydrationTargetMode.DEFICIT
        ToggleSetting(
            label = if (deficitMode) {
                "Hold below a deficit threshold"
            } else {
                "Replace a fixed fraction of sweat"
            },
            checked = deficitMode,
        ) { on ->
            update { s ->
                s.copy(
                    targetMode = if (on) {
                        HydrationTargetMode.DEFICIT
                    } else {
                        HydrationTargetMode.PROPORTIONAL
                    },
                )
            }
        }
        Text(
            if (deficitMode) {
                "Drink nothing until you approach the deficit that actually costs " +
                    "performance, then match your sweat rate to sit there. Asks for " +
                    "less fluid, and follows the evidence more closely."
            } else {
                "Replace a set share of everything you lose, from the first " +
                    "millilitre. Simple and familiar, but asks you to drink during " +
                    "the opening hour when you are nowhere near a real deficit."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        if (deficitMode) {
            SliderSetting(
                label = "Deficit to carry",
                value = settings.allowableDeficitFraction,
                range = 0f..0.02f,
                format = { "%.1f %% of body mass".format(it * 100) },
            ) { update { s -> s.copy(allowableDeficitFraction = it) } }
        } else {
            SliderSetting(
                label = "Replace fraction of sweat",
                value = settings.replacementFraction,
                range = 0.5f..1.0f,
                format = { "%.0f %%".format(it * 100) },
            ) { update { s -> s.copy(replacementFraction = it) } }
        }

        SliderSetting(
            label = "Max absorbable rate",
            value = settings.gutAbsorptionCapMlPerHour,
            range = 500f..1500f,
            format = { "%.0f ml/h".format(it) },
        ) { update { s -> s.copy(gutAbsorptionCapMlPerHour = it) } }

        HorizontalDivider()
        Text("Electrolytes", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sweat is not distilled water. Replacing fluid without sodium over " +
                "many hours dilutes what is left, which is the mechanism behind " +
                "the one hydration failure that actually hospitalises riders.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            "Sweat saltiness: ${settings.sweatSodiumClass.label}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Pick salty if your kit dries with visible white residue. Only a patch " +
                "test can tell you properly; between riders this varies more than " +
                "sweat rate does.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SweatSodiumClass.entries.forEach { option ->
                FilterChip(
                    selected = settings.sweatSodiumClass == option,
                    onClick = { update { s -> s.copy(sweatSodiumClass = option) } },
                    label = { Text(option.label) },
                )
            }
        }

        SliderSetting(
            label = "Replace fraction of sodium",
            value = settings.sodiumReplacementFraction,
            range = 0f..1f,
            format = { "%.0f %%".format(it * 100) },
        ) { update { s -> s.copy(sodiumReplacementFraction = it) } }

        SliderSetting(
            label = "No sodium advice before",
            value = settings.sodiumMinimumDurationMinutes,
            range = 30f..180f,
            format = { "%.0f min".format(it) },
        ) { update { s -> s.copy(sodiumMinimumDurationMinutes = it) } }

        HorizontalDivider()
        Text("Environment", style = MaterialTheme.typography.titleMedium)
        Text(
            "Humidity always comes from the weather service; the Karoo has no " +
                "humidity sensor. The fallbacks are used only when no forecast has " +
                "ever been fetched.",
            style = MaterialTheme.typography.bodySmall,
        )

        SliderSetting(
            label = "Fallback temperature",
            value = settings.fallbackTempC,
            range = -10f..45f,
            format = { "%.0f C".format(it) },
        ) { update { s -> s.copy(fallbackTempC = it) } }

        SliderSetting(
            label = "Fallback humidity",
            value = settings.fallbackHumidityPct,
            range = 10f..100f,
            format = { "%.0f %%".format(it) },
        ) { update { s -> s.copy(fallbackHumidityPct = it) } }

        HorizontalDivider()
        ToggleSetting("Write sweat data to FIT file", settings.fitExportEnabled) {
            update { s -> s.copy(fitExportEnabled = it) }
        }
        ToggleSetting("Dehydration alerts", settings.alertsEnabled) {
            update { s -> s.copy(alertsEnabled = it) }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    format: (Double) -> String,
    onChange: (Double) -> Unit,
) {
    Column {
        Text("$label: ${format(value)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
        )
    }
}

@Composable
private fun ToggleSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
