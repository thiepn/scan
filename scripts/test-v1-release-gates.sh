#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

FINALIZER="${ROOT}/scripts/finalize-v1-release.ps1"
READINESS="${ROOT}/scripts/check-play-readiness.ps1"
RELEASE_WORKFLOW="${ROOT}/.github/workflows/release.yml"

require_literal() {
  local file="$1"
  local literal="$2"
  local label="$3"

  if ! grep -F -- "${literal}" "${file}" >/dev/null; then
    echo "Missing release-gate contract: ${label}" >&2
    echo "file: ${file}" >&2
    exit 1
  fi
}

require_literal   "${FINALIZER}"   'Get-RunCount "play-listing.yml" $MainSha'   "finalizer must require exact-main Google Play Listing Validation"

require_literal   "${READINESS}"   'Get-SuccessfulRunCount "play-listing.yml" $mainSha'   "Play readiness audit must require exact-main Google Play Listing Validation"

require_literal   "${RELEASE_WORKFLOW}"   'actions/workflows/play-listing.yml/runs?branch=main&head_sha=${RELEASE_SHA}&status=success'   "tag publication workflow must query exact release-SHA Play validation"

require_literal   "${RELEASE_WORKFLOW}"   'select(.event == "push" or .event == "workflow_dispatch")'   "tag publication workflow must only trust push/workflow_dispatch Play validation"

echo "v1 release-gate contract self-test passed."
