#!/usr/bin/env bash
# Compares the last demo rush (vu-log) with Lab 4's export from :8080.
#   lost       = students whose commit the server acknowledged but who have no live row
#                (COMMITTED, UPLOADED or VERIFIED) in the export
#   duplicates = students with more than one live row in the export
# Also counts the students who skipped finalize and whose submission ended UPLOADED or VERIFIED.
# Runs the reconcile and drain endpoints first so the answer does not wait for the 60 s scheduler.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

[ -f "$LAST_RUSH_DIR/vu-log.ndjson" ] || { echo "ERROR: no rush log; run make demo-rush first" >&2; exit 1; }
BASE=http://localhost:8080
ID="$(lab4_id C)"
if [ -n "$INTERNAL_TOKEN" ]; then
  curl -s -X POST -H "X-Internal-Token: ${INTERNAL_TOKEN}" "$BASE/internal/reconcile" >/dev/null || true
  curl -s -X POST -H "X-Internal-Token: ${INTERNAL_TOKEN}" "$BASE/internal/jobs/drain" >/dev/null || true
fi
TOKEN="$(teacher_token "$BASE")"
: "${TOKEN:?teacher login failed on $BASE}"
curl -s -f -H "Authorization: Bearer ${TOKEN}" "$BASE/api/assignments/${ID}/export" > "$LAST_RUSH_DIR/export.json"

to_win_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
"$PYTHON" -I "$(to_win_path "$DEMO_DIR")/check.py" "$(to_win_path "$LAST_RUSH_DIR")"
