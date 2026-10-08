#!/usr/bin/env bash
# Execute inside reactivecircus/android-emulator-runner. The runner invokes each
# script line via /bin/sh; wrapping everything in one bash script preserves
# continuations, error handling, and accurate exit status.
set -euo pipefail

mkdir -p v2-ui-evidence

# Verify P22 Compose actions plus P24 on-device file/storage, v22 reopen,
# interrupted capture, backup restore, and legacy schema migration.
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.thiepn.scan.ScanV2LibraryInstrumentedTest,com.thiepn.scan.LargeTextAccessibilityInstrumentedTest,com.thiepn.scan.data.FileStoreDurabilityInstrumentedTest,com.thiepn.scan.data.V22DurabilityInstrumentedTest,com.thiepn.scan.data.InterruptedCaptureRecoveryInstrumentedTest,com.thiepn.scan.data.SecureBackupInstrumentedTest,com.thiepn.scan.data.SecureBackupProvenanceInstrumentedTest,com.thiepn.scan.data.LegacyV1MigrationInstrumentedTest \
  --stacktrace

# connectedAndroidTest can uninstall app packages after running instrumentation.
# Install the prebuilt APK explicitly before visual evidence capture.
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
