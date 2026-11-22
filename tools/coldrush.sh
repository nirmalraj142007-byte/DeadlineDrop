#!/usr/bin/env bash
# Cold rush (phase 34): the 500-student rush on arm C after the service has idled at zero instances.
#   bash tools/coldrush.sh
# 1. pause the Cloud Scheduler jobs that call arm C every minute (dd-drain-c, dd-reconcile-c),
#    since they would keep an instance alive; they are resumed on exit, also on error
# 2. tools/sweep-mode.sh off c (min-instances 0 on arm C only; A and B stay warm, parity differs)
# 3. poll Cloud Monitoring run.googleapis.com/container/instance_count until arm C has had zero
#    instances for IDLE_MIN_S (15 min), counting only points after the last non-zero one
# 4. time one GET /api/time from this machine: the first request after the idle period (cold start)
# 5. resume the scheduler jobs, then tools/run.sh c 500 1 coldrush (a configuration variant)
# 6. append a "cold_rush" block to results/coldstart.json
#
# What the block can and cannot show: run.sh's preflight, the assignment creation and k6's logins
# all reach arm C minutes before the first click, so the first instance's cold start is paid before
# the rush (step 4 measures it). The rush itself starts from one instance at min-instances 0 and
# measures the scale-out to instances 2 and 3 with no warm minimum held for it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/vm/common.sh"

SERVICE="dd-arm-c"
SIZE="${SIZE:-500}"
REPEAT="${REPEAT:-1}"
IDLE_MIN_S="${IDLE_MIN_S:-900}"
POLL_S=60
LOOKBACK_S=3600
IDLE_TIMEOUT_S=5400   # give up after 90 min without 15 idle minutes
SCHEDULER_JOBS=(dd-drain-c dd-reconcile-c)
OUT="${REPO_ROOT}/results/coldstart.json"
PY="$(find_python)"
URL="$(arm_url c)"

PAUSED=false
cleanup() {
  local code=$?
  if [ "${PAUSED}" = true ]; then
    for j in "${SCHEDULER_JOBS[@]}"; do
      gcloud scheduler jobs resume "${j}" --location="${REGION}" --project="${PROJECT_ID}" --quiet \
        || echo "WARNING: could not resume ${j}; resume it by hand" >&2
    done
  fi
  exit "${code}"
}
trap cleanup EXIT

# Seconds arm C has had zero instances by Monitoring (zero points after the last non-zero one), or -1.
zero_seconds() {
  local token start end resp
  token="$(gv auth print-access-token)"
  end="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  start="$(date -u -d "@$(( $(date +%s) - LOOKBACK_S ))" +%Y-%m-%dT%H:%M:%SZ)"
  resp="$(curl -s -f --max-time 30 -G -H "Authorization: Bearer ${token}" \
    "https://monitoring.googleapis.com/v3/projects/${PROJECT_ID}/timeSeries" \
    --data-urlencode "filter=metric.type=\"run.googleapis.com/container/instance_count\" AND resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${SERVICE}\"" \
    --data-urlencode "interval.startTime=${start}" \
    --data-urlencode "interval.endTime=${end}")" || { echo "-1"; return 0; }
  printf '%s' "${resp}" | "${PY}" -c "
import json, sys
from datetime import datetime
total = {}  # instances per minute, summed over the active and idle series (and revisions)
for ts in json.load(sys.stdin).get('timeSeries', []):
    for p in ts.get('points', []):
        t = datetime.fromisoformat(p['interval']['endTime'].replace('Z', '+00:00')).timestamp()
        v = p['value']
        total[t] = total.get(t, 0.0) + float(v.get('doubleValue', v.get('int64Value', 0)))
nonzero = [t for t, v in total.items() if v > 0]
if not nonzero:
    print(-1)  # the warm instance has not shown up yet: silence proves nothing
else:
    last_nz = max(nonzero)
    zeros_after = [t for t, v in total.items() if v == 0 and t > last_nz]
    print(int(max(zeros_after) - last_nz) if zeros_after else -1)
" | tr -d '\r'
}

echo "== 1. pausing Cloud Scheduler jobs that keep ${SERVICE} warm =="
PAUSED=true
for j in "${SCHEDULER_JOBS[@]}"; do
  gcloud scheduler jobs pause "${j}" --location="${REGION}" --project="${PROJECT_ID}" --quiet
done

echo "== 2. sweep mode off for arm C only (min-instances 0) =="
bash "${SCRIPT_DIR}/sweep-mode.sh" off c

echo "== 3. waiting until ${SERVICE} has had zero instances for ${IDLE_MIN_S}s (Cloud Monitoring) =="
waited_from="$(date +%s)"
while :; do
  z="$(zero_seconds)"
  echo "  $(date -u +%H:%M:%SZ) zero instances for ${z}s"
  [ "${z}" -ge "${IDLE_MIN_S}" ] && break
  if [ $(( $(date +%s) - waited_from )) -gt "${IDLE_TIMEOUT_S}" ]; then
    echo "Error: ${SERVICE} did not reach ${IDLE_MIN_S}s at zero instances within ${IDLE_TIMEOUT_S}s" >&2
    exit 1
  fi
  sleep "${POLL_S}"
done
IDLE_S="${z}"
IDLE_CONFIRMED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "== 4. first request after the idle period =="
res="$(curl -s -o /dev/null --max-time 120 -w '%{time_total} %{http_code}' "${URL}/api/time")" || true
FIRST_SECS="${res%% *}"; FIRST_CODE="${res##* }"
echo "  GET /api/time: ${FIRST_SECS}s, HTTP ${FIRST_CODE}"

echo "== 5. resume scheduler jobs, then run.sh c ${SIZE} ${REPEAT} coldrush =="
for j in "${SCHEDULER_JOBS[@]}"; do
  gcloud scheduler jobs resume "${j}" --location="${REGION}" --project="${PROJECT_ID}" --quiet
done
PAUSED=false
RUN_LOG="$(mktemp)"
set +e
bash "${SCRIPT_DIR}/run.sh" c "${SIZE}" "${REPEAT}" coldrush 2>&1 | tee "${RUN_LOG}"
RUN_EXIT=${PIPESTATUS[0]}
set -e
RUN_DIR="$(grep -o 'Run folder: .*' "${RUN_LOG}" | tail -n 1 | sed 's/^Run folder: //' | tr -d '\r')"
rm -f "${RUN_LOG}"
[ -n "${RUN_DIR}" ] && [ -f "${RUN_DIR}/confusion.json" ] || { echo "Error: the cold-rush run left no confusion.json (run.sh exit ${RUN_EXIT})" >&2; exit 1; }

echo "== 6. cold_rush block in results/coldstart.json =="
"${PY}" - "$(to_native_path "${OUT}")" "$(to_native_path "${RUN_DIR}")" "${IDLE_S}" "${IDLE_CONFIRMED_AT}" \
  "${FIRST_SECS}" "${FIRST_CODE}" "${RUN_EXIT}" <<'PYEOF'
import json
import os
import sys

out, run_dir, idle_s, idle_at, first_secs, first_code, run_exit = sys.argv[1:8]
with open(out, encoding="utf-8") as f:
    doc = json.load(f)
conf = json.load(open(os.path.join(run_dir, "confusion.json"), encoding="utf-8"))
meta = json.load(open(os.path.join(run_dir, "meta.json"), encoding="utf-8"))
checks = conf["checks"]
doc["cold_rush"] = {
    "runFolder": "results/runs/" + os.path.basename(run_dir.rstrip("/\\")),
    "idleMinutes": round(int(idle_s) / 60, 1),
    "idleConfirmedAt": idle_at,
    "firstRequestAfterIdleMs": round(float(first_secs) * 1000, 1),
    "firstRequestHttpCode": int(first_code),
    "minInstances": meta.get("minInstances"),
    "students": conf["students"],
    "p95CommitMs": conf["latencyMs"]["commit"]["p95"],
    "p99CommitMs": conf["latencyMs"]["commit"]["p99"],
    "misclassification": {
        "trulyOnTime": conf["trulyOnTime"],
        "onTime_lateOrMissing": conf["onTime_lateOrMissing"],
        "late_onTime": conf["late_onTime"],
        "rate": conf["misclassificationRate"],
    },
    "errorCells": {
        "lost": checks["lost"],
        "duplicates": checks["duplicates"],
        "pendingAtExport": checks["pendingAtExport"],
        "connectionErrorResponses": conf["connectionErrors"]["responses"],
        "connectionErrorStudents": conf["connectionErrors"]["students"],
    },
    "runExit": int(run_exit),
    "method": (
        "Cloud Scheduler jobs dd-drain-c/dd-reconcile-c paused and arm C at min-instances 0 (tools/sweep-mode.sh "
        "off c); Cloud Monitoring instance_count polled until arm C had zero instances for at least 15 min "
        "(idleMinutes = zero points after the last non-zero one). One GET /api/time from the operator's machine "
        "timed the first request after the idle period; then the scheduler jobs were resumed and tools/run.sh "
        "c 500 1 coldrush ran. Its preflight, assignment creation and k6 logins reach arm C before the first "
        "click, so the first instance's cold start is paid before the rush; the rush measures scale-out from "
        "one instance with no warm minimum. Values come from the run folder's confusion.json."
    ),
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(doc, f, indent=2)
    f.write("\n")
print(json.dumps(doc["cold_rush"], indent=2))
PYEOF
exit "${RUN_EXIT}"
