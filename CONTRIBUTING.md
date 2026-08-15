# Contributing

Thanks for taking an interest. This is a small project and contributions are welcome.

## The one thing to understand first

The repository is split in two, and the split is deliberate:

```
model/   Pure Kotlin. The physiological model. No Android, no karoo-ext, no I/O.
app/     The Android extension. Karoo streams, weather, persistence, data fields.
```

**`model/` is where the value is, and it is testable in about two seconds with no
device, no emulator and no authentication.** If you can put your change there, do.
Logic that lives in `app/` can effectively only be verified by riding a bike with it.

If you find yourself adding a pure function to `app/`, that is usually a sign it
belongs in `model/` instead. Several things have already moved that way.

## Building

Requires JDK 17.

karoo-ext is published only to GitHub Packages, which requires authentication even
for public packages. You need a token with the **`read:packages`** scope in
`~/.gradle/gradle.properties`:

```properties
gpr.user=<your github username>
gpr.key=<token with read:packages>
```

If you use the GitHub CLI: `gh auth refresh -s read:packages` then `gh auth token`.

```bash
./gradlew :model:test        # no authentication needed
./gradlew build              # everything, needs the token
```

To see what the model actually predicts:

```bash
./gradlew :model:test --tests '*ReferenceTable*' --rerun-tasks -i
```

## Changing the physiological model

This is the part that needs care, because a wrong model does not crash. It produces
plausible-looking numbers that are simply incorrect, and a rider may act on them.

If you change anything in `HeatBalance`:

1. **Run the reference table and read it.** Green tests are not sufficient. Two real
   bugs in this project were found only by printing the prediction surface and
   noticing that ordinary scenarios were being labelled uncompensable.
2. **Do not tune constants to make a band pass.** The bands in
   `HeatBalanceCalibrationTest` are judgement calls drawn from literature ranges,
   not ground truth. If your change is physically justified and a band disagrees,
   argue for widening the band in the pull request rather than quietly fitting to it.
3. **State your source.** New coefficients should cite where they come from, or be
   explicitly labelled as an approximation the way `AIRFLOW_FACTOR` and
   `SWEAT_OVERSHOOT` are.
4. **Preserve the structural tests.** Monotonicity in power, temperature and
   humidity, and the slow-climb-versus-fast-flat comparison, are not arbitrary. They
   encode physics that any correct model must satisfy.

## Calibration data is especially welcome

The single most useful contribution is real measurement. If you:

- weigh yourself nude before and after a ride,
- record what you drank,
- and export the FIT file with the `sweat_loss` developer field,

then `(before - after) + fluid consumed` is your true sweat loss. Open an issue with
that number alongside the recorded estimate, the conditions, and your rider details.
Enough of those and the approximate constants can become fitted ones.

## Style

- Follow the surrounding code. Kotlin official style, 4 spaces, 100 column soft limit.
- **Comment why, not what.** The existing comments explain reasoning and trade-offs;
  a comment restating the line below it will be asked about in review.
- Prefer clarity to cleverness. This code is read by people reasoning about
  thermodynamics, not by people admiring Kotlin.

## Pull requests

- One logical change per pull request.
- Make sure `./gradlew build` passes. CI runs the model tests on forks without
  secrets, so you will get signal even without a token.
- Describe the *behavioural* effect. "Adds a term for solar radiation" is less useful
  than "raises predicted sweat by roughly 15% on clear-sky rides above 25 C".

## Reporting problems

For a wrong estimate rather than a crash, please include: power, speed, temperature,
humidity, rider mass and height, clothing setting, and what the field displayed. A
screenshot of the field plus the FIT file is ideal. Without conditions, a report that
the number "looks too high" cannot be acted on.
