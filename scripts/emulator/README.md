# Emulator harness

`../emulator.sh` builds this image, boots an AVD configured to match the Karoo
screen (480x800, density 320, matching the real 3.2 inch 292 ppi panel), installs
the debug and androidTest APKs, and runs the instrumented tests.

Create the AVD once:

```bash
sdkmanager "emulator" "system-images;android-30;google_apis;x86_64"
avdmanager create avd -n karoo -k "system-images;android-30;google_apis;x86_64" -d pixel
```

then set in `~/.android/avd/karoo.avd/config.ini`:

```ini
hw.lcd.width=480
hw.lcd.height=800
hw.lcd.density=320
hw.ramSize=2048
```

API 30 is a guess. Karoo 2 is documented as Android 8 (API 26); the Karoo 3 OS
version is not published anywhere I could find. Check the real device with
`adb shell getprop ro.build.version.sdk` and adjust.
