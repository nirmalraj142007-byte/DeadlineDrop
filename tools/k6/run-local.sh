#!/usr/bin/env bash
# Runs local k6 load test for a specified arm (a, b, or c).
set -euo pipefail

ARM="${1:-${ARM:-c}}"
N="${2:-${N:-20}}"
GRACE_S="${GRACE_S:-60}"

case "${ARM}" in
  c|C)
    PORT=8080
    ARM_LOWER="c"
    ;;
  b|B)
    PORT=8083
    ARM_LOWER="b"
    ;;
  a|A)
    PORT=8082
    ARM_LOWER="a"
    ;;
  *)
    echo "Unknown ARM: ${ARM}. Choose a, b, or c." >&2
    exit 1
    ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

if [ -z "${SEED_PASSWORD:-}" ] && [ -f .env ]; then
  SEED_PASSWORD=$(grep -E "^SEED_PASSWORD=" .env | cut -d'=' -f2- | tr -d '\r\n')
fi
: "${SEED_PASSWORD:?SEED_PASSWORD must be set in environment or .env}"

# 1. make-files with MAX_MB=2
MAX_MB=2 bash "${REPO_ROOT}/tools/k6/files/make-files.sh"

# 2. Setup output folder
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
to_win_path() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    echo "$1"
  fi
}

OUT_DIR="${REPO_ROOT}/results/local/${TIMESTAMP}-${ARM_LOWER}"
mkdir -p "${OUT_DIR}"
WIN_OUT_DIR="$(to_win_path "${OUT_DIR}")"
WIN_FILES_DIR="$(to_win_path "${REPO_ROOT}/tools/k6/files/out")"
WIN_SCRIPT="$(to_win_path "${REPO_ROOT}/tools/k6/rush.js")"

# 3. T = now + 3 min
NOW_S=$(date +%s)
T_S=$(( NOW_S + 180 ))
T_EPOCH_MS=$(( T_S * 1000 ))

echo "=== Running k6-local ==="
echo "Arm: ${ARM_LOWER} (Port: ${PORT})"
echo "Students: ${N}"
echo "Deadline T: ${T_S} (${T_EPOCH_MS} ms)"
echo "Grace: ${GRACE_S} s"
echo "Output: ${OUT_DIR}"

PYTHON="${PYTHON:-$(command -v python 2>/dev/null || command -v python3 2>/dev/null || echo /c/Users/Nirmalraj/AppData/Local/Programs/Python/Python313/python)}"
WIN_REPO_ROOT="$(to_win_path "${REPO_ROOT}")"

# 4. Generate schedule
"${PYTHON}" "${WIN_REPO_ROOT}/tools/gen_schedule.py" \
  --mode placeholder \
  --cohort-minutes 1 \
  --n "${N}" \
  --max-mb 2 \
  --grace-s "${GRACE_S}" \
  --out "${WIN_OUT_DIR}/schedule"

# 4b. Log in as the teacher and create a NEW assignment for this run, so every run
# has its own deadline (T) and its own export. The seeded "Lab 1" is never used.
BASE="http://localhost:${PORT}"
TEACHER_TOKEN=$(curl -s -f -X POST "${BASE}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"teacher@dd.local\",\"password\":\"${SEED_PASSWORD}\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
: "${TEACHER_TOKEN:?teacher login failed on ${BASE}}"

COURSE_ID=$(curl -s -f -H "Authorization: Bearer ${TEACHER_TOKEN}" "${BASE}/api/courses" \
  | "${PYTHON}" -c "
import json, sys
courses = json.load(sys.stdin)
match = [c['id'] for c in courses if c.get('code') == 'DEMO101']
print(match[0] if match else '')
" | tr -d '\r\n')
: "${COURSE_ID:?course DEMO101 not found for the teacher on ${BASE}}"

DEADLINE_ISO=$("${PYTHON}" -c "
import datetime
print(datetime.datetime.fromtimestamp(${T_S}, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
" | tr -d '\r\n')

ASSIGNMENT_ID=$(curl -s -f -X POST "${BASE}/api/courses/${COURSE_ID}/assignments" \
  -H "Authorization: Bearer ${TEACHER_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"k6-local ${ARM_LOWER} ${TIMESTAMP}\",\"deadlineAt\":\"${DEADLINE_ISO}\",\"graceSeconds\":${GRACE_S},\"maxBytes\":26214400,\"allowedTypes\":[\"application/pdf\"]}" \
  | grep -o '"id":"[^"]*"' | head -n1 | cut -d'"' -f4)
: "${ASSIGNMENT_ID:?assignment creation failed on ${BASE}}"
echo "Assignment: ${ASSIGNMENT_ID} (deadline ${DEADLINE_ISO}, grace ${GRACE_S} s)"

# 5. Run k6 with --console-output
echo "Starting k6 rush scenario..."
k6 run \
  --console-output "${WIN_OUT_DIR}/vu-log.ndjson" \
  -e ARM="${ARM_LOWER}" \
  -e BASE_URL="http://localhost:${PORT}" \
  -e SHARD="${WIN_OUT_DIR}/schedule/shard-all.json" \
  -e T_EPOCH_MS="${T_EPOCH_MS}" \
  -e GRACE_S="${GRACE_S}" \
  -e ASSIGNMENT_ID="${ASSIGNMENT_ID}" \
  -e PASSWORD="${SEED_PASSWORD}" \
  -e FILES_DIR="${WIN_FILES_DIR}" \
  -e COHORT_MINUTES="1" \
  "${WIN_SCRIPT}" || true

if [ -f summary.json ]; then
  mv summary.json "${OUT_DIR}/summary.json"
fi

# 6. Wait for T + grace + 2 min
TARGET_END_S=$(( T_S + GRACE_S + 120 ))
CURRENT_S=$(date +%s)
REMAINING_S=$(( TARGET_END_S - CURRENT_S ))

if [ "${REMAINING_S}" -gt 0 ]; then
  echo "Waiting ${REMAINING_S}s for T + grace + 2 min (scheduler drain)..."
  sleep "${REMAINING_S}"
fi

# Drain queue if needed locally
if [ -f .env ]; then
  INTERNAL_TOKEN=$(grep -E "^INTERNAL_TOKEN=" .env | cut -d'=' -f2- | tr -d '\r\n')
  if [ -n "${INTERNAL_TOKEN}" ]; then
    curl -s -X POST -H "X-Internal-Token: ${INTERNAL_TOKEN}" "http://localhost:${PORT}/internal/reconcile" >/dev/null 2>&1 || true
    curl -s -X POST -H "X-Internal-Token: ${INTERNAL_TOKEN}" "http://localhost:${PORT}/internal/jobs/drain" >/dev/null 2>&1 || true
  fi
fi

# 7. Fetch /export for THIS run's assignment (re-login: the first token may have expired)
echo "Fetching /export for assignment ${ASSIGNMENT_ID} from ${BASE}..."
TEACHER_TOKEN=$(curl -s -f -X POST "${BASE}/api/auth/login"   -H "Content-Type: application/json"   -d "{\"email\":\"teacher@dd.local\",\"password\":\"${SEED_PASSWORD}\"}"   | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

curl -s -f -H "Authorization: Bearer ${TEACHER_TOKEN}"   "${BASE}/api/assignments/${ASSIGNMENT_ID}/export"   > "${OUT_DIR}/export.json"
echo "${ASSIGNMENT_ID}" > "${OUT_DIR}/assignment-id.txt"

# 8. Planned class (vu-log.ndjson) against recorded timing/status (export)
echo "=== Planned vs recorded for Arm ${ARM_LOWER} ==="
"${PYTHON}" -c "
import json
import re
from collections import Counter

with open(r'${WIN_OUT_DIR}/export.json', 'r', encoding='utf-8') as f:
    rows = json.load(f)
recorded = {r['studentEmail']: r for r in rows}

planned = {}
lines = 0
with open(r'${WIN_OUT_DIR}/vu-log.ndjson', 'r', encoding='utf-8') as f:
    for raw in f:
        raw = raw.strip()
        if not raw:
            continue
        lines += 1
        # k6 console output is logfmt: time=... level=info msg=\"<escaped json>\"
        m = re.search(r'msg=(\".*\")\s*\$', raw)
        if not m:
            continue
        try:
            entry = json.loads(json.loads(m.group(1)))
        except ValueError:
            continue
        if isinstance(entry, dict) and 'plannedClass' in entry:
            planned[entry['email']] = entry['plannedClass']

table = Counter()
for email, cls in planned.items():
    r = recorded.get(email)
    table[(cls, r.get('timing') if r else 'MISSING', r.get('status') if r else '-')] += 1

print(f'vu-log.ndjson lines: {lines}  (students with plannedClass: {len(planned)})')
print(f'{\"plannedClass\":<14}{\"timing\":<12}{\"status\":<26}count')
for (cls, timing, status), n in sorted(table.items()):
    print(f'{cls:<14}{str(timing):<12}{str(status):<26}{n}')
print(f'Total exported rows: {len(rows)}')
"
