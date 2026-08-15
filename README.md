# karoo-sweat

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that estimates **sweat
loss** from a physiological heat-balance model and tells you **how much you should
have drunk by now**.

No other Karoo extension does this. [nomride](https://github.com/yrkan/nomride) logs
what you drink and models carbohydrate burn; this one models what you *lose*.

## Data fields

| Field | Type | Shows |
|---|---|---|
| **Hydration** | graphical | Drink target, sweat rate, projected body mass loss, colour-coded |
| **Drink by now** | numeric | Cumulative recommended intake, ml |
| **Sweat loss** | numeric | Cumulative estimated sweat loss, ml |
| **Sweat rate** | numeric | Current sweat rate, ml/h |
| **Body mass lost** | numeric | Projected % body mass lost if you drink nothing |

Sweat loss, sweat rate and drink target are also written into the recorded FIT file
as developer fields, so you can verify the model after the ride.

## How the estimate works

Partitional calorimetry, the standard heat-stress framework, reduced to the inputs a
bike computer actually has:

1. **Metabolic rate** from power and gross efficiency.
2. **Net heat production** = metabolic minus mechanical power.
3. **Dry heat loss** (convection + radiation), treating bare and clothed skin as
   parallel paths. This split matters enormously: a rider in summer kit has roughly
   half their surface area bare, and ignoring that makes every warm ride look like
   uncompensable heat stress.
4. **Respiratory losses** from ventilation.
5. What remains is the **evaporative requirement**. Divided by the latent heat of
   vaporisation, adjusted for evaporative efficiency, that is a sweat rate.
6. **Skin wettedness** (required over maximum evaporation) captures humidity: as skin
   approaches saturation, sweat drips instead of evaporating, so fluid is lost with
   no cooling benefit. Above 1.0 the heat stress is uncompensable and core
   temperature will drift up.

The drink target is sweat loss times a replacement fraction, capped by a gut
absorption limit, because recommending more fluid than the gut can take is not merely
useless but actively causes GI distress.

### Environmental inputs

- **Humidity** always comes from [Open-Meteo](https://open-meteo.com/). The Karoo has
  no humidity sensor and karoo-ext exposes no humidity data type.
- **Temperature** comes from Open-Meteo by default, optionally from the on-device
  `TYPE_TEMPERATURE_ID` stream. The device sensor is biased upward by self-heating
  and direct sun, which is why it is not the default.
- Weather is cached in DataStore and refetched hourly or after 5 km of movement, so
  it keeps working offline. HTTP goes through Karoo's `MakeHttpRequest`, which routes
  via the companion phone when the head unit has no connectivity of its own.

### Without a power meter

Falls back to estimating power from heart rate reserve, anchored so that 88% HRR maps
to your FTP. This is materially less accurate and the graphical field labels itself
`HR est` when it is active.

## Accuracy, honestly

The *structure* of this model is sound and it responds correctly to intensity,
temperature, humidity, airspeed, body size and clothing. The *absolute magnitude* for
any individual rider is uncertain by roughly ±30% until calibrated, because
individual sweat rates vary two- to three-fold at identical workload in identical
conditions. No first-principles model can predict that.

Two constants are frank approximations rather than derived quantities, and are the
first things to revisit if calibration disagrees:

- `AIRFLOW_FACTOR` (0.55) — a cyclist sits in their own wake, so effective airflow is
  well below ground speed.
- `SWEAT_OVERSHOOT` (1.6) — thermoregulation is feed-forward, so measured sweat
  consistently exceeds the calorimetric requirement.

**Calibrate it.** Weigh yourself nude before and after a ride, add back the fluid you
drank, and compare with the recorded `sweat_loss` field. Adjust the sweat multiplier
in settings until they agree. Two or three rides across different temperatures is
enough.

### Reference predictions

75 kg / 178 cm rider, summer kit, gross efficiency 0.22:

| Power | Speed | Temp | RH | Sweat | Wettedness |
|---|---|---|---|---|---|
| 150 W | 25 km/h | 10 °C | 60% | 95 ml/h | 0.00 |
| 200 W | 28 km/h | 18 °C | 55% | 397 ml/h | 0.12 |
| 250 W | 30 km/h | 25 °C | 50% | 1316 ml/h | 0.37 |
| 250 W | 8 km/h | 25 °C | 50% | 2320 ml/h | 0.85 |
| 250 W | 30 km/h | 32 °C | 40% | 1959 ml/h | 0.50 |
| 250 W | 30 km/h | 32 °C | 85% | 2500 ml/h | 1.00 uncompensable |
| 300 W | 34 km/h | 28 °C | 45% | 2045 ml/h | 0.50 |

Note the slow hot climb: same power as the fast flat, but almost no convective
cooling, so nearly twice the sweat rate.

## Project layout

```
model/   Pure Kotlin. The entire physiological model, no Android or Karoo deps.
         41 unit tests, runs in ~2 s.
app/     The Android extension: Karoo streams, weather, persistence, data fields.
```

The separation is deliberate. The part worth getting right is testable without a
device, an emulator, or the karoo-ext dependency.

## Building

Requires JDK 17 and the Android SDK.

karoo-ext is published only to GitHub Packages, which requires authentication even
for public packages. Put a token with the `read:packages` scope in
`~/.gradle/gradle.properties`:

```properties
gpr.user=<your github username>
gpr.key=<token with read:packages>
```

Then:

```bash
./gradlew :model:test        # physiology tests, no auth needed
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To see the model's prediction surface:

```bash
./gradlew :model:test --tests '*ReferenceTable*' --rerun-tasks -i
```

## Status

Early. The model is tested and calibrated against literature ranges; the Android
layer has not yet been run on a device.

## Licence

MIT.
