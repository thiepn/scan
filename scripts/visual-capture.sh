#!/usr/bin/env bash
set -uo pipefail

mkdir -p visual-screenshots

gradle :app:connectedDebugAndroidTest   -Pandroid.testInstrumentationRunnerArguments.class=com.thiepn.scan.VisualScreenshotInstrumentedTest   --stacktrace
status=$?

for file in $(adb shell run-as com.thiepn.scan ls files 2>/dev/null | tr -d '\r' | grep '\.png$' || true); do
  adb exec-out run-as com.thiepn.scan cat "files/$file" > "visual-screenshots/$file" || true
done

exit "$status"
