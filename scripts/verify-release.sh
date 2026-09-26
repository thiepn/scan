#!/usr/bin/env bash
set -euo pipefail

APK="${1:?usage: verify-release.sh <apk> <aab> [checksums-output]}"
AAB="${2:?usage: verify-release.sh <apk> <aab> [checksums-output]}"
CHECKSUMS="${3:-release-checksums.sha256}"

BUILD_TOOLS_DIR="$(find "${ANDROID_HOME}/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
APKSIGNER="${BUILD_TOOLS_DIR}/apksigner"
APK_ANALYZER="${ANDROID_HOME}/cmdline-tools/latest/bin/apkanalyzer"

test -x "${APKSIGNER}"
test -x "${APK_ANALYZER}"
test -s "${APK}"
test -s "${AAB}"

"${APKSIGNER}" verify --verbose --print-certs "${APK}"
jarsigner -verify "${AAB}" >/dev/null

APPLICATION_ID="$("${APK_ANALYZER}" manifest application-id "${APK}")"
VERSION_NAME="$("${APK_ANALYZER}" manifest version-name "${APK}")"
VERSION_CODE="$("${APK_ANALYZER}" manifest version-code "${APK}")"
MIN_SDK="$("${APK_ANALYZER}" manifest min-sdk "${APK}")"
TARGET_SDK="$("${APK_ANALYZER}" manifest target-sdk "${APK}")"
DEBUGGABLE="$("${APK_ANALYZER}" manifest debuggable "${APK}")"
MANIFEST="$("${APK_ANALYZER}" manifest print "${APK}")"

test "${APPLICATION_ID}" = "com.thiepn.scan"
test "${VERSION_NAME}" = "1.0.0"
test "${VERSION_CODE}" = "1"
test "${MIN_SDK}" = "26"
test "${TARGET_SDK}" = "36"
test "${DEBUGGABLE}" = "false"

grep -Fq 'android:allowBackup="false"' <<<"${MANIFEST}"
grep -Fq 'android:usesCleartextTraffic="false"' <<<"${MANIFEST}"
grep -Fq 'android:name="androidx.core.content.FileProvider"' <<<"${MANIFEST}"

PERMISSIONS="$("${APK_ANALYZER}" manifest permissions "${APK}")"
FORBIDDEN_PERMISSIONS=(
  "android.permission.INTERNET"
  "android.permission.READ_EXTERNAL_STORAGE"
  "android.permission.WRITE_EXTERNAL_STORAGE"
  "android.permission.MANAGE_EXTERNAL_STORAGE"
  "android.permission.REQUEST_INSTALL_PACKAGES"
)

for permission in "${FORBIDDEN_PERMISSIONS[@]}"; do
  if grep -Fqx "${permission}" <<<"${PERMISSIONS}"; then
    echo "Forbidden permission found in release APK: ${permission}" >&2
    exit 1
  fi
done

sha256sum "${APK}" "${AAB}" > "${CHECKSUMS}"
cat "${CHECKSUMS}"
