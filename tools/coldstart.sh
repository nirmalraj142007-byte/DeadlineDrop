#!/usr/bin/env bash
# Measure arm C's Cloud Run cold start: GET /api/time, timed by curl, when no instance exists.
#   bash tools/coldstart.sh            (about 90 minutes, unattended)
# For each of SAMPLES samples: wait until Cloud Monitoring's run.googleapis.com/container/instance_count
# for dd-arm-c has been 0 for ZERO_FOR_S seconds, then time one request. Writes results/coldstart.json.
#
# Cloud Scheduler calls arm C every minute (deploy/scheduler.sh), which keeps an instance alive, so the
# dd-drain-c and dd-reconcile-c jobs are PAUSED for the duration and resumed on exit (also on error).
# min-instances is set to 0 for the measurement and restored from deploy/arms.env on exit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/vm/common.sh"
# shellcheck source=deploy/arms.env
source "${REPO_ROOT}/deploy/arms.env"
RESTORE_MIN_INSTANCES="${MIN_INSTANCES}"

SERVICE="dd-arm-c"
SAMPLES="${SAMPLES:-5}"
ZERO_FOR_S=120
POLL_S=30
INGEST_LAG_S=240        # instance_count points can arrive minutes late; never trust silence sooner
SAMPLE_TIMEOUT_S=2700   # give up on one sample after 45 min
LOOKBACK_S=1200
SCHEDULER_JOBS=(dd-drain-c dd-reconcile-c)
OUT="${REPO_ROOT}/results/coldstart.json"
PY="$(find_python)"
URL="$(arm_url c)"
SAMPLES_FILE="$(mktemp)"

set_min_instances() {
  local want="$1" now
  now="$(gv run services describe "${SERVICE}" --region="${REGION}" --project="${PROJECT_ID}" \
    --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/minScale")')"
  now="${now:-0}"
  if [ "${now}" = "${want}" ]; then
    echo "${SERVICE}: min-instances already ${want}"
  else
    echo "${SERVICE}: min-instances ${now} -> ${want}"
    gcloud run services update "${SERVICE}" --region="${REGION}" --project="${PROJECT_ID}" \
      --min-instances="${want}" --quiet
  fi
}

cleanup() {
  local code=$?
  echo "== restoring: resume scheduler jobs, min-instances ${RESTORE_MIN_INSTANCES} =="
  for j in "${SCHEDULER_JOBS[@]}"; do
    gcloud scheduler jobs resume "${j}" --location="${REGION}" --project="${PROJECT_ID}" --quiet \
      || echo "WARNING: could not resume ${j}; resume it by hand" >&2
  done
  set_min_instances "${RESTORE_MIN_INSTANCES}" || echo "WARNING: could not restore min-instances" >&2
  rm -f "${SAMPLES_FILE}"
  exit "${code}"
}
trap cleanup EXIT

# Seconds the service has had zero instances according to Monitoring, or -1 if not yet provably 120 s.
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
import json, sys, time
from datetime import datetime
lag, last_request = int(sys.argv[1]), int(sys.argv[2])
now = time.time()
pts = []
for ts in json.load(sys.stdin).get('timeSeries', []):
    for p in ts.get('points', []):
        t = datetime.fromisoformat(p['interval']['endTime'].replace('Z', '+00:00')).timestamp()
        v = p['value']
        pts.append((t, float(v.get('doubleValue', v.get('int64Value', 0)))))
nonzero = [t for t, v in pts if v > 0]
if nonzero:
    last_nz = max(nonzero)
    zeros_after = [t for t, v in pts if v == 0 and t > last_nz]
    # zero-valued points observed after the last instance
    print(int(max(zeros_after) - last_nz) if zeros_after else -1)
elif now - last_request >= lag:
    # no instance at all in the lookback window, and long enough since our own request
    # that its instance would have been reported by now
    print(int(now - last_request))
else:
    print(-1)
" "${INGEST_LAG_S}" "${LAST_REQUEST_S}" | tr -d '\r'
}

echo "== pausing Cloud Scheduler jobs that keep ${SERVICE} warm =="
for j in "${SCHEDULER_JOBS[@]}"; do
  gcloud scheduler jobs pause "${j}" --location="${REGION}" --project="${PROJECT_ID}" --quiet
done
set_min_instances 0

LAST_REQUEST_S="$(date +%s)"
for i in $(seq 1 "${SAMPLES}"); do
  echo "== sample ${i}/${SAMPLES}: waiting for ${ZERO_FOR_S}s at zero instances =="
  waited_from="$(date +%s)"
  while true; do
    z="$(zero_seconds)"
    if [ "${z}" -ge "${ZERO_FOR_S}" ]; then echo "  zero instances for >= ${z}s"; break; fi
    if [ $(( $(date +%s) - waited_from )) -gt "${SAMPLE_TIMEOUT_S}" ]; then
      echo "Error: ${SERVICE} did not stay at zero instances within ${SAMPLE_TIMEOUT_S}s" >&2
      exit 1
    fi
    sleep "${POLL_S}"
  done
  at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  res="$(curl -s -o /dev/null --max-time 120 -w '%{time_total} %{http_code}' "${URL}/api/time")" || true
  LAST_REQUEST_S="$(date +%s)"
  secs="${res%% *}"; code="${res##* }"
  echo "  GET /api/time: ${secs}s, HTTP ${code}"
  printf '%s %s %s %s\n' "${at}" "${secs}" "${code}" "${z}" >> "${SAMPLES_FILE}"
done

mkdir -p "$(dirname "${OUT}")"
"${PY}" - "$(to_native_path "${SAMPLES_FILE}")" "$(to_native_path "${OUT}")" "${URL}" "${ZERO_FOR_S}" <<'PYEOF'
import json, statistics, sys
src, out, url, zero_for = sys.argv[1:5]
samples = []
for line in open(src, encoding="utf-8"):
    at, secs, code, z = line.split()
    samples.append({"at": at, "ms": round(float(secs) * 1000, 1), "httpCode": int(code), "zeroInstancesForS": int(z)})
ok = [s["ms"] for s in samples if s["httpCode"] == 200]
result = {
    "service": "dd-arm-c",
    "url": url + "/api/time",
    "samples": samples,
    "median": round(statistics.median(ok), 1) if ok else None,
    "unit": "ms",
    "method": (
        f"min-instances 0 and Cloud Scheduler jobs dd-drain-c/dd-reconcile-c paused; before each sample, "
        f"poll Cloud Monitoring run.googleapis.com/container/instance_count (REST, gcloud access token) "
        f"until the service has had zero instances for {zero_for} s; then one GET /api/time from the "
        f"operator's machine, timed by curl -w %{{time_total}} (DNS + TCP + TLS + cold start + response). "
        f"Median over HTTP 200 samples."
    ),
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(result, f, indent=2)
    f.write("\n")
print(json.dumps(result, indent=2))
PYEOF
