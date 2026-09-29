#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROMOTER="${ROOT}/scripts/promote-v1-accepted-artifact.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

SHA="0123456789abcdef0123456789abcdef01234567"
RUN_ID="123456789"
SIGNER="aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa"

make_fixture() {
  local dir="$1"
  rm -rf "${dir}"
  mkdir -p "${dir}"

  printf 'accepted-apk\n' > "${dir}/Scan-v1.0.0-acceptance.apk"
  printf 'accepted-aab\n' > "${dir}/Scan-v1.0.0-acceptance.aab"
  printf 'baseline-apk\n' > "${dir}/Scan-v1.0.0-pre-v1-baseline.apk"

  (
    cd "${dir}"
    sha256sum \
      Scan-v1.0.0-acceptance.apk \
      Scan-v1.0.0-acceptance.aab \
      Scan-v1.0.0-pre-v1-baseline.apk \
      > acceptance-checksums.sha256
  )

  cat > "${dir}/acceptance-signing.txt" <<EOF
Signer #1 certificate SHA-256 digest: ${SIGNER}
EOF

  cat > "${dir}/production-acceptance-metadata.txt" <<EOF
release_sha=${SHA}
pre_v1_baseline_sha=fedcba9876543210fedcba9876543210fedcba98
workflow_run_id=${RUN_ID}
workflow_run_attempt=1
Signer #1 certificate SHA-256 digest: ${SIGNER}
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

ACCEPT="${TMP}/accept"
OUT="${TMP}/out"

make_fixture "${ACCEPT}"
bash "${PROMOTER}" "${ACCEPT}" "${SHA}" "${RUN_ID}" "${OUT}" >/dev/null
cmp -s "${ACCEPT}/Scan-v1.0.0-acceptance.apk" "${OUT}/Scan-v1.0.0.apk"
cmp -s "${ACCEPT}/Scan-v1.0.0-acceptance.aab" "${OUT}/Scan-v1.0.0.aab"
( cd "${OUT}" && sha256sum -c release-checksums.sha256 >/dev/null )
grep -Fx "release_sha=${SHA}" "${OUT}/release-provenance.txt" >/dev/null
grep -Fx "acceptance_run_id=${RUN_ID}" "${OUT}/release-provenance.txt" >/dev/null

make_fixture "${ACCEPT}"
printf 'tamper\n' >> "${ACCEPT}/Scan-v1.0.0-acceptance.apk"
expect_failure "tampered accepted APK" bash "${PROMOTER}" "${ACCEPT}" "${SHA}" "${RUN_ID}" "${OUT}"

make_fixture "${ACCEPT}"
sed -i "s/workflow_run_id=${RUN_ID}/workflow_run_id=999/" "${ACCEPT}/production-acceptance-metadata.txt"
expect_failure "wrong acceptance run id" bash "${PROMOTER}" "${ACCEPT}" "${SHA}" "${RUN_ID}" "${OUT}"

make_fixture "${ACCEPT}"
sed -i 's/aa:aa:aa:aa/bb:bb:bb:bb/' "${ACCEPT}/acceptance-signing.txt"
expect_failure "signer mismatch" bash "${PROMOTER}" "${ACCEPT}" "${SHA}" "${RUN_ID}" "${OUT}"

make_fixture "${ACCEPT}"
printf 'unexpected\n' > "${ACCEPT}/extra.txt"
expect_failure "unexpected artifact file" bash "${PROMOTER}" "${ACCEPT}" "${SHA}" "${RUN_ID}" "${OUT}"

make_fixture "${ACCEPT}"
rm "${ACCEPT}/Scan-v1.0.0-acceptance.aab"
expect_failure "missing accepted AAB" bash "${PROMOTER}" "${ACCEPT}" "${SHA}" "${RUN_ID}" "${OUT}"

make_fixture "${ACCEPT}"
printf '%s  %s\n' "$(printf bad | sha256sum | awk '{print $1}')" "../escape.apk" >> "${ACCEPT}/acceptance-checksums.sha256"
expect_failure "non-canonical checksum path" bash "${PROMOTER}" "${ACCEPT}" "${SHA}" "${RUN_ID}" "${OUT}"

echo "Accepted-artifact promotion self-test passed."
