#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOLVER="${ROOT}/scripts/resolve-v1-release-state.sh"
WAITER="${ROOT}/scripts/wait-v1-release-immutable.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

mkdir -p "${TMP}/bin"

cat > "${TMP}/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

scenario="${SCENARIO:?SCENARIO is required}"
state_file="${STATE_FILE:?STATE_FILE is required}"

if [ "${1:-}" = "release" ] && [ "${2:-}" = "view" ]; then
  if [ "${scenario}" = "missing" ]; then
    exit 1
  fi
  if printf '%s\n' "$@" | grep -q -- '--json'; then
    if [ "${scenario}" = "draft" ]; then
      printf 'true\n'
    else
      printf 'false\n'
    fi
  fi
  exit 0
fi

if [ "${1:-}" = "release" ] && [ "${2:-}" = "delete" ]; then
  printf 'deleted\n' >> "${state_file}"
  exit 0
fi

if [ "${1:-}" = "api" ]; then
  count=0
  if [ -f "${state_file}.count" ]; then
    count="$(cat "${state_file}.count")"
  fi
  count=$((count + 1))
  printf '%s\n' "${count}" > "${state_file}.count"

  case "${scenario}" in
    public_immutable)
      printf 'true\n'
      ;;
    public_mutable)
      printf 'false\n'
      ;;
    public_delayed)
      if [ "${count}" -lt 3 ]; then
        printf 'false\n'
      else
        printf 'true\n'
      fi
      ;;
    *)
      printf 'false\n'
      ;;
  esac
  exit 0
fi

echo "Unexpected gh invocation: $*" >&2
exit 97
EOF
chmod +x "${TMP}/bin/gh"

export PATH="${TMP}/bin:${PATH}"
export GITHUB_REPOSITORY="thiepn/scan"
export SCAN_RELEASE_IMMUTABLE_ATTEMPTS=4
export SCAN_RELEASE_IMMUTABLE_RETRY_DELAY_SECONDS=0

run_resolver() {
  local scenario="$1"
  local state="${TMP}/${scenario}"
  SCENARIO="${scenario}" STATE_FILE="${state}" bash "${RESOLVER}" v1.0.0
}

expect_failure() {
  local label="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    echo "Expected failure did not occur: ${label}" >&2
    exit 1
  fi
}

result="$(run_resolver missing)"
test "${result}" = "create"

result="$(run_resolver draft)"
test "${result}" = "create"
grep -Fx "deleted" "${TMP}/draft" >/dev/null

result="$(run_resolver public_immutable)"
test "${result}" = "reuse_public"

result="$(run_resolver public_delayed)"
test "${result}" = "reuse_public"
test "$(cat "${TMP}/public_delayed.count")" -eq 3

expect_failure "mutable public release" env \
  SCENARIO=public_mutable \
  STATE_FILE="${TMP}/public_mutable" \
  GITHUB_REPOSITORY="${GITHUB_REPOSITORY}" \
  SCAN_RELEASE_IMMUTABLE_ATTEMPTS=2 \
  SCAN_RELEASE_IMMUTABLE_RETRY_DELAY_SECONDS=0 \
  PATH="${PATH}" \
  bash "${RESOLVER}" v1.0.0

SCENARIO=public_delayed \
STATE_FILE="${TMP}/waiter_delayed" \
SCAN_RELEASE_IMMUTABLE_ATTEMPTS=4 \
SCAN_RELEASE_IMMUTABLE_RETRY_DELAY_SECONDS=0 \
bash "${WAITER}" v1.0.0 >/dev/null

test "$(cat "${TMP}/waiter_delayed.count")" -eq 3

echo "v1 release recovery self-test passed."
