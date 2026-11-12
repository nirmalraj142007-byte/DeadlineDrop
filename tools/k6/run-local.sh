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

# 5. Run k6 with --console-output
echo "Starting k6 rush scenario..."
k6 run \
  --console-output "${WIN_OUT_DIR}/vu-log.ndjson" \
  -e ARM="${ARM_LOWER}" \
  -e BASE_URL="http://localhost:${PORT}" \
  -e SHARD="${WIN_OUT_DIR}/schedule/shard-all.json" \
  -e T_EPOCH_MS="${T_EPOCH_MS}" \
  -e GRACE_S="${GRACE_S}" \
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

# 7. Fetch /export as JSON into OUT_DIR
echo "Fetching /export from http://localhost:${PORT}..."
TEACHER_TOKEN=$(curl -s -f -X POST "http://localhost:${PORT}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"teacher@dd.local\",\"password\":\"${SEED_PASSWORD}\"}" \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

curl -s -f -H "Authorization: Bearer ${TEACHER_TOKEN}" \
  "http://localhost:${PORT}/api/assignments/11111111-1111-1111-1111-111111111111/export" \
  > "${OUT_DIR}/export.json"

# 8. Print counts by status
echo "=== Export Status Counts for Arm ${ARM_LOWER} ==="
"${PYTHON}" -c "
import json
from collections import Counter

with open(r'${WIN_OUT_DIR}/export.json', 'r', encoding='utf-8') as f:
    rows = json.load(f)

counts = Counter(r.get('status') for r in rows)
timings = Counter(r.get('timing') for r in rows)

print(f'Total exported rows: {len(rows)}')
print(f'Status counts: {dict(counts)}')
print(f'Timing counts: {dict(timings)}')
"
