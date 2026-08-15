#!/usr/bin/env bash
# Run a Karoo-like Android emulator and exercise the app against it.
#
# What this can verify: the app installs and launches, the settings screen works,
# the foreground service starts, DataStore persistence behaves, and the data field
# composes and stays legible at Karoo dimensions.
#
# What it CANNOT verify: anything involving the Karoo system service. KarooSystemService
# binds to a proprietary Karoo OS component that does not exist on a stock emulator,
# so data streams, ride lifecycle, field registration and FIT writing are untestable
# here. Only a real device covers those.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The host user usually is not in the kvm group, but docker runs as root and can
# pass the device through, so acceleration works without touching group membership.
docker build -q -t karoo-emu "$REPO/scripts/emulator" >/dev/null

docker rm -f karoo-emu >/dev/null 2>&1 || true
docker run -d --name karoo-emu --device /dev/kvm \
  -v "$ANDROID_HOME:/sdk" -v "$AVD_HOME:/avd" -v "$REPO:/work" \
  karoo-emu \
  emulator -avd karoo -no-window -no-audio -no-boot-anim -no-snapshot \
           -gpu swiftshader_indirect -accel on

echo "waiting for boot..."
docker exec karoo-emu bash -c '
  for i in $(seq 1 60); do
    [ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = "1" ] && exit 0
    sleep 10
  done
  exit 1'

docker exec karoo-emu bash -c '
  D="adb -s emulator-5554"
  $D install -r -t /work/app/build/outputs/apk/debug/app-debug.apk
  $D install -r -t /work/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  $D shell am instrument -w \
    de.timpara.karoosweat.test/androidx.test.runner.AndroidJUnitRunner'

echo
echo "Emulator is running. Useful next steps:"
echo "  docker exec karoo-emu adb -s emulator-5554 shell am start -n de.timpara.karoosweat/.ui.HarnessActivity"
echo "  docker exec karoo-emu adb -s emulator-5554 shell screencap -p /sdcard/s.png"
echo "  docker exec karoo-emu adb -s emulator-5554 pull /sdcard/s.png /work/screenshot.png"
echo "  docker rm -f karoo-emu   # when finished"
