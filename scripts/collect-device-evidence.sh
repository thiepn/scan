#!/usr/bin/env bash
set -euo pipefail

LABEL="${1:-checkpoint}"
OUTPUT_ROOT="${SCAN_DEVICE_EVIDENCE_DIR:-device-evidence}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
SAFE_LABEL="$(printf '%s' "${LABEL}" | tr -cs 'A-Za-z0-9._-' '-')"
OUTPUT_DIR="${OUTPUT_ROOT}/${SAFE_LABEL}-${STAMP}"

ADB=(adb)
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  ADB+=( -s "${ANDROID_SERIAL}" )
fi

command -v adb >/dev/null 2>&1 || {
  echo "adb is required" >&2
  exit 1
}

mkdir -p "${OUTPUT_DIR}"
"${ADB[@]}" wait-for-device

PACKAGE="com.thiepn.scan"

{
  echo "captured_at_utc=${STAMP}"
  echo "label=${LABEL}"
  echo "manufacturer=$("${ADB[@]}" shell getprop ro.product.manufacturer | tr -d '\r')"
  echo "model=$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
  echo "device=$("${ADB[@]}" shell getprop ro.product.device | tr -d '\r')"
  echo "android_release=$("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "build_fingerprint=$("${ADB[@]}" shell getprop ro.build.fingerprint | tr -d '\r')"
  echo "font_scale=$("${ADB[@]}" shell settings get system font_scale | tr -d '\r')"
  echo "accessibility_enabled=$("${ADB[@]}" shell settings get secure accessibility_enabled | tr -d '\r')"
  echo "enabled_accessibility_services=$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
  echo
  echo "=== display ==="
  "${ADB[@]}" shell wm size | tr -d '\r' || true
  "${ADB[@]}" shell wm density | tr -d '\r' || true
  echo
  echo "=== memory ==="
  "${ADB[@]}" shell cat /proc/meminfo | head -n 8 | tr -d '\r' || true
  echo
  echo "=== storage ==="
  "${ADB[@]}" shell df -h /data | tr -d '\r' || true
  echo
  echo "=== package ==="
  "${ADB[@]}" shell dumpsys package "${PACKAGE}" 2>/dev/null |
    grep -E 'versionName=|versionCode=|firstInstallTime=|lastUpdateTime=' |
    tr -d '\r' || true
} > "${OUTPUT_DIR}/device.txt"

"${ADB[@]}" exec-out screencap -p > "${OUTPUT_DIR}/screen.png" || true

UI_DUMP="/sdcard/scan-v1-ui.xml"
if "${ADB[@]}" shell uiautomator dump "${UI_DUMP}" >/dev/null 2>&1; then
  "${ADB[@]}" exec-out cat "${UI_DUMP}" > "${OUTPUT_DIR}/ui.xml" || true
  "${ADB[@]}" shell rm -f "${UI_DUMP}" >/dev/null 2>&1 || true
fi

"${ADB[@]}" shell dumpsys activity activities 2>/dev/null   > "${OUTPUT_DIR}/activity.txt" || true

printf '%s\n' "${OUTPUT_DIR}"
