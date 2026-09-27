#!/usr/bin/env bash
set -uo pipefail

mkdir -p visual-screenshots

gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace || exit $?

APP_APK="$(find app/build/outputs/apk/debug -name '*debug.apk' -not -name '*androidTest*' -print -quit)"
TEST_APK="$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)"

test -s "$APP_APK" || { echo "Missing debug APK" >&2; exit 1; }
test -s "$TEST_APK" || { echo "Missing androidTest APK" >&2; exit 1; }

adb install -r "$APP_APK"
adb install -r "$TEST_APK"

set +e
adb shell am instrument -w -r   -e class com.thiepn.scan.VisualScreenshotInstrumentedTest   com.thiepn.scan.test/androidx.test.runner.AndroidJUnitRunner   | tee visual-screenshots/instrumentation.txt
status=${PIPESTATUS[0]}
set -e

for file in $(adb shell run-as com.thiepn.scan ls files 2>/dev/null | tr -d '\r' | grep '\.png$' || true); do
  adb exec-out run-as com.thiepn.scan cat "files/$file" > "visual-screenshots/$file" || true
done

exit "$status"
