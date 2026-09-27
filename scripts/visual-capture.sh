#!/usr/bin/env bash
set -uo pipefail

mkdir -p visual-screenshots
gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.thiepn.scan.VisualScreenshotInstrumentedTest --stacktrace
status=$?

adb pull /sdcard/scan-v1-screenshots/. visual-screenshots/ >/dev/null 2>&1 || true

exit "$status"
