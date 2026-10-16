#!/usr/bin/env bash
set -euo pipefail
api="${1:?API argument required}"
mkdir -p build/device-evidence
# Capture test-emulator diagnostics before the runner tears it down, including on failure.
trap 'adb logcat -d > build/device-evidence/runtime.log 2>&1 || true; adb shell dumpsys meminfo > build/device-evidence/memory.txt 2>&1 || true' EXIT
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :isolation-probe:assembleDebug --console=plain
# Preserve the baseline while producing a genuinely newer first-party guest APK.
mkdir -p build/probe-updates
cp isolation-probe/build/outputs/apk/debug/isolation-probe-debug.apk build/probe-updates/probe-v1.apk
./gradlew :isolation-probe:assembleDebug -PprobeVersionCode=2 --console=plain
cp isolation-probe/build/outputs/apk/debug/isolation-probe-debug.apk build/probe-updates/probe-v2.apk
cp build/probe-updates/probe-v1.apk isolation-probe/build/outputs/apk/debug/isolation-probe-debug.apk
python3 scripts/test-virtual-runtime.py --api "$api" --reboot --host-update --guest-update-apk build/probe-updates/probe-v2.apk
