#!/usr/bin/env bash
# Rush of 200 placeholder students against :8080 (arm C), timed to Lab 4's current deadline.
# Set the deadline first: make demo-deadline SECONDS=120. About 10% of students skip finalize
# (browser "dies" after the upload), which the reconciler must settle; make check reports it.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

command -v k6 >/dev/null || { echo "ERROR: k6 is not installed" >&2; exit 1; }
BASE=http://localhost:8080
GRACE_S=900                                # Lab 4 grace window; k6 stops retrying after deadline + this
SCHEDULE_TAIL_S="${SCHEDULE_TAIL_S:-60}"   # late clickers arrive within this many seconds after T
ASSIGNMENT_ID="$(lab4_id C)"
: "${ASSIGNMENT_ID:?no Lab 4 id for arm C}"

T_EPOCH_MS="$(psql_db dd_c -c "SELECT (extract(epoch FROM deadline_at) * 1000)::bigint FROM assignments WHERE id = '${ASSIGNMENT_ID}'" | tr -d '\r\n')"
: "${T_EPOCH_MS:?could not read the Lab 4 deadline}"
NOW_MS=$(( $(date +%s) * 1000 ))
if [ "$T_EPOCH_MS" -lt $(( NOW_MS - 60000 )) ]; then
  echo "ERROR: the Lab 4 deadline passed more than a minute ago; run make demo-deadline SECONDS=120" >&2
  exit 1
fi
echo "Lab 4 deadline in $(( (T_EPOCH_MS - NOW_MS) / 1000 )) s"

to_win_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
rm -rf "$LAST_RUSH_DIR"; mkdir -p "$LAST_RUSH_DIR"
OUT="$(to_win_path "$LAST_RUSH_DIR")"

MAX_MB=2 bash "$ROOT/tools/k6/files/make-files.sh" >/dev/null
"$PYTHON" "$(to_win_path "$ROOT")/tools/gen_schedule.py" --mode placeholder --cohort-minutes 1 \
  --n 200 --max-mb 2 --grace-s "$SCHEDULE_TAIL_S" --skip-finalize-frac 0.1 --out "$OUT/schedule" >/dev/null
echo "$ASSIGNMENT_ID" > "$LAST_RUSH_DIR/assignment-id.txt"

k6 run \
  --console-output "$OUT/vu-log.ndjson" \
  -e ARM=c -e BASE_URL="$BASE" \
  -e SHARD="$OUT/schedule/shard-all.json" \
  -e T_EPOCH_MS="$T_EPOCH_MS" -e GRACE_S="$GRACE_S" \
  -e ASSIGNMENT_ID="$ASSIGNMENT_ID" -e PASSWORD="$SEED_PASSWORD" \
  -e FILES_DIR="$(to_win_path "$ROOT/tools/k6/files/out")" \
  -e COHORT_MINUTES=1 \
  "$(to_win_path "$ROOT/tools/k6/rush.js")" || true
# k6's handleSummary writes summary.json into the current directory; keep the repo root clean.
if [ -f "$ROOT/summary.json" ]; then mv "$ROOT/summary.json" "$LAST_RUSH_DIR/summary.json"; fi
echo "rush finished; run: make check"
