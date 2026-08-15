# Changelog

All notable changes to this project are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- The heat balance derived metabolic rate purely from mechanical power, so a coasting
  or descending rider (power = 0) accrued exactly zero sweat loss, which is not a
  physiological state. A basal metabolic term (~45 W/m^2 of body surface area, ~87 W
  for a 75 kg rider) is now always present, and an ungated insensible-loss floor
  guarantees a plausible minimum during descents. This raised estimates across all
  riding by roughly 15-20%, since the previous model omitted resting metabolism
  everywhere, not only at zero power. Reference table and calibration bands
  regenerated. (#12)

- The hydration field rendered every label at 0sp when the Karoo reported a
  zero-sized `ViewConfig`, a known firmware issue on releases up to ~1.527
  (karoo-ext#26). The field appeared entirely blank with no indication why. Font
  sizes now fall back to a sane base.

### Added

- Extension icon in the release manifest (`iconUrl`), so the companion app no longer
  lists the extension without one. The PNG is rendered from the same droplet path as
  `ic_sweat.xml` by `scripts/render_icon.py`, committed as `docs/icon.png` and
  published as a release asset; CI re-renders it and fails on drift. (#16)
- Debug-only harness activity that drives the real model and renders the real
  Glance view with synthetic inputs, so field layout can be checked without a Karoo.
- Emulator harness (`scripts/emulator.sh`) running a Karoo-sized AVD in Docker.
- Six instrumented tests covering DataStore persistence, including that a corrupt
  payload degrades to defaults rather than taking the data fields down mid-ride.

### Changed

- The manifest description now states that the app must be opened once before the
  data fields appear, that humidity needs the companion phone in range at least once
  per ride, and that estimates are uncalibrated out of the box. All three were
  documented only in the README, which a rider sideloading from the companion app
  never sees. (#16)

## [0.1.0] - 2026-08-15

First release. **Not yet validated on hardware or against real weigh-in data.**

### Added

- Physiological sweat rate model using partitional calorimetry: metabolic heat from
  power, dry heat exchange with bare and clothed skin as parallel paths, respiratory
  losses, evaporative requirement, skin wettedness and evaporative efficiency.
- Heart rate fallback for riders without a power meter, anchored so that 88% of heart
  rate reserve maps to the rider's FTP.
- Five data fields: a graphical hydration field plus numeric drink target, sweat loss,
  sweat rate and projected body mass loss.
- Drink target derived from sweat loss with a configurable replacement fraction and a
  gut absorption rate cap.
- Ambient temperature and humidity from Open-Meteo, cached in DataStore for offline
  use, with an optional on-device temperature sensor source and manual fallbacks.
- In-ride alerts at 2% and 3% projected body mass loss, fired once per threshold.
- FIT developer fields `sweat_loss`, `sweat_rate` and `drink_target` for post-ride
  verification.
- Settings screen for height, gross efficiency, clothing insulation, sweat
  multiplier, replacement fraction, gut cap and fallbacks.
- 67 unit tests covering the model, ride lifecycle, ambient source selection,
  serialisation compatibility and geographic distance.

### Known limitations

- Absolute magnitude is uncertain by roughly plus or minus 30% until the sweat
  multiplier is calibrated against a weigh-in test.
- `AIRFLOW_FACTOR` and `SWEAT_OVERSHOOT` are documented approximations rather than
  derived quantities.
- Humidity requires connectivity at least once; without it the configured fallback is
  used and the field labels itself accordingly.
- No solar radiation term, so clear-sky rides are likely underestimated.

[Unreleased]: https://github.com/timpara/karoo-sweat/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/timpara/karoo-sweat/releases/tag/v0.1.0
