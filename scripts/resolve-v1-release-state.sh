#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <release-tag>" >&2
  exit 2
fi

TAG="$1"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! gh release view "${TAG}" >/dev/null 2>&1; then
  echo "No existing release found; a verified draft will be created." >&2
  echo "create"
  exit 0
fi

DRAFT_STATE="$(gh release view "${TAG}" --json isDraft --jq '.isDraft')"
if [ "${DRAFT_STATE}" = "true" ]; then
  echo "Removing stale draft release from an earlier failed attempt." >&2
  gh release delete "${TAG}" --yes >/dev/null
  echo "create"
  exit 0
fi

if ! bash "${ROOT}/scripts/wait-v1-release-immutable.sh" "${TAG}"; then
  echo "A non-draft release exists but is not immutable. Refusing to reuse or overwrite it." >&2
  exit 1
fi

echo "Found an existing immutable public release. Treating this as a retry/recovery run." >&2
echo "reuse_public"
