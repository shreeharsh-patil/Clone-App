#!/usr/bin/env bash
set -euo pipefail
api="${1:?API argument required}"
serial=emulator-5554
avd="clone-apps-ci-$api"
ANDROID_AVD_HOME=$(mktemp -d "${RUNNER_TEMP:-/tmp}/clone-apps-avds.XXXXXX")
export ANDROID_AVD_HOME
avd_dir="$ANDROID_AVD_HOME/$avd.avd"
emulator="$ANDROID_HOME/emulator/emulator"
mkdir -p build/device-evidence
echo no | avdmanager create avd --name "$avd" --path "$avd_dir" \
    --package "system-images;android-$api;google_apis;x86_64" --device pixel_7
test -s "$avd_dir/config.ini"
# Some command-line tools register the AVD in their own preferences directory.
if [ ! -f "$ANDROID_AVD_HOME/$avd.ini" ]; then
    printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-%s\n' "$avd_dir" "$api" > "$ANDROID_AVD_HOME/$avd.ini"
fi
"$emulator" -list-avds | grep -Fx "$avd"

"$emulator" -avd "$avd" -port 5554 -no-window -noaudio -no-snapshot -no-boot-anim \
    -gpu swiftshader -memory 4096 -cores 2 > build/device-evidence/emulator.log 2>&1 &
emulator_pid=$!
collect_diagnostics() {
    timeout 30 adb -s "$serial" logcat -d > build/device-evidence/runtime.log 2>&1 || true
    timeout 30 adb -s "$serial" shell dumpsys meminfo > build/device-evidence/memory.txt 2>&1 || true
    kill "$emulator_pid" 2>/dev/null || true
}
trap collect_diagnostics EXIT

python3 -u scripts/test-virtual-runtime.py --serial "$serial" --api "$api" --ci-emulator --reboot --host-update \
    --probe-apk build/probe-updates/probe-v1.apk --guest-update-apk build/probe-updates/probe-v2.apk
