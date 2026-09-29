#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <release-tag>" >&2
  exit 2
fi

TAG="$1"
REPOSITORY="${GITHUB_REPOSITORY:-thiepn/scan}"
ATTEMPTS="${SCAN_RELEASE_IMMUTABLE_ATTEMPTS:-12}"
DELAY="${SCAN_RELEASE_IMMUTABLE_RETRY_DELAY_SECONDS:-5}"

if [[ ! "${ATTEMPTS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "SCAN_RELEASE_IMMUTABLE_ATTEMPTS must be a positive integer." >&2
  exit 2
fi
if [[ ! "${DELAY}" =~ ^[0-9]+$ ]]; then
  echo "SCAN_RELEASE_IMMUTABLE_RETRY_DELAY_SECONDS must be a non-negative integer." >&2
  exit 2
fi

RELEASE_ENDPOINT="/repos/${REPOSITORY}/releases/tags/${TAG}"

for attempt in $(seq 1 "${ATTEMPTS}"); do
  IMMUTABLE="$(gh api \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2026-03-10" \
    "${RELEASE_ENDPOINT}" \
    --jq '.immutable')"

  if [ "${IMMUTABLE}" = "true" ]; then
    echo "GitHub reports ${TAG} as immutable." >&2
    exit 0
  fi

  if [ "${attempt}" -lt "${ATTEMPTS}" ]; then
    echo "${TAG} is not reported immutable yet (attempt ${attempt}/${ATTEMPTS}); retrying..." >&2
    if [ "${DELAY}" -gt 0 ]; then
      sleep "${DELAY}"
    fi
  fi
done

echo "${TAG} did not become immutable after ${ATTEMPTS} checks." >&2
exit 1
