#!/usr/bin/env bash
# Preflight for one arm before a counted or dry run:
#   OUT=path/to/preflight.json bash tools/preflight.sh <a|b|c>
# (a) parity of all arms, (b) Cloud SQL connection budget (review G8), (c) clock skew between
# each k6 VM and the arm's server (review G3, abort above 100 ms), (d) warm service (< 200 ms).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/vm/common.sh"

ARM="${1:-}"
case "${ARM}" in a|b|c) ;; *) echo "Usage: OUT=<file> $0 <a|b|c>" >&2; exit 1 ;; esac
OUT="${OUT:-preflight.json}"
PROXY_PORT="${PROXY_PORT:-15432}"
SKEW_LIMIT_MS=100
WARM_LIMIT_MS=200
SAMPLES=10
CONN_RESERVE=5

PY="$(find_python)"
TMP="$(mktemp -d)"
PROXY_PID=""
cleanup() {
  if [ -n "${PROXY_PID}" ]; then kill "${PROXY_PID}" 2>/dev/null || true; fi
  rm -rf "${TMP}"
}
trap cleanup EXIT

fail() { echo "PREFLIGHT FAILED: $*" >&2; exit 1; }

# ---- (a) parity ----
echo "== (a) parity =="
bash "${SCRIPT_DIR}/parity.sh" || fail "arms A, B and C are not identical (diff above)"
echo "parity clean"

# ---- (b) connection budget ----
echo "== (b) connection budget =="
SERVICES_JSON="${TMP}/services.json"
gv run services list --region="${REGION}" --project="${PROJECT_ID}" --format=json > "${SERVICES_JSON}"
BUDGET_JSON="${TMP}/budget.json"
"${PY}" - "$(to_native_path "${SERVICES_JSON}")" "$(to_native_path "${BUDGET_JSON}")" <<'PYEOF' || fail "could not read maxScale / DB_POOL_MAX from the deployed services"
import json
import os
import sys

# Test hook: PREFLIGHT_MAX_INSTANCES_OVERRIDE replaces both limits on every arm.
override = os.environ.get("PREFLIGHT_MAX_INSTANCES_OVERRIDE")
with open(sys.argv[1], encoding="utf-8") as f:
    services = json.load(f)
rows = []
for s in services:
    name = s["metadata"]["name"]
    if not name.startswith("dd-arm-"):
        continue
    tmpl = s["spec"]["template"]
    revision_max = int(tmpl["metadata"]["annotations"]["autoscaling.knative.dev/maxScale"])
    service_max = int(s["metadata"]["annotations"]["run.googleapis.com/maxScale"])
    if override:
        revision_max = service_max = int(override)
    # Cloud Run enforces the smaller of the two limits, so the budget must too.
    max_scale = min(revision_max, service_max)
    env = {e["name"]: e.get("value") for e in tmpl["spec"]["containers"][0].get("env", [])}
    pool = int(env["DB_POOL_MAX"])
    rows.append({"service": name, "revisionMaxScale": revision_max, "serviceMaxScale": service_max, "maxScale": max_scale, "dbPoolMax": pool, "connections": max_scale * pool})
with open(sys.argv[2], "w", encoding="utf-8") as f:
    json.dump(rows, f)
for r in rows:
    print(f'{r["service"]}: maxScale {r["maxScale"]} x pool {r["dbPoolMax"]} = {r["connections"]}')
PYEOF

echo "Starting cloud-sql-proxy for ${SQL_INSTANCE} on 127.0.0.1:${PROXY_PORT}"
cloud-sql-proxy --gcloud-auth --port "${PROXY_PORT}" "${PROJECT_ID}:${REGION}:${SQL_INSTANCE}" > "${TMP}/proxy.log" 2>&1 &
PROXY_PID=$!
for _ in $(seq 1 30); do
  if grep -q "ready for new connections" "${TMP}/proxy.log" 2>/dev/null; then break; fi
  if ! kill -0 "${PROXY_PID}" 2>/dev/null; then cat "${TMP}/proxy.log" >&2; fail "cloud-sql-proxy exited"; fi
  sleep 1
done
grep -q "ready for new connections" "${TMP}/proxy.log" || { cat "${TMP}/proxy.log" >&2; fail "cloud-sql-proxy not ready after 30 s"; }

DB_PASS="$(gv secrets versions access latest --secret=dd-db-password --project="${PROJECT_ID}")"
if command -v psql >/dev/null 2>&1; then
  MAX_CONN="$(PGPASSWORD="${DB_PASS}" PGCONNECT_TIMEOUT=10 psql -h 127.0.0.1 -p "${PROXY_PORT}" -U dd -d postgres -Atc 'SHOW max_connections' | tr -d '\r')"
else
  # No local psql: use the postgres:17 image the Docker Compose stack already uses.
  MAX_CONN="$(docker run --rm -e PGPASSWORD="${DB_PASS}" -e PGCONNECT_TIMEOUT=10 postgres:17 \
    psql -h host.docker.internal -p "${PROXY_PORT}" -U dd -d postgres -Atc 'SHOW max_connections' | tr -d '\r')"
fi
case "${MAX_CONN}" in ''|*[!0-9]*) fail "SHOW max_connections returned '${MAX_CONN}'" ;; esac
kill "${PROXY_PID}" 2>/dev/null || true
PROXY_PID=""

TOTAL_CONN="$("${PY}" -c "import json,sys; print(sum(r['connections'] for r in json.load(open(sys.argv[1], encoding='utf-8'))))" "$(to_native_path "${BUDGET_JSON}")" | tr -d '\r')"
LIMIT=$(( MAX_CONN - CONN_RESERVE ))
echo "sum(maxScale x pool) = ${TOTAL_CONN}; max_connections = ${MAX_CONN}; allowed <= ${LIMIT}"
[ "${TOTAL_CONN}" -le "${LIMIT}" ] || fail "connection budget exceeded (${TOTAL_CONN} > ${LIMIT})"

# ---- (c) skew and (d) warm, measured from each VM ----
echo "== (c) clock skew and (d) warm =="
URL="$(arm_url "${ARM}")"
[ -n "${URL}" ] || fail "no URL for dd-arm-${ARM}"
SAMPLE_CMD='for i in $(seq 1 3); do curl -s -o /dev/null --max-time 10 '"${URL}"'/api/time; done;
for i in $(seq 1 '"${SAMPLES}"'); do
  t0=$(date +%s%3N); body=$(curl -s --max-time 10 -w "|%{time_total}" '"${URL}"'/api/time); t1=$(date +%s%3N)
  echo "$t0 $t1 $body"
done'
# Skew and warmth describe the clocks and the server, not the link. netem delays only egress, so on a
# shaped VM the probe's midpoint and time_total carry the cohort's one-way delay (phase 19 measured
# 135-254 ms "skew" that scaled with the delay). Probe with shaping cleared, then put the cohort's
# shaping back before k6 starts.
for c in "${COHORTS[@]}"; do
  vm="$(vm_name "${c}")"
  vm_ssh "${vm}" "mkdir -p ~/${VM_HOME_DIR}" >/dev/null || fail "ssh to ${vm} failed (is the fleet up? bash tools/vm/create-fleet.sh create)"
  vm_scp "${REPO_ROOT}/tools/vm/shape.sh" "${REPO_ROOT}/tools/vm/cohorts.env" "${vm}:${VM_HOME_DIR}/" >/dev/null || fail "copy shape.sh to ${vm} failed"
  vm_ssh "${vm}" "sudo bash ~/${VM_HOME_DIR}/shape.sh clear >/dev/null; ${SAMPLE_CMD}" | tr -d '\r' > "${TMP}/samples-${c}.txt"; rc=${PIPESTATUS[0]}
  vm_ssh "${vm}" "sudo bash ~/${VM_HOME_DIR}/shape.sh ${c} >/dev/null && sudo tc qdisc show | grep -q netem" >/dev/null \
    || fail "could not restore '${c}' shaping on ${vm}"
  [ "${rc}" -eq 0 ] || fail "skew/warm probe on ${vm} failed"
  echo "${vm}: probed unshaped, '${c}' shaping restored"
done

RESULT_JSON="${TMP}/vm-result.json"
"${PY}" - "$(to_native_path "${TMP}")" "$(to_native_path "${RESULT_JSON}")" "${SKEW_LIMIT_MS}" "${WARM_LIMIT_MS}" "${COHORTS[*]}" <<'PYEOF' || true
import json
import re
import statistics
import sys

tmp, out, skew_limit, warm_limit, cohorts = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), sys.argv[5].split()
result = {}
for c in cohorts:
    samples = []
    with open(f"{tmp}/samples-{c}.txt", encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^(\d+) (\d+) .*\"epochMs\"\s*:\s*(\d+).*\|([0-9.]+)\s*$", line.strip())
            if m:
                t0, t1, epoch, secs = int(m[1]), int(m[2]), int(m[3]), float(m[4])
                samples.append({"t0": t0, "t1": t1, "rttMs": t1 - t0, "epochMs": epoch,
                                "skewMs": epoch - (t0 + t1) / 2, "curlMs": secs * 1000})
    if not samples:
        result[c] = {"ok": False, "error": "no parsable samples"}
        continue
    best = min(samples, key=lambda s: s["rttMs"])
    median_ms = statistics.median(s["curlMs"] for s in samples)
    result[c] = {
        "ok": abs(best["skewMs"]) <= skew_limit and median_ms < warm_limit,
        "samples": len(samples),
        "skewMs": best["skewMs"],
        "bestRttMs": best["rttMs"],
        "maxAbsSkewMs": max(abs(s["skewMs"]) for s in samples),
        "warmMedianMs": round(median_ms, 1),
    }
with open(out, "w", encoding="utf-8") as f:
    json.dump(result, f)
PYEOF
[ -s "${RESULT_JSON}" ] || fail "could not compute skew samples"

MAX_ABS_SKEW="$("${PY}" -c "
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
print(max((abs(v['skewMs']) if 'skewMs' in v else 1e9) for v in r.values()))" "$(to_native_path "${RESULT_JSON}")" | tr -d '\r')"

# ---- write preflight.json ----
"${PY}" - "$(to_native_path "${OUT}")" "$(to_native_path "${BUDGET_JSON}")" "$(to_native_path "${RESULT_JSON}")" \
  "${ARM}" "${URL}" "${MAX_CONN}" "${TOTAL_CONN}" "${CONN_RESERVE}" "${SKEW_LIMIT_MS}" "${WARM_LIMIT_MS}" <<'PYEOF'
import datetime
import json
import sys

out, budget, vms, arm, url, max_conn, total_conn, reserve, skew_limit, warm_limit = sys.argv[1:11]
doc = {
    "arm": arm,
    "baseUrl": url,
    "checkedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "parity": "clean",
    "connectionBudget": {
        "services": json.load(open(budget, encoding="utf-8")),
        "sum": int(total_conn),
        "maxConnections": int(max_conn),
        "reserve": int(reserve),
        "limit": int(max_conn) - int(reserve),
    },
    "skewLimitMs": int(skew_limit),
    "warmLimitMs": int(warm_limit),
    "vms": json.load(open(vms, encoding="utf-8")),
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(doc, f, indent=2)
    f.write("\n")
PYEOF

echo "wrote ${OUT}"
"${PY}" -c "
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
for c, v in r.items():
    print(f\"{c}: skew {v.get('skewMs')} ms (best rtt {v.get('bestRttMs')} ms), warm median {v.get('warmMedianMs')} ms\")" "$(to_native_path "${RESULT_JSON}")"

"${PY}" -c "import sys; sys.exit(0 if float(sys.argv[1]) <= float(sys.argv[2]) else 1)" "${MAX_ABS_SKEW}" "${SKEW_LIMIT_MS}" \
  || fail "clock skew above ${SKEW_LIMIT_MS} ms on at least one VM (review G3)"
"${PY}" -c "
import json, sys
r = json.load(open(sys.argv[1], encoding='utf-8'))
bad = [c for c, v in r.items() if v.get('warmMedianMs', 1e9) >= float(sys.argv[2])]
sys.exit(1 if bad else 0)" "$(to_native_path "${RESULT_JSON}")" "${WARM_LIMIT_MS}" \
  || fail "dd-arm-${ARM} is not warm: /api/time median >= ${WARM_LIMIT_MS} ms from a VM"
echo "PREFLIGHT OK for arm ${ARM}"
