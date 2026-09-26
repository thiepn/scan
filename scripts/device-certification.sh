#!/usr/bin/env bash
set -euo pipefail

API_LEVEL="${1:?usage: device-certification.sh <api-level>}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"

BASE_APK="$(find "${RUNNER_TEMP}/scan-baseline/app/build/outputs/apk/debug" -name '*.apk' -print -quit)"
CANDIDATE_DEBUG="$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)"
RELEASE_APK="$(find "${RUNNER_TEMP}/release-candidate" -name '*.apk' -print -quit)"

for artifact in "${BASE_APK}" "${CANDIDATE_DEBUG}" "${RELEASE_APK}"; do
  if [[ -z "${artifact}" || ! -s "${artifact}" ]]; then
    echo "Missing certification APK: ${artifact:-<empty>}" >&2
    exit 1
  fi
done

wait_for_database() {
  local attempt
  for attempt in {1..20}; do
    if adb shell run-as com.thiepn.scan ls databases/scan.db >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done

  echo "scan.db was not created after launch" >&2
  adb shell run-as com.thiepn.scan ls -la databases >&2 || true
  return 1
}

uninstall_scan() {
  adb shell am force-stop com.thiepn.scan >/dev/null 2>&1 || true
  adb shell am force-stop com.thiepn.scan.test >/dev/null 2>&1 || true
  adb uninstall com.thiepn.scan.test >/dev/null 2>&1 || true

  local attempt
  for attempt in {1..5}; do
    if ! adb shell pm path com.thiepn.scan 2>/dev/null | grep -q '^package:'; then
      return 0
    fi

    if adb uninstall com.thiepn.scan 2>/dev/null | grep -q '^Success'; then
      return 0
    fi
    sleep 1
  done

  echo "Could not uninstall com.thiepn.scan cleanly" >&2
  adb shell pm path com.thiepn.scan >&2 || true
  return 1
}

# Upgrade-path smoke.
adb install "${BASE_APK}"
adb shell am start -W -n com.thiepn.scan/.MainActivity
wait_for_database

adb shell am force-stop com.thiepn.scan
adb install -r "${CANDIDATE_DEBUG}"
adb shell am start -W -n com.thiepn.scan/.MainActivity
wait_for_database

# Install the independently signed release artifact only after the upgrade
# state has been certified.
uninstall_scan

adb install "${RELEASE_APK}"
adb shell am start -W -n com.thiepn.scan/.MainActivity
adb shell pidof com.thiepn.scan >/dev/null

adb shell settings put system font_scale 2.00
adb shell am force-stop com.thiepn.scan
adb shell am start -W -n com.thiepn.scan/.MainActivity
adb shell uiautomator dump /sdcard/scan-window.xml
adb pull /sdcard/scan-window.xml "${RUNNER_TEMP}/scan-window-${API_LEVEL}.xml" >/dev/null
grep -q 'text="Scan"' "${RUNNER_TEMP}/scan-window-${API_LEVEL}.xml"

# Instrumentation runs last on a clean debug install so the Android test
# runner owns package installation/removal and cannot invalidate the upgrade
# or release-install checks above.
uninstall_scan
gradle :app:connectedDebugAndroidTest --stacktrace
