#!/usr/bin/env bash
# One cloud run of one arm, driven from this machine:
#   bash tools/run.sh <a|b|c> <size> <repeat> [fault] [--dryrun]
# size = number of students, repeat = repetition index, fault = name of tools/faults/<fault>.sh
# or "none". The fleet must exist (bash tools/vm/create-fleet.sh create).
# Counted results are never edited by hand (results/README.md). --dryrun writes counted:false.
#
# Resumable (counted runs only): if a folder for the same arm, size, repeat and fault already holds a
# confusion.json and is not marked invalid, the run is skipped (exit 0). A run that fails for an
# infrastructure reason (preflight fails, including clock skew above 100 ms; a VM stops answering or
# k6 dies; any other step of this script fails) is marked "valid": false with an "invalidReason" in
# its meta.json, and run.sh exits 4. A folder left without confusion.json by a killed run.sh is
# marked invalid the next time. The next invocation repeats an invalid run into a new folder with the
# suffix _rerun (_rerun2, ...); both folders stay.
# Exit codes: 0 ok or skipped; 1 usage; 3 arm C misjudged a student (a valid result); 4 invalid run.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/vm/common.sh"

ARGS=()
DRYRUN=false
for a in "$@"; do
  if [ "${a}" = "--dryrun" ]; then DRYRUN=true; else ARGS+=("${a}"); fi
done
set -- "${ARGS[@]+"${ARGS[@]}"}"
ARM="${1:-}"; SIZE="${2:-}"; REPEAT="${3:-}"; FAULT="${4:-none}"
case "${ARM}" in a|b|c) ;; *) echo "Usage: $0 <a|b|c> <size> <repeat> [fault] [--dryrun]" >&2; exit 1 ;; esac
case "${SIZE}" in ''|*[!0-9]*) echo "size must be a positive integer" >&2; exit 1 ;; esac
case "${REPEAT}" in ''|*[!0-9]*) echo "repeat must be a non-negative integer" >&2; exit 1 ;; esac

COHORT_MIN="${COHORT_MIN:-10}"
GRACE_S=300                         # experiment grace (docs/decisions.md D3)
MAX_BYTES=26214400
MAX_MB="${MAX_MB:-25}"
SCHEDULE_MODE="${SCHEDULE_MODE:-profiles}"  # cohorts.env holds the docs/network-profiles.md values
EXPORT_WAIT_MAX_S=300               # extra wait for COMMITTED/UPLOADED rows to settle before export
EXPORT_POLL_S=30
VM_CHECK_EVERY_S=120                # how often the wait checks that each VM still runs k6
EXIT_INVALID=4
START_MARGIN_S=20                   # k6 must be running this long before the earliest click
TEACHER_EMAIL="teacher@dd.local"
PY="$(find_python)"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
ARM_UPPER="$(echo "${ARM}" | tr '[:lower:]' '[:upper:]')"
KEY="${ARM_UPPER}_${SIZE}_r${REPEAT}"
[ "${FAULT}" != "none" ] && KEY="${KEY}_${FAULT}"
RUNS_DIR="${REPO_ROOT}/results/runs"
COUNTED=true; [ "${DRYRUN}" = true ] && COUNTED=false

# meta_set <meta.json> <json object>: merge keys into a run's meta.json (created if missing).
meta_set() {
  "${PY}" -c '
import json, os, sys
path, patch = sys.argv[1], json.loads(sys.argv[2])
meta = {}
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        meta = json.load(f)
meta.update(patch)
with open(path, "w", encoding="utf-8") as f:
    json.dump(meta, f, indent=2)
    f.write("\n")
' "$(to_native_path "$1")" "$2"
}
meta_get() { # meta_get <meta.json> <key>  -> lower-case value, or empty
  [ -f "$1" ] || return 0
  "${PY}" -c "import json,sys; v=json.load(open(sys.argv[1], encoding='utf-8')).get(sys.argv[2]); print('' if v is None else str(v).lower())" \
    "$(to_native_path "$1")" "$2" | tr -d '\r'
}

# Resume (counted runs only): look at earlier folders for this key.
SUFFIX=""
if [ "${DRYRUN}" = false ]; then
  previous=0
  for d in "${RUNS_DIR}"/*_"${KEY}" "${RUNS_DIR}"/*_"${KEY}"_rerun*; do
    [ -d "${d}" ] || continue
    previous=$(( previous + 1 ))
    valid="$(meta_get "${d}/meta.json" valid)"
    if [ -f "${d}/confusion.json" ] && [ "${valid}" != "false" ]; then
      echo "SKIP ${KEY}: already done in $(basename "${d}")"
      exit 0
    fi
    if [ "${valid}" != "false" ]; then
      echo "Marking $(basename "${d}") invalid: run.sh did not finish (no confusion.json)"
      meta_set "${d}/meta.json" "{\"valid\": false, \"invalidReason\": \"interrupted: run.sh did not finish (no confusion.json); marked by the next invocation\", \"invalidAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
    fi
  done
  if [ "${previous}" -eq 1 ]; then SUFFIX="_rerun"; elif [ "${previous}" -gt 1 ]; then SUFFIX="_rerun${previous}"; fi
fi

RUN_NAME="${STAMP}_${KEY}${SUFFIX}"
[ "${DRYRUN}" = true ] && RUN_NAME="${RUN_NAME}_dryrun"
RUN_DIR="${RUNS_DIR}/${RUN_NAME}"
mkdir -p "${RUN_DIR}"
META="${RUN_DIR}/meta.json"
echo "Run folder: ${RUN_DIR}"
RERUN_OF=null; [ -n "${SUFFIX}" ] && RERUN_OF="\"${KEY}\""
meta_set "${META}" "{\"arm\": \"${ARM}\", \"size\": ${SIZE}, \"repeat\": ${REPEAT}, \"fault\": \"${FAULT}\", \"counted\": ${COUNTED}, \"valid\": true, \"startedAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\", \"rerunOf\": ${RERUN_OF}}"

# Any failure from here on is an infrastructure failure: mark the run invalid. FINISHED=true once
# meta.json is complete; after that the exit code is analyze.py's (3 = a valid arm C result).
STEP="start"
FINISHED=false
INVALID_REASON=""
invalidate() { # invalidate <reason>: mark the run invalid and stop
  INVALID_REASON="$1"
  exit "${EXIT_INVALID}"
}
on_exit() {
  local rc=$?
  if [ "${FINISHED}" != true ] && [ "${rc}" -ne 0 ]; then
    local reason="${INVALID_REASON:-run.sh exited ${rc} during step '${STEP}'}"
    reason="${reason//\"/\'}"
    meta_set "${META}" "{\"valid\": false, \"invalidReason\": \"${reason}\", \"invalidAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}" || true
    echo "RUN INVALID: ${reason} (${RUN_DIR})" >&2
    exit "${EXIT_INVALID}"
  fi
}
trap on_exit EXIT

if [ "${FAULT}" != "none" ] && [ ! -f "${REPO_ROOT}/tools/faults/${FAULT}.sh" ]; then
  echo "Error: fault '${FAULT}' needs tools/faults/${FAULT}.sh (arrives in phase 34)." >&2
  exit 1
fi

# (1) preflight
STEP="preflight"
if ! OUT="${RUN_DIR}/preflight.json" bash "${REPO_ROOT}/tools/preflight.sh" "${ARM}" 2>&1 | tee "${RUN_DIR}/preflight.log"; then
  why="$(grep -o 'PREFLIGHT FAILED: .*' "${RUN_DIR}/preflight.log" | tail -n 1 | tr -d '\r')"
  invalidate "${why:-preflight failed (see preflight.log)}"
fi
STEP="payload files"

# (1b) payload files are generated ON each VM (make-files.sh keeps existing files of the right size),
# before the assignment exists: copying ~58 MB per VM would not fit in the start margin below.
prepare_files() {
  local vm; vm="$(vm_name "$1")"
  retry vm_scp "${REPO_ROOT}/tools/k6/files/make-files.sh" "${vm}:${VM_HOME_DIR}/make-files.sh" >/dev/null
  retry vm_ssh "${vm}" "MAX_MB=${MAX_MB} OUT_DIR=\$HOME/${VM_HOME_DIR}/files bash ~/${VM_HOME_DIR}/make-files.sh" >/dev/null
}
pids=()
for c in "${COHORTS[@]}"; do prepare_files "${c}" & pids+=($!); done
for p in "${pids[@]}"; do wait "${p}" || { echo "payload file generation on a VM failed" >&2; exit 1; }; done

BASE="$(arm_url "${ARM}")"
SEED_PASSWORD="$(gv secrets versions access latest --secret=dd-seed-password --project="${PROJECT_ID}")"

login() { # login <email>  -> access token
  curl -s -f --max-time 20 -X POST "${BASE}/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"password\":\"${SEED_PASSWORD}\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4
}

STEP="schedule and copies"
# (2) schedule: seed depends only on (size, repeat), so all three arms replay the same schedule.
# Schedule and copies come BEFORE T is fixed: through a shaped controller link the gcloud ssh/scp calls
# alone can exceed the start margin, and none of them depends on T.
SEED=$(( 16#$(printf 'dd-schedule:%s:%s' "${SIZE}" "${REPEAT}" | sha256sum | cut -c1-7) ))
"${PY}" "$(to_native_path "${REPO_ROOT}/tools/gen_schedule.py")" \
  --mode "${SCHEDULE_MODE}" --n "${SIZE}" --cohort-minutes "${COHORT_MIN}" --grace-s "${GRACE_S}" \
  --max-mb "${MAX_MB}" --seed "${SEED}" --out "$(to_native_path "${RUN_DIR}/schedule")"

# (3) copy shard and k6 scripts to each VM (in parallel)
copy_to_vm() {
  local c="$1" vm; vm="$(vm_name "$1")"
  retry vm_ssh "${vm}" "pkill -x k6; rm -rf ~/${VM_HOME_DIR}/run && mkdir -p ~/${VM_HOME_DIR}/run/lib"
  retry vm_scp "${RUN_DIR}/schedule/shard-${c}.json" "${vm}:${VM_HOME_DIR}/run/shard.json"
  retry vm_scp "${REPO_ROOT}/tools/k6/rush.js" "${vm}:${VM_HOME_DIR}/run/rush.js"
  retry vm_scp "${REPO_ROOT}/tools/k6/lib/flows.js" "${vm}:${VM_HOME_DIR}/run/lib/flows.js"
  retry vm_put_secret "${vm}" "${VM_HOME_DIR}/run/pw" "${SEED_PASSWORD}"
}
pids=()
for c in "${COHORTS[@]}"; do copy_to_vm "${c}" & pids+=($!); done
for p in "${pids[@]}"; do wait "${p}" || { echo "copy to a VM failed" >&2; exit 1; }; done

STEP="assignment"
# (4) fresh assignment: T = now + COHORT_MIN + 2 min
# k6 logs in its whole shard in setup() before any student clicks, so the lead before the earliest
# click grows with the largest shard: 0.5 s per login (rush.js SETUP_BUDGET_S_PER_LOGIN) on top of 2 min.
MAX_SHARD="$("${PY}" -c "
import json, sys
print(max(len(json.load(open(p, encoding='utf-8'))) for p in sys.argv[1:]))" \
  $(for c in "${COHORTS[@]}"; do to_native_path "${RUN_DIR}/schedule/shard-${c}.json"; done) | tr -d '\r')"
LEAD_S=$(( 120 + (MAX_SHARD + 1) / 2 ))
NOW_S="$(date +%s)"
T_S=$(( NOW_S + COHORT_MIN * 60 + LEAD_S ))
echo "Largest shard ${MAX_SHARD} students: k6 starts ${LEAD_S}s before the earliest click"
T_EPOCH_MS=$(( T_S * 1000 ))
DEADLINE_ISO="$("${PY}" -c "import datetime,sys; print(datetime.datetime.fromtimestamp(int(sys.argv[1]), datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))" "${T_S}" | tr -d '\r')"

TEACHER_TOKEN="$(login "${TEACHER_EMAIL}")"
[ -n "${TEACHER_TOKEN}" ] || { echo "teacher login failed on ${BASE}" >&2; exit 1; }
COURSE_ID="$(curl -s -f --max-time 20 -H "Authorization: Bearer ${TEACHER_TOKEN}" "${BASE}/api/courses" \
  | "${PY}" -c "
import json, sys
m = [c['id'] for c in json.load(sys.stdin) if c.get('code') == 'DEMO101']
print(m[0] if m else '')" | tr -d '\r')"
[ -n "${COURSE_ID}" ] || { echo "course DEMO101 not found on ${BASE}" >&2; exit 1; }
ASSIGNMENT_ID="$(curl -s -f --max-time 20 -X POST "${BASE}/api/courses/${COURSE_ID}/assignments" \
  -H "Authorization: Bearer ${TEACHER_TOKEN}" -H "Content-Type: application/json" \
  -d "{\"title\":\"run ${RUN_NAME}\",\"deadlineAt\":\"${DEADLINE_ISO}\",\"graceSeconds\":${GRACE_S},\"maxBytes\":${MAX_BYTES},\"allowedTypes\":[\"application/pdf\"]}" \
  | grep -o '"id":"[^"]*"' | head -n1 | cut -d'"' -f4)"
[ -n "${ASSIGNMENT_ID}" ] || { echo "assignment creation failed on ${BASE}" >&2; exit 1; }
echo "${ASSIGNMENT_ID}" > "${RUN_DIR}/assignment-id.txt"
echo "Assignment ${ASSIGNMENT_ID}, deadline T=${DEADLINE_ISO}, grace ${GRACE_S}s"

EARLIEST_CLICK_S=$(( T_S - COHORT_MIN * 60 ))
if [ "$(date +%s)" -gt $(( EARLIEST_CLICK_S - START_MARGIN_S )) ]; then
  echo "Error: assignment creation took too long; k6 cannot start ${START_MARGIN_S}s before the earliest click. Re-run." >&2
  exit 1
fi

STEP="start k6"
# (5) start k6 on each VM that has students (in parallel: each gcloud ssh takes seconds)
start_k6() {
  local vm; vm="$(vm_name "$1")"
  # setsid -f: k6 gets its own session, so the SSH call returns at once. With "nohup k6 ... &" the call
  # stayed open until k6 exited (measured: 38 s for a 25 s script), and a network blip on the
  # operator's link during the run then looked like a failed start (phase 19 arm C attempt).
  # "pgrep || ..." makes the call safe to retry: a retry never starts a second k6.
  retry vm_ssh "${vm}" "cd ~/${VM_HOME_DIR}/run && (pgrep -x k6 >/dev/null || setsid -f k6 run --console-output vu-log.ndjson --summary-export summary.json \
    -e ARM=${ARM} -e BASE_URL=${BASE} -e SHARD=\$PWD/shard.json -e T_EPOCH_MS=${T_EPOCH_MS} -e GRACE_S=${GRACE_S} \
    -e ASSIGNMENT_ID=${ASSIGNMENT_ID} -e PASSWORD=\"\$(cat pw)\" -e FILES_DIR=\$HOME/${VM_HOME_DIR}/files \
    -e COHORT_MINUTES=${COHORT_MIN} rush.js > k6.out 2>&1 < /dev/null) && pgrep -x k6 >/dev/null" >/dev/null
}
STARTED=()
pids=()
for c in "${COHORTS[@]}"; do
  count="$("${PY}" -c "import json,sys; print(len(json.load(open(sys.argv[1], encoding='utf-8'))))" "$(to_native_path "${RUN_DIR}/schedule/shard-${c}.json")" | tr -d '\r')"
  if [ "${count}" -eq 0 ]; then echo "$(vm_name "${c}"): empty shard, not starting k6"; continue; fi
  start_k6 "${c}" & pids+=($!)
  STARTED+=("${c}")
  echo "$(vm_name "${c}"): starting k6 (${count} students)"
done
for p in "${pids[@]}"; do wait "${p}" || { echo "starting k6 on a VM failed" >&2; exit 1; }; done
echo "k6 started on: ${STARTED[*]} ($(( EARLIEST_CLICK_S - $(date +%s) ))s before the earliest click)"

# (6) fault: the script owns its timing relative to T
if [ "${FAULT}" != "none" ]; then
  ARM="${ARM}" T_EPOCH_MS="${T_EPOCH_MS}" GRACE_S="${GRACE_S}" ASSIGNMENT_ID="${ASSIGNMENT_ID}" RUN_DIR="${RUN_DIR}" \
    bash "${REPO_ROOT}/tools/faults/${FAULT}.sh" &
  FAULT_PID=$!
fi

# (7) wait until T + grace + 2 min, checking every VM_CHECK_EVERY_S that each VM answers and still
# runs k6 (or has finished and written its summary). A VM that fails three tries makes the run invalid.
STEP="wait"
END_S=$(( T_S + GRACE_S + 120 ))
echo "Waiting $(( END_S - $(date +%s) ))s until T + grace + 2 min"
vm_alive() {
  vm_ssh "$(vm_name "$1")" "pgrep -x k6 >/dev/null || test -s ~/${VM_HOME_DIR}/run/summary.json" >/dev/null 2>&1
}
while :; do
  REMAINING=$(( END_S - $(date +%s) ))
  [ "${REMAINING}" -le 0 ] && break
  sleep $(( REMAINING < VM_CHECK_EVERY_S ? REMAINING : VM_CHECK_EVERY_S ))
  for c in "${STARTED[@]}"; do
    retry vm_alive "${c}" 2>/dev/null \
      || invalidate "VM $(vm_name "${c}") stopped answering, or k6 died without a summary, at $(date -u +%H:%M:%SZ)"
  done
done
if [ -n "${FAULT_PID:-}" ]; then wait "${FAULT_PID}" || echo "Warning: fault script exited non-zero" >&2; fi

# (8) export, vu-logs, summaries. Before the final export, wait until no submission of this assignment
# is still COMMITTED or UPLOADED (finalize and the reconciler settle them), at most EXPORT_WAIT_MAX_S
# more; meta.json records which came first.
STEP="export"
TEACHER_TOKEN="$(login "${TEACHER_EMAIL}")"
[ -n "${TEACHER_TOKEN}" ] || invalidate "teacher login failed before export"
WAIT_START_S="$(date +%s)"
while :; do
  curl -s -f --max-time 120 -H "Authorization: Bearer ${TEACHER_TOKEN}" \
    "${BASE}/api/assignments/${ASSIGNMENT_ID}/export" > "${RUN_DIR}/export.json"
  PENDING_ROWS="$("${PY}" -c "
import json, sys
print(sum(1 for r in json.load(open(sys.argv[1], encoding='utf-8')) if r.get('status') in ('COMMITTED', 'UPLOADED')))" \
    "$(to_native_path "${RUN_DIR}/export.json")" | tr -d '\r')"
  WAITED_S=$(( $(date +%s) - WAIT_START_S ))
  if [ "${PENDING_ROWS}" -eq 0 ]; then EXPORT_WAIT_UNTIL="noPending"; break; fi
  if [ "${WAITED_S}" -ge "${EXPORT_WAIT_MAX_S}" ]; then EXPORT_WAIT_UNTIL="maxWait"; break; fi
  echo "${PENDING_ROWS} rows still COMMITTED or UPLOADED after ${WAITED_S}s; polling again in ${EXPORT_POLL_S}s"
  sleep "${EXPORT_POLL_S}"
done
echo "Export taken after ${WAITED_S}s extra wait (${EXPORT_WAIT_UNTIL}, ${PENDING_ROWS} rows pending)"
STEP="collect vu-logs"
for c in "${STARTED[@]}"; do
  vm="$(vm_name "${c}")"
  retry vm_scp "${vm}:${VM_HOME_DIR}/run/vu-log.ndjson" "${RUN_DIR}/vu-log-${c}.ndjson"
  retry vm_scp "${vm}:${VM_HOME_DIR}/run/summary.json" "${RUN_DIR}/summary-${c}.json"
  retry vm_scp "${vm}:${VM_HOME_DIR}/run/k6.out" "${RUN_DIR}/k6-${c}.out"
done

# (9) meta.json
STEP="meta.json"
SCHEDULE_LABEL="$("${PY}" -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['mode'])" \
  "$(to_native_path "${RUN_DIR}/schedule/schedule-meta.json")" | tr -d '\r')"
SERVICE_JSON="$(mktemp)"
gv run services describe "dd-arm-${ARM}" --region="${REGION}" --project="${PROJECT_ID}" --format=json > "${SERVICE_JSON}"
REVISION="$("${PY}" -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['status']['latestReadyRevisionName'])" "$(to_native_path "${SERVICE_JSON}")" | tr -d '\r')"
IMAGE_DIGEST="$(gv run revisions describe "${REVISION}" --region="${REGION}" --project="${PROJECT_ID}" --format='value(status.imageDigest)')"
SQL_TIER_NOW="$(gv sql instances describe "${SQL_INSTANCE}" --project="${PROJECT_ID}" --format='value(settings.tier)')"
K6_VERSION="$(retry vm_ssh "$(vm_name "${COHORTS[0]}")" "k6 version" | tr -d '\r' | head -n 1)"
GIT_SHA="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
[ -n "$(git -C "${REPO_ROOT}" status --porcelain)" ] && GIT_SHA="${GIT_SHA}-dirty"
"${PY}" - "$(to_native_path "${RUN_DIR}/meta.json")" "$(to_native_path "${RUN_DIR}/preflight.json")" "$(to_native_path "${SERVICE_JSON}")" \
  "${ARM}" "${SIZE}" "${REPEAT}" "${FAULT}" "${COUNTED}" "${DEADLINE_ISO}" "${COHORT_MIN}" "${GRACE_S}" \
  "${REGION}" "${SQL_TIER_NOW}" "${K6_VERSION}" "${IMAGE_DIGEST}" "${GIT_SHA}" "${SCHEDULE_LABEL}" "${ASSIGNMENT_ID}" "${SEED}" "${COHORTS[*]}" \
  "${EXPORT_WAIT_UNTIL}" "${WAITED_S}" "${PENDING_ROWS}" "${EXPORT_WAIT_MAX_S}" <<'PYEOF'
import json
import sys

(out, pre, svc, arm, size, repeat, fault, counted, t_iso, cohort_min, grace_s, region, tier,
 k6v, digest, sha, mode, assignment, seed, cohorts, wait_until, waited_s, pending_rows,
 wait_max_s) = sys.argv[1:25]
with open(out, encoding="utf-8") as f:
    started = json.load(f)  # written at the start of run.sh: valid, startedAt, rerunOf
pre = json.load(open(pre, encoding="utf-8"))
svc = json.load(open(svc, encoding="utf-8"))
tmpl = svc["spec"]["template"]
ann = tmpl["metadata"]["annotations"]
spec = tmpl["spec"]
env = {e["name"]: e.get("value") for e in spec["containers"][0].get("env", [])}
limits = spec["containers"][0]["resources"]["limits"]
vm_names = ["dd-k6-" + c for c in cohorts.split()]
meta = {
    "arm": arm,
    "size": int(size),
    "repeat": int(repeat),
    "fault": fault,
    "counted": counted == "true",
    "assignmentId": assignment,
    "T": t_iso,
    "cohortMinutes": float(cohort_min),
    "graceS": int(grace_s),
    "maxInstances": int(ann["autoscaling.knative.dev/maxScale"]),
    "minInstances": int(ann.get("autoscaling.knative.dev/minScale", "0")),
    "cpu": limits["cpu"],
    "memory": limits["memory"],
    "concurrency": spec["containerConcurrency"],
    "pool": int(env["DB_POOL_MAX"]),
    "maxConnections": pre["connectionBudget"]["maxConnections"],
    "sqlTier": tier,
    "region": region,
    "skewMs": {"dd-k6-" + c: v.get("skewMs") for c, v in pre["vms"].items()},
    "k6Version": k6v,
    "imageDigest": digest,
    "gitSha": sha,
    "scheduleMode": mode,
    "scheduleSeed": int(seed),
    "vmNames": vm_names,
    "valid": started.get("valid", True),
    "startedAt": started.get("startedAt"),
    "rerunOf": started.get("rerunOf"),
    # Extra wait after T + grace + 2 min: "noPending" = no COMMITTED/UPLOADED row was left;
    # "maxWait" = the cap was reached with pendingRowsAtExport rows still pending.
    "exportWait": {"until": wait_until, "waitedS": int(waited_s), "maxS": int(wait_max_s),
                   "pendingRowsAtExport": int(pending_rows)},
}
with open(out, "w", encoding="utf-8") as f:
    json.dump(meta, f, indent=2)
    f.write("\n")
PYEOF
rm -f "${SERVICE_JSON}"
FINISHED=true

# (10) analysis: exit 3 means arm C misjudged a student (review G3). The bucket is still cleaned
# below, then run.sh exits with analyze.py's code.
ANALYZE_EXIT=0
"${PY}" "$(to_native_path "${REPO_ROOT}/tools/analyze.py")" "$(to_native_path "${RUN_DIR}")" || ANALYZE_EXIT=$?

# (11) remove this run's objects from the bucket (object keys: a/<assignmentId>/...)
gcloud storage rm --recursive "gs://${BUCKET}/a/${ASSIGNMENT_ID}/" --project="${PROJECT_ID}" 2>&1 \
  | tail -n 3 || echo "No objects to delete (or already gone)."

echo "Run complete: ${RUN_DIR} (counted=${COUNTED}, analyze exit ${ANALYZE_EXIT})"
exit "${ANALYZE_EXIT}"
