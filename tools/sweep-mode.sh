#!/usr/bin/env bash
# Turn the SWEEP block of deploy/arms.env on or off for all three arms, or for the arms named:
#   bash tools/sweep-mode.sh on|off [a] [b] [c]
# Redeploys dd-arm-a, dd-arm-b and dd-arm-c with the image digest they already run, so only the
# Cloud Run settings change (on: SWEEP_MIN_INSTANCES warm instances and startup CPU boost; off:
# MIN_INSTANCES from arms.env). Then runs tools/parity.sh and fails if the arms differ.
# "on" keeps one instance per arm warm, which is billed while it idles: turn it off after a sweep.
# With only some arms named (phase 34 cold rush: "off c"), the arms differ on purpose, so parity is
# reported but does not fail the script.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/vm/common.sh"

MODE="${1:-}"
case "${MODE}" in on|off) ;; *) echo "Usage: $0 on|off [a] [b] [c]" >&2; exit 1 ;; esac
shift
TARGETS=("$@")
[ "${#TARGETS[@]}" -gt 0 ] || TARGETS=(a b c)
for arm in "${TARGETS[@]}"; do
  case "${arm}" in a|b|c) ;; *) echo "Usage: $0 on|off [a] [b] [c] (got arm '${arm}')" >&2; exit 1 ;; esac
done

# The image every arm runs now. They must agree: a sweep compares arms built from the same code.
digest_of() {
  local rev
  rev="$(gv run services describe "dd-arm-$1" --region="${REGION}" --project="${PROJECT_ID}" --format='value(status.latestReadyRevisionName)')"
  [ -n "${rev}" ] || { echo "Error: dd-arm-$1 has no ready revision" >&2; return 1; }
  gv run revisions describe "${rev}" --region="${REGION}" --project="${PROJECT_ID}" --format='value(status.imageDigest)'
}
DIGEST=""
for arm in a b c; do
  d="$(digest_of "${arm}")"
  case "${d}" in *@sha256:*) ;; *) echo "Error: could not read the image digest of dd-arm-${arm} (got '${d}')" >&2; exit 1 ;; esac
  echo "dd-arm-${arm}: ${d}"
  if [ -z "${DIGEST}" ]; then DIGEST="${d}"
  elif [ "${d}" != "${DIGEST}" ]; then
    echo "Error: the arms run different images; deploy one image to all three before changing sweep mode." >&2
    exit 1
  fi
done

for arm in "${TARGETS[@]}"; do
  echo "=== sweep mode ${MODE}: redeploying dd-arm-${arm} with ${DIGEST} ==="
  SWEEP="${MODE}" bash "${REPO_ROOT}/deploy/deploy-arm.sh" "${arm}" "${DIGEST}"
done

echo "=== parity ==="
if [ "${#TARGETS[@]}" -lt 3 ]; then
  if bash "${SCRIPT_DIR}/parity.sh"; then
    echo "parity clean"
  else
    echo "parity differs (expected: sweep mode ${MODE} applied to ${TARGETS[*]} only)"
  fi
  exit 0
fi
if bash "${SCRIPT_DIR}/parity.sh"; then
  echo "parity clean (sweep mode ${MODE})"
else
  echo "Error: parity is not clean after sweep mode ${MODE} (diff above)." >&2
  exit 1
fi
