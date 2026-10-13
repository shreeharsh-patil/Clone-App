#!/usr/bin/env bash
set -euo pipefail
api="${1:?API argument required}"
adb install -r isolation-probe/build/outputs/apk/debug/isolation-probe-debug.apk
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.profileFlow=true --console=plain
if [ "$api" = '36' ]; then
  mkdir -p build/device-evidence
  adb pull /sdcard/Android/data/org.parallelnest.app/files/evidence build/device-evidence
  python3 scripts/verify-device-recovery.py
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb shell am instrument -w -r -e class org.parallelnest.app.RecoveryDeletionTest -e deleteFlow true org.parallelnest.app.test/androidx.test.runner.AndroidJUnitRunner | tee build/deletion-result.txt
  grep -q 'OK (1 test)' build/deletion-result.txt
fi
