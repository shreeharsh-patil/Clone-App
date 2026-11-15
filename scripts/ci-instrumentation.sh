#!/usr/bin/env bash
set -euo pipefail
api="${1:?API argument required}"
serial=emulator-5554
avd="clone-apps-ci-$api"
mkdir -p build/device-evidence
echo no | avdmanager create avd --name "$avd" --package "system-images;android-$api;google_apis;x86_64" --device pixel_7

"$ANDROID_HOME/emulator/emulator" -avd "$avd" -port 5554 -no-window -noaudio -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect -memory 4096 -cores 2 > build/device-evidence/emulator.log 2>&1 &
emulator_pid=$!
collect_diagnostics() {
    timeout 30 adb -s "$serial" logcat -d > build/device-evidence/runtime.log 2>&1 || true
    timeout 30 adb -s "$serial" shell dumpsys meminfo > build/device-evidence/memory.txt 2>&1 || true
    kill "$emulator_pid" 2>/dev/null || true
}
trap collect_diagnostics EXIT

python3 -u scripts/test-virtual-runtime.py --serial "$serial" --api "$api" --ci-emulator --reboot --host-update \
    --probe-apk build/probe-updates/probe-v1.apk --guest-update-apk build/probe-updates/probe-v2.apk
