#!/usr/bin/env bash
set -euo pipefail

gradle :app:connectedDebugAndroidTest   -Pandroid.testInstrumentationRunnerArguments.class=com.thiepn.scan.VisualScreenshotInstrumentedTest   --stacktrace

mkdir -p visual-screenshots
adb pull /sdcard/scan-v1-screenshots/. visual-screenshots/
