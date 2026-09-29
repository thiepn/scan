#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "Usage: $0 <trusted-local-dir> <downloaded-release-dir> <expected-release-sha> <expected-acceptance-run-id>" >&2
  exit 2
fi

LOCAL_DIR="$1"
REMOTE_DIR="$2"
EXPECTED_SHA="$3"
EXPECTED_RUN_ID="$4"

if [[ ! "${EXPECTED_SHA}" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "Expected release SHA must be a 40-character Git SHA." >&2
  exit 1
fi
EXPECTED_SHA="${EXPECTED_SHA,,}"

if [[ ! "${EXPECTED_RUN_ID}" =~ ^[1-9][0-9]*$ ]]; then
  echo "Expected acceptance run ID must be a positive integer." >&2
  exit 1
fi

for dir in "${LOCAL_DIR}" "${REMOTE_DIR}"; do
  if [ ! -d "${dir}" ]; then
    echo "Required directory is missing: ${dir}" >&2
    exit 1
  fi
done

EXPECTED_FILES=(
  "Scan-v1.0.0.apk"
  "Scan-v1.0.0.aab"
  "release-checksums.sha256"
  "release-provenance.txt"
)

assert_exact_file_set() {
  local dir="$1"
  local label="$2"

  mapfile -t actual < <(
    find "${dir}" -mindepth 1 -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort
  )
  mapfile -t expected < <(printf '%s\n' "${EXPECTED_FILES[@]}" | LC_ALL=C sort)

  if [ "${#actual[@]}" -ne "${#expected[@]}" ] || \
     [ "$(printf '%s\n' "${actual[@]}")" != "$(printf '%s\n' "${expected[@]}")" ]; then
    echo "${label} release asset set is not exact." >&2
    echo "Expected:" >&2
    printf '  %s\n' "${expected[@]}" >&2
    echo "Actual:" >&2
    printf '  %s\n' "${actual[@]}" >&2
    exit 1
  fi
}

assert_exact_file_set "${LOCAL_DIR}" "Trusted local"
assert_exact_file_set "${REMOTE_DIR}" "Downloaded remote"

for name in "${EXPECTED_FILES[@]}"; do
  if ! cmp -s "${LOCAL_DIR}/${name}" "${REMOTE_DIR}/${name}"; then
    echo "Remote release asset differs from trusted local asset: ${name}" >&2
    exit 1
  fi
done

validate_checksum_manifest() {
  local dir="$1"
  local manifest="${dir}/release-checksums.sha256"

  declare -A hashes=()
  while read -r hash name extra; do
    if [ -z "${hash:-}" ] && [ -z "${name:-}" ]; then
      continue
    fi
    if [ -n "${extra:-}" ] || [[ ! "${hash:-}" =~ ^[0-9a-fA-F]{64}$ ]] || [ -z "${name:-}" ]; then
      echo "Malformed release checksum line." >&2
      exit 1
    fi

    name="${name#\*}"
    if [[ "${name}" == */* ]] || [[ "${name}" == *\\* ]] || [ "${name}" = "." ] || [ "${name}" = ".." ]; then
      echo "Release checksum contains a non-canonical file name: ${name}" >&2
      exit 1
    fi
    if [ -n "${hashes[${name}]+x}" ]; then
      echo "Duplicate release checksum entry: ${name}" >&2
      exit 1
    fi
    hashes["${name}"]="${hash,,}"
  done < "${manifest}"

  if [ "${#hashes[@]}" -ne 2 ]; then
    echo "Release checksum manifest must contain exactly APK and AAB entries." >&2
    exit 1
  fi

  for name in "Scan-v1.0.0.apk" "Scan-v1.0.0.aab"; do
    if [ -z "${hashes[${name}]+x}" ]; then
      echo "Release checksum manifest is missing ${name}." >&2
      exit 1
    fi
    actual="$(sha256sum "${dir}/${name}" | awk '{print $1}')"
    if [ "${actual}" != "${hashes[${name}]}" ]; then
      echo "Release checksum mismatch: ${name}" >&2
      exit 1
    fi
  done
}

validate_checksum_manifest "${LOCAL_DIR}"
validate_checksum_manifest "${REMOTE_DIR}"

provenance_value() {
  local file="$1"
  local key="$2"
  local count
  count="$(grep -c "^${key}=" "${file}" || true)"
  if [ "${count}" -ne 1 ]; then
    echo "Release provenance must contain exactly one ${key}= entry." >&2
    exit 1
  fi
  grep "^${key}=" "${file}" | cut -d= -f2-
}

PROVENANCE="${REMOTE_DIR}/release-provenance.txt"
release_sha="$(provenance_value "${PROVENANCE}" release_sha)"
acceptance_run_id="$(provenance_value "${PROVENANCE}" acceptance_run_id)"
artifact_name="$(provenance_value "${PROVENANCE}" acceptance_artifact_name)"
apk_hash="$(provenance_value "${PROVENANCE}" acceptance_apk_sha256)"
aab_hash="$(provenance_value "${PROVENANCE}" acceptance_aab_sha256)"
baseline_hash="$(provenance_value "${PROVENANCE}" pre_v1_baseline_sha256)"
signer_hash="$(provenance_value "${PROVENANCE}" signer_sha256)"

if [ "${release_sha,,}" != "${EXPECTED_SHA}" ]; then
  echo "Release provenance SHA does not match expected release SHA." >&2
  exit 1
fi
if [ "${acceptance_run_id}" != "${EXPECTED_RUN_ID}" ]; then
  echo "Release provenance acceptance run ID does not match expected run." >&2
  exit 1
fi
if [ "${artifact_name}" != "scan-v1-production-acceptance-${EXPECTED_SHA}" ]; then
  echo "Release provenance acceptance artifact name is incorrect." >&2
  exit 1
fi

for pair in \
  "acceptance_apk_sha256:${apk_hash}" \
  "acceptance_aab_sha256:${aab_hash}" \
  "pre_v1_baseline_sha256:${baseline_hash}" \
  "signer_sha256:${signer_hash}"
do
  key="${pair%%:*}"
  value="${pair#*:}"
  if [[ ! "${value}" =~ ^[0-9a-fA-F]{64}$ ]]; then
    echo "Release provenance ${key} is not a SHA-256 digest." >&2
    exit 1
  fi
done

actual_apk_hash="$(sha256sum "${REMOTE_DIR}/Scan-v1.0.0.apk" | awk '{print $1}')"
actual_aab_hash="$(sha256sum "${REMOTE_DIR}/Scan-v1.0.0.aab" | awk '{print $1}')"

if [ "${apk_hash,,}" != "${actual_apk_hash}" ]; then
  echo "Release provenance APK hash does not match downloaded APK." >&2
  exit 1
fi
if [ "${aab_hash,,}" != "${actual_aab_hash}" ]; then
  echo "Release provenance AAB hash does not match downloaded AAB." >&2
  exit 1
fi

echo "Verified downloaded release asset set, bytes, checksums and provenance for ${EXPECTED_SHA}."
