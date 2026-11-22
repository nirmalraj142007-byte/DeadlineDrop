#!/usr/bin/env bash
# Shared helpers for tools/faults/<fault>.sh. Source it; do not run it.
# Every fault script is called by tools/run.sh once per stage (FAULT_STAGE = pre, inject or post);
# see the header of tools/run.sh for what each stage gets.

FAULTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${FAULTS_DIR}/../vm/common.sh"
# shellcheck source=deploy/arms.env
source "${REPO_ROOT}/deploy/arms.env"

PY="$(find_python)"

now_ms() { "${PY}" -c 'import time; print(int(time.time() * 1000))' | tr -d '\r'; }
iso_ms() { # iso_ms <epoch ms> -> 2026-11-22T10:00:00.123Z
  "${PY}" -c 'import datetime, sys; t = datetime.datetime.fromtimestamp(int(sys.argv[1]) / 1000, datetime.timezone.utc); print(t.strftime("%Y-%m-%dT%H:%M:%S.") + f"{t.microsecond // 1000:03d}Z")' "$1" | tr -d '\r'
}

# sleep_until_ms <epoch ms>: returns at once if that moment has passed (and says so).
sleep_until_ms() {
  local target="$1" now wait_ms
  now="$(now_ms)"
  wait_ms=$(( target - now ))
  if [ "${wait_ms}" -le 0 ]; then
    echo "target $(iso_ms "${target}") already passed by ${wait_ms#-} ms"
    return 0
  fi
  echo "sleeping ${wait_ms} ms until $(iso_ms "${target}")"
  sleep "$("${PY}" -c "print(${wait_ms} / 1000)" | tr -d '\r')"
}

# json_write <file> <python dict literal built from argv>: write a small JSON record.
# Usage: json_write out.json key1 value1 key2 value2 ... (values are strings; "null" stays null,
# integers stay integers).
json_write() {
  local out="$1"; shift
  "${PY}" - "$(to_native_path "${out}")" "$@" <<'PYEOF'
import json
import os
import sys

out, rest = sys.argv[1], sys.argv[2:]
record = {}
if os.path.exists(out):
    with open(out, encoding="utf-8") as f:
        record = json.load(f)
for k, v in zip(rest[0::2], rest[1::2]):
    if v == "null":
        record[k] = None
    elif v.lstrip("-").isdigit():
        record[k] = int(v)
    else:
        record[k] = v
with open(out, "w", encoding="utf-8") as f:
    json.dump(record, f, indent=2)
    f.write("\n")
PYEOF
}

# The image digest (repo@sha256:...) an arm runs now; a fault redeploy keeps it, so only settings change.
arm_digest() {
  local rev d
  rev="$(gv run services describe "dd-arm-$1" --region="${REGION}" --project="${PROJECT_ID}" --format='value(status.latestReadyRevisionName)')"
  [ -n "${rev}" ] || { echo "Error: dd-arm-$1 has no ready revision" >&2; return 1; }
  d="$(gv run revisions describe "${rev}" --region="${REGION}" --project="${PROJECT_ID}" --format='value(status.imageDigest)')"
  case "${d}" in *@sha256:*) echo "${d}" ;; *) echo "Error: could not read the image digest of dd-arm-$1 (got '${d}')" >&2; return 1 ;; esac
}

# Sweep mode the arm runs now (on when min-instances equals SWEEP_MIN_INSTANCES), so a fault
# redeploy does not silently switch it off.
arm_sweep_mode() {
  local min
  min="$(gv run services describe "dd-arm-$1" --region="${REGION}" --project="${PROJECT_ID}" \
    --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/minScale")')"
  if [ "${min:-0}" = "${SWEEP_MIN_INSTANCES}" ] && [ "${SWEEP_MIN_INSTANCES}" != "${MIN_INSTANCES}" ]; then echo on; else echo off; fi
}

# redeploy_arm <arm> VAR=value...: redeploy with the same image and sweep mode, with the given env
# overrides for deploy/deploy-arm.sh (CHAOS_ENABLED, RECEIPT_ENABLED).
redeploy_arm() {
  local arm="$1"; shift
  local digest sweep
  digest="$(arm_digest "${arm}")" || return 1
  sweep="$(arm_sweep_mode "${arm}")"
  echo "redeploying dd-arm-${arm} (${digest}, sweep mode ${sweep}) with $*"
  env SWEEP="${sweep}" "$@" bash "${REPO_ROOT}/deploy/deploy-arm.sh" "${arm}" "${digest}"
}

# admin_token <base url>: log in as the seeded admin; prints the access token.
admin_token() {
  local pw
  pw="$(gv secrets versions access latest --secret=dd-seed-password --project="${PROJECT_ID}")"
  curl -s -f --max-time 30 -X POST "$1/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"admin@dd.local\",\"password\":\"${pw}\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4
}

# chaos_status <base url> <token>: HTTP code of GET /admin/chaos/status (200 on, 404 off).
chaos_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 30 -H "Authorization: Bearer $2" "$1/admin/chaos/status"
}

require_stage() {
  case "${FAULT_STAGE:-}" in pre|inject|post) ;; *) echo "Error: FAULT_STAGE must be pre, inject or post (got '${FAULT_STAGE:-}'); run through tools/run.sh" >&2; exit 1 ;; esac
  : "${ARM:?ARM missing}" "${RUN_DIR:?RUN_DIR missing}"
}
