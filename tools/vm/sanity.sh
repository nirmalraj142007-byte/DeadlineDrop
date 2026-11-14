#!/usr/bin/env bash
# Shape each k6 VM with its cohort, run tools/k6/sanity.js from it against arm C, and check the
# measured link against tools/vm/cohorts.env:
#   throughput within ±10% of <COHORT>_RATE, RTT (TCP connect) within ±10 ms of <COHORT>_DELAY.
#   bash tools/vm/sanity.sh
# Writes results/sanity/<cohort>.txt (full k6 output + verdict). Exit 1 if any cohort is outside.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tools/vm/common.sh
source "${SCRIPT_DIR}/common.sh"
# shellcheck source=tools/vm/cohorts.env
source "${SCRIPT_DIR}/cohorts.env"

RATE_TOL_PCT=10
RTT_TOL_MS=10
OUT_DIR="${REPO_ROOT}/results/sanity"
mkdir -p "${OUT_DIR}"
PY="$(find_python)"
BASE="$(arm_url c)"
SEED_PASSWORD="$(gv secrets versions access latest --secret=dd-seed-password --project="${PROJECT_ID}")"

login() {
  curl -s -f --max-time 20 -X POST "${BASE}/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"email\":\"$1\",\"password\":\"${SEED_PASSWORD}\"}" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4
}

# A sanity assignment of its own, deadline two hours away, so it never touches a run's assignment.
TEACHER_TOKEN="$(login teacher@dd.local)"
[ -n "${TEACHER_TOKEN}" ] || { echo "teacher login failed on ${BASE}" >&2; exit 1; }
COURSE_ID="$(curl -s -f --max-time 20 -H "Authorization: Bearer ${TEACHER_TOKEN}" "${BASE}/api/courses" \
  | "${PY}" -c "import json,sys; m=[c['id'] for c in json.load(sys.stdin) if c.get('code')=='DEMO101']; print(m[0] if m else '')" | tr -d '\r')"
[ -n "${COURSE_ID}" ] || { echo "course DEMO101 not found on ${BASE}" >&2; exit 1; }
DEADLINE_ISO="$("${PY}" -c "import datetime as d; print((d.datetime.now(d.timezone.utc)+d.timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))" | tr -d '\r')"
ASSIGNMENT_ID="$(curl -s -f --max-time 20 -X POST "${BASE}/api/courses/${COURSE_ID}/assignments" \
  -H "Authorization: Bearer ${TEACHER_TOKEN}" -H "Content-Type: application/json" \
  -d "{\"title\":\"sanity $(date -u +%Y%m%dT%H%M%SZ)\",\"deadlineAt\":\"${DEADLINE_ISO}\",\"graceSeconds\":300,\"maxBytes\":26214400,\"allowedTypes\":[\"application/pdf\"]}" \
  | grep -o '"id":"[^"]*"' | head -n1 | cut -d'"' -f4)"
[ -n "${ASSIGNMENT_ID}" ] || { echo "assignment creation failed on ${BASE}" >&2; exit 1; }
echo "Sanity assignment ${ASSIGNMENT_ID} on ${BASE}"

run_cohort() {
  local c="$1" i="$2" vm out email
  vm="$(vm_name "${c}")"
  out="${OUT_DIR}/${c}.txt"
  email="$(printf 'student%04d@dd.local' "${i}")"
  vm_ssh "${vm}" "sudo bash ~/${VM_HOME_DIR}/shape.sh ${c}" > "${out}.shape" 2>&1
  vm_ssh "${vm}" "rm -rf ~/${VM_HOME_DIR}/sanity && mkdir -p ~/${VM_HOME_DIR}/sanity/lib" >/dev/null
  vm_scp "${REPO_ROOT}/tools/k6/sanity.js" "${vm}:${VM_HOME_DIR}/sanity/sanity.js"
  vm_scp "${REPO_ROOT}/tools/k6/lib/flows.js" "${vm}:${VM_HOME_DIR}/sanity/lib/flows.js"
  vm_put_secret "${vm}" "${VM_HOME_DIR}/sanity/pw" "${SEED_PASSWORD}"
  vm_ssh "${vm}" "cd ~/${VM_HOME_DIR}/sanity && k6 run --quiet --no-color --log-format raw \
    -e BASE_URL=${BASE} -e ASSIGNMENT_ID=${ASSIGNMENT_ID} -e EMAIL=${email} -e PASSWORD=\"\$(cat pw)\" \
    sanity.js 2>&1; rm -f pw" > "${out}.k6" 2>&1
}

pids=()
i=1
for c in "${COHORTS[@]}"; do run_cohort "${c}" "${i}" & pids+=($!); i=$((i + 1)); done
for p in "${pids[@]}"; do wait "${p}" || { echo "sanity on a VM failed; see results/sanity/*.k6" >&2; exit 1; }; done

FAILED=0
for c in "${COHORTS[@]}"; do
  out="${OUT_DIR}/${c}.txt"
  upper="$(echo "${c}" | tr '[:lower:]' '[:upper:]')"
  rate_var="${upper}_RATE"; delay_var="${upper}_DELAY"
  verdict="$("${PY}" - "$(to_native_path "${out}.k6")" "${!rate_var}" "${!delay_var}" "${RATE_TOL_PCT}" "${RTT_TOL_MS}" <<'PYEOF'
import json, re, sys
path, rate, delay, rate_tol, rtt_tol = sys.argv[1:6]
line = [l for l in open(path, encoding="utf-8", errors="replace") if l.startswith("SANITY ")]
if not line:
    print("FAIL no SANITY line in k6 output"); sys.exit(0)
s = json.loads(line[-1][len("SANITY "):])
m = re.fullmatch(r"([0-9.]+)(k|m|g)bit", rate.lower())
want_mbps = float(m.group(1)) * {"k": 1e-3, "m": 1.0, "g": 1e3}[m.group(2)]
want_rtt = float(delay.lower().removesuffix("ms"))
rate_dev = (s["throughputMbps"] - want_mbps) / want_mbps * 100
rtt_dev = s["rttMs"] - want_rtt
ok = abs(rate_dev) <= float(rate_tol) and abs(rtt_dev) <= float(rtt_tol)
print(f"{'PASS' if ok else 'FAIL'} throughput {s['throughputMbps']} Mbit/s vs {want_mbps} ({rate_dev:+.1f}%, limit +/-{rate_tol}%); "
      f"RTT {s['rttMs']} ms vs {want_rtt} ({rtt_dev:+.1f} ms, limit +/-{rtt_tol} ms)")
PYEOF
)"
  verdict="$(echo "${verdict}" | tr -d '\r')"
  {
    echo "# k6 sanity from $(vm_name "${c}") (cohort ${c}) against ${BASE}, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "# profile: rate ${!rate_var}, delay ${!delay_var}; assignment ${ASSIGNMENT_ID}"
    echo "## shaping"; cat "${out}.shape"
    echo "## k6"; cat "${out}.k6"
    echo "## verdict"; echo "${verdict}"
  } > "${out}"
  rm -f "${out}.shape" "${out}.k6"
  echo "${c}: ${verdict}"
  case "${verdict}" in PASS*) ;; *) FAILED=1 ;; esac
done
exit "${FAILED}"
