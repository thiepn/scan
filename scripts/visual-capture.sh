#!/usr/bin/env bash
set -euo pipefail

mkdir -p visual-screenshots

gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace

APP_APK="$(find app/build/outputs/apk/debug -name '*debug.apk' -not -name '*androidTest*' -print -quit)"
TEST_APK="$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)"

test -s "$APP_APK"
test -s "$TEST_APK"

adb install -r "$APP_APK"
adb install -r "$TEST_APK"

set +e
adb shell am instrument -w -r   -e class com.thiepn.scan.AdvancedVisualComposablesInstrumentedTest   com.thiepn.scan.test/androidx.test.runner.AndroidJUnitRunner   | tee visual-screenshots/instrumentation.txt
instrument_status=${PIPESTATUS[0]}
set -e

for package in com.thiepn.scan.test com.thiepn.scan; do
  for file in $(adb shell run-as "$package" ls files 2>/dev/null | tr -d '\r' | grep '\.png$' || true); do
    adb exec-out run-as "$package" cat "files/$file" > "visual-screenshots/$file" || true
  done
done

if grep -q 'FAILURES!!!' visual-screenshots/instrumentation.txt; then
  exit 1
fi

exit "$instrument_status"
