#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "Usage: $0 <acceptance-dir> <expected-release-sha> <expected-run-id> <output-dir>" >&2
  exit 2
fi

ACCEPTANCE_DIR="$1"
EXPECTED_SHA="$2"
EXPECTED_RUN_ID="$3"
OUTPUT_DIR="$4"

if [[ ! "${EXPECTED_SHA}" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Expected release SHA must be a 40-character Git SHA." >&2
  exit 1
fi
EXPECTED_SHA="${EXPECTED_SHA,,}"

if [[ ! "${EXPECTED_RUN_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "Expected acceptance run ID must be a positive integer." >&2
  exit 1
fi

if [ ! -d "${ACCEPTANCE_DIR}" ]; then
  echo "Acceptance directory does not exist: ${ACCEPTANCE_DIR}" >&2
  exit 1
fi

EXPECTED_FILES=(
  "Scan-v1.0.0-acceptance.apk"
  "Scan-v1.0.0-acceptance.aab"
  "Scan-v1.0.0-pre-v1-baseline.apk"
  "acceptance-checksums.sha256"
  "acceptance-signing.txt"
  "production-acceptance-metadata.txt"
)

mapfile -t ACTUAL_FILES < <(
  find "${ACCEPTANCE_DIR}" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort
)
mapfile -t EXPECTED_SORTED < <(printf '%s\n' "${EXPECTED_FILES[@]}" | LC_ALL=C sort)

if [ "${#ACTUAL_FILES[@]}" -ne "${#EXPECTED_SORTED[@]}" ] || \
   [ "$(printf '%s\n' "${ACTUAL_FILES[@]}")" != "$(printf '%s\n' "${EXPECTED_SORTED[@]}")" ]; then
  echo "Acceptance artifact file set is not exact." >&2
  echo "Expected:" >&2
  printf '  %s\n' "${EXPECTED_SORTED[@]}" >&2
  echo "Actual:" >&2
  printf '  %s\n' "${ACTUAL_FILES[@]}" >&2
  exit 1
fi

CHECKSUMS="${ACCEPTANCE_DIR}/acceptance-checksums.sha256"
METADATA="${ACCEPTANCE_DIR}/production-acceptance-metadata.txt"
SIGNING="${ACCEPTANCE_DIR}/acceptance-signing.txt"

for name in "${EXPECTED_FILES[@]}"; do
  test -f "${ACCEPTANCE_DIR}/${name}"
done

REQUIRED_BINARIES=(
  "Scan-v1.0.0-acceptance.apk"
  "Scan-v1.0.0-acceptance.aab"
  "Scan-v1.0.0-pre-v1-baseline.apk"
)

declare -A MANIFEST_HASHES=()
while read -r hash name extra; do
  if [ -z "${hash:-}" ] && [ -z "${name:-}" ]; then
    continue
  fi
  if [ -n "${extra:-}" ] || [[ ! "${hash:-}" =~ ^[0-9a-fA-F]{64}$ ]] || [ -z "${name:-}" ]; then
    echo "Malformed acceptance checksum line." >&2
    exit 1
  fi

  name="${name#\*}"
  if [[ "${name}" == */* ]] || [[ "${name}" == *\\* ]] || [ "${name}" = "." ] || [ "${name}" = ".." ]; then
    echo "Acceptance checksum contains a non-canonical file name: ${name}" >&2
    exit 1
  fi
  if [ -n "${MANIFEST_HASHES[${name}]+x}" ]; then
    echo "Duplicate acceptance checksum entry: ${name}" >&2
    exit 1
  fi
  MANIFEST_HASHES["${name}"]="${hash,,}"
done < "${CHECKSUMS}"

if [ "${#MANIFEST_HASHES[@]}" -ne "${#REQUIRED_BINARIES[@]}" ]; then
  echo "Acceptance checksum manifest must contain exactly the three accepted binaries." >&2
  exit 1
fi

for name in "${REQUIRED_BINARIES[@]}"; do
  if [ -z "${MANIFEST_HASHES[${name}]+x}" ]; then
    echo "Acceptance checksum manifest is missing ${name}." >&2
    exit 1
  fi
  actual="$(sha256sum "${ACCEPTANCE_DIR}/${name}" | awk '{print $1}')"
  if [ "${actual}" != "${MANIFEST_HASHES[${name}]}" ]; then
    echo "Acceptance checksum mismatch: ${name}" >&2
    exit 1
  fi
done

metadata_value() {
  local key="$1"
  local count
  count="$(grep -c "^${key}=" "${METADATA}" || true)"
  if [ "${count}" -ne 1 ]; then
    echo "Acceptance metadata must contain exactly one ${key}= entry." >&2
    exit 1
  fi
  grep "^${key}=" "${METADATA}" | cut -d= -f2-
}

metadata_sha="$(metadata_value release_sha)"
metadata_run_id="$(metadata_value workflow_run_id)"
metadata_attempt="$(metadata_value workflow_run_attempt)"
metadata_baseline="$(metadata_value pre_v1_baseline_sha)"

if [ "${metadata_sha,,}" != "${EXPECTED_SHA}" ]; then
  echo "Acceptance metadata release SHA does not match the expected release SHA." >&2
  exit 1
fi
if [ "${metadata_run_id}" != "${EXPECTED_RUN_ID}" ]; then
  echo "Acceptance metadata workflow run ID does not match the expected run ID." >&2
  exit 1
fi
if [[ ! "${metadata_attempt}" =~ ^[1-9][0-9]*$ ]]; then
  echo "Acceptance metadata workflow run attempt is invalid." >&2
  exit 1
fi
if [[ ! "${metadata_baseline}" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Acceptance metadata pre-v1 baseline SHA is invalid." >&2
  exit 1
fi

metadata_signer="$(grep -E '^Signer #1 certificate SHA-256 digest:[[:space:]]*[0-9a-fA-F:]+' "${METADATA}" || true)"
signing_signer="$(grep -E '^Signer #1 certificate SHA-256 digest:[[:space:]]*[0-9a-fA-F:]+' "${SIGNING}" || true)"

if [ "$(printf '%s\n' "${metadata_signer}" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Acceptance metadata must contain exactly one signer SHA-256 digest." >&2
  exit 1
fi
if [ "$(printf '%s\n' "${signing_signer}" | sed '/^$/d' | wc -l)" -ne 1 ]; then
  echo "Acceptance signing report must contain exactly one signer SHA-256 digest." >&2
  exit 1
fi
if [ "${metadata_signer}" != "${signing_signer}" ]; then
  echo "Acceptance signer digest differs between metadata and signing report." >&2
  exit 1
fi

signer_digest="$(printf '%s' "${metadata_signer#*:}" | tr -d '[:space:]:' | tr '[:upper:]' '[:lower:]')"
if [[ ! "${signer_digest}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Acceptance signer SHA-256 digest is malformed." >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"
rm -f \
  "${OUTPUT_DIR}/Scan-v1.0.0.apk" \
  "${OUTPUT_DIR}/Scan-v1.0.0.aab" \
  "${OUTPUT_DIR}/release-checksums.sha256" \
  "${OUTPUT_DIR}/release-provenance.txt"

cp "${ACCEPTANCE_DIR}/Scan-v1.0.0-acceptance.apk" "${OUTPUT_DIR}/Scan-v1.0.0.apk"
cp "${ACCEPTANCE_DIR}/Scan-v1.0.0-acceptance.aab" "${OUTPUT_DIR}/Scan-v1.0.0.aab"

cmp -s "${ACCEPTANCE_DIR}/Scan-v1.0.0-acceptance.apk" "${OUTPUT_DIR}/Scan-v1.0.0.apk"
cmp -s "${ACCEPTANCE_DIR}/Scan-v1.0.0-acceptance.aab" "${OUTPUT_DIR}/Scan-v1.0.0.aab"

(
  cd "${OUTPUT_DIR}"
  sha256sum Scan-v1.0.0.apk Scan-v1.0.0.aab > release-checksums.sha256
)

release_apk_hash="$(sha256sum "${OUTPUT_DIR}/Scan-v1.0.0.apk" | awk '{print $1}')"
release_aab_hash="$(sha256sum "${OUTPUT_DIR}/Scan-v1.0.0.aab" | awk '{print $1}')"

if [ "${release_apk_hash}" != "${MANIFEST_HASHES[Scan-v1.0.0-acceptance.apk]}" ]; then
  echo "Promoted APK bytes differ from the accepted APK." >&2
  exit 1
fi
if [ "${release_aab_hash}" != "${MANIFEST_HASHES[Scan-v1.0.0-acceptance.aab]}" ]; then
  echo "Promoted AAB bytes differ from the accepted AAB." >&2
  exit 1
fi

cat > "${OUTPUT_DIR}/release-provenance.txt" <<EOF
release_sha=${EXPECTED_SHA}
acceptance_run_id=${EXPECTED_RUN_ID}
acceptance_artifact_name=scan-v1-production-acceptance-${EXPECTED_SHA}
acceptance_apk_sha256=${release_apk_hash}
acceptance_aab_sha256=${release_aab_hash}
pre_v1_baseline_sha256=${MANIFEST_HASHES[Scan-v1.0.0-pre-v1-baseline.apk]}
signer_sha256=${signer_digest}
EOF

echo "Promoted exact accepted APK/AAB bytes for ${EXPECTED_SHA} from acceptance run ${EXPECTED_RUN_ID}."
