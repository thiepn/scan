#!/usr/bin/env bash
# Execute inside reactivecircus/android-emulator-runner. The runner invokes each
# script line via /bin/sh; wrapping everything in one bash script preserves
# continuations, error handling, and accurate exit status.
set -euo pipefail

mkdir -p v2-ui-evidence

# The Compose tests check the primary scan action, import affordance, mode
# chooser, and large-text accessibility without invoking external ML Kit UI.
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.thiepn.scan.ScanV2LibraryInstrumentedTest,com.thiepn.scan.LargeTextAccessibilityInstrumentedTest \
  --stacktrace

adb shell pm clear com.thiepn.scan
adb shell settings put system font_scale 1.0
adb shell cmd uimode night no
adb shell am start -W -n com.thiepn.scan/.MainActivity
sleep 5
adb exec-out screencap -p > v2-ui-evidence/library-light.png

adb shell cmd uimode night yes
adb shell am force-stop com.thiepn.scan
adb shell am start -W -n com.thiepn.scan/.MainActivity
sleep 5
adb exec-out screencap -p > v2-ui-evidence/library-dark.png

adb shell cmd uimode night no
adb shell settings put system font_scale 2.0
adb shell am force-stop com.thiepn.scan
adb shell am start -W -n com.thiepn.scan/.MainActivity
sleep 5
adb exec-out screencap -p > v2-ui-evidence/library-font200.png

for screenshot in v2-ui-evidence/*.png; do
  test -s "$screenshot"
  file "$screenshot" | grep -q 'PNG image data'
done
sha256sum v2-ui-evidence/*.png > v2-ui-evidence/checksums.sha256
