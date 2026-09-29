#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERIFIER="${ROOT}/scripts/verify-release-publication.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

SHA="0123456789abcdef0123456789abcdef01234567"
RUN_ID="123456789"
SIGNER="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
BASELINE_HASH="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

make_fixture() {
  local dir="$1"
  rm -rf "${dir}"
  mkdir -p "${dir}"

  printf 'accepted-apk\n' > "${dir}/Scan-v1.0.0.apk"
  printf 'accepted-aab\n' > "${dir}/Scan-v1.0.0.aab"

  (
    cd "${dir}"
    sha256sum Scan-v1.0.0.apk Scan-v1.0.0.aab > release-checksums.sha256
  )

  local apk_hash
  local aab_hash
  apk_hash="$(sha256sum "${dir}/Scan-v1.0.0.apk" | awk '{print $1}')"
  aab_hash="$(sha256sum "${dir}/Scan-v1.0.0.aab" | awk '{print $1}')"

  cat > "${dir}/release-provenance.txt" <<EOF
release_sha=${SHA}
acceptance_run_id=${RUN_ID}
acceptance_artifact_name=scan-v1-production-acceptance-${SHA}
acceptance_apk_sha256=${apk_hash}
acceptance_aab_sha256=${aab_hash}
pre_v1_baseline_sha256=${BASELINE_HASH}
signer_sha256=${SIGNER}
EOF
}

expect_failure() {
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "Expected failure did not occur: ${label}" >&2
    exit 1
  fi
}

LOCAL="${TMP}/local"
REMOTE="${TMP}/remote"

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}" >/dev/null

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
printf 'tamper\n' >> "${REMOTE}/Scan-v1.0.0.apk"
expect_failure "tampered downloaded APK" bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}"

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
printf 'extra\n' > "${REMOTE}/unexpected.txt"
expect_failure "unexpected remote asset" bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}"

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
rm "${REMOTE}/Scan-v1.0.0.aab"
expect_failure "missing remote AAB" bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}"

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
sed -i "s/release_sha=${SHA}/release_sha=fedcba9876543210fedcba9876543210fedcba98/" "${REMOTE}/release-provenance.txt"
expect_failure "wrong remote release SHA" bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}"

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
sed -i "s/acceptance_run_id=${RUN_ID}/acceptance_run_id=999/" "${REMOTE}/release-provenance.txt"
expect_failure "wrong remote acceptance run" bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}"

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
printf '%s  %s\n' "$(printf bad | sha256sum | awk '{print $1}')" "extra.apk" >> "${REMOTE}/release-checksums.sha256"
expect_failure "extra checksum entry" bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}"

make_fixture "${LOCAL}"
cp -a "${LOCAL}" "${REMOTE}"
sed -i 's/signer_sha256=.*/signer_sha256=not-a-hash/' "${REMOTE}/release-provenance.txt"
expect_failure "malformed signer provenance" bash "${VERIFIER}" "${LOCAL}" "${REMOTE}" "${SHA}" "${RUN_ID}"

echo "Release-publication verification self-test passed."
